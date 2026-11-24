package com.library.books.application.port;

import com.library.books.domain.MemberNotification;
import java.util.List;

/** Driven port: pending notifications per member, delivered at most once. */
public interface NotificationInbox {
    void push(MemberNotification notification);

    /** Returns and removes the member's pending notifications. */
    List<MemberNotification> drain(String memberId);
}
