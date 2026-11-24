package com.library.books.adapter.in.web;

import com.library.books.adapter.in.web.LibraryDtos.AdvanceClockRequest;
import com.library.books.adapter.in.web.LibraryDtos.ClockResponse;
import com.library.books.adapter.out.clock.AdjustableClock;
import com.library.books.application.LibraryService;
import com.library.books.domain.ForbiddenException;
import com.library.books.domain.LibraryDates;
import com.library.books.domain.Member;
import com.library.books.domain.ValidationException;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo-only "time travel" for the admin: moves the library's date forward so due dates, overdue
 * blocks and late fees can be seen without waiting. Disabled with
 * {@code library.demo.time-travel=false}, in which case these endpoints do not exist (404).
 */
@RestController
@RequestMapping("/admin/clock")
@ConditionalOnProperty(name = "library.demo.time-travel", havingValue = "true")
public class AdminController {

    private final AdjustableClock clock;
    private final LibraryService library;

    public AdminController(AdjustableClock clock, LibraryService library) {
        this.clock = clock;
        this.library = library;
    }

    @GetMapping
    public ClockResponse today(Member member) {
        requireAdmin(member);
        LocalDate today = library.today();
        return new ClockResponse(today, "Library date: " + LibraryDates.format(today) + ".");
    }

    @PostMapping("/advance")
    public ClockResponse advance(Member member, @RequestBody AdvanceClockRequest request) {
        requireAdmin(member);
        if (request.days() == null || request.days() < 1 || request.days() > 365) {
            throw new ValidationException("Days must be between 1 and 365.");
        }
        clock.advance(Duration.ofDays(request.days()));
        LocalDate today = library.today();
        return new ClockResponse(today, "Library date moved forward " + request.days() + " day"
                + (request.days() == 1 ? "" : "s") + " to " + LibraryDates.format(today) + ".");
    }

    private static void requireAdmin(Member member) {
        if (!member.admin()) {
            throw new ForbiddenException("Sorry, only the admin can change the library date.");
        }
    }
}
