package com.library.books.adapter.out.memory;

import com.library.books.application.port.NotificationInbox;
import com.library.books.domain.MemberNotification;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryNotificationInbox implements NotificationInbox {

    private final ConcurrentMap<String, List<MemberNotification>> pending = new ConcurrentHashMap<>();

    @Override
    public void push(MemberNotification notification) {
        pending.compute(notification.memberId(), (id, queue) -> {
            List<MemberNotification> next = queue == null ? new ArrayList<>() : new ArrayList<>(queue);
            next.add(notification);
            return List.copyOf(next);
        });
    }

    @Override
    public List<MemberNotification> drain(String memberId) {
        List<MemberNotification> drained = pending.remove(memberId);
        return drained == null ? List.of() : drained;
    }
}
