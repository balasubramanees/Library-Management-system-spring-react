package com.library.books.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A member's current loan of a book: who has it, since when, until when, and how many times the
 * due date has been extended. Immutable; changes produce a new {@code Loan}.
 */
public record Loan(String memberId, String memberName, LocalDate borrowedOn, LocalDate dueDate, int extensions) {

    public Loan {
        Objects.requireNonNull(memberId);
        Objects.requireNonNull(memberName);
        Objects.requireNonNull(borrowedOn);
        Objects.requireNonNull(dueDate);
    }

    public static Loan start(Member member, LocalDate today, LendingPolicy policy) {
        return new Loan(member.id(), member.name(), today, today.plusDays(policy.loanPeriodDays()), 0);
    }

    /** Days past the due date (0 when not overdue). Returning on the due date is on time. */
    public long overdueDays(LocalDate today) {
        return Math.max(0, ChronoUnit.DAYS.between(dueDate, today));
    }

    public boolean isOverdue(LocalDate today) {
        return overdueDays(today) > 0;
    }

    public int extensionsLeft(LendingPolicy policy) {
        return Math.max(0, policy.maxExtensions() - extensions);
    }

    /** Late fee if the book were returned today. */
    public Money lateFee(LocalDate today, LendingPolicy policy) {
        return policy.lateFeePerDay().times(overdueDays(today));
    }

    Loan extended(LendingPolicy policy) {
        return new Loan(memberId, memberName, borrowedOn, dueDate.plusDays(policy.extensionDays()), extensions + 1);
    }
}
