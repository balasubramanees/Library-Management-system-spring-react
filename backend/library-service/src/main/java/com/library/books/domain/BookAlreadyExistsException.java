package com.library.books.domain;

public class BookAlreadyExistsException extends LibraryException {
    public BookAlreadyExistsException(String title) {
        super("Sorry, \"" + title + "\" is already registered in the library.");
    }

    @Override
    public String code() {
        return "BOOK_ALREADY_EXISTS";
    }
}
