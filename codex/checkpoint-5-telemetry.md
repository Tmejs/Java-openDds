# Checkpoint 5: Telemetry Publisher and Monitor

## Scope

Add a keyed OpenDDS telemetry publisher and monitor, deterministic sequence
tracking, local freshness detection, explicit reliability and history controls,
and end-to-end execution over the existing shared-memory and RTPS/UDP modes.

## Implementation

- `TelemetryDevice` registers one `TelemetrySample` instance per `device_id`,
  waits for a reader association, publishes a finite deterministic sequence,
  and waits for acknowledgements before a reliable finite run exits.
- `TelemetryMonitor` reads samples on the DDS listener thread, hands copied
  scalar state to a synchronized tracker, prints current values and gaps, and
  keeps a finite run alive until the last received value becomes stale.
- `TelemetryTracker` stores state independently for every keyed device. Forward
  sequence jumps accumulate missing samples; duplicate and older sequences do
  not replace the last value or refresh its receive time.
- Freshness uses the reader's local monotonic clock. The device wall-clock
  timestamp remains domain data and does not participate in age calculation.
- `EndpointQos` constructs the full Java QoS object graph required by the JNI
  holder API, retrieves OpenDDS defaults, then applies named reliable or
  best-effort delivery and an explicit `KEEP_LAST` depth. The ping helper
  delegates to this shared implementation and preserves its reliable API.
- Both launchers expose reliability, history depth, publish interval, and stale
  threshold. Compose passes the same reliability/history settings to the
  writer and reader so requested/offered QoS stays compatible.
- The smoke gate now requires ten published and received samples, zero gaps,
  and a stale final value in both transport topologies.

## Verification

- Test-first tracker run failed to compile before `TelemetryTracker` and
  `TelemetryView` existed. The implemented suite passes five cases covering
  first state, local age, forward gaps, duplicates, older samples, multiple
  device keys, the exact stale boundary, and unknown keys.
- Test-first schedule run failed to compile before `TelemetrySchedule` existed.
  Its four tests pass for exact finite output, a nonzero initial sequence, zero
  count, and negative-count rejection.
- Test-first QoS-option run failed to compile before `EndpointQos` existed.
  Its two tests pass for the two named reliability modes and invalid input.
- `mvn --batch-mode --no-transfer-progress clean verify` inside
  `java-opendds-builder:25-3.34.0` passed all six reactor projects and all 21
  JUnit tests.
- `./scripts/smoke-test.sh` passed ping over both transports, network domain 7,
  the expected 43/42 domain mismatch, and telemetry over shared memory and
  RTPS/UDP. Each telemetry run published and received exactly 10 samples,
  reported zero gaps, and observed `device-1` become stale after sequence 10.
- A separate network run with `--reliability best-effort` completed 10 samples
  with zero observed gaps on this host. This observation is not a delivery
  guarantee; the mode deliberately permits loss.
- `bash -n scripts/*.sh`, both Compose `config --quiet` checks, and
  `git diff --check` passed.

## Learning notes and limits

- DDS reliability and history are separate policies. Reliable delivery can
  retransmit and is the default baseline; `KEEP_LAST` bounds retained samples.
  History depth does not make volatile data durable or recover a best-effort
  sample that was already lost.
- A finite monitor waits for its sample target and then for staleness. This
  makes the learning run demonstrate both data delivery and freshness without
  relying on clock synchronization between containers.
- Two JVMs in the shared-memory container can interleave human-readable startup
  lines on the common Compose stream. Machine checks use single-process summary
  lines and do not infer application failure from cosmetic startup interleaving.

## Independent review

Pending before push.
