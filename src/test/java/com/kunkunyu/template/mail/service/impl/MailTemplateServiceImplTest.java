package com.kunkunyu.template.mail.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kunkunyu.template.mail.service.NotificationTemplateValidator;
import java.time.Instant;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.notification.NotificationCenter;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class MailTemplateServiceImplTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    NotificationCenter notificationCenter;

    MailTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MailTemplateServiceImpl(
            client, notificationCenter, new NotificationTemplateValidator());
    }

    @Test
    void sendsDirectlyAndAlwaysRemovesTemporarySubscription() {
        var subscription = new Subscription();
        when(notificationCenter.subscribe(any(), any()))
            .thenReturn(Mono.just(subscription));
        when(notificationCenter.notify(any())).thenReturn(Mono.empty());
        when(notificationCenter.unsubscribe(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.sendVerificationNotification(reasonType(), user()))
            .verifyComplete();

        var reasonCaptor = ArgumentCaptor.forClass(Reason.class);
        verify(notificationCenter).notify(reasonCaptor.capture());
        verify(notificationCenter).unsubscribe(any(), any());

        var reason = reasonCaptor.getValue();
        assertThat(reason.getMetadata().getName())
            .isEqualTo("mail-template-verification");
        assertThat(reason.getMetadata().getCreationTimestamp()).isNotNull();
        assertThat(reason.getSpec().getReasonType()).isEqualTo("new-device-login");
        assertThat(reason.getSpec().getAttributes()).containsEntry("browser", "browser");
    }

    @Test
    void removesTemporarySubscriptionWhenNotificationFails() {
        var subscription = new Subscription();
        var sendFailure = new IllegalStateException("send failed");
        when(notificationCenter.subscribe(any(), any()))
            .thenReturn(Mono.just(subscription));
        when(notificationCenter.notify(any())).thenReturn(Mono.error(sendFailure));
        when(notificationCenter.unsubscribe(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.sendVerificationNotification(reasonType(), user()))
            .expectErrorMatches(error -> error == sendFailure)
            .verify();

        verify(notificationCenter).unsubscribe(any(), any());
    }

    @Test
    void propagatesCleanupFailureAfterSuccessfulNotification() {
        var subscription = new Subscription();
        var cleanupFailure = new IllegalStateException("cleanup failed");
        when(notificationCenter.subscribe(any(), any()))
            .thenReturn(Mono.just(subscription));
        when(notificationCenter.notify(any())).thenReturn(Mono.empty());
        when(notificationCenter.unsubscribe(any(), any()))
            .thenReturn(Mono.error(cleanupFailure));

        StepVerifier.create(service.sendVerificationNotification(reasonType(), user()))
            .expectErrorSatisfies(error -> {
                assertThat(error).hasMessageContaining("cleanup failed");
                assertThat(error.getCause()).isSameAs(cleanupFailure);
            })
            .verify();
    }

    @Test
    void publicSendPathFetchesValidatesNotifiesAndCleansUp() {
        var reasonType = reasonType();
        var customTemplate = notificationTemplate(
            "template-one-new-device-login-v2",
            "new-device-login",
            Instant.parse("2026-09-01T00:00:00Z"),
            "<p th:text=\"${browser}\">browser</p>");
        var authentication = new TestingAuthenticationToken(
            "admin", "password", "super-role");

        when(client.fetch(User.class, "admin")).thenReturn(Mono.just(user()));
        when(client.fetch(ReasonType.class, "new-device-login"))
            .thenReturn(Mono.just(reasonType));
        when(client.listAll(eq(NotificationTemplate.class), any(), any()))
            .thenReturn(Flux.just(customTemplate));
        when(notificationCenter.subscribe(any(), any()))
            .thenReturn(Mono.just(new Subscription()));
        when(notificationCenter.notify(any())).thenReturn(Mono.empty());
        when(notificationCenter.unsubscribe(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.sendVerification(
                "new-device-login", customTemplate.getMetadata().getName())
                .contextWrite(ReactiveSecurityContextHolder
                    .withAuthentication(authentication)))
            .verifyComplete();

        verify(notificationCenter).notify(any());
        verify(notificationCenter).unsubscribe(any(), any());
    }

    @Test
    void saveCreatesNewestTemplateThenDeletesSupersededVersion() {
        mockPendingReasons();
        var oldTemplate = notificationTemplate(
            "template-one-new-device-login",
            "new-device-login",
            Instant.parse("2025-01-01T00:00:00Z"),
            "<p>old</p>");
        var createdTemplate = notificationTemplate(
            "template-one-new-device-login-generated",
            "new-device-login",
            Instant.parse("2026-09-01T00:00:00Z"),
            "<p th:text=\"${browser}\">browser</p>");
        var content = createdTemplate.getSpec().getTemplate();

        when(client.fetch(ReasonType.class, "new-device-login"))
            .thenReturn(Mono.just(reasonType()));
        when(client.listAll(eq(NotificationTemplate.class), any(), any()))
            .thenReturn(Flux.just(oldTemplate), Flux.just(createdTemplate, oldTemplate));
        when(client.create(any(NotificationTemplate.class)))
            .thenReturn(Mono.just(createdTemplate));
        when(client.delete(oldTemplate)).thenReturn(Mono.just(oldTemplate));

        StepVerifier.create(service.saveTemplate(
                "new-device-login", content, false))
            .assertNext(saved -> assertThat(saved).isSameAs(createdTemplate))
            .verifyComplete();

        var createCaptor = ArgumentCaptor.forClass(NotificationTemplate.class);
        verify(client).create(createCaptor.capture());
        assertThat(createCaptor.getValue().getMetadata().getGenerateName())
            .startsWith("template-one-new-device-login-");
        verify(client).delete(oldTemplate);
    }

    @Test
    void saveFailsWhenHaloWouldSelectACompetingTemplate() {
        mockPendingReasons();
        var createdTemplate = notificationTemplate(
            "template-one-new-device-login-generated",
            "new-device-login",
            Instant.parse("2026-09-01T00:00:00Z"),
            "<p th:text=\"${browser}\">browser</p>");
        var competingTemplate = notificationTemplate(
            "template-competing-new-device-login",
            "new-device-login",
            Instant.parse("2026-09-01T00:00:01Z"),
            "<p>competitor</p>");

        when(client.fetch(ReasonType.class, "new-device-login"))
            .thenReturn(Mono.just(reasonType()));
        when(client.listAll(eq(NotificationTemplate.class), any(), any()))
            .thenReturn(Flux.empty(), Flux.just(competingTemplate, createdTemplate));
        when(client.create(any(NotificationTemplate.class)))
            .thenReturn(Mono.just(createdTemplate));
        when(client.delete(createdTemplate)).thenReturn(Mono.just(createdTemplate));

        StepVerifier.create(service.saveTemplate(
                "new-device-login",
                createdTemplate.getSpec().getTemplate(),
                false))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) error).getStatusCode().value())
                    .isEqualTo(409);
                assertThat(error).hasMessageContaining(competingTemplate
                    .getMetadata().getName());
            })
            .verify();

        verify(client).delete(createdTemplate);
    }

    @Test
    void saveCreatesCurrentVersionsForEveryExistingLanguage() {
        mockPendingReasons();
        var existingDefault = notificationTemplate(
            "template-default",
            "new-device-login",
            Instant.parse("2025-01-01T00:00:00Z"),
            "<p>default</p>");
        var existingChinese = notificationTemplate(
            "template-zh-cn",
            "new-device-login",
            Instant.parse("2025-01-01T00:00:01Z"),
            "<p>中文</p>");
        existingChinese.getSpec().getReasonSelector().setLanguage("zh_CN");

        var createdDefault = notificationTemplate(
            "template-one-new-device-login-default",
            "new-device-login",
            Instant.parse("2026-09-01T00:00:00Z"),
            "<p th:text=\"${browser}\">browser</p>");
        var createdChinese = notificationTemplate(
            "template-one-new-device-login-zh-cn",
            "new-device-login",
            Instant.parse("2026-09-01T00:00:01Z"),
            "<p th:text=\"${browser}\">browser</p>");
        createdChinese.getSpec().getReasonSelector().setLanguage("zh_CN");

        when(client.fetch(ReasonType.class, "new-device-login"))
            .thenReturn(Mono.just(reasonType()));
        when(client.listAll(eq(NotificationTemplate.class), any(), any()))
            .thenReturn(
                Flux.just(existingChinese, existingDefault),
                Flux.just(createdChinese, createdDefault,
                    existingChinese, existingDefault));
        when(client.create(any(NotificationTemplate.class)))
            .thenAnswer(invocation -> {
                NotificationTemplate candidate = invocation.getArgument(0);
                var language = candidate.getSpec().getReasonSelector()
                    .getLanguage();
                return Mono.just("zh_CN".equals(language)
                    ? createdChinese : createdDefault);
            });

        StepVerifier.create(service.saveTemplate(
                "new-device-login",
                createdDefault.getSpec().getTemplate(),
                false))
            .assertNext(saved -> assertThat(saved).isSameAs(createdDefault))
            .verifyComplete();

        var createCaptor = ArgumentCaptor.forClass(NotificationTemplate.class);
        verify(client, times(2)).create(createCaptor.capture());
        assertThat(createCaptor.getAllValues())
            .extracting(template -> template.getSpec().getReasonSelector()
                .getLanguage())
            .containsExactlyInAnyOrder("default", "zh_CN");
    }

    @Test
    void publicSendRejectsLocalizedTemplateThatWouldOverrideCustomDefault() {
        var customDefault = notificationTemplate(
            "template-one-new-device-login-v2",
            "new-device-login",
            Instant.parse("2026-09-01T00:00:00Z"),
            "<p th:text=\"${browser}\">browser</p>");
        var localizedWinner = notificationTemplate(
            "template-new-device-login-zh-cn",
            "new-device-login",
            Instant.parse("2025-01-01T00:00:00Z"),
            "<p>中文</p>");
        localizedWinner.getSpec().getReasonSelector().setLanguage("zh_CN");
        var authentication = new TestingAuthenticationToken(
            "admin", "password", "super-role");

        when(client.fetch(User.class, "admin")).thenReturn(Mono.just(user()));
        when(client.fetch(ReasonType.class, "new-device-login"))
            .thenReturn(Mono.just(reasonType()));
        when(client.listAll(eq(NotificationTemplate.class), any(), any()))
            .thenReturn(Flux.just(customDefault, localizedWinner));

        StepVerifier.create(service.sendVerification(
                "new-device-login", customDefault.getMetadata().getName())
                .contextWrite(ReactiveSecurityContextHolder
                    .withAuthentication(authentication)))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) error)
                    .getStatusCode().value()).isEqualTo(409);
                assertThat(error).hasMessageContaining(
                    localizedWinner.getMetadata().getName());
            })
            .verify();

        verify(notificationCenter, never()).notify(any());
    }

    @Test
    void saveRequiresExplicitConsentWhenReasonsArePending() {
        var pendingReason = new Reason();
        var pendingMetadata = new Metadata();
        pendingMetadata.setName("reason-pending");
        pendingMetadata.setCreationTimestamp(Instant.now());
        pendingReason.setMetadata(pendingMetadata);
        pendingReason.setSpec(new Reason.Spec()
            .setReasonType("new-device-login"));
        var content = notificationTemplate(
            "template-one-new-device-login",
            "new-device-login",
            Instant.now(),
            "<p th:text=\"${browser}\">browser</p>")
            .getSpec().getTemplate();

        when(client.fetch(ReasonType.class, "new-device-login"))
            .thenReturn(Mono.just(reasonType()));
        mockPendingReasons(pendingReason);

        StepVerifier.create(service.saveTemplate(
                "new-device-login", content, false))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) error)
                    .getStatusCode().value()).isEqualTo(409);
                assertThat(error).hasMessageContaining("1 条待处理通知");
            })
            .verify();

        verify(client, never()).create(any(NotificationTemplate.class));
    }

    @Test
    void cancellationRollsBackAlreadyCreatedLanguageTemplates() {
        mockPendingReasons();
        var existingDefault = notificationTemplate(
            "template-default",
            "new-device-login",
            Instant.parse("2025-01-01T00:00:00Z"),
            "<p>default</p>");
        var existingChinese = notificationTemplate(
            "template-zh-cn",
            "new-device-login",
            Instant.parse("2025-01-01T00:00:01Z"),
            "<p>中文</p>");
        existingChinese.getSpec().getReasonSelector().setLanguage("zh_CN");
        var createdDefault = notificationTemplate(
            "template-one-new-device-login-default",
            "new-device-login",
            Instant.parse("2026-09-01T00:00:00Z"),
            "<p th:text=\"${browser}\">browser</p>");
        var firstCreated = Sinks.<Void>one();

        when(client.fetch(ReasonType.class, "new-device-login"))
            .thenReturn(Mono.just(reasonType()));
        when(client.listAll(eq(NotificationTemplate.class), any(), any()))
            .thenReturn(Flux.just(existingChinese, existingDefault));
        when(client.create(any(NotificationTemplate.class)))
            .thenAnswer(invocation -> {
                NotificationTemplate candidate = invocation.getArgument(0);
                var language = candidate.getSpec().getReasonSelector()
                    .getLanguage();
                if ("default".equals(language)) {
                    return Mono.just(createdDefault)
                        .doOnSuccess(ignored -> firstCreated.tryEmitEmpty());
                }
                return Mono.never();
            });
        when(client.delete(createdDefault)).thenReturn(Mono.just(createdDefault));

        StepVerifier.create(service.saveTemplate(
                "new-device-login",
                createdDefault.getSpec().getTemplate(),
                false))
            .then(() -> firstCreated.asMono().block(Duration.ofSeconds(2)))
            .thenCancel()
            .verify(Duration.ofSeconds(3));

        verify(client, timeout(2_000)).delete(createdDefault);
    }

    @Test
    void publicSendRejectsTemplateIdentityReplacedByAnotherSave() {
        var selectedCustom = notificationTemplate(
            "template-one-new-device-login-current",
            "new-device-login",
            Instant.parse("2026-09-01T00:00:01Z"),
            "<p th:text=\"${browser}\">browser</p>");
        var authentication = new TestingAuthenticationToken(
            "admin", "password", "super-role");

        when(client.fetch(User.class, "admin")).thenReturn(Mono.just(user()));
        when(client.fetch(ReasonType.class, "new-device-login"))
            .thenReturn(Mono.just(reasonType()));
        when(client.listAll(eq(NotificationTemplate.class), any(), any()))
            .thenReturn(Flux.just(selectedCustom));

        StepVerifier.create(service.sendVerification(
                "new-device-login", "template-one-new-device-login-stale")
                .contextWrite(ReactiveSecurityContextHolder
                    .withAuthentication(authentication)))
            .expectErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) error)
                    .getStatusCode().value()).isEqualTo(409);
                assertThat(error).hasMessageContaining("已被其他保存操作替换");
            })
            .verify();

        verify(notificationCenter, never()).notify(any());
    }

    @Test
    void serializesPluginOperationsForTheSameReasonType() {
        var selectedCustom = notificationTemplate(
            "template-one-new-device-login-current",
            "new-device-login",
            Instant.parse("2026-09-01T00:00:01Z"),
            "<p th:text=\"${browser}\">browser</p>");
        var authentication = new TestingAuthenticationToken(
            "admin", "password", "super-role");
        var verificationEntered = Sinks.<Void>one();
        var deletionCompleted = Sinks.<Void>one();

        when(client.fetch(User.class, "admin")).thenReturn(Mono.just(user()));
        when(client.fetch(ReasonType.class, "new-device-login"))
            .thenReturn(Mono.just(reasonType()));
        when(client.listAll(eq(NotificationTemplate.class), any(), any()))
            .thenReturn(Flux.just(selectedCustom), Flux.just(selectedCustom));
        when(notificationCenter.subscribe(any(), any()))
            .thenReturn(Mono.defer(() -> {
                verificationEntered.tryEmitEmpty();
                return Mono.never();
            }));
        when(client.delete(selectedCustom)).thenReturn(Mono.just(selectedCustom));

        var verification = service.sendVerification(
                "new-device-login", selectedCustom.getMetadata().getName())
            .contextWrite(ReactiveSecurityContextHolder
                .withAuthentication(authentication))
            .subscribe();
        verificationEntered.asMono().block(Duration.ofSeconds(2));

        var deletion = service.deleteCustomTemplates("new-device-login")
            .doOnSuccess(ignored -> deletionCompleted.tryEmitEmpty())
            .subscribe();
        StepVerifier.create(deletionCompleted.asMono())
            .expectTimeout(Duration.ofMillis(200))
            .verify();

        verification.dispose();
        deletionCompleted.asMono().block(Duration.ofSeconds(2));
        verify(client).delete(selectedCustom);
        deletion.dispose();
    }

    @Test
    void usesCorrectCoreUserApiVersionForSubscriptionSubject() {
        var interestReason = service.createInterestReason(
            "new-device-login");

        assertThat(interestReason.getSubject().getApiVersion()).isEqualTo("v1alpha1");
        assertThat(interestReason.getSubject().getKind()).isEqualTo("User");
        assertThat(interestReason.getSubject().getName())
            .startsWith("mail-template-verification-");
    }

    private static User user() {
        var metadata = new Metadata();
        metadata.setName("admin");

        var spec = new User.UserSpec();
        spec.setEmail("preview@example.com");

        var user = new User();
        user.setMetadata(metadata);
        user.setSpec(spec);
        return user;
    }

    private static ReasonType reasonType() {
        var property = new ReasonType.ReasonProperty();
        property.setName("browser");
        property.setType("string");

        var spec = new ReasonType.Spec();
        spec.setDisplayName("新设备登录");
        spec.setDescription("新设备登录提醒");
        spec.setProperties(List.of(property));

        var metadata = new Metadata();
        metadata.setName("new-device-login");

        var reasonType = new ReasonType();
        reasonType.setMetadata(metadata);
        reasonType.setSpec(spec);
        return reasonType;
    }

    private static NotificationTemplate notificationTemplate(
        String name,
        String reasonTypeName,
        Instant creationTimestamp,
        String htmlBody) {
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setCreationTimestamp(creationTimestamp);

        var content = new NotificationTemplate.Template();
        content.setTitle("[(${site.title})] test");
        content.setHtmlBody(htmlBody);
        content.setRawBody("browser: [(${browser})]");

        var selector = new NotificationTemplate.ReasonSelector();
        selector.setReasonType(reasonTypeName);
        selector.setLanguage("default");

        var spec = new NotificationTemplate.Spec();
        spec.setReasonSelector(selector);
        spec.setTemplate(content);

        var template = new NotificationTemplate();
        template.setMetadata(metadata);
        template.setSpec(spec);
        return template;
    }

    private void mockPendingReasons(Reason... reasons) {
        when(client.list(
                eq(Reason.class),
                org.mockito.ArgumentMatchers.<Predicate<Reason>>any(),
                org.mockito.ArgumentMatchers.<Comparator<Reason>>any()))
            .thenReturn(Flux.fromArray(reasons));
    }
}
