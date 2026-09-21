package com.kunkunyu.template.mail.service;

import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.ReasonType;

public interface MailTemplateService {

    Mono<Void> verifyMailTemplatSend(String reasonTypeName);

    Mono<Void> verifyMailTemplatSend(ReasonType reasonType);
}
