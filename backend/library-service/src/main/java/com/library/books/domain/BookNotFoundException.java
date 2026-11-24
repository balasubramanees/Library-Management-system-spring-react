package com.library.books.domain;

public class BookNotFoundException extends LibraryException {
    public BookNotFoundException(String title) {
        super("Sorry, \"" + title + "\" is not registered.");
    }

    @Override
    public String code() {
        return "BOOK_NOT_FOUND";
    }
}
