package com.library.books.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root for the single copy of a title: its current {@link Loan} and its FIFO wait list.
 *
 * <p>Invariants:
 * <ul>
 *   <li>A book that is not on loan has an empty wait list. On return the book is handed straight
 *       to the head of the wait list with a fresh loan period, so it never shows as "available"
 *       while someone is waiting (as in the example session, where Bob receives "1984").</li>
 *   <li>A loan can be extended at most {@link LendingPolicy#maxExtensions()} times, and not once
 *       it is overdue.</li>
 * </ul>
 */
public final class Book {

    private final String id;
    private final String title;
    private final String titleKey;
    private Loan loan;
    private final List<Member> waitlist;

    private Book(String id, String title, String titleKey, Loan loan, List<Member> waitlist) {
        this.id = Objects.requireNonNull(id);
        this.title = Objects.requireNonNull(title);
        this.titleKey = Objects.requireNonNull(titleKey);
        this.loan = loan;
        this.waitlist = new ArrayList<>(waitlist);
    }

    public static Book register(String id, BookTitle title) {
        return new Book(id, title.value(), title.key(), null, List.of());
    }

    public static Book restore(Snapshot snapshot) {
        return new Book(snapshot.id(), snapshot.title(), snapshot.titleKey(), snapshot.loan(), snapshot.waitlist());
    }

    /** Lends the book to the member until {@code today + loan period}. */
    public Loan borrow(Member member, LocalDate today, LendingPolicy policy) {
        if (isBorrowedBy(member.id())) {
            throw new AlreadyBorrowedException(title);
        }
        if (loan != null) {
            throw new BookNotAvailableException(title);
        }
        loan = Loan.start(member, today, policy);
        return loan;
    }

    /**
     * Returns the book. If someone is waiting, it is lent to them straight away.
     *
     * @return the loan that ended (to compute late fees) and the new loan, if the book was handed over
     */
    public Returned returnBy(String memberId, LocalDate today, LendingPolicy policy) {
        if (!isBorrowedBy(memberId)) {
            throw new NotBorrowedByMemberException(title);
        }
        Loan ended = loan;
        loan = waitlist.isEmpty() ? null : Loan.start(waitlist.remove(0), today, policy);
        return new Returned(ended, Optional.ofNullable(loan));
    }

    /** Moves the due date by {@link LendingPolicy#extensionDays()}. */
    public Loan extend(String memberId, LocalDate today, LendingPolicy policy) {
        if (!isBorrowedBy(memberId)) {
            throw new NotBorrowedByMemberException(title);
        }
        if (loan.isOverdue(today)) {
            throw new ExtensionNotAllowedException("Sorry, \"" + title + "\" is already overdue (due "
                    + LibraryDates.format(loan.dueDate()) + ") and cannot be extended. Please return it.");
        }
        if (loan.extensionsLeft(policy) == 0) {
            throw new ExtensionNotAllowedException("Sorry, the return date of \"" + title + "\" has already been moved "
                    + policy.maxExtensions() + " times, which is the maximum. Please return it by "
                    + LibraryDates.format(loan.dueDate()) + ".");
        }
        loan = loan.extended(policy);
        return loan;
    }

    /** Appends the member to the wait list and returns their 1-based position. */
    public int joinWaitlist(Member member) {
        if (loan == null) {
            throw new BookAvailableException(title);
        }
        if (isBorrowedBy(member.id())) {
            throw new AlreadyBorrowedException(title);
        }
        Optional<Integer> existing = waitlistPositionOf(member.id());
        if (existing.isPresent()) {
            throw new AlreadyWaitlistedException(title, existing.get());
        }
        waitlist.add(member);
        return waitlist.size();
    }

    public boolean isAvailable() {
        return loan == null;
    }

    public boolean isBorrowedBy(String memberId) {
        return loan != null && loan.memberId().equals(memberId);
    }

    public Optional<Loan> loan() {
        return Optional.ofNullable(loan);
    }

    /** 1-based wait list position of the member, if waiting. */
    public Optional<Integer> waitlistPositionOf(String memberId) {
        for (int i = 0; i < waitlist.size(); i++) {
            if (waitlist.get(i).id().equals(memberId)) {
                return Optional.of(i + 1);
            }
        }
        return Optional.empty();
    }

    public int waitlistLength() {
        return waitlist.size();
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String titleKey() {
        return titleKey;
    }

    public Snapshot snapshot() {
        return new Snapshot(id, title, titleKey, loan, List.copyOf(waitlist));
    }

    /** Outcome of a return: the loan that ended and, if the book was handed over, the new loan. */
    public record Returned(Loan endedLoan, Optional<Loan> handedOverTo) {}

    /** Immutable persisted state of a book. */
    public record Snapshot(String id, String title, String titleKey, Loan loan, List<Member> waitlist) {}
}
