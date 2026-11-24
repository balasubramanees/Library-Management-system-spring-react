package com.library.books.application;

import com.library.books.application.LibraryViews.AccountView;
import com.library.books.application.LibraryViews.AdminDashboard;
import com.library.books.application.LibraryViews.AdminLoanView;
import com.library.books.application.LibraryViews.AdminMemberView;
import com.library.books.application.LibraryViews.AdminSummary;
import com.library.books.application.LibraryViews.LoanState;
import com.library.books.application.LibraryViews.BookView;
import com.library.books.application.LibraryViews.BorrowResult;
import com.library.books.application.LibraryViews.DepositResult;
import com.library.books.application.LibraryViews.ExtendResult;
import com.library.books.application.LibraryViews.LoanView;
import com.library.books.application.LibraryViews.MemberStatus;
import com.library.books.application.LibraryViews.ReturnResult;
import com.library.books.application.LibraryViews.WaitlistResult;
import com.library.books.application.LibraryViews.WaitlistView;
import com.library.books.application.port.AccountRepository;
import com.library.books.application.port.BookRepository;
import com.library.books.application.port.NotificationInbox;
import com.library.books.domain.Book;
import com.library.books.domain.BookAlreadyExistsException;
import com.library.books.domain.BookNotFoundException;
import com.library.books.domain.BookTitle;
import com.library.books.domain.BorrowingBlockedException;
import com.library.books.domain.ForbiddenException;
import com.library.books.domain.LendingPolicy;
import com.library.books.domain.LibraryDates;
import com.library.books.domain.Loan;
import com.library.books.domain.Member;
import com.library.books.domain.MemberAccount;
import com.library.books.domain.MemberNotification;
import com.library.books.domain.Money;
import com.library.books.domain.ValidationException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Application service: one method per use case. Business rules live in the domain ({@link Book},
 * {@link com.library.books.domain.Loan}, {@link MemberAccount}); this class orchestrates the ports
 * and enforces who may do what.
 *
 * <p>Roles: the admin is the librarian (manages the catalogue, sees all loans); members borrow.
 *
 * <p>Concurrency: every use case is a read-modify-write, so writes take an exclusive lock and reads
 * a shared one. With a database adapter this would become a transaction plus optimistic locking.
 */
public class LibraryService {

    static final String ADMIN_CANNOT_LEND =
            "Sorry, the admin account manages the library and cannot borrow, return or join wait lists.";

    /** A loan due within this many days (today included) is flagged as "due soon" on the admin dashboard. */
    static final int DUE_SOON_DAYS = 3;

    private final BookRepository books;
    private final AccountRepository accounts;
    private final NotificationInbox inbox;
    private final Supplier<String> ids;
    private final Clock clock;
    private final LendingPolicy policy;
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);

    public LibraryService(BookRepository books, AccountRepository accounts, NotificationInbox inbox,
                          Supplier<String> ids, Clock clock, LendingPolicy policy) {
        this.books = books;
        this.accounts = accounts;
        this.inbox = inbox;
        this.ids = ids;
        this.clock = clock;
        this.policy = policy;
    }

    public LendingPolicy policy() {
        return policy;
    }

    /** The library's current date, in its time zone. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** {@code add [book_name]} - admin only. */
    public Book addBook(Member actor, String rawTitle) {
        if (!actor.admin()) {
            throw new ForbiddenException("Sorry, only the admin can add books to the library.");
        }
        BookTitle title = BookTitle.of(rawTitle);
        return write(() -> {
            books.findByTitleKey(title.key()).ifPresent(existing -> {
                throw new BookAlreadyExistsException(existing.title());
            });
            Book book = Book.register(ids.get(), title);
            books.save(book);
            return book;
        });
    }

    /** {@code list} - every book, from the caller's point of view. */
    public List<BookView> listBooks(Member actor) {
        LocalDate today = today();
        return read(() -> books.findAll().stream().map(book -> toView(book, actor, today)).toList());
    }

    /** {@code borrow [book_name]} - members only, and only while in good standing. */
    public BorrowResult borrow(Member actor, String rawTitle) {
        requireMember(actor);
        BookTitle title = BookTitle.of(rawTitle);
        return write(() -> {
            LocalDate today = today();
            Book book = requireBook(title);
            requireGoodStanding(actor, accountOf(actor, today), today);
            Loan loan = book.borrow(actor, today, policy);
            books.save(book);
            return new BorrowResult(book.title(), loan.dueDate());
        });
    }

    /**
     * {@code return [book_name]} - charges the late fee (if any) to the member's deposit and hands
     * the book to the next member on the wait list.
     */
    public ReturnResult returnBook(Member actor, String rawTitle) {
        requireMember(actor);
        BookTitle title = BookTitle.of(rawTitle);
        return write(() -> {
            LocalDate today = today();
            Book book = requireBook(title);
            Book.Returned returned = book.returnBy(actor.id(), today, policy);
            books.save(book);

            MemberAccount account = accountOf(actor, today);
            long daysLate = returned.endedLoan().overdueDays(today);
            Money fee = returned.endedLoan().lateFee(today, policy);
            if (fee.isPositive()) {
                account = account.chargeLateFee(fee, book.title(), daysLate, today);
                accounts.save(account);
            }
            returned.handedOverTo().ifPresent(next -> inbox.push(new MemberNotification(
                    ids.get(), next.memberId(), MemberNotification.Type.WAITLIST_ALLOCATED, book.id(), book.title(),
                    "You got the book \"" + book.title() + "\" from your waitlist. Please return it by "
                            + LibraryDates.format(next.dueDate()) + ".",
                    clock.instant())));
            return new ReturnResult(book.title(), returned.handedOverTo().isPresent(), daysLate, fee, account.balance());
        });
    }

    /** Moves the due date of the caller's loan (at most {@link LendingPolicy#maxExtensions()} times). */
    public ExtendResult extendLoan(Member actor, String rawTitle) {
        requireMember(actor);
        BookTitle title = BookTitle.of(rawTitle);
        return write(() -> {
            LocalDate today = today();
            Book book = requireBook(title);
            Loan loan = book.extend(actor.id(), today, policy);
            books.save(book);
            return new ExtendResult(book.title(), loan.dueDate(), loan.extensionsLeft(policy));
        });
    }

    /** {@code waitlist [book_name]} - members only, and only while in good standing. */
    public WaitlistResult joinWaitlist(Member actor, String rawTitle) {
        requireMember(actor);
        BookTitle title = BookTitle.of(rawTitle);
        return write(() -> {
            LocalDate today = today();
            Book book = requireBook(title);
            requireGoodStanding(actor, accountOf(actor, today), today);
            int position = book.joinWaitlist(actor);
            books.save(book);
            return new WaitlistResult(book.title(), position);
        });
    }

    /** Records an additional cash deposit. */
    public DepositResult deposit(Member actor, long rupees) {
        requireMember(actor);
        Money amount = Money.rupees(rupees);
        if (amount.compareTo(policy.minimumTopUp()) < 0 || rupees > 100_000) {
            throw new ValidationException("Deposit must be between " + policy.minimumTopUp() + " and Rs. 100000.");
        }
        return write(() -> {
            LocalDate today = today();
            MemberAccount account = accountOf(actor, today).deposit(amount, today);
            accounts.save(account);
            return new DepositResult(amount, account.balance());
        });
    }

    /**
     * {@code status} - the caller's loans (with due dates), wait lists, deposit account and pending
     * notifications (consumed by this call, so each is shown once). A member's first status call
     * opens their deposit account: this is the registration step.
     */
    public MemberStatus status(Member actor) {
        return write(() -> {
            LocalDate today = today();
            List<Book> all = books.findAll();
            List<LoanView> borrowed = all.stream()
                    .filter(book -> book.isBorrowedBy(actor.id()))
                    .map(book -> {
                        Loan loan = book.loan().orElseThrow();
                        return new LoanView(book.id(), book.title(), loan.borrowedOn(), loan.dueDate(),
                                loan.extensions(), loan.extensionsLeft(policy), loan.overdueDays(today),
                                loan.lateFee(today, policy));
                    })
                    .toList();
            List<WaitlistView> waitlists = all.stream()
                    .flatMap(book -> book.waitlistPositionOf(actor.id())
                            .map(position -> new WaitlistView(book.id(), book.title(), position))
                            .stream())
                    .toList();
            AccountView account = null;
            if (!actor.admin()) {
                MemberAccount memberAccount = accountOf(actor, today);
                account = new AccountView(memberAccount.balance(), blockedReason(actor, memberAccount, today),
                        memberAccount.ledger());
            }
            return new MemberStatus(borrowed, waitlists, inbox.drain(actor.id()), account, today);
        });
    }

    /**
     * The admin's overview: catalogue and membership counts, every current loan (soonest due first)
     * and every registered member with their loans, wait lists, deposit and standing.
     */
    public AdminDashboard dashboard(Member actor) {
        if (!actor.admin()) {
            throw new ForbiddenException("Sorry, only the admin can see the library dashboard.");
        }
        return read(() -> {
            LocalDate today = today();
            List<Book> all = books.findAll();

            List<AdminLoanView> loans = all.stream()
                    .flatMap(book -> book.loan().stream().map(loan -> {
                        long daysUntilDue = ChronoUnit.DAYS.between(today, loan.dueDate());
                        LoanState state = loan.isOverdue(today) ? LoanState.OVERDUE
                                : daysUntilDue <= DUE_SOON_DAYS ? LoanState.DUE_SOON : LoanState.ON_TIME;
                        return new AdminLoanView(book.id(), book.title(), loan.memberId(), loan.memberName(),
                                loan.borrowedOn(), loan.dueDate(), daysUntilDue, state, loan.extensions(),
                                book.waitlistLength(), loan.lateFee(today, policy));
                    }))
                    .sorted(Comparator.comparing(AdminLoanView::dueDate).thenComparing(AdminLoanView::title))
                    .toList();

            List<AdminMemberView> members = accounts.findAll().stream()
                    .map(account -> {
                        List<AdminLoanView> own = loans.stream()
                                .filter(loan -> loan.memberId().equals(account.memberId())).toList();
                        int overdue = (int) own.stream().filter(loan -> loan.state() == LoanState.OVERDUE).count();
                        int dueSoon = (int) own.stream().filter(loan -> loan.state() == LoanState.DUE_SOON).count();
                        int waitlists = (int) all.stream()
                                .filter(book -> book.waitlistPositionOf(account.memberId()).isPresent()).count();
                        String blocked = overdue > 0 ? overdue + " overdue book" + (overdue == 1 ? "" : "s")
                                : !account.balance().isPositive() ? "Deposit balance is " + account.balance()
                                : null;
                        return new AdminMemberView(account.memberId(), account.memberName(), own.size(), overdue,
                                dueSoon, waitlists, account.balance(), blocked);
                    })
                    .sorted(Comparator.comparing(AdminMemberView::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();

            int borrowed = loans.size();
            AdminSummary summary = new AdminSummary(
                    all.size(),
                    all.size() - borrowed,
                    borrowed,
                    members.size(),
                    (int) members.stream().filter(member -> member.booksBorrowed() > 0).count(),
                    (int) loans.stream().filter(loan -> loan.state() == LoanState.OVERDUE).count(),
                    (int) loans.stream().filter(loan -> loan.state() == LoanState.DUE_SOON).count(),
                    all.stream().mapToInt(Book::waitlistLength).sum(),
                    (int) members.stream().filter(member -> member.blockedReason() != null).count(),
                    members.stream().map(AdminMemberView::balance).reduce(Money.ZERO, Money::plus),
                    loans.stream().map(AdminLoanView::lateFeeSoFar).reduce(Money.ZERO, Money::plus));
            return new AdminDashboard(today, DUE_SOON_DAYS, summary, members, loans);
        });
    }

    // --- rules --------------------------------------------------------------------------------

    /** The admin account is the librarian: it does not borrow, return, queue or hold a deposit. */
    private static void requireMember(Member actor) {
        if (actor.admin()) {
            throw new ForbiddenException(ADMIN_CANNOT_LEND);
        }
    }

    private void requireGoodStanding(Member actor, MemberAccount account, LocalDate today) {
        String reason = blockedReason(actor, account, today);
        if (reason != null) {
            throw new BorrowingBlockedException(reason);
        }
    }

    /** Why the member may not borrow or join wait lists right now, or null if they may. */
    private String blockedReason(Member actor, MemberAccount account, LocalDate today) {
        long overdue = books.findAll().stream()
                .flatMap(book -> book.loan().stream())
                .filter(loan -> loan.memberId().equals(actor.id()) && loan.isOverdue(today))
                .count();
        if (overdue > 0) {
            return "Sorry, you have " + overdue + " overdue book" + (overdue == 1 ? "" : "s")
                    + ". Please return " + (overdue == 1 ? "it" : "them") + " before borrowing or joining a wait list.";
        }
        if (!account.balance().isPositive()) {
            return "Sorry, your deposit balance is " + account.balance()
                    + ". Please top up your deposit before borrowing or joining a wait list.";
        }
        return null;
    }

    /** Gets the member's account, opening it with the initial cash deposit on first use. */
    private MemberAccount accountOf(Member actor, LocalDate today) {
        return accounts.findByMemberId(actor.id()).orElseGet(() -> {
            MemberAccount opened = MemberAccount.open(actor.id(), actor.name(), policy.initialDeposit(), today);
            accounts.save(opened);
            inbox.push(new MemberNotification(ids.get(), actor.id(), MemberNotification.Type.DEPOSIT_RECEIVED,
                    null, null, policy.initialDeposit() + " received as deposit in cash.", clock.instant()));
            return opened;
        });
    }

    private BookView toView(Book book, Member actor, LocalDate today) {
        Loan loan = book.loan().orElse(null);
        boolean mine = book.isBorrowedBy(actor.id());
        boolean showLoan = loan != null && (mine || actor.admin());
        return new BookView(
                book.id(),
                book.title(),
                book.isAvailable(),
                mine,
                book.waitlistPositionOf(actor.id()).orElse(null),
                book.waitlistLength(),
                showLoan && actor.admin() ? loan.memberName() : null,
                showLoan ? loan.dueDate() : null,
                showLoan ? loan.overdueDays(today) : null);
    }

    private Book requireBook(BookTitle title) {
        return books.findByTitleKey(title.key()).orElseThrow(() -> new BookNotFoundException(title.value()));
    }

    private <T> T read(Supplier<T> action) {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    private <T> T write(Supplier<T> action) {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
