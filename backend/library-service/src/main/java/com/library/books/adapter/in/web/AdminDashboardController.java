package com.library.books.adapter.in.web;

import com.library.books.adapter.in.web.LibraryDtos.AdminDashboardResponse;
import com.library.books.application.LibraryService;
import com.library.books.domain.Member;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The librarian's overview: members, current loans and what is overdue or due soon. Admin only (403 otherwise). */
@RestController
public class AdminDashboardController {

    private final LibraryService library;

    public AdminDashboardController(LibraryService library) {
        this.library = library;
    }

    @GetMapping("/admin/dashboard")
    public AdminDashboardResponse dashboard(Member member) {
        return AdminDashboardResponse.from(library.dashboard(member));
    }
}
