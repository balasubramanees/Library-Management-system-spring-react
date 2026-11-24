package com.library.gateway;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

import com.sun.net.httpserver.HttpServer;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the real gateway against a fake user-service (JDK HTTP server). library-service is pointed at
 * a port where nothing listens, to exercise the circuit breaker fallback.
 * Each test uses its own X-Forwarded-For so per-client rate limits do not interfere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRestAssuredTest {

    private static final HttpServer fakeUserService = startFakeUserService();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("library.gateway.user-service-url", () -> "http://localhost:" + fakeUserService.getAddress().getPort());
        registry.add("library.gateway.library-service-url", () -> "http://localhost:1");
        registry.add("library.gateway.rate-limit.capacity", () -> "5");
        registry.add("library.gateway.rate-limit.refill-per-second", () -> "0.01");
    }

    @AfterAll
    static void stop() {
        fakeUserService.stop(0);
    }

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void routesSessionsToUserServiceStrippingApiPrefix() {
        given().header("X-Forwarded-For", "10.0.0.1").contentType(ContentType.JSON).body("{\"name\":\"alice\"}")
        .when().post("/api/sessions")
        .then()
            .statusCode(201)
            .body("path", equalTo("/sessions"))
            .body("method", equalTo("POST"));
    }

    @Test
    void createsAndPropagatesRequestId() {
        given().header("X-Forwarded-For", "10.0.0.2")
        .when().get("/api/sessions/current")
        .then()
            .statusCode(200)
            .header("X-Request-Id", matchesPattern("[0-9a-f-]{36}"))
            .header("X-Content-Type-Options", "nosniff");

        given().header("X-Forwarded-For", "10.0.0.2").header("X-Request-Id", "trace-1")
        .when().get("/api/sessions/current")
        .then()
            .header("X-Request-Id", "trace-1")
            .body("receivedRequestId", equalTo("trace-1"));
    }

    @Test
    void downstreamServerErrorIsTurnedIntoFallback503() {
        given().header("X-Forwarded-For", "10.0.0.3")
        .when().delete("/api/sessions/boom")
        .then()
            .statusCode(503)
            .header("Retry-After", "10")
            .body("error.code", equalTo("SERVICE_UNAVAILABLE"))
            .body("error.message", equalTo("The user service is temporarily unavailable. Please try again in a few seconds."));
    }

    @Test
    void unreachableDownstreamIsTurnedIntoFallback503() {
        given().header("X-Forwarded-For", "10.0.0.4").header("Authorization", "Bearer x")
        .when().get("/api/books")
        .then()
            .statusCode(503)
            .body("error.code", equalTo("SERVICE_UNAVAILABLE"))
            .body("error.message", equalTo("The library service is temporarily unavailable. Please try again in a few seconds."));
    }

    @Test
    void businessErrorsPassThroughUntouched() {
        given().header("X-Forwarded-For", "10.0.0.5")
        .when().get("/api/sessions/unauthorized")
        .then().statusCode(401).body("error.code", equalTo("UNAUTHENTICATED"));
    }

    @Test
    void rateLimitReturns429WithRetryAfter() {
        for (int i = 0; i < 5; i++) {
            given().header("X-Forwarded-For", "10.0.0.6").when().get("/api/sessions/current").then().statusCode(200);
        }
        given().header("X-Forwarded-For", "10.0.0.6")
        .when().get("/api/sessions/current")
        .then()
            .statusCode(429)
            .header("Retry-After", matchesPattern("\\d+"))
            .body("error.code", equalTo("RATE_LIMITED"));

        given().header("X-Forwarded-For", "10.0.0.7").when().get("/api/sessions/current").then().statusCode(200);
    }

    @Test
    void healthEndpointIsUp() {
        given().when().get("/actuator/health").then().statusCode(200).body("status", equalTo("UP"));
    }

    private static HttpServer startFakeUserService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                exchange.getRequestBody().readAllBytes();
                int status;
                String body;
                if (path.endsWith("/boom")) {
                    status = 500;
                    body = "{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"x\",\"requestId\":\"r\"}}";
                } else if (path.endsWith("/unauthorized")) {
                    status = 401;
                    body = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"Please log in first.\",\"requestId\":\"r\"}}";
                } else {
                    status = "POST".equals(exchange.getRequestMethod()) ? 201 : 200;
                    body = "{\"path\":\"" + path + "\",\"method\":\"" + exchange.getRequestMethod()
                            + "\",\"receivedRequestId\":\"" + exchange.getRequestHeaders().getFirst("X-Request-Id") + "\"}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
