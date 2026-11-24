package com.library.books.domain;

import java.time.Instant;

/**
 * A message for a member, delivered once with their next status (usually at login).
 *
 * @param bookId and {@code title} are null for notifications not about a book (deposits)
 */
public record MemberNotification(
        String id, String memberId, Type type, String bookId, String title, String message, Instant createdAt) {

    public enum Type {
        /** A returned book was handed to this member from the wait list. */
        WAITLIST_ALLOCATED,
        /** The member's cash deposit was recorded. */
        DEPOSIT_RECEIVED
    }
}
