package com.library.books.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A member's deposit account. Opened with a cash deposit at registration; late fees are deducted
 * from it. The balance may go negative when fees exceed it, in which case borrowing is blocked
 * until the member tops it up. Immutable: every operation returns a new account.
 */
public record MemberAccount(String memberId, String memberName, Money balance, List<Entry> ledger) {

    /** One movement on the account; {@code amount} is negative for charges. */
    public record Entry(LocalDate date, String description, Money amount) {}

    public MemberAccount {
        Objects.requireNonNull(memberId);
        Objects.requireNonNull(memberName);
        Objects.requireNonNull(balance);
        ledger = List.copyOf(ledger);
    }

    public static MemberAccount open(String memberId, String memberName, Money deposit, LocalDate today) {
        return new MemberAccount(memberId, memberName, deposit, List.of(new Entry(today, "Deposit received in cash", deposit)));
    }

    public MemberAccount deposit(Money amount, LocalDate today) {
        return with(new Entry(today, "Deposit received in cash", amount));
    }

    public MemberAccount chargeLateFee(Money fee, String title, long daysLate, LocalDate today) {
        return with(new Entry(today, "Late fee for \"" + title + "\" (" + daysLate + " day" + (daysLate == 1 ? "" : "s") + ")",
                Money.ZERO.minus(fee)));
    }

    private MemberAccount with(Entry entry) {
        List<Entry> next = new ArrayList<>(ledger);
        next.add(entry);
        return new MemberAccount(memberId, memberName, balance.plus(entry.amount()), next);
    }
}
