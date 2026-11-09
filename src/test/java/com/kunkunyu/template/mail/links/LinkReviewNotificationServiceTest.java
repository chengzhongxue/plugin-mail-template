package com.kunkunyu.template.mail.links;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.kunkunyu.template.mail.service.impl.NotificationTemplateValidator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Unstructured;
import run.halo.app.extension.Watcher;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.ReasonPayload;
import run.halo.app.notification.UserIdentity;

class LinkReviewNotificationServiceTest {
    @Test
    void usesFrozenApprovedValuesButOriginalRejectedValues() {
        var updated = application("APPROVED", "applicant@example.com");
        var spec = new java.util.HashMap<>((Map<String, Object>) updated.getData().get("spec"));
        spec.put("approval", Map.of("request", Map.of("displayName", "审核后名称", "url", "https://approved.example")));
        updated.getData().put("spec", spec);
        var review = LinkReviewNotificationService.reviewTransition(application("APPROVING", "a@example.com"), updated);
        assertEquals("审核后名称", review.displayName());
        assertEquals("https://approved.example", review.websiteUrl());
        spec.put("status", "REJECTED");
        review = LinkReviewNotificationService.reviewTransition(application("PENDING", "a@example.com"), updated);
        assertEquals("网站", review.displayName());
    }

    @Test
    void watcherStartupFailureDoesNotEscapeAndCanRestart() {
        doThrow(new IllegalStateException("watch unavailable")).doNothing().when(client).watch(any());
        assertDoesNotThrow(service::start);
        assertDoesNotThrow(service::start);
        verify(client, times(2)).watch(any());
        service.stop();
    }

    @Test
    void malformedUpdateDoesNotEscapeWatcher() {
        service.start();
        var captor = ArgumentCaptor.forClass(Watcher.class);
        verify(client).watch(captor.capture());
        var broken = mock(run.halo.app.extension.Extension.class);
        when(broken.getApiVersion()).thenThrow(new IllegalStateException("bad resource"));
        assertDoesNotThrow(() -> captor.getValue().onUpdate(application("PENDING", "a@example.com"), broken));
        service.stop();
    }
    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final NotificationCenter center = mock(NotificationCenter.class);
    private final NotificationReasonEmitter emitter = mock(NotificationReasonEmitter.class);
    private final LinkReviewNotificationService service =
        new LinkReviewNotificationService(client, center, emitter);

    @Test
    void detectsOnlyFinalReviewTransitions() {
        for (var previous : List.of("PENDING", "APPROVING")) {
            for (var status : List.of("APPROVED", "REJECTED")) {
                var review = LinkReviewNotificationService.reviewTransition(
                    application(previous, "applicant@example.com"),
                    application(status, " applicant@example.com "));
                assertNotNull(review);
                assertEquals(status, review.status());
                assertEquals("applicant@example.com", review.email());
            }
        }
        for (var statuses : List.of(List.of("PENDING", "PENDING"),
            List.of("PENDING", "APPROVING"), List.of("APPROVED", "APPROVED"),
            List.of("REJECTED", "REJECTED"), List.of("", "APPROVED"))) {
            assertNull(LinkReviewNotificationService.reviewTransition(
                application(statuses.getFirst(), "a@example.com"),
                application(statuses.getLast(), "a@example.com")));
        }
    }

    @Test
    void ignoresMissingEmailDeletedAndUnrelatedResources() {
        var pending = application("PENDING", "a@example.com");
        assertNull(LinkReviewNotificationService.reviewTransition(pending,
            application("REJECTED", "  ")));
        var deleted = application("APPROVED", "a@example.com");
        deleted.getMetadata().setDeletionTimestamp(java.time.Instant.now());
        assertNull(LinkReviewNotificationService.reviewTransition(pending, deleted));
        var link = application("APPROVED", "a@example.com");
        link.setKind("Link");
        assertNull(LinkReviewNotificationService.reviewTransition(pending, link));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void subscribesApplicantBeforeEmittingEachResult() {
        when(center.subscribe(any(), any())).thenReturn(Mono.empty());
        when(emitter.emit(any(), any())).thenReturn(Mono.empty());
        for (var status : List.of("APPROVED", "REJECTED")) {
            service.publish(new LinkReviewNotificationService.Review(
                "application-one", status, "applicant@example.com", "网站", "https://example.com"))
                .block();
        }
        var subscribers = ArgumentCaptor.forClass(Subscription.Subscriber.class);
        var interests = ArgumentCaptor.forClass(Subscription.InterestReason.class);
        verify(center, times(2)).subscribe(subscribers.capture(), interests.capture());
        subscribers.getAllValues().forEach(subscriber -> assertEquals(
            UserIdentity.anonymousWithEmail("applicant@example.com").name(), subscriber.getName()));
        interests.getAllValues().forEach(interest -> {
            assertEquals("LinkApplication", interest.getSubject().getKind());
            assertEquals("application-one", interest.getSubject().getName());
        });
        var payload = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).emit(eq(LinkReviewNotificationService.APPROVED), payload.capture());
        var builder = ReasonPayload.builder();
        payload.getValue().accept(builder);
        assertEquals("审核通过", builder.build().getAttributes().get("reviewResult"));
        verify(emitter).emit(eq(LinkReviewNotificationService.REJECTED), any());
        var order = inOrder(center, emitter);
        order.verify(center).subscribe(any(), any());
        order.verify(emitter).emit(eq(LinkReviewNotificationService.APPROVED), any());
        verify(center, never()).unsubscribe(any(), any());
    }

    @Test
    void doesNotEmitWhenSubscriptionFails() {
        when(center.subscribe(any(), any())).thenReturn(Mono.error(new IllegalStateException("failed")));
        assertThrows(IllegalStateException.class, () -> service.publish(
            new LinkReviewNotificationService.Review("a", "APPROVED", "a@example.com", "A", "url"))
            .block());
        verifyNoInteractions(emitter);
    }

    @Test
    void registersOneWatcherAndDisposesItOnStop() {
        service.start();
        service.start();
        var captor = ArgumentCaptor.forClass(Watcher.class);
        verify(client).watch(captor.capture());
        var watcher = captor.getValue();
        var hook = mock(Runnable.class);
        watcher.registerDisposeHook(hook);
        // Startup/add events must never notify historical approved applications.
        watcher.onAdd(application("APPROVED", "a@example.com"));
        verifyNoInteractions(center, emitter);
        service.stop();
        assertTrue(watcher.isDisposed());
        verify(hook).run();
        watcher.onUpdate(application("PENDING", "a@example.com"),
            application("APPROVED", "a@example.com"));
        verifyNoInteractions(center, emitter);
        service.start();
        verify(client, times(2)).watch(any());
        service.stop();
    }

    @Test
    void defaultTemplatesDeclareAllAttributesAndRenderSuccessfully() throws Exception {
        for (var suffix : List.of("approved", "rejected")) {
            try (var stream = getClass().getResourceAsStream(
                "/extensions/link-application-" + suffix + ".yaml")) {
                assertNotNull(stream);
                var documents = new Yaml().loadAll(stream).iterator();
                var reason = Unstructured.OBJECT_MAPPER.convertValue(documents.next(), ReasonType.class);
                var template = Unstructured.OBJECT_MAPPER.convertValue(documents.next(), NotificationTemplate.class);
                assertEquals(reason.getMetadata().getName(), template.getSpec().getReasonSelector().getReasonType());
                assertEquals(List.of("displayName", "websiteUrl", "reviewResult"),
                    reason.getSpec().getProperties().stream().map(ReasonType.ReasonProperty::getName).toList());
                new NotificationTemplateValidator().validate(template.getSpec().getTemplate(), reason);
            }
        }
    }

    private static Unstructured application(String status, String email) {
        return new Unstructured(new java.util.HashMap<>(Map.of(
            "apiVersion", "core.halo.run/v1alpha1", "kind", "LinkApplication",
            "metadata", new java.util.HashMap<>(Map.of("name", "application-one")),
            "spec", Map.of("status", status, "email", email,
                "displayName", "网站", "url", "https://example.com"))));
    }
}
