package com.kunkunyu.template.mail.service.impl;

import static run.halo.app.extension.index.query.Queries.equal;

import com.kunkunyu.template.mail.service.MailTemplateService;
import com.kunkunyu.template.mail.service.NotificationTemplateValidator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.UserIdentity;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailTemplateServiceImpl implements MailTemplateService {

    private static final String DEFAULT_LANGUAGE = "default";
    private static final String VERIFICATION_REASON_NAME =
        "mail-template-verification";

    private final ReactiveExtensionClient client;
    private final NotificationCenter notificationCenter;
    private final NotificationTemplateValidator templateValidator;
    private final ConcurrentMap<String, Semaphore> operationLocks =
        new ConcurrentHashMap<>();

    @Override
    public Mono<Void> validateTemplate(
        String reasonTypeName, NotificationTemplate.Template template) {
        return getReasonType(reasonTypeName)
            .flatMap(reasonType -> Mono.fromRunnable(
                () -> templateValidator.validate(template, reasonType)));
    }

    @Override
    public Mono<Long> countPendingReasons(String reasonTypeName) {
        return getReasonType(reasonTypeName)
            .then(countPendingReasonsByType(reasonTypeName));
    }

    @Override
    public Mono<NotificationTemplate> saveTemplate(
        String reasonTypeName,
        NotificationTemplate.Template template,
        boolean allowPendingReplay) {
        return withReasonTypeLock(reasonTypeName, () ->
            getReasonType(reasonTypeName)
            .flatMap(reasonType -> Mono.fromRunnable(
                    () -> templateValidator.validate(template, reasonType))
                .then(countPendingReasonsByType(reasonTypeName))
                .flatMap(pendingCount -> {
                    if (pendingCount > 0 && !allowPendingReplay) {
                        return Mono.error(new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "存在 %d 条待处理通知；保存后 Halo 可能补发历史消息，"
                                .formatted(pendingCount)
                                + "请确认后再试"));
                    }
                    return Mono.empty();
                })
                .then(Mono.defer(() -> listReasonTemplates(reasonTypeName)
                    .filter(candidate -> !ExtensionUtil.isDeleted(candidate))
                    .collectList()))
                .flatMap(existingTemplates -> createCustomTemplateSet(
                    reasonTypeName, template, existingTemplates))));
    }

    @Override
    public Mono<Void> deleteCustomTemplates(String reasonTypeName) {
        return withReasonTypeLock(reasonTypeName, () ->
            getReasonType(reasonTypeName)
            .thenMany(listCustomTemplates(reasonTypeName))
            .flatMap(client::delete)
            .then());
    }

    @Override
    public Mono<Void> sendVerification(
        String reasonTypeName, String templateName) {
        return withReasonTypeLock(reasonTypeName, () -> Mono.zip(
                getCurrentUser(),
                getReasonType(reasonTypeName),
                getSelectedCustomTemplate(reasonTypeName, templateName))
            .flatMap(tuple -> {
                var user = tuple.getT1();
                var reasonType = tuple.getT2();
                var customTemplate = tuple.getT3();
                return Mono.fromRunnable(() -> templateValidator.validate(
                        customTemplate.getSpec().getTemplate(), reasonType))
                    .then(sendVerificationNotification(reasonType, user));
            }));
    }

    Mono<Void> sendVerificationNotification(ReasonType reasonType, User user) {
        var username = user.getMetadata().getName();
        var email = user.getSpec().getEmail();
        var reasonTypeName = reasonType.getMetadata().getName();
        var subscriber = createSubscriber(email);
        var interestReason = createInterestReason(reasonTypeName);
        var reason = createReason(reasonType, username, interestReason);

        return Mono.usingWhen(
            notificationCenter.subscribe(subscriber, interestReason),
            ignored -> notificationCenter.notify(reason),
            ignored -> notificationCenter.unsubscribe(subscriber, interestReason),
            (ignored, error) -> cleanupAfterFailure(
                subscriber, interestReason, error),
            ignored -> cleanupAfterCancellation(subscriber, interestReason));
    }

    private Reason createReason(
        ReasonType reasonType,
        String username,
        Subscription.InterestReason interestReason) {
        var reasonTypeName = reasonType.getMetadata().getName();
        var displayName = reasonType.getSpec().getDisplayName();

        var subject = Reason.Subject.builder()
            .apiVersion(interestReason.getSubject().getApiVersion())
            .kind(interestReason.getSubject().getKind())
            .name(interestReason.getSubject().getName())
            .title("验证模板：" + StringUtils.defaultIfBlank(
                displayName, reasonTypeName))
            .build();

        var metadata = new Metadata();
        metadata.setName(VERIFICATION_REASON_NAME);
        metadata.setCreationTimestamp(Instant.now());

        var spec = new Reason.Spec()
            .setReasonType(reasonTypeName)
            .setAuthor(UserIdentity.of(username).name())
            .setSubject(subject)
            .setAttributes(templateValidator.createReasonAttributes(reasonType));

        var reason = new Reason();
        reason.setMetadata(metadata);
        reason.setSpec(spec);
        return reason;
    }

    private Subscription.Subscriber createSubscriber(String email) {
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(email).name());
        return subscriber;
    }

    private Mono<Void> cleanupAfterFailure(
        Subscription.Subscriber subscriber,
        Subscription.InterestReason interestReason,
        Throwable originalError) {
        return notificationCenter.unsubscribe(subscriber, interestReason)
            .onErrorResume(cleanupError -> {
                originalError.addSuppressed(cleanupError);
                log.warn(
                    "Failed to remove mail template verification subscription "
                        + "after notification error",
                    cleanupError);
                return Mono.empty();
            });
    }

    private Mono<Void> cleanupAfterCancellation(
        Subscription.Subscriber subscriber,
        Subscription.InterestReason interestReason) {
        return notificationCenter.unsubscribe(subscriber, interestReason)
            .doOnError(error -> log.warn(
                "Failed to remove cancelled mail template verification subscription",
                error))
            .onErrorComplete();
    }

    Subscription.InterestReason createInterestReason(String reasonTypeName) {
        var interestReason = new Subscription.InterestReason();
        interestReason.setReasonType(reasonTypeName);
        interestReason.setSubject(Subscription.ReasonSubject.builder()
            .apiVersion(new GroupVersion(User.GROUP, User.VERSION).toString())
            .kind(User.KIND)
            // A unique subject prevents replacement of a real subscription.
            .name("mail-template-verification-" + UUID.randomUUID())
            .build());
        return interestReason;
    }

    Mono<User> getCurrentUser() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(authentication -> authentication.isAuthenticated())
            .map(authentication -> authentication.getName())
            .flatMap(username -> client.fetch(User.class, username))
            .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "请先登录")))
            .flatMap(user -> {
                var email = user.getSpec().getEmail();
                if (StringUtils.isBlank(email)) {
                    return Mono.error(new ServerWebInputException(
                        "当前用户未设置邮箱，请先在个人资料中设置邮箱"));
                }
                return Mono.just(user);
            });
    }

    private Mono<ReasonType> getReasonType(String reasonTypeName) {
        if (StringUtils.isBlank(reasonTypeName)) {
            return Mono.error(new ServerWebInputException("通知类型不能为空"));
        }
        return client.fetch(ReasonType.class, reasonTypeName)
            .switchIfEmpty(Mono.error(() ->
                new ServerWebInputException("未找到对应的通知类型")));
    }

    private Mono<NotificationTemplate> getSelectedCustomTemplate(
        String reasonTypeName, String expectedTemplateName) {
        return listReasonTemplates(reasonTypeName)
            .filter(template -> !ExtensionUtil.isDeleted(template))
            .collectList()
            .flatMap(templates -> {
                var selectedByLanguage = newestByLanguage(templates);
                if (selectedByLanguage.isEmpty()) {
                    return Mono.error(new ServerWebInputException(
                        "请先保存自定义模板"));
                }

                var nonCustomWinner = selectedByLanguage.values().stream()
                    .filter(template -> !isCustomTemplate(
                        template, reasonTypeName))
                    .findFirst();
                if (nonCustomWinner.isPresent()) {
                    return Mono.error(selectionConflict(nonCustomWinner.get()));
                }

                var selectedTemplates = List.copyOf(
                    selectedByLanguage.values());
                var expectedTemplateIsSelected = selectedTemplates.stream()
                    .anyMatch(template -> template.getMetadata().getName()
                        .equals(expectedTemplateName));
                if (!expectedTemplateIsSelected) {
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "准备测试的模板已被其他保存操作替换，请重新保存后再试"));
                }
                var first = selectedTemplates.get(0);
                var hasDifferentContent = selectedTemplates.stream()
                    .skip(1)
                    .anyMatch(template -> !templateContentEquals(
                        first.getSpec().getTemplate(),
                        template.getSpec().getTemplate()));
                if (hasDifferentContent) {
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "不同语言的自定义模板内容不一致，请重新保存后再测试"));
                }

                return Mono.just(selectedByLanguage.getOrDefault(
                    DEFAULT_LANGUAGE, first));
            });
    }

    private Flux<NotificationTemplate> listCustomTemplates(String reasonTypeName) {
        return listReasonTemplates(reasonTypeName)
            .filter(template -> !ExtensionUtil.isDeleted(template))
            .filter(template -> isCustomTemplate(template, reasonTypeName));
    }

    private Flux<NotificationTemplate> listReasonTemplates(String reasonTypeName) {
        var options = new ListOptions();
        options.setFieldSelector(FieldSelector.of(
            equal("spec.reasonSelector.reasonType", reasonTypeName)));
        return client.listAll(
            NotificationTemplate.class,
            options,
            Sort.by(Sort.Order.desc("metadata.creationTimestamp")));
    }

    private Mono<Long> countPendingReasonsByType(String reasonTypeName) {
        return client.list(
                Reason.class,
                reason -> reasonTypeName.equals(reason.getSpec().getReasonType()),
                Comparator.comparing(reason ->
                    reason.getMetadata().getCreationTimestamp()))
            .filter(reason -> !ExtensionUtil.isDeleted(reason))
            .count();
    }

    private Mono<NotificationTemplate> createCustomTemplateSet(
        String reasonTypeName,
        NotificationTemplate.Template templateContent,
        List<NotificationTemplate> existingTemplates) {
        var previousCustomTemplates = existingTemplates.stream()
            .filter(template -> isCustomTemplate(template, reasonTypeName))
            .toList();
        Set<String> languages = new LinkedHashSet<>();
        languages.add(DEFAULT_LANGUAGE);
        existingTemplates.stream()
            .map(this::languageOf)
            .forEach(languages::add);

        return Mono.usingWhen(
            Mono.fromSupplier(() -> new ArrayList<NotificationTemplate>()),
            createdTemplates -> Flux.fromIterable(languages)
                .concatMap(language -> createCustomTemplate(
                        reasonTypeName, language, copyTemplate(templateContent))
                    .doOnNext(createdTemplates::add))
                .collectList()
                .flatMap(created -> assertTemplatesAreSelected(
                        reasonTypeName, created)
                    .then(cleanupSupersededTemplates(previousCustomTemplates))
                    .thenReturn(created.stream()
                        .filter(template -> DEFAULT_LANGUAGE.equals(
                            languageOf(template)))
                        .findFirst()
                        .orElse(created.get(0)))),
            ignored -> Mono.empty(),
            (createdTemplates, error) -> cleanupCreatedTemplates(
                createdTemplates, "failed", error),
            createdTemplates -> cleanupCreatedTemplates(
                createdTemplates, "cancelled", null));
    }

    private Mono<NotificationTemplate> createCustomTemplate(
        String reasonTypeName,
        String language,
        NotificationTemplate.Template templateContent) {
        var metadata = new Metadata();
        metadata.setGenerateName(customTemplateBaseName(reasonTypeName) + "-");

        var selector = new NotificationTemplate.ReasonSelector();
        selector.setReasonType(reasonTypeName);
        selector.setLanguage(StringUtils.defaultIfBlank(
            language, DEFAULT_LANGUAGE));

        var spec = new NotificationTemplate.Spec();
        spec.setReasonSelector(selector);
        spec.setTemplate(templateContent);

        var template = new NotificationTemplate();
        template.setMetadata(metadata);
        template.setSpec(spec);
        return client.create(template);
    }

    private Mono<Void> assertTemplatesAreSelected(
        String reasonTypeName,
        List<NotificationTemplate> createdTemplates) {
        var expectedNames = createdTemplates.stream()
            .map(template -> template.getMetadata().getName())
            .collect(java.util.stream.Collectors.toSet());
        return listReasonTemplates(reasonTypeName)
            .filter(template -> !ExtensionUtil.isDeleted(template))
            .collectList()
            .flatMap(candidates -> {
                var selectedByLanguage = newestByLanguage(candidates);
                if (selectedByLanguage.size() < createdTemplates.size()) {
                    return Mono.error(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Halo 未找到刚保存的全部语言模板"));
                }

                return selectedByLanguage.values().stream()
                    .filter(selected -> !expectedNames.contains(
                        selected.getMetadata().getName()))
                    .findFirst()
                    .<Mono<Void>>map(selected -> Mono.error(
                        selectionConflict(selected)))
                    .orElseGet(Mono::empty);
            });
    }

    private Mono<Void> cleanupSupersededTemplates(
        List<NotificationTemplate> previousTemplates) {
        return Flux.fromIterable(previousTemplates)
            .flatMap(template -> client.delete(template)
                .onErrorResume(error -> {
                    log.warn("Failed to delete superseded mail template {}",
                        template.getMetadata().getName(), error);
                    return Mono.empty();
                }))
            .then();
    }

    private Mono<Void> cleanupCreatedTemplates(
        List<NotificationTemplate> createdTemplates,
        String operationState,
        Throwable originalError) {
        return Flux.fromIterable(createdTemplates)
            .concatMap(template -> client.delete(template)
                .onErrorResume(deleteError -> {
                    if (originalError != null) {
                        originalError.addSuppressed(deleteError);
                    }
                    log.warn("Failed to delete {} mail template {}",
                        operationState,
                        template.getMetadata().getName(),
                        deleteError);
                    return Mono.empty();
                }))
            .then();
    }

    private boolean isCustomTemplate(
        NotificationTemplate template, String reasonTypeName) {
        var name = template.getMetadata().getName();
        var baseName = customTemplateBaseName(reasonTypeName);
        return name.equals(baseName) || name.startsWith(baseName + "-");
    }

    private String customTemplateBaseName(String reasonTypeName) {
        return CUSTOM_TEMPLATE_PREFIX + reasonTypeName;
    }

    private String languageOf(NotificationTemplate template) {
        return StringUtils.defaultIfBlank(
            template.getSpec().getReasonSelector().getLanguage(),
            DEFAULT_LANGUAGE);
    }

    private Map<String, NotificationTemplate> newestByLanguage(
        List<NotificationTemplate> templates) {
        Map<String, NotificationTemplate> selected = new LinkedHashMap<>();
        templates.forEach(template -> selected.putIfAbsent(
            languageOf(template), template));
        return selected;
    }

    private ResponseStatusException selectionConflict(
        NotificationTemplate selected) {
        return new ResponseStatusException(
            HttpStatus.CONFLICT,
            "模板 %s 会被 Halo 优先使用，请重新保存后再测试"
                .formatted(selected.getMetadata().getName()));
    }

    private boolean templateContentEquals(
        NotificationTemplate.Template left,
        NotificationTemplate.Template right) {
        return java.util.Objects.equals(left.getTitle(), right.getTitle())
            && java.util.Objects.equals(left.getHtmlBody(), right.getHtmlBody())
            && java.util.Objects.equals(left.getRawBody(), right.getRawBody());
    }

    private NotificationTemplate.Template copyTemplate(
        NotificationTemplate.Template source) {
        var copy = new NotificationTemplate.Template();
        copy.setTitle(source.getTitle());
        copy.setHtmlBody(source.getHtmlBody());
        copy.setRawBody(source.getRawBody());
        return copy;
    }

    private <T> Mono<T> withReasonTypeLock(
        String reasonTypeName, Supplier<Mono<T>> operation) {
        var semaphore = operationLocks.computeIfAbsent(
            reasonTypeName, ignored -> new Semaphore(1, true));
        var acquire = Mono.fromCallable(() -> {
                semaphore.acquire();
                return semaphore;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .doOnDiscard(Semaphore.class, Semaphore::release);
        return Mono.usingWhen(
            acquire,
            ignored -> Mono.defer(operation),
            ignored -> release(semaphore),
            (ignored, error) -> release(semaphore),
            ignored -> release(semaphore));
    }

    private Mono<Void> release(Semaphore semaphore) {
        return Mono.fromRunnable(semaphore::release);
    }
}
