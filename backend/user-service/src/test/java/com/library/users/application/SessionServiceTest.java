package com.library.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.library.users.adapter.out.memory.InMemorySessionStore;
import com.library.users.adapter.out.memory.InMemoryUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final SessionService service =
            new SessionService(new InMemoryUserRepository(), new InMemorySessionStore(Duration.ofMinutes(30), clock));

    @Test
    void firstLoginCreatesUserAndSecondReusesIt() {
        LoginResult first = service.login("Alice");
        LoginResult second = service.login("alice");

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.user().name()).as("display name from first login").isEqualTo("Alice");
        assertThat(first.token()).isNotEqualTo(second.token());
    }

    @Test
    void tokenResolvesToUserUntilLogout() {
        String token = service.login("Bob").token();
        assertThat(service.currentUser(token).name()).isEqualTo("Bob");

        assertThat(service.logout(token).name()).isEqualTo("Bob");

        assertThatThrownBy(() -> service.currentUser(token)).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> service.logout(token)).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void sessionsExpireAfterIdleTimeoutButActivityKeepsThemAlive() {
        String token = service.login("Carol").token();

        now.set(now.get().plus(Duration.ofMinutes(20)));
        assertThat(service.currentUser(token).name()).isEqualTo("Carol");

        now.set(now.get().plus(Duration.ofMinutes(20)));
        assertThat(service.currentUser(token).name()).as("activity refreshed the idle timer").isEqualTo("Carol");

        now.set(now.get().plus(Duration.ofMinutes(31)));
        assertThatThrownBy(() -> service.currentUser(token)).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void unknownTokenIsRejected() {
        assertThatThrownBy(() -> service.currentUser("forged")).isInstanceOf(UnauthenticatedException.class);
    }
}
