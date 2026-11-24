package com.library.users.adapter.in.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Black-box HTTP tests of the session API against a real embedded server.
 * The context is shared, so every test uses its own user names.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionControllerRestAssuredTest {

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.basePath = "/sessions";
    }

    private String login(String name) {
        return given().contentType(ContentType.JSON).body("{\"name\":\"" + name + "\"}")
                .when().post()
                .then().extract().path("token");
    }

    @Test
    void adminLoginCreatesUserAndGrantsManagementAccess() {
        given().contentType(ContentType.JSON).body("{\"name\":\"admin\"}")
        .when().post()
        .then()
            .statusCode(201)
            .header("X-Request-Id", notNullValue())
            .body("token", notNullValue())
            .body("created", equalTo(true))
            .body("user.name", equalTo("admin"))
            .body("user.isAdmin", equalTo(true))
            .body("messages", contains("Hello, admin!", "You have access to library management."));
    }

    @Test
    void repeatLoginReturns200AndKeepsOriginalSpelling() {
        login("Alice");

        given().contentType(ContentType.JSON).body("{\"name\":\"  alice \"}")
        .when().post()
        .then()
            .statusCode(200)
            .body("created", equalTo(false))
            .body("user.name", equalTo("Alice"))
            .body("user.isAdmin", equalTo(false))
            .body("messages", hasSize(1));
    }

    @Test
    void currentSessionResolvesTokenToUser() {
        String token = login("Bob");

        given().auth().oauth2(token)
        .when().get("/current")
        .then().statusCode(200).body("name", equalTo("Bob")).body("id", equalTo("bob"));
    }

    @Test
    void logoutSaysGoodbyeAndInvalidatesToken() {
        String token = login("Carol");

        given().auth().oauth2(token)
        .when().delete("/current")
        .then().statusCode(200).body("message", equalTo("Goodbye, Carol!"));

        given().auth().oauth2(token)
        .when().get("/current")
        .then().statusCode(401).body("error.code", equalTo("UNAUTHENTICATED"));
    }

    @Test
    void missingOrForgedTokenIs401() {
        given().when().get("/current").then().statusCode(401).body("error.message", equalTo("Please log in first."));
        given().auth().oauth2("forged").when().delete("/current").then().statusCode(401);
    }

    @Test
    void blankNameIs400WithErrorEnvelope() {
        given().contentType(ContentType.JSON).body("{\"name\":\"   \"}")
        .when().post()
        .then()
            .statusCode(400)
            .body("error.code", equalTo("VALIDATION_ERROR"))
            .body("error.message", equalTo("User name must not be empty."))
            .body("error.requestId", notNullValue());
    }

    @Test
    void malformedJsonIs400() {
        given().contentType(ContentType.JSON).body("{not json")
        .when().post()
        .then().statusCode(400).body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    void requestIdIsPropagated() {
        given().header("X-Request-Id", "trace-42").contentType(ContentType.JSON).body("{\"name\":\"dave\"}")
        .when().post()
        .then().header("X-Request-Id", equalTo("trace-42"));
    }
}
