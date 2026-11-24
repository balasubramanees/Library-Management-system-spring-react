package com.library.users.application;

import com.library.users.domain.User;
import com.library.users.domain.UserName;

/**
 * Use cases for {@code login [name]} and {@code logout}. There are no passwords in the brief, so
 * logging in with an unknown name registers that user (implicit registration).
 */
public class SessionService {

    private final UserRepository users;
    private final SessionStore sessions;

    public SessionService(UserRepository users, SessionStore sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    public LoginResult login(String rawName) {
        UserName name = UserName.of(rawName);
        User candidate = User.register(name);
        // Atomic get-or-create: two concurrent first logins of "alice" yield one user.
        User stored = users.saveIfAbsent(candidate);
        boolean created = stored == candidate;
        return new LoginResult(stored, created, sessions.create(stored.id()));
    }

    public User currentUser(String token) {
        return sessions.resolve(token)
                .flatMap(users::findById)
                .orElseThrow(UnauthenticatedException::new);
    }

    public User logout(String token) {
        User user = currentUser(token);
        sessions.revoke(token);
        return user;
    }
}
