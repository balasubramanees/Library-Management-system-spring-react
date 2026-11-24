package com.library.books.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.library.books.adapter.out.clock.AdjustableClock;
import com.library.books.adapter.out.memory.InMemoryAccountRepository;
import com.library.books.adapter.out.memory.InMemoryBookRepository;
import com.library.books.adapter.out.memory.InMemoryNotificationInbox;
import com.library.books.application.LibraryViews.BookView;
import com.library.books.application.LibraryViews.MemberStatus;
import com.library.books.domain.BookAlreadyExistsException;
import com.library.books.domain.BookNotFoundException;
import com.library.books.domain.BorrowingBlockedException;
import com.library.books.domain.ExtensionNotAllowedException;
import com.library.books.domain.ForbiddenException;
import com.library.books.domain.LendingPolicy;
import com.library.books.domain.Member;
import com.library.books.domain.MemberNotification;
import com.library.books.domain.Money;
import com.library.books.domain.ValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LibraryServiceTest {

    private static final Member ADMIN = new Member("admin", "admin", true);
    private static final Member ALICE = new Member("alice", "Alice", false);
    private static final Member BOB = new Member("bob", "Bob", false);
    private static final Member CAROL = new Member("carol", "Carol", false);
    private static final LocalDate START = LocalDate.of(2026, 11, 24);

    private final AtomicInteger sequence = new AtomicInteger();
    /** 24 Nov 2026, 11:30 in Kolkata; tests move it forward to make loans overdue. */
    private final AdjustableClock clock =
            new AdjustableClock(Clock.fixed(Instant.parse("2026-11-24T06:00:00Z"), ZoneId.of("Asia/Kolkata")));
    private final LibraryService library = new LibraryService(
            new InMemoryBookRepository(),
            new InMemoryAccountRepository(),
            new InMemoryNotificationInbox(),
            () -> "id-" + sequence.incrementAndGet(),
            clock,
            LendingPolicy.DEFAULT);

    private void daysPass(int days) {
        clock.advance(Duration.ofDays(days));
    }

    @Nested
    class Catalogue {
        @Test
        void onlyAdminCanAddBooks() {
            assertThatThrownBy(() -> library.addBook(ALICE, "1984")).isInstanceOf(ForbiddenException.class);
            assertThat(library.addBook(ADMIN, "1984").title()).isEqualTo("1984");
        }

        @Test
        void duplicateTitlesAreRejectedCaseInsensitively() {
            library.addBook(ADMIN, "The Great Gatsby");
            assertThatThrownBy(() -> library.addBook(ADMIN, "the great gatsby"))
                    .isInstanceOf(BookAlreadyExistsException.class)
                    .hasMessageContaining("\"The Great Gatsby\"");
        }

        @Test
        void listKeepsRegistrationOrderAndShowsLoanDetailsOnlyToBorrowerAndAdmin() {
            library.addBook(ADMIN, "The Great Gatsby");
            library.addBook(ADMIN, "1984");
            library.borrow(ALICE, "1984");

            BookView forAlice = library.listBooks(ALICE).get(1);
            BookView forBob = library.listBooks(BOB).get(1);
            BookView forAdmin = library.listBooks(ADMIN).get(1);

            assertThat(library.listBooks(ALICE)).extracting(BookView::title).containsExactly("The Great Gatsby", "1984");
            assertThat(forAlice.borrowedByMe()).isTrue();
            assertThat(forAlice.dueDate()).isEqualTo(START.plusDays(14));
            assertThat(forAlice.borrowerName()).isNull();
            assertThat(forBob.dueDate()).isNull();
            assertThat(forBob.borrowerName()).isNull();
            assertThat(forAdmin.borrowerName()).isEqualTo("Alice");
            assertThat(forAdmin.dueDate()).isEqualTo(START.plusDays(14));
            assertThat(forAdmin.overdueDays()).isZero();
        }

        @Test
        void unknownTitleIsReportedWithTheTitleTheUserTyped() {
            assertThatThrownBy(() -> library.borrow(BOB, "1985"))
                    .isInstanceOf(BookNotFoundException.class)
                    .hasMessage("Sorry, \"1985\" is not registered.");
            assertThatThrownBy(() -> library.joinWaitlist(BOB, "1985")).isInstanceOf(BookNotFoundException.class);
        }

        @Test
        void titlesAreMatchedLeniently() {
            library.addBook(ADMIN, "The Great Gatsby");
            assertThat(library.borrow(ALICE, "  the GREAT   gatsby ").title()).isEqualTo("The Great Gatsby");
        }
    }

    @Nested
    class Registration {
        @Test
        void firstStatusOpensAccountWithCashDepositAndNotifiesOnce() {
            MemberStatus first = library.status(ALICE);

            assertThat(first.account().balance()).isEqualTo(Money.rupees(500));
            assertThat(first.notifications()).singleElement().satisfies(n -> {
                assertThat(n.type()).isEqualTo(MemberNotification.Type.DEPOSIT_RECEIVED);
                assertThat(n.message()).isEqualTo("Rs. 500 received as deposit in cash.");
            });
            assertThat(library.status(ALICE).notifications()).isEmpty();
        }

        @Test
        void adminHasNoDepositAccount() {
            assertThat(library.status(ADMIN).account()).isNull();
            assertThatThrownBy(() -> library.deposit(ADMIN, 500)).isInstanceOf(ForbiddenException.class);
        }

        @Test
        void topUpAddsToTheBalanceWithinLimits() {
            library.status(ALICE);
            assertThat(library.deposit(ALICE, 250).balance()).isEqualTo(Money.rupees(750));
            assertThatThrownBy(() -> library.deposit(ALICE, 50)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> library.deposit(ALICE, 1_000_000)).isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class DueDatesAndExtensions {
        @Test
        void borrowingSetsADueDateFourteenDaysAhead() {
            library.addBook(ADMIN, "1984");
            assertThat(library.borrow(ALICE, "1984").dueDate()).isEqualTo(LocalDate.of(2026, 12, 8));
        }

        @Test
        void returnDateCanBeMovedTwiceBySevenDays() {
            library.addBook(ADMIN, "1984");
            library.borrow(ALICE, "1984");

            assertThat(library.extendLoan(ALICE, "1984").dueDate()).isEqualTo(LocalDate.of(2026, 12, 15));
            var last = library.extendLoan(ALICE, "1984");
            assertThat(last.dueDate()).isEqualTo(LocalDate.of(2026, 12, 22));
            assertThat(last.extensionsLeft()).isZero();
            assertThatThrownBy(() -> library.extendLoan(ALICE, "1984")).isInstanceOf(ExtensionNotAllowedException.class);
        }

        @Test
        void statusShowsDueDatesAndExtensionsLeft() {
            library.addBook(ADMIN, "1984");
            library.borrow(ALICE, "1984");
            library.extendLoan(ALICE, "1984");

            var loan = library.status(ALICE).borrowed().get(0);
            assertThat(loan.dueDate()).isEqualTo(LocalDate.of(2026, 12, 15));
            assertThat(loan.extensionsUsed()).isEqualTo(1);
            assertThat(loan.extensionsLeft()).isEqualTo(1);
            assertThat(loan.overdueDays()).isZero();
        }
    }

    @Nested
    class Overdue {
        @Test
        void lateReturnChargesTenRupeesPerDayToTheDeposit() {
            library.addBook(ADMIN, "1984");
            library.borrow(ALICE, "1984");
            daysPass(14 + 3);

            var result = library.returnBook(ALICE, "1984");

            assertThat(result.daysLate()).isEqualTo(3);
            assertThat(result.lateFee()).isEqualTo(Money.rupees(30));
            assertThat(result.balance()).isEqualTo(Money.rupees(470));
            assertThat(library.status(ALICE).account().ledger()).hasSize(2);
        }

        @Test
        void onTimeReturnCostsNothing() {
            library.addBook(ADMIN, "1984");
            library.borrow(ALICE, "1984");
            daysPass(14);
            assertThat(library.returnBook(ALICE, "1984").lateFee()).isEqualTo(Money.ZERO);
        }

        @Test
        void memberWithAnOverdueBookCannotBorrowOrQueueUntilReturned() {
            library.addBook(ADMIN, "1984");
            library.addBook(ADMIN, "Dune");
            library.addBook(ADMIN, "Emma");
            library.borrow(ALICE, "1984");
            library.borrow(BOB, "Emma");
            daysPass(15);

            assertThatThrownBy(() -> library.borrow(ALICE, "Dune"))
                    .isInstanceOf(BorrowingBlockedException.class)
                    .hasMessage("Sorry, you have 1 overdue book. Please return it before borrowing or joining a wait list.");
            assertThatThrownBy(() -> library.joinWaitlist(ALICE, "Emma")).isInstanceOf(BorrowingBlockedException.class);
            assertThat(library.status(ALICE).account().blockedReason()).isNotNull();

            library.returnBook(ALICE, "1984");
            assertThat(library.borrow(ALICE, "Dune").title()).isEqualTo("Dune");
        }

        @Test
        void negativeBalanceBlocksBorrowingUntilToppedUp() {
            library.addBook(ADMIN, "1984");
            library.addBook(ADMIN, "Dune");
            library.borrow(ALICE, "1984");
            daysPass(14 + 60);
            assertThat(library.returnBook(ALICE, "1984").balance()).isEqualTo(Money.rupees(-100));

            assertThatThrownBy(() -> library.borrow(ALICE, "Dune"))
                    .isInstanceOf(BorrowingBlockedException.class)
                    .hasMessageContaining("deposit balance is -Rs. 100");

            library.deposit(ALICE, 500);
            assertThat(library.borrow(ALICE, "Dune").title()).isEqualTo("Dune");
        }
    }

    @Nested
    class AdminDashboardView {
        @Test
        void onlyAdminCanSeeTheDashboard() {
            assertThatThrownBy(() -> library.dashboard(ALICE)).isInstanceOf(ForbiddenException.class);
        }

        @Test
        void countsMembersAndLoansAndFlagsOverdueAndDueSoon() {
            library.addBook(ADMIN, "1984");
            library.addBook(ADMIN, "Dune");
            library.addBook(ADMIN, "Emma");
            library.status(ALICE);
            library.status(BOB);
            library.status(CAROL);
            library.borrow(ALICE, "1984");              // due START + 14
            daysPass(5);
            library.borrow(BOB, "Dune");                // due START + 19
            library.joinWaitlist(CAROL, "1984");
            daysPass(11);                               // today = START + 16

            LibraryViews.AdminDashboard dashboard = library.dashboard(ADMIN);

            assertThat(dashboard.today()).isEqualTo(START.plusDays(16));
            assertThat(dashboard.summary()).isEqualTo(new LibraryViews.AdminSummary(
                    3, 1, 2, 3, 2, 1, 1, 1, 1, Money.rupees(1500), Money.rupees(20)));
            assertThat(dashboard.loans()).extracting(LibraryViews.AdminLoanView::title,
                            LibraryViews.AdminLoanView::memberName, LibraryViews.AdminLoanView::daysUntilDue,
                            LibraryViews.AdminLoanView::state)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("1984", "Alice", -2L, LibraryViews.LoanState.OVERDUE),
                            org.assertj.core.groups.Tuple.tuple("Dune", "Bob", 3L, LibraryViews.LoanState.DUE_SOON));
            assertThat(dashboard.members()).extracting(LibraryViews.AdminMemberView::name,
                            LibraryViews.AdminMemberView::booksBorrowed, LibraryViews.AdminMemberView::waitlists,
                            LibraryViews.AdminMemberView::blockedReason)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("Alice", 1, 0, "1 overdue book"),
                            org.assertj.core.groups.Tuple.tuple("Bob", 1, 0, null),
                            org.assertj.core.groups.Tuple.tuple("Carol", 0, 1, null));
        }
    }

    @Nested
    class Waitlists {
        @Test
        void returnAllocatesToWaitlistWithFreshLoanAndNotifiesOnce() {
            library.addBook(ADMIN, "1984");
            library.borrow(ALICE, "1984");
            assertThat(library.joinWaitlist(BOB, "1984").position()).isEqualTo(1);
            assertThat(library.joinWaitlist(CAROL, "1984").position()).isEqualTo(2);
            library.status(BOB); // registration notification consumed
            daysPass(5);

            assertThat(library.returnBook(ALICE, "1984").handedToWaitlist()).isTrue();

            MemberStatus bob = library.status(BOB);
            assertThat(bob.borrowed()).singleElement().satisfies(loan -> {
                assertThat(loan.title()).isEqualTo("1984");
                assertThat(loan.dueDate()).isEqualTo(START.plusDays(5 + 14));
            });
            assertThat(bob.notifications()).singleElement().satisfies(n -> assertThat(n.message())
                    .isEqualTo("You got the book \"1984\" from your waitlist. Please return it by 13 Dec 2026."));
            assertThat(library.status(BOB).notifications()).as("delivered once").isEmpty();
            assertThat(library.status(CAROL).waitlists()).singleElement()
                    .satisfies(w -> assertThat(w.position()).isEqualTo(1));
        }
    }

    @Test
    void adminIsALibrarianAndCannotBorrowReturnExtendOrWaitlist() {
        library.addBook(ADMIN, "1984");
        assertThatThrownBy(() -> library.borrow(ADMIN, "1984"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("admin account manages the library");
        assertThatThrownBy(() -> library.returnBook(ADMIN, "1984")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> library.extendLoan(ADMIN, "1984")).isInstanceOf(ForbiddenException.class);
        library.borrow(ALICE, "1984");
        assertThatThrownBy(() -> library.joinWaitlist(ADMIN, "1984")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void concurrentBorrowsOfTheSameBookAllocateItExactlyOnce() throws Exception {
        library.addBook(ADMIN, "1984");
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Member member = new Member("m" + i, "M" + i, false);
            attempts.add(() -> {
                start.await();
                try {
                    library.borrow(member, "1984");
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            });
        }
        List<Future<Boolean>> results = new ArrayList<>();
        attempts.forEach(a -> results.add(pool.submit(a)));
        start.countDown();

        int successes = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                successes++;
            }
        }
        pool.shutdown();
        assertThat(successes).isEqualTo(1);
    }
}
