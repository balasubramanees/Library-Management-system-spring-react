package com.library.books.domain;

public class AlreadyBorrowedException extends LibraryException {
    public AlreadyBorrowedException(String title) {
        super("You have already borrowed \"" + title + "\".");
    }

    @Override
    public String code() {
        return "ALREADY_BORROWED";
    }
}
