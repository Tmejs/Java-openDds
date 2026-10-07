package io.github.tmejs.opendds.telemetry;

import java.util.OptionalLong;

/** Deterministic finite sequence source for the telemetry publisher. */
public final class TelemetrySchedule {
    private int remaining;
    private long lastSequence;

    public TelemetrySchedule(int count, long initialSequence) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        this.remaining = count;
        this.lastSequence = initialSequence;
    }

    public OptionalLong nextSequence() {
        if (remaining == 0) {
            return OptionalLong.empty();
        }
        lastSequence = Math.incrementExact(lastSequence);
        remaining--;
        return OptionalLong.of(lastSequence);
    }
}
