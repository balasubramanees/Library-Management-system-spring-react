package com.library.users.application;

public class UnauthenticatedException extends RuntimeException {
    public UnauthenticatedException() {
        super("Please log in first.");
    }
}
