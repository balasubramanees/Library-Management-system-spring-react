package com.library.books.adapter.in.web;

import com.library.books.application.LibraryViews.LoanView;
import com.library.books.application.LibraryViews.MemberStatus;
import com.library.books.domain.LibraryDates;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders {@code status} as the CLI in the problem statement prints it, extended with due dates,
 * overdue warnings and the deposit balance.
 */
final class StatusFormatter {

    private StatusFormatter() {}

    static List<String> lines(MemberStatus status) {
        List<String> lines = new ArrayList<>();
        status.notifications().forEach(n -> lines.add(n.message()));
        if (status.borrowed().isEmpty()) {
            lines.add("You don't have any books borrowed yet.");
        } else {
            lines.add("Your borrowed books:");
            for (int i = 0; i < status.borrowed().size(); i++) {
                lines.add((i + 1) + ". " + describe(status.borrowed().get(i)));
            }
        }
        if (!status.waitlists().isEmpty()) {
            lines.add("Your wait lists:");
            for (int i = 0; i < status.waitlists().size(); i++) {
                var entry = status.waitlists().get(i);
                lines.add((i + 1) + ". " + entry.title() + " - position " + entry.position());
            }
        }
        if (status.account() != null) {
            lines.add("Your deposit balance: " + status.account().balance());
            if (status.account().blockedReason() != null) {
                lines.add(status.account().blockedReason());
            }
        }
        return lines;
    }

    static String describe(LoanView loan) {
        String line = loan.title() + " - due " + LibraryDates.format(loan.dueDate());
        if (loan.overdueDays() > 0) {
            line += " (OVERDUE by " + loan.overdueDays() + " day" + (loan.overdueDays() == 1 ? "" : "s")
                    + ", late fee so far " + loan.lateFeeSoFar() + ")";
        }
        return line;
    }
}
