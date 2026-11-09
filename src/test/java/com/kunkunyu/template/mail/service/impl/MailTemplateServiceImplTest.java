package com.kunkunyu.template.mail.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;

class MailTemplateServiceImplTest {
    @Test
    void verifiesOverrideAndKeepsSubscriptionForAsynchronousRecipientResolution() {
        var client = mock(ReactiveExtensionClient.class);
        var center = mock(NotificationCenter.class);
        var emitter = mock(NotificationReasonEmitter.class);
        var validator = mock(NotificationTemplateValidator.class);
        var service = new MailTemplateServiceImpl(client, emitter, center, validator);
        var reason = new ReasonType();
        var metadata = new Metadata();
        metadata.setName("comment");
        reason.setMetadata(metadata);
        var reasonSpec = new ReasonType.Spec();
        reasonSpec.setDisplayName("Comment");
        reason.setSpec(reasonSpec);
        var base = template("base");
        var override = template("template-one-comment");
        when(client.listAll(eq(NotificationTemplate.class), any(), any()))
            .thenReturn(Flux.fromIterable(List.of(base, override)));
        when(center.subscribe(any(), any())).thenReturn(Mono.empty());
        when(emitter.emit(any(), any())).thenReturn(Mono.empty());
        service.sendVerifyNotification(reason, "admin", "admin@example.com").block();
        verify(validator).validate(override.getSpec().getTemplate(), reason);
        verify(emitter).emit(eq("comment"), any());
        verify(center, never()).unsubscribe(any(), any());
        org.junit.jupiter.api.Assertions.assertEquals("v1alpha1",
            service.createInterestReason("admin@example.com", "comment").getSubject().getApiVersion());
    }

    private NotificationTemplate template(String name) {
        var template = new NotificationTemplate();
        var metadata = new Metadata();
        metadata.setName(name);
        template.setMetadata(metadata);
        var spec = new NotificationTemplate.Spec();
        spec.setTemplate(new NotificationTemplate.Template());
        template.setSpec(spec);
        return template;
    }
}
