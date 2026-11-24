package com.library.books.adapter.out.userservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.library.books.application.port.MemberDirectory;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Exercises the real Resilience4j wiring (retry + circuit breaker aspects) against a fake user-service
 * running on a JDK HTTP server whose behaviour each test controls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserServiceClientResilienceTest {

    private static final AtomicInteger hits = new AtomicInteger();
    private static volatile int statusToReturn = 200;
    private static final HttpServer fakeUserService = startFakeUserService();

    @Autowired
    MemberDirectory directory;

    @Autowired
    CircuitBreakerRegistry breakers;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("library.user-service.base-url",
                () -> "http://localhost:" + fakeUserService.getAddress().getPort());
        registry.add("resilience4j.circuitbreaker.instances.userService.sliding-window-size", () -> "4");
        registry.add("resilience4j.circuitbreaker.instances.userService.minimum-number-of-calls", () -> "4");
        registry.add("resilience4j.circuitbreaker.instances.userService.wait-duration-in-open-state", () -> "60s");
        registry.add("resilience4j.circuitbreaker.instances.userService.automatic-transition-from-open-to-half-open-enabled", () -> "false");
        registry.add("resilience4j.retry.instances.userService.max-attempts", () -> "2");
        registry.add("resilience4j.retry.instances.userService.wait-duration", () -> "10ms");
    }

    @BeforeEach
    void reset() {
        hits.set(0);
        statusToReturn = 200;
        breaker().reset();
    }

    @AfterAll
    static void stopServer() {
        fakeUserService.stop(0);
    }

    @Test
    void validTokenResolvesToMember() {
        assertThat(directory.findByToken("good")).hasValueSatisfying(member -> {
            assertThat(member.id()).isEqualTo("admin");
            assertThat(member.admin()).isTrue();
        });
    }

    @Test
    void invalidTokenIsNotAFailure() {
        statusToReturn = 401;
        for (int i = 0; i < 10; i++) {
            assertThat(directory.findByToken("bad")).isEmpty();
        }
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(hits.get()).as("401 is not retried").isEqualTo(10);
    }

    @Test
    void serverErrorsAreRetriedThenOpenTheCircuitWhichFailsFast() {
        statusToReturn = 500;

        assertThatThrownBy(() -> directory.findByToken("t")).isInstanceOf(UserServiceUnavailableException.class);
        assertThat(hits.get()).as("one retry").isEqualTo(2);
        assertThatThrownBy(() -> directory.findByToken("t")).isInstanceOf(UserServiceUnavailableException.class);

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int hitsWhenOpened = hits.get();
        assertThatThrownBy(() -> directory.findByToken("t")).isInstanceOf(CallNotPermittedException.class);
        assertThat(hits.get()).as("open circuit does not call user-service").isEqualTo(hitsWhenOpened);
    }

    @Test
    void forcedOpenCircuitFailsFastWithoutCallingUserService() {
        CircuitBreaker breaker = breaker();
        breaker.transitionToForcedOpenState();
        assertThatThrownBy(() -> directory.findByToken("t")).isInstanceOf(CallNotPermittedException.class);
        assertThat(hits.get()).isZero();
    }

    private CircuitBreaker breaker() {
        return breakers.circuitBreaker("userService");
    }

    private static HttpServer startFakeUserService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/sessions/current", exchange -> {
                hits.incrementAndGet();
                int status = statusToReturn;
                byte[] body = (status == 200
                        ? "{\"id\":\"admin\",\"name\":\"admin\",\"isAdmin\":true}"
                        : "{\"error\":{\"code\":\"X\",\"message\":\"x\",\"requestId\":\"r\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
