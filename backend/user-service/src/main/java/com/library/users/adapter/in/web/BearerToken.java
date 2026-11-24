package com.library.users.adapter.in.web;

import com.library.users.application.UnauthenticatedException;

final class BearerToken {

    private static final String PREFIX = "Bearer ";

    private BearerToken() {}

    static String from(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            throw new UnauthenticatedException();
        }
        String token = authorizationHeader.substring(PREFIX.length()).strip();
        if (token.isEmpty()) {
            throw new UnauthenticatedException();
        }
        return token;
    }
}
