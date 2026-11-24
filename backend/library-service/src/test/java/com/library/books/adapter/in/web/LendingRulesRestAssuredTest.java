package com.library.books.adapter.in.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

/**
 * Due dates, extensions, late fees, borrowing blocks and deposits over HTTP. Uses the admin's demo
 * clock to move time forward; runs in its own Spring context, so time travel does not leak into
 * other test classes.
 */
class LendingRulesRestAssuredTest extends RestAssuredSupport {

    @Test
    void dueDateCanBeExtendedTwiceThenNoMore() {
        add("Persuasion").statusCode(201);
        borrow("Dave", "Persuasion").statusCode(200);

        extend("Dave", "Persuasion").statusCode(200)
                .body("message", equalTo("The return date of \"Persuasion\" is now " + inDays(21) + ". 1 extension left."))
                .body("extensionsLeft", equalTo(1));
        waitlist("Erin", "Persuasion").statusCode(200);
        extend("Dave", "Persuasion").statusCode(200)
                .body("message", equalTo("The return date of \"Persuasion\" is now " + inDays(28) + ". This was the last extension."));
        extend("Dave", "Persuasion").statusCode(409)
                .body("error.code", equalTo("EXTENSION_NOT_ALLOWED"))
                .body("error.message", equalTo("Sorry, the return date of \"Persuasion\" has already been moved 2 times, "
                        + "which is the maximum. Please return it by " + inDays(28) + "."));
        extend("Erin", "Persuasion").statusCode(409).body("error.code", equalTo("NOT_BORROWED_BY_USER"));
    }

    @Test
    void overdueBookBlocksBorrowingAndCostsTenRupeesPerDay() {
        add("Middlemarch").statusCode(201);
        add("Walden").statusCode(201);
        borrow("Frank", "Middlemarch").statusCode(200);
        String due = inDays(14);

        advanceClock(17).statusCode(200);

        list("admin").body("books.find { it.title == 'Middlemarch' }.borrowerName", equalTo("Frank"))
                .body("books.find { it.title == 'Middlemarch' }.overdueDays", equalTo(3));
        list("Bob").body("books.find { it.title == 'Middlemarch' }.borrowerName", nullValue())
                .body("books.find { it.title == 'Middlemarch' }.dueDate", nullValue());
        status("Frank").body("account.blocked", equalTo(true))
                .body("borrowed[0].overdueDays", equalTo(3))
                .body("borrowed[0].lateFeeSoFar", equalTo(30))
                .body("lines", hasItem("1. Middlemarch - due " + due + " (OVERDUE by 3 days, late fee so far Rs. 30)"));
        borrow("Frank", "Walden").statusCode(409)
                .body("error.code", equalTo("BORROWING_BLOCKED"))
                .body("error.message", equalTo(
                        "Sorry, you have 1 overdue book. Please return it before borrowing or joining a wait list."));
        extend("Frank", "Middlemarch").statusCode(409).body("error.code", equalTo("EXTENSION_NOT_ALLOWED"));

        giveBack("Frank", "Middlemarch").statusCode(200)
                .body("message", equalTo("You returned \"Middlemarch\". It was 3 days late: Rs. 30 late fee deducted "
                        + "from your deposit (balance Rs. 470)."))
                .body("lateFee", equalTo(30))
                .body("balance", equalTo(470));
        status("Frank").body("account.balance", equalTo(470))
                .body("account.blocked", equalTo(false))
                .body("account.ledger.amount", contains(500, -30));
        borrow("Frank", "Walden").statusCode(200);
    }

    @Test
    void depositsAreValidatedAndRecorded() {
        status("Carol").body("account.balance", equalTo(500));
        deposit("Carol", 200).statusCode(200)
                .body("message", equalTo("Rs. 200 received as deposit in cash. Your deposit balance is Rs. 700."))
                .body("balance", equalTo(700));
        deposit("Carol", 50).statusCode(400).body("error.message", equalTo("Deposit must be between Rs. 100 and Rs. 100000."));
        as("Carol").body("{}").when().post("/me/deposit").then().statusCode(400);
    }

    @Test
    void statusExposesTheLendingPolicy() {
        status("Alice").body("policy.loanPeriodDays", equalTo(14))
                .body("policy.extensionDays", equalTo(7))
                .body("policy.maxExtensions", equalTo(2))
                .body("policy.lateFeePerDay", equalTo(10))
                .body("policy.initialDeposit", equalTo(500));
    }

    @Test
    void onlyAdminCanMoveTheClockAndOnlyForward() {
        as("admin").when().get("/admin/clock").then().statusCode(200).body("today", equalTo(today().toString()));
        advanceClock(0).statusCode(400);
        as("Bob").body("{\"days\":1}").when().post("/admin/clock/advance").then().statusCode(403);
        given().when().get("/admin/clock").then().statusCode(401);
    }
}
