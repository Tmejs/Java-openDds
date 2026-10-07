package io.github.tmejs.opendds.telemetry;

import Learning.TelemetrySample;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryTrackerTest {
    @Test
    void storesTheFirstSampleAndCalculatesAgeFromLocalReceiveTime() {
        TelemetryTracker tracker = new TelemetryTracker();
        TelemetrySample sample = sample("sensor-a", 1, 21.5, 48.25);

        tracker.accept(sample, 1_000);

        TelemetryView view = tracker.view("sensor-a", 1_400, 500).orElseThrow();
        assertEquals("sensor-a", view.deviceId());
        assertEquals(1, view.sequence());
        assertEquals(21.5, view.temperatureC());
        assertEquals(48.25, view.humidityPercent());
        assertEquals(400, view.ageNanos());
        assertEquals(0, view.missingSamples());
        assertFalse(view.stale());
    }

    @Test
    void countsForwardGapsAndIgnoresDuplicateAndOlderSamples() {
        TelemetryTracker tracker = new TelemetryTracker();
        tracker.accept(sample("sensor-a", 1, 20, 40), 1_000);
        tracker.accept(sample("sensor-a", 4, 24, 44), 2_000);

        TelemetryView afterGap = tracker.view("sensor-a", 2_500, 5_000).orElseThrow();
        assertEquals(4, afterGap.sequence());
        assertEquals(2, afterGap.missingSamples());

        tracker.accept(sample("sensor-a", 4, 99, 99), 3_000);
        tracker.accept(sample("sensor-a", 3, 88, 88), 4_000);

        TelemetryView unchanged = tracker.view("sensor-a", 4_000, 5_000).orElseThrow();
        assertEquals(4, unchanged.sequence());
        assertEquals(24, unchanged.temperatureC());
        assertEquals(44, unchanged.humidityPercent());
        assertEquals(2_000, unchanged.ageNanos());
        assertEquals(2, unchanged.missingSamples());
    }

    @Test
    void tracksEachDeviceSeparately() {
        TelemetryTracker tracker = new TelemetryTracker();
        tracker.accept(sample("sensor-a", 1, 20, 40), 1_000);
        tracker.accept(sample("sensor-b", 7, 30, 50), 2_000);

        assertEquals(1, tracker.view("sensor-a", 2_500, 5_000).orElseThrow().sequence());
        assertEquals(7, tracker.view("sensor-b", 2_500, 5_000).orElseThrow().sequence());
        assertEquals(0, tracker.view("sensor-b", 2_500, 5_000).orElseThrow().missingSamples());
    }

    @Test
    void marksTheSampleStaleAtTheConfiguredThreshold() {
        TelemetryTracker tracker = new TelemetryTracker();
        tracker.accept(sample("sensor-a", 1, 20, 40), 1_000);

        assertFalse(tracker.view("sensor-a", 5_999, 5_000).orElseThrow().stale());
        assertTrue(tracker.view("sensor-a", 6_000, 5_000).orElseThrow().stale());
    }

    @Test
    void returnsEmptyForAnUnknownDevice() {
        TelemetryTracker tracker = new TelemetryTracker();

        assertEquals(Optional.empty(), tracker.view("missing", 1_000, 5_000));
    }

    private static TelemetrySample sample(String id, long sequence,
                                          double temperature, double humidity) {
        TelemetrySample sample = new TelemetrySample();
        sample.device_id = id;
        sample.sequenceNumber = sequence;
        sample.temperature_c = temperature;
        sample.humidity_percent = humidity;
        return sample;
    }
}
