package com.library.users.adapter.out.memory;

import com.library.users.application.UserRepository;
import com.library.users.domain.User;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Process-local storage: every restart is a clean slate, as the brief requires. */
public class InMemoryUserRepository implements UserRepository {

    private final ConcurrentMap<String, User> users = new ConcurrentHashMap<>();

    @Override
    public Optional<User> findById(String id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public User saveIfAbsent(User user) {
        User existing = users.putIfAbsent(user.id(), user);
        return existing != null ? existing : user;
    }
}
