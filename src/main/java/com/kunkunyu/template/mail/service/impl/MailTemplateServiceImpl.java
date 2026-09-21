package com.kunkunyu.template.mail.service.impl;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.isNull;

import com.kunkunyu.template.mail.service.MailTemplateService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebInputException;
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
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;

@Service
@RequiredArgsConstructor
public class MailTemplateServiceImpl implements MailTemplateService {

    private final ReactiveExtensionClient client;

    private final NotificationReasonEmitter notificationReasonEmitter;

    private final NotificationCenter notificationCenter;

    private final NotificationTemplateValidator notificationTemplateValidator;

    private static final String DEFAULT_LANGUAGE = "default";

    @Override
    public Mono<Void> verifyMailTemplatSend(String reasonTypeName) {
        return getCurrentUser()
            .flatMap(user -> client.fetch(ReasonType.class, reasonTypeName)
                .switchIfEmpty(
                    Mono.error(() -> new ServerWebInputException("没有对应模版"))
                )
                .flatMap(reasonType -> sendVerifyNotification(reasonType,user.getMetadata().getName(),user.getSpec().getEmail()))
            );
    }


    @Override
    public Mono<Void> verifyMailTemplatSend(ReasonType reasonType) {
        return getCurrentUser()
            .flatMap(user -> sendVerifyNotification(reasonType,user.getMetadata().getName(),user.getSpec().getEmail()));
    }


    public Mono<Void> sendVerifyNotification(ReasonType reasonType, String username, String email) {
        return findEffectiveTemplate(reasonType.getMetadata().getName())
            .flatMap(notificationTemplate -> Mono.fromRunnable(() -> {
                var spec = notificationTemplate.getSpec();
                notificationTemplateValidator.validate(
                    spec == null ? null : spec.getTemplate(), reasonType);
            })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(TemplateValidationException.class,
                    error -> new ServerWebInputException(error.getMessage()))
                .then(Mono.defer(() -> emitVerifyNotification(reasonType, username, email))));
    }

    private Mono<Void> emitVerifyNotification(ReasonType reasonType, String username, String email) {
        String reasonTypeName = reasonType.getMetadata().getName();
        var subscribeNotification = subscribeVerifyNotification(email,reasonTypeName);
        var interestReasonSubject = createInterestReason(email,reasonTypeName).getSubject();
        var unsubscrNotification = unsubscribeVerifyNotification(email,reasonTypeName);

        var attributes = notificationTemplateValidator.createReasonAttributes(reasonType);

        var reasonSubject = Reason.Subject.builder()
            .apiVersion(interestReasonSubject.getApiVersion())
            .kind(interestReasonSubject.getKind())
            .name(interestReasonSubject.getName())
            .title("验证模板："+reasonType.getSpec().getDisplayName())
            .build();

        var emitReasonMono = notificationReasonEmitter.emit(reasonTypeName,
            builder -> {
                builder.attributes(attributes)
                    .author(UserIdentity.of(username))
                    .subject(reasonSubject);
            });

        return Mono.when(subscribeNotification).then(emitReasonMono).then(unsubscrNotification);
    }

    private Mono<NotificationTemplate> findEffectiveTemplate(String reasonTypeName) {
        var listOptions = ListOptions.builder()
            .fieldQuery(and(
                equal("spec.reasonSelector.reasonType", reasonTypeName),
                isNull("metadata.deletionTimestamp")
            ))
            .build();
        return client.listAll(NotificationTemplate.class, listOptions, ExtensionUtil.defaultSort())
            .next();
    }

    private static String metadataName(NotificationTemplate notificationTemplate) {
        var metadata = notificationTemplate.getMetadata();
        return metadata == null ? null : metadata.getName();
    }

    private static String templateLanguage(NotificationTemplate notificationTemplate) {
        var spec = notificationTemplate.getSpec();
        if (spec == null || spec.getReasonSelector() == null) {
            return null;
        }
        return spec.getReasonSelector().getLanguage();
    }

    Mono<Void> subscribeVerifyNotification(String email, String reasonType) {
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(email).name());
        var interestReason = createInterestReason(email,reasonType);
        return notificationCenter.subscribe(subscriber, interestReason).then();
    }

    Mono<Void> unsubscribeVerifyNotification(String email, String reasonType) {
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(email).name());
        var interestReason = createInterestReason(email,reasonType);
        return notificationCenter.unsubscribe(subscriber, interestReason).then();
    }


    Subscription.InterestReason createInterestReason(String email, String reasonType) {
        var interestReason = new Subscription.InterestReason();
        interestReason.setReasonType(reasonType);
        interestReason.setSubject(Subscription.ReasonSubject.builder()
            .apiVersion(new GroupVersion(User.GROUP, User.KIND).toString())
            .kind(User.KIND)
            .name(UserIdentity.anonymousWithEmail(email).name())
            .build());
        return interestReason;
    }

    Mono<User> getCurrentUser() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .map(Principal::getName)
            .flatMap(username -> client.fetch(User.class, username))
            .flatMap(user -> {
                var email = user.getSpec().getEmail();
                if (StringUtils.isBlank(email)) {
                    return Mono.error(new ServerWebInputException(
                        "Your email is missing, please set it in your profile."));
                }
                return Mono.just(user);
            });
    }
}
