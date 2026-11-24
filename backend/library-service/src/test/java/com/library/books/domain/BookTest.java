package com.library.books.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BookTest {

    private static final LendingPolicy POLICY = LendingPolicy.DEFAULT; // 14 days, +7, max 2, Rs. 10/day
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private static final Member ALICE = new Member("alice", "Alice", false);
    private static final Member BOB = new Member("bob", "Bob", false);
    private static final Member CAROL = new Member("carol", "Carol", false);

    private final Book book = Book.register("b1", BookTitle.of("1984"));

    @Nested
    class Borrowing {
        @Test
        void loanIsDueAfterTheLoanPeriod() {
            Loan loan = book.borrow(ALICE, TODAY, POLICY);
            assertThat(loan.dueDate()).isEqualTo(LocalDate.of(2026, 12, 8));
            assertThat(loan.extensionsLeft(POLICY)).isEqualTo(2);
            assertThat(book.isAvailable()).isFalse();
            assertThat(book.isBorrowedBy("alice")).isTrue();
        }

        @Test
        void borrowedBookIsNotAvailableToOthers() {
            book.borrow(ALICE, TODAY, POLICY);
            assertThatThrownBy(() -> book.borrow(BOB, TODAY, POLICY))
                    .isInstanceOf(BookNotAvailableException.class)
                    .hasMessage("Sorry, \"1984\" is currently not available.");
        }

        @Test
        void borrowingTwiceIsRejected() {
            book.borrow(ALICE, TODAY, POLICY);
            assertThatThrownBy(() -> book.borrow(ALICE, TODAY, POLICY)).isInstanceOf(AlreadyBorrowedException.class);
        }
    }

    @Nested
    class Extending {
        @Test
        void eachExtensionAddsSevenDaysUpToTwice() {
            book.borrow(ALICE, TODAY, POLICY);
            assertThat(book.extend("alice", TODAY, POLICY).dueDate()).isEqualTo(LocalDate.of(2026, 12, 15));
            Loan second = book.extend("alice", TODAY.plusDays(10), POLICY);
            assertThat(second.dueDate()).isEqualTo(LocalDate.of(2026, 12, 22));
            assertThat(second.extensionsLeft(POLICY)).isZero();

            assertThatThrownBy(() -> book.extend("alice", TODAY, POLICY))
                    .isInstanceOf(ExtensionNotAllowedException.class)
                    .hasMessageContaining("already been moved 2 times");
        }

        @Test
        void extensionIsAllowedEvenWhenOthersAreWaiting() {
            book.borrow(ALICE, TODAY, POLICY);
            book.joinWaitlist(BOB);
            assertThat(book.extend("alice", TODAY, POLICY).extensions()).isEqualTo(1);
        }

        @Test
        void overdueLoanCannotBeExtended() {
            book.borrow(ALICE, TODAY, POLICY);
            assertThatThrownBy(() -> book.extend("alice", TODAY.plusDays(15), POLICY))
                    .isInstanceOf(ExtensionNotAllowedException.class)
                    .hasMessageContaining("already overdue");
        }

        @Test
        void extendingOnTheDueDateIsStillAllowed() {
            book.borrow(ALICE, TODAY, POLICY);
            assertThat(book.extend("alice", TODAY.plusDays(14), POLICY).dueDate()).isEqualTo(LocalDate.of(2026, 12, 15));
        }

        @Test
        void onlyTheBorrowerCanExtend() {
            book.borrow(ALICE, TODAY, POLICY);
            assertThatThrownBy(() -> book.extend("bob", TODAY, POLICY)).isInstanceOf(NotBorrowedByMemberException.class);
        }
    }

    @Nested
    class Returning {
        @Test
        void returnMakesBookAvailableWhenNobodyWaits() {
            book.borrow(ALICE, TODAY, POLICY);
            Book.Returned returned = book.returnBy("alice", TODAY, POLICY);
            assertThat(returned.handedOverTo()).isEmpty();
            assertThat(returned.endedLoan().overdueDays(TODAY)).isZero();
            assertThat(book.isAvailable()).isTrue();
        }

        @Test
        void lateReturnReportsDaysLateAndFee() {
            book.borrow(ALICE, TODAY, POLICY);
            LocalDate returnedOn = TODAY.plusDays(14 + 3);
            Loan ended = book.returnBy("alice", returnedOn, POLICY).endedLoan();
            assertThat(ended.overdueDays(returnedOn)).isEqualTo(3);
            assertThat(ended.lateFee(returnedOn, POLICY)).isEqualTo(Money.rupees(30));
        }

        @Test
        void returnHandsBookToHeadOfWaitlistWithAFreshLoan() {
            book.borrow(ALICE, TODAY, POLICY);
            book.joinWaitlist(BOB);
            book.joinWaitlist(CAROL);
            LocalDate returnedOn = TODAY.plusDays(5);

            Book.Returned returned = book.returnBy("alice", returnedOn, POLICY);

            assertThat(returned.handedOverTo()).hasValueSatisfying(loan -> {
                assertThat(loan.memberName()).isEqualTo("Bob");
                assertThat(loan.dueDate()).isEqualTo(returnedOn.plusDays(14));
            });
            assertThat(book.waitlistPositionOf("carol")).contains(1);
            assertThat(book.waitlistPositionOf("bob")).isEmpty();
        }

        @Test
        void onlyTheBorrowerCanReturn() {
            book.borrow(ALICE, TODAY, POLICY);
            assertThatThrownBy(() -> book.returnBy("bob", TODAY, POLICY))
                    .isInstanceOf(NotBorrowedByMemberException.class)
                    .hasMessage("Sorry, you didn't borrow \"1984\".");
        }
    }

    @Nested
    class Waitlisting {
        @Test
        void positionsAreOneBasedAndInArrivalOrder() {
            book.borrow(ALICE, TODAY, POLICY);
            assertThat(book.joinWaitlist(BOB)).isEqualTo(1);
            assertThat(book.joinWaitlist(CAROL)).isEqualTo(2);
        }

        @Test
        void cannotWaitForAnAvailableBook() {
            assertThatThrownBy(() -> book.joinWaitlist(BOB)).isInstanceOf(BookAvailableException.class);
        }

        @Test
        void holderCannotWaitForOwnBookAndNobodyJoinsTwice() {
            book.borrow(ALICE, TODAY, POLICY);
            assertThatThrownBy(() -> book.joinWaitlist(ALICE)).isInstanceOf(AlreadyBorrowedException.class);
            book.joinWaitlist(BOB);
            assertThatThrownBy(() -> book.joinWaitlist(BOB))
                    .isInstanceOf(AlreadyWaitlistedException.class)
                    .hasMessageContaining("your position is 1");
        }
    }

    @Test
    void snapshotRoundTripPreservesStateAndIsDefensive() {
        book.borrow(ALICE, TODAY, POLICY);
        book.joinWaitlist(BOB);

        Book.Snapshot snapshot = book.snapshot();
        Book restored = Book.restore(snapshot);
        restored.returnBy("alice", TODAY, POLICY);

        assertThat(restored.isBorrowedBy("bob")).isTrue();
        assertThat(book.isBorrowedBy("alice")).as("original untouched").isTrue();
        assertThat(snapshot.waitlist()).extracting(Member::id).containsExactly("bob");
    }
}
