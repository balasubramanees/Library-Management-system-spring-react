package com.library.books.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** One date format for every user-facing message, e.g. "08 Dec 2026". */
public final class LibraryDates {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private LibraryDates() {}

    public static String format(LocalDate date) {
        return FORMAT.format(date);
    }
}
