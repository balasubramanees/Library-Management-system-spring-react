package com.library.users.config;

import com.library.users.adapter.out.memory.InMemorySessionStore;
import com.library.users.adapter.out.memory.InMemoryUserRepository;
import com.library.users.application.SessionService;
import com.library.users.application.SessionStore;
import com.library.users.application.UserRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root: the only place that knows which adapters implement which ports. */
@Configuration
public class UserServiceConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    UserRepository userRepository() {
        return new InMemoryUserRepository();
    }

    @Bean
    SessionStore sessionStore(@Value("${library.session.idle-timeout:PT30M}") Duration idleTimeout, Clock clock) {
        return new InMemorySessionStore(idleTimeout, clock);
    }

    @Bean
    SessionService sessionService(UserRepository users, SessionStore sessions) {
        return new SessionService(users, sessions);
    }
}
