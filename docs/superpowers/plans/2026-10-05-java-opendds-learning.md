# Java 25 and OpenDDS Learning Project Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a documented Maven multimodule Java 25 application that demonstrates OpenDDS ping/pong round-trip measurement and simulated telemetry over shared-memory and network transports.

**Architecture:** A Maven reactor contains generated DDS type support plus separate ping requester/responder and telemetry device/monitor applications. A pinned Linux container builds OpenDDS's Java/JNI bindings with MPC and `make`; the same Java artifacts run under two Docker configurations that change discovery/network deployment and data transport.

**Tech Stack:** Java 25, Maven, OpenDDS 3.34.0 candidate, OpenDDS MPC/`make`, Linux Docker, JUnit Jupiter, Docker Compose.

**Spec:** [2026-10-05-java-opendds-learning-design.md](../specs/2026-10-05-java-opendds-learning-design.md)

## Global Constraints

- Use Java 25 and Maven for the Java application modules.
- Run builds and examples in a Linux Docker environment.
- Pin the OpenDDS release and container/build environment for reproducibility. OpenDDS 3.34.0 is the proposed baseline; verify Java 25 compatibility before relying on it.
- Keep OpenDDS IDL/JNI generation and its native type-support build on the documented OpenDDS MPC and `make` toolchain. Maven can invoke that build and manage the Java application modules.
- Provide two run configurations over the same Java application modules: shared memory and network communication.
- Documentation is a first-class deliverable for a mid-level developer new to DDS/OpenDDS.

## Review Focus

- Java 25 or the selected OpenDDS release cannot compile/load generated JNI bindings — prove this with the isolated compatibility probe before creating the reactor.
- A generated type-support `.so` is missing from the runtime search path — test a fresh container launch using only documented launch scripts.
- Discovery or topic matching fails across container services — run a bounded smoke test with one explicit domain/topic and verify matching before longer examples.
- A requester runs without a responder or receives an unrelated reply — unit-test timeout and correlation behavior, then run a no-responder smoke case.
- Telemetry samples are duplicated, out of order, or have sequence gaps — unit-test tracker behavior and exercise the monitor with deterministic sample sequences.

---

## File Structure

The repository starts with the approved design spec only. Add product files by responsibility:

- `compatibility/java25-opendds-3.34/`: isolated probe container and report that establishes the exact supported Java/OpenDDS build and run commands before application scaffolding.
- `docker/opendds-builder/Dockerfile`: pinned Linux/JDK/OpenDDS/Maven build environment based on the successful probe.
- `pom.xml`: root Maven reactor with group `io.github.tmejs.opendds`, version `0.1.0-SNAPSHOT`, Java 25 compiler configuration, shared test dependencies, and module order.
- `dds-types/pom.xml`, `dds-types/src/main/idl/Learning.idl`, `dds-types/scripts/generate.sh`: developer-owned IDL and deterministic generated type-support build.
- `ping-requester/` and `ping-responder/`: one Java entry point and focused tests each; both depend on `dds-types`.
- `ping-requester/src/main/java/io/github/tmejs/opendds/ping/PingReplyMatcher.java`: correlation for one in-flight ping request at a time.
- `telemetry-device/` and `telemetry-monitor/`: simulated publisher and monitoring subscriber with unit tests for the monitoring state.
- `telemetry-device/src/main/java/io/github/tmejs/opendds/telemetry/TelemetrySchedule.java`: deterministic sample sequence generation, independent of real sleeps and wall-clock reads.
- `docker/compose.shared-memory.yml` and `docker/compose.network.yml`: run modes with distinct process layouts but the same application artifacts. Shared-memory services are `ping-lab` and `telemetry-lab`; network services are `ping-requester`, `ping-responder`, `telemetry-device`, and `telemetry-monitor`.
- `scripts/run-shared-memory.sh`, `scripts/run-network.sh`, `scripts/smoke-test.sh`: copyable lifecycle and smoke-test commands.
- `README.md` and `docs/learning-guide.md`: concise verified quick start and the concept-by-concept tutorial; `docs/diagrams/` holds source diagrams.

Generated Java and C++ files, JARs, and shared libraries belong under ignored build output directories. Do not hand-edit generated files or commit compiled artifacts.

## Git Checkpoint Protocol

For each numbered checkpoint after the bootstrap commit, first verify `main` is clean, fetch `origin`, and confirm local `main` equals `origin/main`. Create the matching fresh branch from `main`: `codex/checkpoint-1-java25-bindings`, `codex/checkpoint-2-maven-reactor`, `codex/checkpoint-3-ping-measurements`, `codex/checkpoint-4-transport-modes`, `codex/checkpoint-5-telemetry`, or `codex/checkpoint-6-learning-docs`. Complete that checkpoint's local tests and `git diff --check`, request an independent review, record evidence under `codex/`, fix Critical/Important findings, re-review, push the branch, verify its three SHAs, then merge with `--no-ff`, run the applicable merged gate, push `main`, and verify local/tracking/remote `main` SHAs. Do not force-push or delete completed branches. Do not begin the next checkpoint until the current one is merged and synchronized. If authentication or another external condition prevents synchronization, stop before creating the checkpoint branch and report the exact blocker.

## Checkpoint 1: Verify Java 25 and OpenDDS Java Bindings

### Task 1: Build the compatibility probe

**Files:**
- Create: `compatibility/java25-opendds-3.34/Dockerfile`
- Create: `compatibility/java25-opendds-3.34/run.sh`
- Create: `compatibility/java25-opendds-3.34/README.md`

**Interfaces:**
- `run.sh` is the single entry point. It builds/runs the probe image, checks `java --version` is 25, and exits non-zero on any build, native-load, or sample-exchange failure.
- The README records the exact OpenDDS version, image base, JDK/Maven versions, commands, generated JAR/native library paths, and observed result.

- [x] **Step 1: Inspect the OpenDDS 3.34.0 Java installation and Docker release instructions.** Record the required compiler, Perl, JDK, MPC, and ACE/TAO setup in the probe README. Check whether the official image has Java bindings enabled; do not assume the C++ Docker quick start includes them.
- [x] **Step 2: Create the probe image from the successful base path.** Use the pinned OpenDDS release image if it contains Java-enabled OpenDDS and JDK 25. Otherwise use a pinned Linux JDK 25 image, install the documented C++/Perl build prerequisites, download OpenDDS 3.34.0, run `./configure --java`, and build with the documented GNU Make workflow. Keep the branch decision and exact image/tag in the README.
- [x] **Step 3: Run OpenDDS's maintained Java Messenger example inside the image.** After `source setenv.sh`, run `cd java/tests/messenger && ./run_test.pl`; confirm its subscriber receives the publisher's expected message. The example's IDL is the minimal topic-type probe; use its Java/JNI loader setup as the source for later module integration.
- [x] **Step 4: Make `run.sh` fail on a broken probe.** It must check the Java major version, run the generated publisher/subscriber exchange, preserve the command's exit code, and print a short success line only after the subscriber confirms receipt.
- [x] **Step 5: Record the compatibility result.** State whether Java 25 plus OpenDDS 3.34.0 passed generation, compilation, native loading, and exchange individually. If any stage fails, stop this plan and update the design spec with the exact failure and a proposed compatible version pair for user review before choosing it.
- [x] **Step 6: Run the probe from a clean Docker build.** Run `./compatibility/java25-opendds-3.34/run.sh`. Expected: the image builds from pinned inputs, the OpenDDS Java example exchanges a sample, and the script exits 0; otherwise preserve the full failing stage in the README.
- [x] **Step 7: Check and commit the probe.** Run `git diff --check`, inspect the Dockerfile/script/report diff, then commit with `test: verify OpenDDS Java 25 bindings`.

## Checkpoint 2: Create the Maven Reactor and DDS Types Module

### Task 2: Scaffold the root Maven reactor

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Create: `docker/opendds-builder/Dockerfile`
- Create: `dds-types/pom.xml`
- Create: `ping-requester/pom.xml`
- Create: `ping-responder/pom.xml`
- Create: `telemetry-device/pom.xml`
- Create: `telemetry-monitor/pom.xml`

**Interfaces:**
- Maven modules are `dds-types`, `ping-requester`, `ping-responder`, `telemetry-device`, and `telemetry-monitor`, in dependency order.
- All modules compile with `<maven.compiler.release>25</maven.compiler.release>` and share the same test-library version through root dependency management.
- The builder image reproduces the successful Task 1 environment; it must not silently change OpenDDS or JDK versions.

- [x] **Step 1: Add the reactor POM.** Set `packaging` to `pom`, set the five modules in dependency order, set Java release 25, and centralize JUnit Jupiter dependency management. Configure a current Maven Surefire version known to run on JDK 25 based on the successful probe.
- [x] **Step 2: Ignore generated output.** Add `.gitignore` entries for `target/`, MPC-generated Makefiles/workspaces, generated C++/Java type-support output, native `.so`/`.dll`/`.dylib`, and local environment files. Do not ignore handwritten `.idl`, `.mpc`, Docker, shell, or documentation sources.
- [x] **Step 3: Build the pinned application builder image.** Copy the proven Task 1 toolchain into `docker/opendds-builder/Dockerfile`; pin base image and OpenDDS source/archive version and expose the `DDS_ROOT`, `ACE_ROOT`, `TAO_ROOT`, `MPC_ROOT`, Java, and Maven environment needed by the probe. The image installs the OpenDDS runtime JARs into its local Maven repository for normal transitive dependencies.
- [x] **Step 4: Create empty module POMs.** Give each module `jar` packaging and stable coordinates under one project group/version. Add only the dependency edges specified in the interfaces; do not add web frameworks or runtime frameworks.
- [x] **Step 5: Verify reactor structure.** Run `mvn -version` and `mvn -q validate` inside the builder image. Expected: Maven runs on JDK 25 and all five modules are discovered without downloading or generating product code.
- [x] **Step 6: Check and commit the scaffold.** Run `git diff --check`, inspect module order and ignored generated outputs, and commit with `build: scaffold Java 25 Maven reactor`.

### Task 3: Generate and package OpenDDS types through the reactor

**Files:**
- Create: `dds-types/src/main/idl/Learning.idl`
- Create: `dds-types/src/main/mpc/Learning.mpc`
- Create: `dds-types/scripts/generate.sh`
- Modify: `dds-types/pom.xml`
- Create: `dds-types/src/test/java/io/github/tmejs/opendds/types/GeneratedTypeSupportTest.java`

**Interfaces:**
- IDL module `Learning` defines `PingRequest`, `PingReply`, and keyed `TelemetrySample`.
- `PingRequest` and `PingReply` contain `long long sequenceNumber` and `string payload`; `PingReply` also contains `long long responderSequence` so the test can verify correlation. `sequence` is reserved by IDL and cannot be used as a field name.
- `TelemetrySample` uses `string device_id` as its DDS key and contains `long long sequenceNumber`, `long long timestamp_epoch_millis`, `double temperature_c`, and `double humidity_percent`.
- `generate.sh` is idempotent, runs from the repository root, fails on a non-zero MPC/`make` result, and writes artifacts only below `dds-types/target/`.

The handwritten IDL shape is:

```idl
module Learning {
  @topic struct PingRequest {
    long long sequenceNumber;
    string payload;
  };
  @topic struct PingReply {
    long long sequenceNumber;
    string payload;
    long long responderSequence;
  };
  @topic struct TelemetrySample {
    @key string device_id;
    long long sequenceNumber;
    long long timestamp_epoch_millis;
    double temperature_c;
    double humidity_percent;
  };
};
```

- [x] **Step 1: Write the smallest generated-type test.** Add `GeneratedTypeSupportTest` that constructs a `Learning.PingRequest`, assigns sequence `1` and payload `"probe"`, and asserts both generated fields retain their values. Compile with generated classes on the Maven test classpath.
- [ ] The assertion body is:

```java
Learning.PingRequest request = new Learning.PingRequest();
request.sequenceNumber = 1L;
request.payload = "probe";
assertEquals(1L, request.sequenceNumber);
assertEquals("probe", request.payload);
```
- [x] **Step 2: Run the test before generation.** Run `mvn -pl dds-types -Dtest=GeneratedTypeSupportTest test` in the builder image. Expected: FAIL because generated type support is not yet available.
- [x] **Step 3: Define the IDL schema.** Add the three agreed types and annotate each topic type using the annotation syntax verified by the Task 1 example. Mark `TelemetrySample.device_id` as the key.
- [x] **Step 4: Add the MPC project and generator script.** Follow the successful probe's export macro, `TypeSupport_Files`, Java flags, and output paths. Source OpenDDS's `setenv.sh`, run `mwc.pl -type gnuace`, then `make`; copy generated Java classes/JAR and native type-support library into module `target/` outputs consumed by Maven.
- [x] **Step 5: Wire generation into Maven.** Bind `generate.sh` to `generate-sources`, add generated classes with a pinned build-helper plugin if the probe output is source, and package the native library under `target/classes/native/linux-x86_64/` or `target/classes/native/linux-aarch64/` selected from the builder architecture. Add OpenDDS runtime JARs to the compile/test classpath from the pinned builder installation using the paths verified in Task 1.
- [x] **Step 6: Run the generated-type test.** Run `mvn -pl dds-types -Dtest=GeneratedTypeSupportTest test`. Expected: PASS with the generated source compiled by Maven and no committed generated files. The test also loads the packaged native type-support library.
- [x] **Step 7: Check and commit the module.** Run `git diff --check`, verify a clean rebuild regenerates outputs, and commit with `build: generate OpenDDS Java type support`.

## Checkpoint 3: Implement and Measure Ping/Pong

### Task 4: Add deterministic round-trip statistics

**Files:**
- Create: `ping-requester/src/main/java/io/github/tmejs/opendds/ping/LatencyStatistics.java`
- Create: `ping-requester/src/test/java/io/github/tmejs/opendds/ping/LatencyStatisticsTest.java`
- Modify: `ping-requester/pom.xml`

**Interfaces:**
- `LatencyStatistics.record(long elapsedNanos)` accepts only positive elapsed values.
- `LatencyStatistics.summary()` returns `Summary(int samples, long p50Nanos, long p95Nanos, long p99Nanos)` and throws `IllegalStateException` when empty.
- Percentiles use nearest-rank: sort a copy and select index `ceil(p * n) - 1`.

- [x] **Step 1: Write tests for a single sample, unsorted samples, and nearest-rank boundaries.** For values `[40, 10, 30, 20]`, assert sample count 4 and p50/p95/p99 are 20/40/40. Assert one sample of 17 yields all percentiles equal to 17.
- [ ] The core JUnit assertion is:

```java
LatencyStatistics statistics = new LatencyStatistics();
for (long value : List.of(40L, 10L, 30L, 20L)) {
    statistics.record(value);
}
assertEquals(new LatencyStatistics.Summary(4, 20, 40, 40), statistics.summary());
```
- [x] **Step 2: Run the focused test and confirm failure.** Run `mvn -pl ping-requester -Dtest=LatencyStatisticsTest test`. Expected: compilation/test failure because the API does not exist.
- [x] **Step 3: Implement the immutable summary and percentile calculation.** Use `Math.ceil(percentile * size) - 1`, sort a defensive copy, and reject non-positive measurements in `record`.
- [ ] The percentile helper is:

```java
private static long percentile(List<Long> sorted, double percentile) {
    int index = (int) Math.ceil(percentile * sorted.size()) - 1;
    return sorted.get(Math.max(0, index));
}
```
- [x] **Step 4: Test empty and invalid input.** Assert `summary()` on no samples throws `IllegalStateException`; assert `record(0)` and `record(-1)` throw `IllegalArgumentException`.
- [x] **Step 5: Run the focused test and commit.** Run `mvn -pl ping-requester -Dtest=LatencyStatisticsTest test`, expected PASS; run `git diff --check`; commit `feat: calculate ping round-trip percentiles`.

### Task 5: Add the ping requester and responder

**Files:**
- Create: `ping-requester/src/main/java/io/github/tmejs/opendds/ping/PingRequester.java`
- Create: `ping-requester/src/main/java/io/github/tmejs/opendds/ping/PingReplyMatcher.java`
- Create: `ping-requester/src/test/java/io/github/tmejs/opendds/ping/PingReplyMatcherTest.java`
- Create: `ping-responder/src/main/java/io/github/tmejs/opendds/ping/PingResponder.java`
- Create: `dds-types/src/main/java/io/github/tmejs/opendds/types/NativeTypeSupport.java`
- Modify: `ping-requester/pom.xml`, `ping-responder/pom.xml`

**Interfaces:**
- `PingRequester.main(String[] args)` accepts `--domain`, `--count`, `--warmup-count`, `--payload-bytes`, and `--timeout-seconds`; invalid or missing values exit with a usage error.
- `PingResponder.main(String[] args)` accepts `--domain` and optional finite `--count` (zero means continue until shutdown), and responds to each request with the same sequence/payload and a matching responder sequence.
- The requester sends one outstanding request at a time. `PingReplyMatcher.matches(long expectedSequence, Learning.PingReply reply)` returns true only when the reply sequence matches; unrelated replies are ignored and logged.

- [x] **Step 1: Write tests for reply correlation.** Verify a reply with the expected sequence is accepted and one with a different sequence is ignored.
- [x] The test's essential assertions are:

```java
Learning.PingReply reply = new Learning.PingReply();
reply.sequenceNumber = 2L;
assertTrue(PingReplyMatcher.matches(2L, reply));
reply.sequenceNumber = 99L;
assertFalse(PingReplyMatcher.matches(2L, reply));
```
- [x] **Step 2: Run matcher test and confirm failure.** Run `mvn -pl ping-requester -Dtest=PingReplyMatcherTest test`. Expected: fail before the matcher exists.
- [x] **Step 3: Implement the reply matcher and timeout result.** Use a `BlockingQueue<Learning.PingReply>` to transfer listener callbacks to the requester thread; wait with the configured timeout and accept only the expected sequence. A timeout reports the expected sequence and elapsed wait.
- [x] **Step 4: Implement the responder using generated `PingRequest`/`PingReply` types.** Follow the verified OpenDDS Messenger lifecycle: create participant, register generated type support, create topic, create reader/writer, wait for data, reply, then delete contained entities and participant in `finally`.
- [x] **Step 5: Implement the requester and monotonic timing.** Record `System.nanoTime()` immediately before writing each request; compute elapsed time when its matching reply is received. Perform a configurable warm-up count, exclude warm-up samples from `LatencyStatistics`, enforce the overall reply timeout, then print count and p50/p95/p99 in both milliseconds and nanoseconds.
- [x] **Step 6: Test no-responder behavior.** Run requester with no responder and a two-second timeout. Expected: clear non-zero exit, no hang, and a report showing zero received replies. Run a requester/responder exchange with count 10; expected: ten matched replies and a summary.
- [x] **Step 7: Run module checks and commit.** Run `mvn -pl ping-requester,ping-responder -am verify` inside the builder. Expected: all focused tests pass and the sample exchange succeeds. Run `git diff --check`; commit `feat: add DDS ping requester and responder`.

## Checkpoint 4: Add Shared-Memory and Network Run Modes

### Task 6: Configure and smoke-test the two transports

**Files:**
- Create: `docker/compose.shared-memory.yml`
- Create: `docker/compose.network.yml`
- Create: `docker/config/shared-memory.ini`
- Create: `docker/config/network-rtps.ini`
- Create: `scripts/run-shared-memory.sh`
- Create: `scripts/run-network.sh`
- Create: `scripts/smoke-test.sh`

**Interfaces:**
- Shared-memory mode runs the ping requester/responder or telemetry device/monitor JVM processes inside one Linux container and selects the OpenDDS `shmem` data transport.
- Network mode runs those same roles as separate Compose services and selects RTPS over UDP with explicit reachable service addresses/ports; use static peers if multicast discovery fails in the tested Docker network.
- `run-shared-memory.sh` and `run-network.sh` accept `--scenario ping|telemetry`, print the selected configuration, and exit non-zero when Compose or an application process fails.

- [x] **Step 1: Write `smoke-test.sh` before the Compose files.** For ping, run `docker compose -f docker/compose.shared-memory.yml config` and `docker compose -f docker/compose.network.yml config`; for telemetry, assert the same files define `telemetry-lab`, `telemetry-device`, and `telemetry-monitor`. Run each scenario with 10 samples and a 30-second timeout; check expected counts and selected transport labels in both logs.
- [x] **Step 2: Run the smoke test before configurations exist.** Run `./scripts/smoke-test.sh`; expected: non-zero with a message naming the missing Compose configuration or service.
- [x] **Step 3: Add shared-memory configuration.** Use one service/container with both Java processes and a shared `shmem` instance. Keep discovery configuration explicit and separate from the data transport. Add a shell supervisor that traps INT/TERM, terminates the child JVM, waits for it, and returns the failing child's exit status.
- [x] **Step 4: Add network configuration.** Put two services on one named Docker bridge network; use RTPS discovery and RTPS/UDP data transport. Configure advertised addresses to resolve between service names. If multicast discovery fails in this network, add explicit static peers and document why.
- [x] **Step 5: Add run scripts.** For `--scenario ping`, `run-shared-memory.sh` uses `--exit-code-from ping-lab`, and `run-network.sh` uses `--exit-code-from ping-requester`. For `--scenario telemetry`, use `telemetry-lab` and `telemetry-monitor`. Each passes `--count` and `--timeout-seconds` through environment variables and preserves Compose's exit code.
- [x] **Step 6: Verify both paths and transport selection.** Run `./scripts/smoke-test.sh`. Expected: ping exchanges exactly 10 replies and telemetry receives 10 samples per mode; logs/configuration identify `shmem` in shared-memory mode and RTPS/UDP in network mode. Also run the network services with different domain IDs; expected: no match and requester timeout.
- [ ] **Step 7: Check and commit the transport setup.** Run `git diff --check`, inspect both Compose networks and process cleanup, then commit `feat: add OpenDDS shared-memory and network demos`.

## Checkpoint 5: Add Telemetry and Monitoring

### Task 7: Implement telemetry tracking and its tests

**Files:**
- Create: `telemetry-monitor/src/main/java/io/github/tmejs/opendds/telemetry/TelemetryTracker.java`
- Create: `telemetry-monitor/src/main/java/io/github/tmejs/opendds/telemetry/TelemetryView.java`
- Create: `telemetry-monitor/src/test/java/io/github/tmejs/opendds/telemetry/TelemetryTrackerTest.java`
- Modify: `telemetry-monitor/pom.xml`

**Interfaces:**
- `TelemetryTracker.accept(TelemetrySample sample, long receivedAtNanos)` stores the latest sample by device ID.
- `TelemetryTracker.view(String deviceId, long nowNanos, long staleAfterNanos)` returns `Optional<TelemetryView>`; the view contains `deviceId`, `sequence`, `temperatureC`, `humidityPercent`, `ageNanos`, `missingSamples`, and `stale`.
- For each accepted forward sequence jump, add `newSequence - previousSequence - 1` to `missingSamples`; ignore duplicate or older sequence values.
- Freshness is calculated from local monotonic receive time, not a remote device wall clock.

- [ ] **Step 1: Write tests for first sample, forward gap, duplicate, out-of-order sample, and stale age.** Use an injected `receivedAtNanos` and `nowNanos` so the tests need no sleeps.
- [ ] Assert the contract with this sequence:

```java
private static Learning.TelemetrySample sample(String id, long sequence) {
    Learning.TelemetrySample sample = new Learning.TelemetrySample();
    sample.device_id = id;
    sample.sequenceNumber = sequence;
    return sample;
}

@Test
void countsGapsAndIgnoresOldSamples() {
    TelemetryTracker tracker = new TelemetryTracker();
    tracker.accept(sample("sensor-a", 1), 1_000);
    tracker.accept(sample("sensor-a", 4), 2_000);
    assertEquals(2, tracker.view("sensor-a", 2_500, 5_000).orElseThrow().missingSamples());
    tracker.accept(sample("sensor-a", 4), 3_000);
    tracker.accept(sample("sensor-a", 3), 4_000);
    assertEquals(4, tracker.view("sensor-a", 4_000, 5_000).orElseThrow().sequence());
    assertTrue(tracker.view("sensor-a", 12_000, 5_000).orElseThrow().stale());
}
```
- [ ] **Step 2: Run the focused test and confirm failure.** Run `mvn -pl telemetry-monitor -Dtest=TelemetryTrackerTest test`. Expected: fail before the tracker API exists.
- [ ] **Step 3: Implement the view record and tracker.** Store per-device latest sample, last receive monotonic time, accumulated gap count; synchronize updates/reads because DDS listeners run on middleware threads.
- [ ] **Step 4: Test exact state behavior.** For sequence 1 then 4, assert two missing samples. For duplicate 4 and older 3, assert displayed sequence and gap count remain unchanged. For `nowNanos - receivedAtNanos >= staleAfterNanos`, assert `stale` is true.
- [ ] **Step 5: Run and commit.** Run `mvn -pl telemetry-monitor -Dtest=TelemetryTrackerTest test`; expected PASS. Run `git diff --check`; commit `feat: track telemetry freshness and sequence gaps`.

### Task 8: Add the telemetry publisher and monitor

**Files:**
- Create: `telemetry-device/src/main/java/io/github/tmejs/opendds/telemetry/TelemetryDevice.java`
- Create: `telemetry-device/src/main/java/io/github/tmejs/opendds/telemetry/TelemetrySchedule.java`
- Create: `telemetry-monitor/src/main/java/io/github/tmejs/opendds/telemetry/TelemetryMonitor.java`
- Create: `telemetry-device/src/test/java/io/github/tmejs/opendds/telemetry/TelemetryScheduleTest.java`
- Modify: `telemetry-device/pom.xml`, `telemetry-monitor/pom.xml`

**Interfaces:**
- `TelemetrySchedule(int count, long initialSequence)` exposes `OptionalLong nextSequence()` and returns exactly `count` successive sequence values before empty.
- Device accepts `--domain`, `--device-id`, `--interval-ms`, and `--count`; each sample increments a per-device sequence and uses generated `TelemetrySample` fields.
- Monitor accepts `--domain`, `--stale-after-ms`, and optional `--count`; it prints the latest values, age, accumulated sequence gaps, and stale status.

- [ ] **Step 1: Write schedule test.** Given `new TelemetrySchedule(3, 0)`, assert `nextSequence()` returns `OptionalLong.of(1)`, then 2, then 3, then empty; for count 0, assert the first result is empty.
- [ ] The test uses:

```java
TelemetrySchedule schedule = new TelemetrySchedule(3, 0);
assertEquals(OptionalLong.of(1), schedule.nextSequence());
assertEquals(OptionalLong.of(2), schedule.nextSequence());
assertEquals(OptionalLong.of(3), schedule.nextSequence());
assertEquals(OptionalLong.empty(), schedule.nextSequence());
```
- [ ] **Step 2: Run the focused test and confirm failure.** Run `mvn -pl telemetry-device -Dtest=TelemetryScheduleTest test`. Expected: fail before the schedule model exists.
- [ ] **Step 3: Implement the deterministic sample schedule.** Keep interval waiting and wall-clock timestamp creation outside `TelemetrySchedule`; the schedule stores only the remaining count and last sequence.
- [ ] **Step 4: Implement DDS writer and reader lifecycles.** Device publishes keyed `TelemetrySample` instances using a reliable baseline. Monitor reads samples, passes each sample with `System.nanoTime()` to `TelemetryTracker`, and periodically prints the resulting view. Both applications validate arguments and clean up DDS entities on normal shutdown.
- [ ] **Step 5: Add the first QoS comparison.** Expose a named reliability option (`reliable` or `best-effort`) and history depth through explicit CLI/config values. Keep reliable as default; print the effective QoS settings at startup.
- [ ] **Step 6: Run both telemetry modes.** Launch device and monitor under each Compose configuration; expected: at least 10 samples per device are observed, sequence gaps remain zero in the reliable baseline, and the monitor becomes stale after the device stops.
- [ ] **Step 7: Run checks and commit.** Run `mvn -pl telemetry-device,telemetry-monitor -am verify`, then both telemetry smoke runs and `git diff --check`; commit `feat: add DDS telemetry publisher and monitor`.

## Checkpoint 6: Finish the Learning Documentation

### Task 9: Write the guided tutorial and operational docs

**Files:**
- Create: `docs/learning-guide.md`
- Create: `docs/diagrams/runtime-flow.mmd`
- Create: `docs/diagrams/build-and-jni.mmd`
- Create: `README.md`
- Create: `docs/troubleshooting.md`

- [ ] **Step 1: Write the quick start from verified commands.** README prerequisites name Java 25, Docker, and the tested Maven/OpenDDS builder. Include commands for the binding probe, Maven verification, shared-memory ping, network ping, and telemetry; do not claim results before running each command.
- [ ] **Step 2: Add the runtime-flow diagram.** Show request writer → ping topic → responder reader → reply writer → requester reader, and telemetry device writer → telemetry topic → monitor reader. Label participants, domain, and topic boundaries.
- [ ] **Step 3: Add the build/JNI diagram.** Show IDL → `tao_idl`/`opendds_idl`/`idl2jni` → generated Java/C++ type support → JAR + `.so` → Maven applications → JVM loading the native library.
- [ ] **Step 4: Explain DDS concepts before commands.** In `docs/learning-guide.md`, define domain, participant, topic/type, publisher/writer, subscriber/reader, discovery, transport, and QoS in the order a reader encounters them. Explain that the monitor is a subscriber and discovery is not application-data brokering.
- [ ] **Step 5: Explain the measurement and telemetry exercises.** Document warm-up exclusion, monotonic requester-side timing, percentile method, known measurement limits, keyed telemetry, sequence gaps, stale age, and the one-policy-at-a-time QoS exercise.
- [ ] **Step 6: Add exact runtime/config troubleshooting.** `docs/troubleshooting.md` has symptom → check → correction entries for no matching participants, wrong domain/topic/type, unavailable UDP address/port, wrong selected transport, missing OpenDDS Java JAR, and missing JNI `.so`/`LD_LIBRARY_PATH`.
- [ ] **Step 7: Verify documentation instructions.** Run each documented command from a clean checkout/build state and confirm expected output. Remove or label any command that cannot be verified; record environment limits and cross-host networking as outside the first version.
- [ ] **Step 8: Review and commit docs.** Run `git diff --check`; read the guide as a mid-level Java developer new to DDS; commit `docs: explain OpenDDS Java integration and transport labs`.

### Task 10: Run the complete local gate and record evidence

**Files:**
- Create: `codex/reviews/checkpoint-6-learning-docs.md`
- Modify: `README.md` only to correct verified command or behavior claims.

- [ ] **Step 1: Build all modules from clean outputs.** Run `mvn clean verify` inside the pinned builder. Expected: all unit tests pass and all modules compile with release 25.
- [ ] **Step 2: Run the Java/OpenDDS compatibility probe.** Run `./compatibility/java25-opendds-3.34/run.sh`. Expected: generated Java/native support loads and the probe exchange succeeds.
- [ ] **Step 3: Run all four local application scenarios.** Run ping and telemetry through both Compose files. Expected: correct participant matching, selected transport, correlated replies, observable telemetry, graceful shutdown, and clear no-responder/domain-mismatch failures.
- [ ] **Step 4: Inspect scope and repository state.** Run `git diff --check`, verify generated outputs are ignored, inspect README claims against run logs, and confirm no GitHub Actions were added.
- [ ] **Step 5: Record results.** Add the exact commands, versions, pass/fail outcomes, machine-dependent latency sample, and known limitations to the local review record. Do not state cross-host or portable latency guarantees.
- [ ] **Step 6: Request independent whole-branch review.** Fix all Critical or Important findings and repeat affected tests plus the review before preparing a push.
