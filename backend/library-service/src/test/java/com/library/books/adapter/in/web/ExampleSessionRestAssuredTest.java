package com.library.books.adapter.in.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import org.junit.jupiter.api.Test;

/**
 * Replays the example session from the problem statement over HTTP, step by step. Output follows
 * the brief, extended with the lending rules: deposit at registration, due dates and balance.
 */
class ExampleSessionRestAssuredTest extends RestAssuredSupport {

    @Test
    void exampleSessionFromTheBrief() {
        String due = inDays(14);

        // admin
        list("admin").statusCode(200).body("books", empty()).body("message", equalTo("No books are registered"));
        add("The Great Gatsby").statusCode(201)
                .body("message", equalTo("Book \"The Great Gatsby\" has been added to the library."));
        add("1984").statusCode(201).body("message", equalTo("Book \"1984\" has been added to the library."));

        // Alice registers (first status opens her deposit account)
        status("Alice").statusCode(200).body("lines", contains(
                "Rs. 500 received as deposit in cash.", "You don't have any books borrowed yet.", "Your deposit balance: Rs. 500"));
        list("Alice").statusCode(200)
                .body("books.title", contains("The Great Gatsby", "1984"))
                .body("books.status", contains("available", "available"));
        borrow("Alice", "1984").statusCode(200)
                .body("message", equalTo("You borrowed \"1984\". Please return it by " + due + "."))
                .body("dueDate", equalTo(today().plusDays(14).toString()));
        list("Alice").body("books.status", contains("available", "borrowed"));
        status("Alice").body("lines", contains("Your borrowed books:", "1. 1984 - due " + due, "Your deposit balance: Rs. 500"));

        // Bob
        status("Bob").statusCode(200);
        borrow("Bob", "1985").statusCode(404).body("error.message", equalTo("Sorry, \"1985\" is not registered."));
        borrow("Bob", "1984").statusCode(409).body("error.message", equalTo("Sorry, \"1984\" is currently not available."));
        waitlist("Bob", "1985").statusCode(404).body("error.code", equalTo("BOOK_NOT_FOUND"));
        waitlist("Bob", "1984").statusCode(200)
                .body("message", equalTo("You are added to the wait list of \"1984\", your position is 1."))
                .body("position", equalTo(1));
        borrow("Bob", "The Great Gatsby").statusCode(200);
        status("Bob").body("lines", contains("Your borrowed books:", "1. The Great Gatsby - due " + due,
                "Your wait lists:", "1. 1984 - position 1", "Your deposit balance: Rs. 500"));

        // Carol
        status("Carol").statusCode(200);
        waitlist("Carol", "1984").statusCode(200)
                .body("message", equalTo("You are added to the wait list of \"1984\", your position is 2."));
        status("Carol").body("lines", contains("You don't have any books borrowed yet.",
                "Your wait lists:", "1. 1984 - position 2", "Your deposit balance: Rs. 500"));

        // Alice returns on time; the book goes straight to Bob with a fresh loan period
        giveBack("Alice", "1984").statusCode(200).body("message", equalTo("You returned \"1984\".")).body("lateFee", equalTo(0));
        list("Alice").body("books.status", contains("borrowed", "borrowed"));

        // Bob is notified once
        status("Bob").body("notifications", hasSize(1))
                .body("lines", contains(
                        "You got the book \"1984\" from your waitlist. Please return it by " + due + ".",
                        "Your borrowed books:", "1. The Great Gatsby - due " + due, "2. 1984 - due " + due,
                        "Your deposit balance: Rs. 500"));
        status("Bob").body("notifications", empty());

        // Carol moved up
        status("Carol").body("lines", contains("You don't have any books borrowed yet.",
                "Your wait lists:", "1. 1984 - position 1", "Your deposit balance: Rs. 500"));
        giveBack("Carol", "The Great Gatsby").statusCode(409)
                .body("error.message", equalTo("Sorry, you didn't borrow \"The Great Gatsby\"."));
    }
}
