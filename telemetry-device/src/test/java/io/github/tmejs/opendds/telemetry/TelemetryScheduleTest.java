package io.github.tmejs.opendds.telemetry;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryScheduleTest {
    @Test
    void returnsExactlyTheConfiguredSuccessiveSequences() {
        TelemetrySchedule schedule = new TelemetrySchedule(3, 0);

        assertEquals(OptionalLong.of(1), schedule.nextSequence());
        assertEquals(OptionalLong.of(2), schedule.nextSequence());
        assertEquals(OptionalLong.of(3), schedule.nextSequence());
        assertEquals(OptionalLong.empty(), schedule.nextSequence());
        assertEquals(OptionalLong.empty(), schedule.nextSequence());
    }

    @Test
    void startsAfterTheProvidedInitialSequence() {
        TelemetrySchedule schedule = new TelemetrySchedule(2, 41);

        assertEquals(OptionalLong.of(42), schedule.nextSequence());
        assertEquals(OptionalLong.of(43), schedule.nextSequence());
        assertEquals(OptionalLong.empty(), schedule.nextSequence());
    }

    @Test
    void zeroCountIsImmediatelyComplete() {
        assertEquals(OptionalLong.empty(), new TelemetrySchedule(0, 0).nextSequence());
    }

    @Test
    void rejectsANegativeCount() {
        assertThrows(IllegalArgumentException.class, () -> new TelemetrySchedule(-1, 0));
    }
}
