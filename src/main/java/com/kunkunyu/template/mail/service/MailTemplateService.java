package com.kunkunyu.template.mail.service;

import reactor.core.publisher.Mono;

public interface MailTemplateService {

    Mono<Void> verifyMailTemplatSend(String reasonTypeName);
}
