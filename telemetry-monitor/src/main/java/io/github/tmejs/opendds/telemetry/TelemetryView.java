package io.github.tmejs.opendds.telemetry;

/** Immutable monitor projection of the latest accepted sample for one keyed device. */
public record TelemetryView(
        String deviceId,
        long sequence,
        double temperatureC,
        double humidityPercent,
        long ageNanos,
        long missingSamples,
        boolean stale) {
}
