package com.library.books.domain;

public class ExtensionNotAllowedException extends LibraryException {
    public ExtensionNotAllowedException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "EXTENSION_NOT_ALLOWED";
    }
}
