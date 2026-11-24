package com.library.books.domain;

/**
 * The library's lending rules, configured in application.yml ({@code library.lending.*}).
 *
 * @param loanPeriodDays  days from borrowing to the due date
 * @param extensionDays   days each extension adds to the due date
 * @param maxExtensions   extensions allowed per loan
 * @param lateFeePerDay   charged for every day a book is returned after its due date
 * @param initialDeposit  cash deposit taken when a member registers
 * @param minimumTopUp    smallest additional deposit accepted
 */
public record LendingPolicy(
        int loanPeriodDays,
        int extensionDays,
        int maxExtensions,
        Money lateFeePerDay,
        Money initialDeposit,
        Money minimumTopUp) {

    public static final LendingPolicy DEFAULT =
            new LendingPolicy(14, 7, 2, Money.rupees(10), Money.rupees(500), Money.rupees(100));

    public LendingPolicy {
        if (loanPeriodDays <= 0 || extensionDays <= 0 || maxExtensions < 0) {
            throw new IllegalArgumentException("Loan period and extension days must be positive");
        }
        if (lateFeePerDay.rupees() < 0 || !initialDeposit.isPositive() || !minimumTopUp.isPositive()) {
            throw new IllegalArgumentException("Fees must not be negative and deposits must be positive");
        }
    }
}
