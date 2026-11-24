package com.library.books.domain;

public class BorrowingBlockedException extends LibraryException {
    public BorrowingBlockedException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "BORROWING_BLOCKED";
    }
}
