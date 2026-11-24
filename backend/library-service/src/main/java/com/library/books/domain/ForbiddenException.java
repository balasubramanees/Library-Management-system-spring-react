package com.library.books.domain;

public class ForbiddenException extends LibraryException {
    public ForbiddenException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "FORBIDDEN";
    }
}
