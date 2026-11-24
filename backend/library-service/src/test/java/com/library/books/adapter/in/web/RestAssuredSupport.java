package com.library.books.adapter.in.web;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

import com.library.books.application.LibraryService;
import com.library.books.application.port.MemberDirectory;
import com.library.books.domain.LibraryDates;
import com.library.books.domain.Member;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Boots library-service on a random port with user-service replaced by a mock {@link MemberDirectory}:
 * the token {@code "<name>-token"} authenticates as {@code <name>}. Each subclass gets a fresh context
 * (fresh data and clock).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class RestAssuredSupport {

    @LocalServerPort
    int port;

    @MockitoBean
    MemberDirectory memberDirectory;

    @Autowired
    LibraryService library;

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        for (String name : new String[] {"admin", "Alice", "Bob", "Carol", "Dave", "Erin", "Frank"}) {
            String id = name.toLowerCase();
            when(memberDirectory.findByToken(id + "-token"))
                    .thenReturn(Optional.of(new Member(id, name, "admin".equals(id))));
        }
        when(memberDirectory.findByToken("expired-token")).thenReturn(Optional.empty());
    }

    /** "08 Dec 2026" for the library's today + {@code days}. */
    String inDays(int days) {
        return LibraryDates.format(library.today().plusDays(days));
    }

    LocalDate today() {
        return library.today();
    }

    static RequestSpecification as(String user) {
        return given().auth().oauth2(user.toLowerCase() + "-token").contentType(ContentType.JSON);
    }

    static String title(String title) {
        return "{\"title\":\"" + title + "\"}";
    }

    static ValidatableResponse add(String title) {
        return as("admin").body(title(title)).when().post("/books").then();
    }

    static ValidatableResponse borrow(String user, String title) {
        return as(user).body(title(title)).when().post("/books/borrow").then();
    }

    static ValidatableResponse giveBack(String user, String title) {
        return as(user).body(title(title)).when().post("/books/return").then();
    }

    static ValidatableResponse waitlist(String user, String title) {
        return as(user).body(title(title)).when().post("/books/waitlist").then();
    }

    static ValidatableResponse list(String user) {
        return as(user).when().get("/books").then();
    }

    static ValidatableResponse extend(String user, String title) {
        return as(user).body(title(title)).when().post("/books/extend").then();
    }

    static ValidatableResponse deposit(String user, long amount) {
        return as(user).body("{\"amount\":" + amount + "}").when().post("/me/deposit").then();
    }

    static ValidatableResponse advanceClock(int days) {
        return as("admin").body("{\"days\":" + days + "}").when().post("/admin/clock/advance").then();
    }

    static ValidatableResponse status(String user) {
        return as(user).when().get("/me/status").then();
    }
}
