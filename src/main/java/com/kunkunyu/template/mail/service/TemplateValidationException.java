package com.kunkunyu.template.mail.service;

import org.springframework.web.server.ServerWebInputException;

public final class TemplateValidationException extends ServerWebInputException {

    public TemplateValidationException(String reason) {
        super(reason);
    }

    public TemplateValidationException(String reason, Throwable cause) {
        super(reason, null, cause);
    }
}
