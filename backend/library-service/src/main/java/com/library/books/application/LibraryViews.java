package com.library.books.application;

import com.library.books.domain.MemberAccount;
import com.library.books.domain.MemberNotification;
import com.library.books.domain.Money;
import java.time.LocalDate;
import java.util.List;

/** Read models returned by {@link LibraryService}. */
public final class LibraryViews {

    private LibraryViews() {}

    /**
     * A catalogue entry as seen by the caller. Loan details are only filled in for the borrower
     * themself or for the admin (librarian); other members only see "borrowed".
     */
    public record BookView(String id, String title, boolean available, boolean borrowedByMe,
                           Integer myWaitlistPosition, int waitlistLength,
                           String borrowerName, LocalDate dueDate, Long overdueDays) {}

    public record LoanView(String bookId, String title, LocalDate borrowedOn, LocalDate dueDate,
                           int extensionsUsed, int extensionsLeft, long overdueDays, Money lateFeeSoFar) {}

    public record WaitlistView(String bookId, String title, int position) {}

    /** {@code blockedReason} is null when the member may borrow. */
    public record AccountView(Money balance, String blockedReason, List<MemberAccount.Entry> ledger) {}

    public record MemberStatus(List<LoanView> borrowed, List<WaitlistView> waitlists,
                               List<MemberNotification> notifications, AccountView account, LocalDate today) {}

    /** How a current loan stands relative to its due date. */
    public enum LoanState { ON_TIME, DUE_SOON, OVERDUE }

    /** One current loan, as the admin (librarian) sees it. {@code daysUntilDue} is negative when overdue. */
    public record AdminLoanView(String bookId, String title, String memberId, String memberName,
                                LocalDate borrowedOn, LocalDate dueDate, long daysUntilDue, LoanState state,
                                int extensionsUsed, int waitlistLength, Money lateFeeSoFar) {}

    /** One registered member, as the admin sees them. {@code blockedReason} is null when they may borrow. */
    public record AdminMemberView(String id, String name, int booksBorrowed, int overdue, int dueSoon,
                                  int waitlists, Money balance, String blockedReason) {}

    public record AdminSummary(int totalBooks, int availableBooks, int borrowedBooks, int members,
                               int membersWithBooks, int overdueLoans, int dueSoonLoans, int waitlistEntries,
                               int blockedMembers, Money depositsHeld, Money lateFeesOutstanding) {}

    /** The admin dashboard: who has what, and which loans need chasing. */
    public record AdminDashboard(LocalDate today, int dueSoonDays, AdminSummary summary,
                                 List<AdminMemberView> members, List<AdminLoanView> loans) {}

    public record BorrowResult(String title, LocalDate dueDate) {}

    public record ReturnResult(String title, boolean handedToWaitlist, long daysLate, Money lateFee, Money balance) {}

    public record ExtendResult(String title, LocalDate dueDate, int extensionsLeft) {}

    public record WaitlistResult(String title, int position) {}

    public record DepositResult(Money amount, Money balance) {}
}
