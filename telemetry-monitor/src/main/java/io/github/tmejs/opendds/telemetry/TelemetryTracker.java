package io.github.tmejs.opendds.telemetry;

import Learning.TelemetrySample;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Tracks the latest forward-moving telemetry sequence for each keyed device.
 *
 * <p>The DDS listener calls {@link #accept(TelemetrySample, long)} on a middleware
 * thread while the monitor reads views on its application thread, so both methods
 * synchronize access to the per-device state.</p>
 */
public final class TelemetryTracker {
    private final Map<String, DeviceState> devices = new HashMap<>();

    public synchronized void accept(TelemetrySample sample, long receivedAtNanos) {
        Objects.requireNonNull(sample, "sample");
        String deviceId = Objects.requireNonNull(sample.device_id, "sample.device_id");
        if (deviceId.isBlank()) {
            throw new IllegalArgumentException("sample.device_id must not be blank");
        }

        DeviceState previous = devices.get(deviceId);
        if (previous != null && sample.sequenceNumber <= previous.sequence) {
            return;
        }

        long missingSamples = previous == null
                ? 0
                : Math.addExact(previous.missingSamples,
                        Math.subtractExact(sample.sequenceNumber, previous.sequence) - 1);
        devices.put(deviceId, new DeviceState(
                sample.sequenceNumber,
                sample.temperature_c,
                sample.humidity_percent,
                receivedAtNanos,
                missingSamples));
    }

    public synchronized Optional<TelemetryView> view(
            String deviceId, long nowNanos, long staleAfterNanos) {
        Objects.requireNonNull(deviceId, "deviceId");
        if (staleAfterNanos < 0) {
            throw new IllegalArgumentException("staleAfterNanos must be non-negative");
        }

        DeviceState state = devices.get(deviceId);
        if (state == null) {
            return Optional.empty();
        }

        long ageNanos = Math.max(0, nowNanos - state.receivedAtNanos);
        return Optional.of(new TelemetryView(
                deviceId,
                state.sequence,
                state.temperatureC,
                state.humidityPercent,
                ageNanos,
                state.missingSamples,
                ageNanos >= staleAfterNanos));
    }

    private record DeviceState(
            long sequence,
            double temperatureC,
            double humidityPercent,
            long receivedAtNanos,
            long missingSamples) {
    }
}
