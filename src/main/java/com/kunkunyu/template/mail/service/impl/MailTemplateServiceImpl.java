package com.kunkunyu.template.mail.service.impl;

import com.kunkunyu.template.mail.service.MailTemplateService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MailTemplateServiceImpl implements MailTemplateService {

    private final ReactiveExtensionClient client;

    private final NotificationReasonEmitter notificationReasonEmitter;

    private final NotificationCenter notificationCenter;

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

    private Mono<Void> sendVerifyNotification(ReasonType reasonType, String username, String email) {
        String reasonTypeName = reasonType.getMetadata().getName();
        var subscribeNotification = subscribeVerifyNotification(email,reasonTypeName);
        var interestReasonSubject = createInterestReason(email,reasonTypeName).getSubject();
        var unsubscrNotification = unsubscribeVerifyNotification(email,reasonTypeName);

        List<ReasonType.ReasonProperty> properties = reasonType.getSpec().getProperties();

        Map<String,Object> attributes = new HashMap<>();

        for (ReasonType.ReasonProperty property : properties) {
            String name = property.getName();
            attributes.put(name,name);
        }

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
