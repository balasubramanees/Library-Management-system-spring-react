package com.library.books.adapter.out.clock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The library's clock: real time plus an offset that the admin can move forward in demo mode
 * ("what happens when a book is not returned?") without waiting two weeks. The offset only ever
 * grows, so time never runs backwards for loans already recorded.
 */
public class AdjustableClock extends Clock {

    private final Clock base;
    private final AtomicReference<Duration> offset;

    public AdjustableClock(Clock base) {
        this(base, new AtomicReference<>(Duration.ZERO));
    }

    private AdjustableClock(Clock base, AtomicReference<Duration> offset) {
        this.base = base;
        this.offset = offset;
    }

    public void advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("The clock can only move forward");
        }
        offset.updateAndGet(current -> current.plus(amount));
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new AdjustableClock(base.withZone(zone), offset);
    }
}
