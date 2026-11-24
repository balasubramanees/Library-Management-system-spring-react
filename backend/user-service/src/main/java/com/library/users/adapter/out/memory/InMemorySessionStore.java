package com.library.users.adapter.out.memory;

import com.library.users.application.SessionStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Random 256-bit bearer tokens with an idle timeout. Tokens die with the process, so restarting the
 * stack invalidates all sessions (the browser then falls back to the login screen).
 */
public class InMemorySessionStore implements SessionStore {

    private record Session(String userId, Instant lastSeen) {}

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration idleTimeout;
    private final Clock clock;

    public InMemorySessionStore(Duration idleTimeout, Clock clock) {
        this.idleTimeout = idleTimeout;
        this.clock = clock;
    }

    @Override
    public String create(String userId) {
        evictExpired();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(userId, clock.instant()));
        return token;
    }

    @Override
    public Optional<String> resolve(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Session refreshed = sessions.computeIfPresent(token,
                (key, session) -> isExpired(session, now) ? null : new Session(session.userId(), now));
        return Optional.ofNullable(refreshed).map(Session::userId);
    }

    @Override
    public void revoke(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private void evictExpired() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }

    private boolean isExpired(Session session, Instant now) {
        return session.lastSeen().plus(idleTimeout).isBefore(now);
    }
}
