package com.library.books.domain;

public class ValidationException extends LibraryException {
    public ValidationException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "VALIDATION_ERROR";
    }
}
