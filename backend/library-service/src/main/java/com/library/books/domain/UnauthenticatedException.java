package com.library.books.domain;

public class UnauthenticatedException extends LibraryException {
    public UnauthenticatedException() {
        super("Please log in first.");
    }

    @Override
    public String code() {
        return "UNAUTHENTICATED";
    }
}
