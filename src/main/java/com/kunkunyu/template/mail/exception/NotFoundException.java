package com.kunkunyu.template.mail.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * NotFoundException.
 *
 */
public class NotFoundException extends ResponseStatusException {
    public NotFoundException(String reason) {
        super(HttpStatus.NOT_FOUND, reason);
    }
}
