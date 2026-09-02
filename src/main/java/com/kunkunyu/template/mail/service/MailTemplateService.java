package com.kunkunyu.template.mail.service;

import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.NotificationTemplate;

public interface MailTemplateService {

    String CUSTOM_TEMPLATE_PREFIX = "template-one-";

    Mono<Void> validateTemplate(
        String reasonTypeName, NotificationTemplate.Template template);

    Mono<Long> countPendingReasons(String reasonTypeName);

    Mono<NotificationTemplate> saveTemplate(
        String reasonTypeName,
        NotificationTemplate.Template template,
        boolean allowPendingReplay);

    Mono<Void> deleteCustomTemplates(String reasonTypeName);

    Mono<Void> sendVerification(String reasonTypeName, String templateName);
}
