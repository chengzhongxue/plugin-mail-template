package com.kunkunyu.template.mail.standalone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;
import run.halo.app.core.extension.notification.Subscription;

class StandaloneNotificationServiceTest {
    @Test
    void rendersNestedDataArraysAndEscapesHtmlWithoutEvaluatingDataAsCode() {
        var input = new StandaloneNotificationService.Input("one@example.com", Map.of(
            "order", Map.of("number", "<script>alert(1)</script>"),
            "items", List.of(Map.of("name", "[[${7*7}]]")), "recipient", "wrong@example.com"));
        var rendered = StandaloneNotificationService.render(Map.of(
            "title", "订单 [[${items.size()}]]",
            "htmlBody", "<p th:text=\"${order.number}\"></p><b th:text=\"${items[0].name}\"></b>",
            "rawBody", "[[${recipient}]]"), input);
        assertEquals("订单 1", rendered.title());
        assertTrue(rendered.htmlBody().contains("&lt;script&gt;"));
        assertTrue(rendered.htmlBody().contains("[[${7*7}]]"));
        assertEquals("one@example.com", rendered.rawBody());
    }

    @Test
    void rejectsMultipleRecipientsHeaderInjectionAndOversizedParameters() {
        for (String email : List.of("one@example.com,two@example.com", "one@example.com\r\nBcc:x@y.com", "invalid")) {
            assertThrows(ResponseStatusException.class, () -> StandaloneNotificationService.validate(
                new StandaloneNotificationService.Input(email, Map.of())));
        }
        assertThrows(ResponseStatusException.class, () -> StandaloneNotificationService.validate(
            new StandaloneNotificationService.Input("one@example.com", Map.of("text", "a".repeat(50 * 1024)))));
        assertThrows(ResponseStatusException.class, () -> StandaloneNotificationService.validate(
            new StandaloneNotificationService.Input("one@example.com", null)));
    }

    @Test
    void rejectsDeepOrExcessiveParameterTrees() {
        Map<String, Object> deep = Map.of("value", "text");
        for (int i = 0; i < 18; i++) deep = Map.of("next", deep);
        var input = new StandaloneNotificationService.Input("one@example.com", deep);
        assertThrows(ResponseStatusException.class, () -> StandaloneNotificationService.validate(input));
        assertThrows(ResponseStatusException.class, () -> StandaloneNotificationService.validate(
            new StandaloneNotificationService.Input("one@example.com", Map.of("items", java.util.Collections.nCopies(2001, 1)))));
    }

    @Test
    void rejectsInvalidExpressionsAndOversizedOutput() {
        var input = new StandaloneNotificationService.Input("one@example.com", Map.of("chunk", "a".repeat(1000),
            "items", java.util.Collections.nCopies(1000, 1)));
        assertThrows(ResponseStatusException.class, () -> StandaloneNotificationService.render(
            Map.of("title", "Hello", "htmlBody", "<p th:text=\"${missing.name}\"></p>"), input));
        assertThrows(ResponseStatusException.class, () -> StandaloneNotificationService.render(
            Map.of("title", "Hello", "htmlBody", "<p th:each=\"i : ${items}\" th:text=\"${chunk}\"></p>"), input));
    }

    @Test
    void previewDoesNotSendAndDisabledTemplateCannotSend() {
        var client = mock(ReactiveExtensionClient.class);
        var center = mock(NotificationCenter.class);
        var emitter = mock(NotificationReasonEmitter.class);
        var template = template(false);
        when(client.fetch(ConfigMap.class, "draft")).thenReturn(Mono.just(template));
        var service = new StandaloneNotificationService(client, center, emitter);
        var input = new StandaloneNotificationService.Input("one@example.com", Map.of());
        assertEquals("Hello", service.preview("draft", input).block().title());
        assertThrows(ResponseStatusException.class, () -> service.send("draft", input, "admin").block());
        verifyNoInteractions(center, emitter);
        template.getMetadata().setLabels(Map.of());
        assertThrows(ResponseStatusException.class, () -> service.preview("draft", input).block());
    }

    @Test
    void isolatesRecipientsAndLimitsSendingWithoutImmediateUnsubscribe() {
        var client = mock(ReactiveExtensionClient.class);
        var center = mock(NotificationCenter.class);
        var emitter = mock(NotificationReasonEmitter.class);
        when(client.fetch(ConfigMap.class, "draft")).thenReturn(Mono.just(template(true)));
        when(center.subscribe(any(), any())).thenReturn(Mono.empty());
        when(emitter.emit(any(), any())).thenReturn(Mono.empty());
        var service = new StandaloneNotificationService(client, center, emitter);
        for (int i = 0; i < 30; i++) service.send("draft",
            new StandaloneNotificationService.Input("user" + i + "@example.com", Map.of()), "admin").block();
        var interests = ArgumentCaptor.forClass(Subscription.InterestReason.class);
        verify(center, times(30)).subscribe(any(), interests.capture());
        assertEquals(30, interests.getAllValues().stream().map(value -> value.getSubject().getName()).distinct().count());
        assertEquals(UserIdentity.anonymousWithEmail("user0@example.com").name(),
            interests.getAllValues().getFirst().getSubject().getName());
        var error = assertThrows(ResponseStatusException.class, () -> service.send("draft",
            new StandaloneNotificationService.Input("next@example.com", Map.of()), "admin").block());
        assertEquals(429, error.getStatusCode().value());
        verify(center, never()).unsubscribe(any(), any());
        verify(emitter, times(30)).emit(eq(StandaloneNotificationService.REASON), any());
    }

    private ConfigMap template(boolean enabled) {
        var template = new ConfigMap();
        var metadata = new Metadata();
        metadata.setName("draft");
        metadata.setLabels(Map.of("mail-template.kunkunyu.com/custom-template", "true"));
        template.setMetadata(metadata);
        template.setData(new HashMap<>(Map.of("mode", "standalone", "title", "Hello",
            "enabled", Boolean.toString(enabled), "htmlBody", "<p>Hello</p>")));
        return template;
    }
}
