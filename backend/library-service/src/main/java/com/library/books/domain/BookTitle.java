package com.library.books.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A book title. Whitespace is normalised and matching is case-insensitive, so "the great  gatsby"
 * finds "The Great Gatsby". The library holds one copy per title.
 */
public record BookTitle(String value, String key) {

    public static final int MAX_LENGTH = 200;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    public static BookTitle of(String raw) {
        if (raw == null) {
            throw new ValidationException("Book title must not be empty.");
        }
        String value = WHITESPACE.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC).strip()).replaceAll(" ");
        if (value.isEmpty()) {
            throw new ValidationException("Book title must not be empty.");
        }
        if (value.length() > MAX_LENGTH) {
            throw new ValidationException("Book title must be at most " + MAX_LENGTH + " characters.");
        }
        if (CONTROL_CHARS.matcher(value).find()) {
            throw new ValidationException("Book title contains invalid characters.");
        }
        return new BookTitle(value, value.toLowerCase(Locale.ROOT));
    }
}
