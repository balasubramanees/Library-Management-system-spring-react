package com.library.users.domain;

/** Input that violates a domain rule (empty name, too long, ...). */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
