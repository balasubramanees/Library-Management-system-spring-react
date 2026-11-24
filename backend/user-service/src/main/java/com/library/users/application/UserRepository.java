package com.library.users.application;

import com.library.users.domain.User;
import java.util.Optional;

/** Driven port: user storage. */
public interface UserRepository {
    Optional<User> findById(String id);

    /** Saves the user unless one with the same id exists; returns the stored user. */
    User saveIfAbsent(User user);
}
