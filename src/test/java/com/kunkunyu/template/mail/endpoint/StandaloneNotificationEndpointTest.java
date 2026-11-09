package com.kunkunyu.template.mail.endpoint;

import static org.mockito.Mockito.*;

import com.kunkunyu.template.mail.standalone.StandaloneNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import reactor.core.publisher.Mono;

class StandaloneNotificationEndpointTest {
    @Test
    void authenticatedRequestsValidateBodyAndForwardIdentity() {
        var service = mock(StandaloneNotificationService.class);
        when(service.send(anyString(), any(), anyString())).thenReturn(Mono.empty());
        var client = WebTestClient.bindToRouterFunction(new StandaloneNotificationEndpoint(service).endpoint())
            .webFilter((exchange, chain) -> chain.filter(exchange.mutate().principal(Mono.just(
                UsernamePasswordAuthenticationToken.authenticated("technical-user", "", java.util.List.of()))).build()))
            .build();
        client.post().uri("/standalone-notifications/draft/send")
            .bodyValue(java.util.Map.of("recipient", "one@example.com", "parameters", java.util.Map.of("order", 1)))
            .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.status").isEqualTo("accepted");
        verify(service).send(eq("draft"), any(), eq("technical-user"));
        client.post().uri("/standalone-notifications/draft/preview").bodyValue("not json")
            .exchange().expectStatus().isBadRequest();
        client.post().uri("/standalone-notifications/draft/preview").bodyValue("x".repeat(65 * 1024))
            .exchange().expectStatus().isEqualTo(413);
        verify(service, never()).preview(anyString(), any());
    }

    @Test
    void rejectsAnonymousSendAndPreviewBeforeReadingTemplates() {
        var service = mock(StandaloneNotificationService.class);
        var client = WebTestClient.bindToRouterFunction(new StandaloneNotificationEndpoint(service).endpoint()).build();
        for (String action : java.util.List.of("send", "preview")) {
            client.post().uri("/standalone-notifications/draft/" + action)
                .bodyValue(java.util.Map.of("recipient", "one@example.com", "parameters", java.util.Map.of()))
                .exchange().expectStatus().isUnauthorized();
        }
        verifyNoInteractions(service);
    }
}
