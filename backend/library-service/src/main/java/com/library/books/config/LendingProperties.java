package com.library.books.config;

import com.library.books.domain.LendingPolicy;
import com.library.books.domain.Money;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code library.lending.*} from application.yml. Amounts are whole rupees. */
@ConfigurationProperties(prefix = "library.lending")
public record LendingProperties(
        int loanPeriodDays,
        int extensionDays,
        int maxExtensions,
        long lateFeePerDay,
        long initialDeposit,
        long minimumTopUp) {

    public LendingPolicy toPolicy() {
        return new LendingPolicy(loanPeriodDays, extensionDays, maxExtensions, Money.rupees(lateFeePerDay),
                Money.rupees(initialDeposit), Money.rupees(minimumTopUp));
    }
}
