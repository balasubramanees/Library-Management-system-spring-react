package com.library.books.domain;

public class NotBorrowedByMemberException extends LibraryException {
    public NotBorrowedByMemberException(String title) {
        super("Sorry, you didn't borrow \"" + title + "\".");
    }

    @Override
    public String code() {
        return "NOT_BORROWED_BY_USER";
    }
}
