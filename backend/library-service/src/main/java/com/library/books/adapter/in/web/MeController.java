package com.library.books.adapter.in.web;

import com.library.books.adapter.in.web.LibraryDtos.AccountDto;
import com.library.books.adapter.in.web.LibraryDtos.DepositRequest;
import com.library.books.adapter.in.web.LibraryDtos.DepositResponse;
import com.library.books.adapter.in.web.LibraryDtos.LoanDto;
import com.library.books.adapter.in.web.LibraryDtos.MemberDto;
import com.library.books.adapter.in.web.LibraryDtos.NotificationDto;
import com.library.books.adapter.in.web.LibraryDtos.PolicyDto;
import com.library.books.adapter.in.web.LibraryDtos.StatusResponse;
import com.library.books.adapter.in.web.LibraryDtos.WaitlistDto;
import com.library.books.application.LibraryService;
import com.library.books.application.LibraryViews.DepositResult;
import com.library.books.application.LibraryViews.MemberStatus;
import com.library.books.domain.Member;
import com.library.books.domain.ValidationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me")
public class MeController {

    private final LibraryService library;

    public MeController(LibraryService library) {
        this.library = library;
    }

    @GetMapping
    public MemberDto me(Member member) {
        return new MemberDto(member.id(), member.name(), member.admin());
    }

    /**
     * {@code status} - loans with due dates, wait lists and deposit account. Also delivers (and
     * clears) pending notifications. A member's first call opens their deposit account.
     */
    @GetMapping("/status")
    public StatusResponse status(Member member) {
        MemberStatus status = library.status(member);
        return new StatusResponse(
                status.today(),
                status.borrowed().stream().map(LoanDto::from).toList(),
                status.waitlists().stream().map(w -> new WaitlistDto(w.bookId(), w.title(), w.position())).toList(),
                status.notifications().stream().map(NotificationDto::from).toList(),
                AccountDto.from(status.account()),
                PolicyDto.from(library.policy()),
                StatusFormatter.lines(status));
    }

    /** Records an additional cash deposit (top-up). */
    @PostMapping("/deposit")
    public DepositResponse deposit(Member member, @RequestBody DepositRequest request) {
        if (request.amount() == null) {
            throw new ValidationException("Deposit amount is required.");
        }
        DepositResult result = library.deposit(member, request.amount());
        return new DepositResponse(result.amount() + " received as deposit in cash. Your deposit balance is "
                + result.balance() + ".", result.balance().rupees());
    }
}
