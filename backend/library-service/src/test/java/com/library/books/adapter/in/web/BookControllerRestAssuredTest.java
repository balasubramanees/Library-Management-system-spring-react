package com.library.books.adapter.in.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

import com.library.books.adapter.out.userservice.UserServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/** Edge cases, authorisation and error mapping of the library REST API. */
class BookControllerRestAssuredTest extends RestAssuredSupport {

    @Test
    void requestsWithoutTokenAre401() {
        given().when().get("/books").then()
                .statusCode(401)
                .body("error.code", equalTo("UNAUTHENTICATED"))
                .body("error.message", equalTo("Please log in first."))
                .body("error.requestId", notNullValue());
    }

    @Test
    void expiredTokenIs401() {
        given().auth().oauth2("expired-token").when().get("/me/status").then().statusCode(401);
    }

    @Test
    void nonAdminCannotAddBooks() {
        as("Alice").body(title("Dune")).when().post("/books").then()
                .statusCode(403)
                .body("error.code", equalTo("FORBIDDEN"))
                .body("error.message", equalTo("Sorry, only the admin can add books to the library."));
    }

    @Test
    void adminCannotBorrowReturnOrWaitlist() {
        add("Ulysses").statusCode(201);
        borrow("admin", "Ulysses").statusCode(403)
                .body("error.code", equalTo("FORBIDDEN"))
                .body("error.message", equalTo(
                        "Sorry, the admin account manages the library and cannot borrow, return or join wait lists."));
        giveBack("admin", "Ulysses").statusCode(403);
        borrow("Alice", "Ulysses").statusCode(200);
        waitlist("admin", "Ulysses").statusCode(403);
        extend("admin", "Ulysses").statusCode(403);
        deposit("admin", 500).statusCode(403);
    }

    @Test
    void duplicateTitleIs409() {
        add("Moby Dick").statusCode(201);
        add("  moby   DICK ").statusCode(409).body("error.code", equalTo("BOOK_ALREADY_EXISTS"));
    }

    @Test
    void blankAndMissingTitlesAre400() {
        add("   ").statusCode(400).body("error.message", equalTo("Book title must not be empty."));
        as("admin").body("{}").when().post("/books").then().statusCode(400).body("error.code", equalTo("VALIDATION_ERROR"));
        as("admin").body("{oops").when().post("/books").then().statusCode(400);
    }

    @Test
    void wrongContentTypeIs415() {
        given().auth().oauth2("admin-token").contentType(ContentType.TEXT).body("Dune")
                .when().post("/books").then().statusCode(415);
    }

    @Test
    void borrowingTwiceAndWaitlistingAvailableOrOwnBookAre409() {
        add("Emma").statusCode(201);
        waitlist("Bob", "Emma").statusCode(409).body("error.code", equalTo("BOOK_AVAILABLE"));
        borrow("Bob", "Emma").statusCode(200);
        borrow("Bob", "Emma").statusCode(409).body("error.code", equalTo("ALREADY_BORROWED"));
        waitlist("Bob", "Emma").statusCode(409).body("error.code", equalTo("ALREADY_BORROWED"));
        waitlist("Carol", "Emma").statusCode(200);
        waitlist("Carol", "Emma").statusCode(409).body("error.code", equalTo("ALREADY_WAITLISTED"));
    }

    @Test
    void returningUnknownBookIs404() {
        giveBack("Alice", "Nope").statusCode(404).body("error.message", equalTo("Sorry, \"Nope\" is not registered."));
    }

    @Test
    void userServiceOutageIs503WithRetryAfter() {
        when(memberDirectory.findByToken("bob-token")).thenThrow(new UserServiceUnavailableException("down"));

        list("Bob").statusCode(503)
                .header("Retry-After", "10")
                .body("error.code", equalTo("SERVICE_UNAVAILABLE"));
    }

    @Test
    void openCircuitIs503() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("userService");
        breaker.transitionToOpenState();
        when(memberDirectory.findByToken("carol-token"))
                .thenThrow(CallNotPermittedException.createCallNotPermittedException(breaker));

        status("Carol").statusCode(503).body("error.code", equalTo("SERVICE_UNAVAILABLE"));
    }

    @Test
    void unknownRouteIs404WithEnvelope() {
        as("Alice").when().get("/nope").then().statusCode(404).body("error.code", equalTo("NOT_FOUND"));
    }

    @Test
    void meReturnsCurrentMember() {
        as("admin").when().get("/me").then().statusCode(200)
                .body("name", equalTo("admin"))
                .body("isAdmin", equalTo(true));
    }
}
