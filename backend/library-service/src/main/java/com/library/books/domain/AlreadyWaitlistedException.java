package com.library.books.domain;

public class AlreadyWaitlistedException extends LibraryException {
    private final int position;

    public AlreadyWaitlistedException(String title, int position) {
        super("You are already on the wait list of \"" + title + "\", your position is " + position + ".");
        this.position = position;
    }

    public int position() {
        return position;
    }

    @Override
    public String code() {
        return "ALREADY_WAITLISTED";
    }
}
