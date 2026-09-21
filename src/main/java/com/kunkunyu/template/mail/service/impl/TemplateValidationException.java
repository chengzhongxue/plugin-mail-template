package com.kunkunyu.template.mail.service.impl;

public class TemplateValidationException extends RuntimeException {

    public TemplateValidationException(String message) {
        super(message);
    }

    public TemplateValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
