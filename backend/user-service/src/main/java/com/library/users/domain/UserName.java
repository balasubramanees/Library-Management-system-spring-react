package com.library.users.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A user's name. Identity is case-insensitive ("alice" and "Alice" are the same person),
 * while the spelling used at first login is kept for display.
 */
public record UserName(String value, String key) {

    public static final int MAX_LENGTH = 50;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    public static UserName of(String raw) {
        if (raw == null) {
            throw new ValidationException("User name must not be empty.");
        }
        String value = WHITESPACE.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC).strip()).replaceAll(" ");
        if (value.isEmpty()) {
            throw new ValidationException("User name must not be empty.");
        }
        if (value.length() > MAX_LENGTH) {
            throw new ValidationException("User name must be at most " + MAX_LENGTH + " characters.");
        }
        if (CONTROL_CHARS.matcher(value).find()) {
            throw new ValidationException("User name contains invalid characters.");
        }
        return new UserName(value, value.toLowerCase(Locale.ROOT));
    }
}
