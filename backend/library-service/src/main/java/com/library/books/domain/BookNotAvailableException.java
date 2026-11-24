package com.library.books.domain;

public class BookNotAvailableException extends LibraryException {
    public BookNotAvailableException(String title) {
        super("Sorry, \"" + title + "\" is currently not available.");
    }

    @Override
    public String code() {
        return "BOOK_NOT_AVAILABLE";
    }
}
