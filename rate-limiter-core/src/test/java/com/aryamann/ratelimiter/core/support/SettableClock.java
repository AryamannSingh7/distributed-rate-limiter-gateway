package com.aryamann.ratelimiter.core.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A {@link Clock} whose instant can be set and advanced, so tests can deterministically exercise
 * time-dependent behaviour (token refill, window rollover) without sleeping.
 */
public final class SettableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public SettableClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    private SettableClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new SettableClock(instant, newZone);
    }

    /** Move the clock forward by the given amount. */
    public void advance(Duration amount) {
        this.instant = this.instant.plus(amount);
    }
}
