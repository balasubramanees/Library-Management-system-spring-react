package com.library.books.domain;

public class BookAvailableException extends LibraryException {
    public BookAvailableException(String title) {
        super("\"" + title + "\" is available right now - borrow it instead of joining the wait list.");
    }

    @Override
    public String code() {
        return "BOOK_AVAILABLE";
    }
}
