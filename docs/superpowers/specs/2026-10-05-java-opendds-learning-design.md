# Java 25 and OpenDDS Learning Project Design

## Purpose

Build a small, runnable, multimodule Java application that teaches a mid-level Java developer how Java applications use OpenDDS correctly. The project should make the DDS concepts, generated code, native JNI boundary, discovery, transports, and QoS understandable through working examples and clear documentation.

The first learning goal is to compare OpenDDS shared-memory and network transports using the same application behavior. The second is to extend a focused ping/pong example with a more realistic telemetry publisher and monitor.

## Agreed constraints

- Use Java 25 and Maven for the Java application modules.
- Run builds and examples in a Linux Docker environment.
- Pin the OpenDDS release and container/build environment for reproducibility. OpenDDS 3.34.0 is the proposed baseline; verify Java 25 compatibility before relying on it.
- Keep OpenDDS IDL/JNI generation and its native type-support build on the documented OpenDDS MPC and `make` toolchain. Maven can invoke that build and manage the Java application modules.
- Provide two run configurations over the same Java application modules: shared memory and network communication.
- Documentation is a first-class deliverable for a mid-level developer new to DDS/OpenDDS.

## Architecture

Use a single Maven reactor rather than duplicating application code between transport demonstrations.

- `dds-types`: developer-owned IDL definitions plus generated Java type-support classes and their native library. Generated output is build output, not hand-edited source.
- `ping-requester`: publishes sequence-numbered requests, receives matching replies, and reports round-trip statistics.
- `ping-responder`: receives requests and publishes matching replies.
- `telemetry-device`: simulates a device publishing keyed, timestamped measurements at a configurable interval.
- `telemetry-monitor`: subscribes to telemetry and reports current values, data age, and sequence gaps.

DDS applications communicate through typed topics. The telemetry monitor is an ordinary subscriber that collects samples; it is not a message broker. Discovery and data transport are separate concepts and must be configured and explained separately. A discovery service can be a later lesson, not a prerequisite for the first examples.

## Data flow and measurement

The ping requester writes a `PingRequest` containing a sequence number and optional payload. The responder reads it and writes a `PingReply` with the same correlation value. The requester measures elapsed time with its own monotonic clock from the request write to the matching reply read. No synchronized wall clocks are needed. The report excludes startup/discovery and warm-up samples, and includes sample count plus p50, p95, and p99 round-trip latency. The documentation must explain that results depend on the host, container, payload, QoS, and run conditions; timings are observations, not portable performance guarantees.

The telemetry IDL defines a keyed sample with device ID, sequence number, timestamp, and a small set of measurements. The monitor shows the latest value per device, age of the last sample, and gaps in observed sequence numbers. Exercises change one QoS policy at a time, beginning with a reliable baseline and then comparing policies such as reliability and history depth.

## Runtime configurations

- **Shared memory:** launch the communicating Java processes inside one Linux container so OpenDDS shared memory is available to both. Configure `shmem` for application data. Document the discovery mechanism independently and verify that logs/configuration show the intended data transport.
- **Network:** launch the same application processes as separate services on a Docker network. Use RTPS over UDP for DDS discovery/data communication, with explicit ports and reachable addresses as needed. Explain Docker network behavior and distinguish local container-network success from communication across physical hosts.

Both modes use the same IDL, Java application code, topics, and workload. Only deployment and transport configuration should vary. The run scripts should make the selected mode visible and provide clear startup, shutdown, and log instructions.

## Build and Java/native integration

The root Maven project manages the Java modules with Java 25 compilation. The `dds-types` build invokes a checked-in script in the pinned Linux build environment. That script uses OpenDDS's supported MPC/`make` workflow to generate Java and C++ type support, compile the native library, and make the resulting JAR and `.so` available to the Maven reactor. Application launch commands must set the class path and native-library path explicitly.

Before expanding the project, perform a minimal compatibility probe: generate one topic type, compile it with the selected OpenDDS release and JDK 25, load the native library in a Java process, and exchange one sample. If this combination is incompatible, record the concrete failure and choose a supported version combination before implementing the examples.

## Documentation and learning sequence

The root README gives verified prerequisites and a short quick start. A learning guide then proceeds through:

1. DDS vocabulary and the participant/domain/topic/publisher/writer/subscriber/reader model.
2. OpenDDS's Java/JNI architecture, IDL, generated source, native library, and build flow.
3. A first ping/pong exchange and how to diagnose discovery and matching.
4. Controlled round-trip measurement and interpretation of results.
5. Telemetry publishing/monitoring, keyed instances, sequence tracking, and QoS experiments.
6. Shared-memory versus RTPS/UDP configuration and Docker networking.
7. Shutdown, common failures, troubleshooting, and links to the relevant OpenDDS documentation.

Use diagrams for process/data flow and for build-time versus runtime components. Explain why each configuration exists, identify generated versus handwritten files, and give copyable commands with expected observations. Mark compatibility and runtime behavior as verified only after local evidence exists. Do not present a DDS discovery service as a data broker.

## Failure behavior and observability

Applications should report participant/topic/writer/reader setup failures with the relevant domain and configuration context. The requester should time out clearly when no matching responder is present or a reply is missing, and must correlate replies to requests. The monitor should expose stale telemetry and sequence gaps. All processes should clean up DDS entities on normal shutdown. Logs should make participant discovery and selected transport inspectable so the exercises can prove which setup ran.

## Verification

- Unit tests cover latency-statistics calculations and telemetry sequence/age tracking without DDS.
- The compatibility probe verifies generation, compilation, JNI library loading, and a minimal sample exchange with Java 25.
- Local Docker integration runs cover ping/pong and telemetry for both transport configurations, including participant matching and message delivery.
- Latency measurements report results without fixed machine-dependent pass thresholds.
- Documentation commands and expected output are reviewed against the actual local runs; `git diff --check` is required.

Do not add GitHub Actions unless explicitly requested. Keep verification local and record unavailable or inapplicable checks plainly.

## Out of scope for the first version

- A real hardware device integration; telemetry begins with a simulator and can later gain a device adapter.
- A central application message broker, database, web UI, or production deployment.
- Cross-host performance claims, security, persistence, failover, and extensive benchmarking.
- A broad QoS catalog; introduce only the policies needed for the first experiments.
