package com.library.books.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MoneyAndAccountTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    @Test
    void formatsRupees() {
        assertThat(Money.rupees(500)).hasToString("Rs. 500");
        assertThat(Money.rupees(-20)).hasToString("-Rs. 20");
        assertThat(Money.rupees(10).times(3)).isEqualTo(Money.rupees(30));
    }

    @Test
    void accountRecordsDepositsAndFeesAndMayGoNegative() {
        MemberAccount account = MemberAccount.open("alice", "Alice", Money.rupees(500), TODAY)
                .chargeLateFee(Money.rupees(530), "1984", 53, TODAY)
                .deposit(Money.rupees(100), TODAY);

        assertThat(account.balance()).isEqualTo(Money.rupees(70));
        assertThat(account.ledger()).extracting(MemberAccount.Entry::amount)
                .containsExactly(Money.rupees(500), Money.rupees(-530), Money.rupees(100));
        assertThat(account.ledger().get(1).description()).isEqualTo("Late fee for \"1984\" (53 days)");
    }

    @Test
    void accountIsImmutable() {
        MemberAccount opened = MemberAccount.open("alice", "Alice", Money.rupees(500), TODAY);
        opened.deposit(Money.rupees(100), TODAY);
        assertThat(opened.balance()).isEqualTo(Money.rupees(500));
    }
}
