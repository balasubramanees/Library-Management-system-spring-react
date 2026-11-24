package com.library.users.application;

import java.util.Optional;

/** Driven port: opaque bearer-token sessions. */
public interface SessionStore {
    String create(String userId);

    /** Resolves a token to a user id and refreshes its idle timeout. */
    Optional<String> resolve(String token);

    void revoke(String token);
}
