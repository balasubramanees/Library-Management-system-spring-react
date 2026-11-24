package com.library.books.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.library.books.application.LibraryViews.AccountView;
import com.library.books.application.LibraryViews.AdminDashboard;
import com.library.books.application.LibraryViews.AdminLoanView;
import com.library.books.application.LibraryViews.AdminMemberView;
import com.library.books.application.LibraryViews.AdminSummary;
import com.library.books.application.LibraryViews.BookView;
import com.library.books.application.LibraryViews.LoanView;
import com.library.books.domain.LendingPolicy;
import com.library.books.domain.MemberAccount;
import com.library.books.domain.MemberNotification;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire format of the library API (mirrored by frontend/packages/contracts). Dates are ISO
 * {@code yyyy-MM-dd}; money is whole rupees.
 */
public final class LibraryDtos {

    private LibraryDtos() {}

    public record TitleRequest(String title) {}

    public record DepositRequest(Long amount) {}

    public record MessageResponse(String message) {}

    public record BorrowResponse(String message, LocalDate dueDate) {}

    public record ReturnResponse(String message, long lateFee, Long balance) {}

    public record ExtendResponse(String message, LocalDate dueDate, int extensionsLeft) {}

    public record WaitlistResponse(String message, int position) {}

    public record DepositResponse(String message, long balance) {}

    /**
     * {@code borrowerName} is only sent to the admin; {@code dueDate}/{@code overdueDays} only to the
     * borrower and the admin.
     */
    public record BookDto(String id, String title, String status, boolean borrowedByMe,
                          Integer myWaitlistPosition, int waitlistLength,
                          String borrowerName, LocalDate dueDate, Long overdueDays) {
        static BookDto from(BookView view) {
            return new BookDto(view.id(), view.title(), view.available() ? "available" : "borrowed",
                    view.borrowedByMe(), view.myWaitlistPosition(), view.waitlistLength(),
                    view.borrowerName(), view.dueDate(), view.overdueDays());
        }
    }

    public record BookListResponse(List<BookDto> books, String message) {}

    public record AddBookResponse(String message, BookDto book) {}

    public record LoanDto(String bookId, String title, LocalDate borrowedOn, LocalDate dueDate,
                          int extensionsUsed, int extensionsLeft, long overdueDays, long lateFeeSoFar) {
        static LoanDto from(LoanView view) {
            return new LoanDto(view.bookId(), view.title(), view.borrowedOn(), view.dueDate(), view.extensionsUsed(),
                    view.extensionsLeft(), view.overdueDays(), view.lateFeeSoFar().rupees());
        }
    }

    public record LedgerEntryDto(LocalDate date, String description, long amount) {
        static LedgerEntryDto from(MemberAccount.Entry entry) {
            return new LedgerEntryDto(entry.date(), entry.description(), entry.amount().rupees());
        }
    }

    /** {@code blockedReason} is null when the member may borrow. */
    public record AccountDto(long balance, boolean blocked, String blockedReason, List<LedgerEntryDto> ledger) {
        static AccountDto from(AccountView view) {
            return view == null ? null : new AccountDto(view.balance().rupees(), view.blockedReason() != null,
                    view.blockedReason(), view.ledger().stream().map(LedgerEntryDto::from).toList());
        }
    }

    public record PolicyDto(int loanPeriodDays, int extensionDays, int maxExtensions, long lateFeePerDay,
                            long initialDeposit, long minimumTopUp) {
        static PolicyDto from(LendingPolicy policy) {
            return new PolicyDto(policy.loanPeriodDays(), policy.extensionDays(), policy.maxExtensions(),
                    policy.lateFeePerDay().rupees(), policy.initialDeposit().rupees(), policy.minimumTopUp().rupees());
        }
    }

    public record NotificationDto(String id, String type, String bookId, String title, String message, String createdAt) {
        static NotificationDto from(MemberNotification notification) {
            return new NotificationDto(notification.id(), notification.type().name(), notification.bookId(),
                    notification.title(), notification.message(), notification.createdAt().toString());
        }
    }

    public record StatusResponse(LocalDate today, List<LoanDto> borrowed, List<WaitlistDto> waitlists,
                                 List<NotificationDto> notifications, AccountDto account, PolicyDto policy,
                                 List<String> lines) {}

    public record WaitlistDto(String bookId, String title, int position) {}

    public record MemberDto(String id, String name, @JsonProperty("isAdmin") boolean isAdmin) {}

    /** {@code state} is ON_TIME, DUE_SOON or OVERDUE; {@code daysUntilDue} is negative when overdue. */
    public record AdminLoanDto(String bookId, String title, String memberId, String memberName,
                               LocalDate borrowedOn, LocalDate dueDate, long daysUntilDue, String state,
                               int extensionsUsed, int waitlistLength, long lateFeeSoFar) {
        static AdminLoanDto from(AdminLoanView view) {
            return new AdminLoanDto(view.bookId(), view.title(), view.memberId(), view.memberName(), view.borrowedOn(),
                    view.dueDate(), view.daysUntilDue(), view.state().name(), view.extensionsUsed(),
                    view.waitlistLength(), view.lateFeeSoFar().rupees());
        }
    }

    /** {@code blockedReason} is null when the member may borrow. */
    public record AdminMemberDto(String id, String name, int booksBorrowed, int overdue, int dueSoon,
                                 int waitlists, long balance, String blockedReason) {
        static AdminMemberDto from(AdminMemberView view) {
            return new AdminMemberDto(view.id(), view.name(), view.booksBorrowed(), view.overdue(), view.dueSoon(),
                    view.waitlists(), view.balance().rupees(), view.blockedReason());
        }
    }

    public record AdminSummaryDto(int totalBooks, int availableBooks, int borrowedBooks, int members,
                                  int membersWithBooks, int overdueLoans, int dueSoonLoans, int waitlistEntries,
                                  int blockedMembers, long depositsHeld, long lateFeesOutstanding) {
        static AdminSummaryDto from(AdminSummary s) {
            return new AdminSummaryDto(s.totalBooks(), s.availableBooks(), s.borrowedBooks(), s.members(),
                    s.membersWithBooks(), s.overdueLoans(), s.dueSoonLoans(), s.waitlistEntries(), s.blockedMembers(),
                    s.depositsHeld().rupees(), s.lateFeesOutstanding().rupees());
        }
    }

    public record AdminDashboardResponse(LocalDate today, int dueSoonDays, AdminSummaryDto summary,
                                         List<AdminMemberDto> members, List<AdminLoanDto> loans) {
        static AdminDashboardResponse from(AdminDashboard d) {
            return new AdminDashboardResponse(d.today(), d.dueSoonDays(), AdminSummaryDto.from(d.summary()),
                    d.members().stream().map(AdminMemberDto::from).toList(),
                    d.loans().stream().map(AdminLoanDto::from).toList());
        }
    }

    public record ClockResponse(LocalDate today, String message) {}

    public record AdvanceClockRequest(Integer days) {}
}
