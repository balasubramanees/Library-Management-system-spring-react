package com.library.books.adapter.in.web;

import com.library.books.adapter.in.web.LibraryDtos.AddBookResponse;
import com.library.books.adapter.in.web.LibraryDtos.BookDto;
import com.library.books.adapter.in.web.LibraryDtos.BookListResponse;
import com.library.books.adapter.in.web.LibraryDtos.BorrowResponse;
import com.library.books.adapter.in.web.LibraryDtos.ExtendResponse;
import com.library.books.adapter.in.web.LibraryDtos.ReturnResponse;
import com.library.books.adapter.in.web.LibraryDtos.TitleRequest;
import com.library.books.adapter.in.web.LibraryDtos.WaitlistResponse;
import com.library.books.application.LibraryService;
import com.library.books.application.LibraryViews.BookView;
import com.library.books.application.LibraryViews.BorrowResult;
import com.library.books.application.LibraryViews.ExtendResult;
import com.library.books.application.LibraryViews.ReturnResult;
import com.library.books.application.LibraryViews.WaitlistResult;
import com.library.books.domain.Book;
import com.library.books.domain.LibraryDates;
import com.library.books.domain.Member;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task-based endpoints that map one-to-one to the commands of the brief (plus {@code extend}).
 * Books are addressed by title (case-insensitive), exactly like {@code borrow "1984"} on the CLI.
 */
@RestController
@RequestMapping("/books")
public class BookController {

    private final LibraryService library;

    public BookController(LibraryService library) {
        this.library = library;
    }

    /** {@code list}. */
    @GetMapping
    public BookListResponse list(Member member) {
        List<BookDto> books = library.listBooks(member).stream().map(BookDto::from).toList();
        return new BookListResponse(books, books.isEmpty() ? "No books are registered" : null);
    }

    /** {@code add [book_name]}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddBookResponse add(Member member, @RequestBody TitleRequest request) {
        Book book = library.addBook(member, request.title());
        var view = new BookView(book.id(), book.title(), true, false, null, 0, null, null, null);
        return new AddBookResponse("Book \"" + book.title() + "\" has been added to the library.", BookDto.from(view));
    }

    /** {@code borrow [book_name]}. */
    @PostMapping("/borrow")
    public BorrowResponse borrow(Member member, @RequestBody TitleRequest request) {
        BorrowResult result = library.borrow(member, request.title());
        return new BorrowResponse("You borrowed \"" + result.title() + "\". Please return it by "
                + LibraryDates.format(result.dueDate()) + ".", result.dueDate());
    }

    /** {@code return [book_name]}; reports any late fee deducted from the deposit. */
    @PostMapping("/return")
    public ReturnResponse returnBook(Member member, @RequestBody TitleRequest request) {
        ReturnResult result = library.returnBook(member, request.title());
        String message = "You returned \"" + result.title() + "\".";
        if (result.lateFee().isPositive()) {
            message += " It was " + result.daysLate() + " day" + (result.daysLate() == 1 ? "" : "s") + " late: "
                    + result.lateFee() + " late fee deducted from your deposit (balance " + result.balance() + ").";
        }
        return new ReturnResponse(message, result.lateFee().rupees(), result.balance().rupees());
    }

    /** Moves the return date of a borrowed book (at most twice per loan). */
    @PostMapping("/extend")
    public ExtendResponse extend(Member member, @RequestBody TitleRequest request) {
        ExtendResult result = library.extendLoan(member, request.title());
        String left = result.extensionsLeft() == 0
                ? "This was the last extension."
                : result.extensionsLeft() + " extension" + (result.extensionsLeft() == 1 ? "" : "s") + " left.";
        return new ExtendResponse("The return date of \"" + result.title() + "\" is now "
                + LibraryDates.format(result.dueDate()) + ". " + left, result.dueDate(), result.extensionsLeft());
    }

    /** {@code waitlist [book_name]}. */
    @PostMapping("/waitlist")
    public WaitlistResponse waitlist(Member member, @RequestBody TitleRequest request) {
        WaitlistResult result = library.joinWaitlist(member, request.title());
        return new WaitlistResponse(
                "You are added to the wait list of \"" + result.title() + "\", your position is " + result.position() + ".",
                result.position());
    }
}
