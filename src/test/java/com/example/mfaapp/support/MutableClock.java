package com.example.mfaapp.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A {@link Clock} the tests move by hand. Pinning time is what makes the TOTP drift window, the
 * replay guard and the attempt-throttle window assertable rather than flaky.
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    public void advance(Duration amount) {
        this.instant = this.instant.plus(amount);
    }
}
