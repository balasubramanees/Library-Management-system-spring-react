package com.library.users.domain;

/** A library member. The reserved user {@value #ADMIN_ID} may manage the catalogue. */
public record User(String id, String name) {

    public static final String ADMIN_ID = "admin";

    public static User register(UserName name) {
        return new User(name.key(), name.value());
    }

    public boolean isAdmin() {
        return ADMIN_ID.equals(id);
    }
}
