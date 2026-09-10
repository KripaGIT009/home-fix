package com.homefix.location.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A {@link Clock} whose current instant can be advanced by tests, making the rate-limit
 * (Requirement 10.1) and staleness (Requirement 10.7) logic fully deterministic.
 */
public class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void advanceSeconds(long seconds) {
        now = now.plus(Duration.ofSeconds(seconds));
    }
}
