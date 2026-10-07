# Java OpenDDS learning labs

This repository is a Java 25 and Maven learning project for integrating
[OpenDDS](https://opendds.org/) through its JNI bindings. It contains one
multi-module application with two repeatable Docker topologies:

- **Shared memory:** central InfoRepo discovery with OpenDDS shared-memory
  transport for application samples.
- **Network:** peer-to-peer RTPS discovery with unicast RTPS/UDP transport for
  application samples.

Both topologies run the same IDL-generated types and Java applications. That
keeps the comparison focused on discovery and transport behavior instead of
duplicating application code.

The labs provide:

- a correlated ping/pong tool with warmup and round-trip p50, p95, and p99;
- a keyed telemetry device and monitor with sequence-gap and staleness reporting;
- deterministic same-host Docker Compose launchers;
- unit, integration, compatibility, and end-to-end smoke checks;
- a guided explanation of the Java, JNI, IDL, DDS, and transport layers.

## Verified baseline

The project pins the following build environment in
[docker/opendds-builder/Dockerfile](docker/opendds-builder/Dockerfile):

| Component | Version |
| --- | --- |
| Java | Eclipse Temurin 25.0.4+7 |
| Maven | 3.9.16 |
| OpenDDS | 3.34.0 |
| ACE/TAO | 6.5.24 |
| Container OS | Ubuntu Noble |
| Verified container architecture | Linux ARM64 |

Linux x86-64 native-library packaging is implemented, but this repository has
only been executed and verified on Linux ARM64 containers so far.

## Prerequisites

Install Git and Docker Engine or Docker Desktop with Docker Compose v2. Allow
enough disk space and time for the first OpenDDS builder image build.

Java, Maven, OpenDDS, a C++ compiler, and the IDL tools run inside the pinned
builder image. A host JDK or host Maven installation is not required.

```bash
git clone https://github.com/Tmejs/Java-openDds.git
cd Java-openDds
```

## Quick start

### 1. Prove the Java/OpenDDS binding

The compatibility probe builds OpenDDS 3.34.0 with Java support, runs its
maintained Java Messenger test, and checks a real publisher/subscriber exchange:

```bash
./compatibility/java25-opendds-3.34/run.sh
```

A successful run ends with:

```text
PASS: Java 25 with OpenDDS 3.34.0 generated, loaded, and exchanged samples through the maintained Java Messenger test.
```

Use `./compatibility/java25-opendds-3.34/run.sh --clean` only when you
intentionally want a no-cache probe.

### 2. Build and verify every Maven module

```bash
docker build \
  --file docker/opendds-builder/Dockerfile \
  --tag java-opendds-builder:25-3.34.0 \
  .

docker run --rm \
  --volume "$PWD:/workspace" \
  --workdir /workspace \
  java-opendds-builder:25-3.34.0 \
  mvn --batch-mode --no-transfer-progress clean verify
```

This generates Java and native type support under `dds-types/target/`, builds
all five modules, and runs the unit and integration tests. Generated files stay
out of Git.

### 3. Run ping/pong over shared memory

```bash
./scripts/run-shared-memory.sh --scenario ping --count 10
```

Look for:

```text
RUN_MODE=shared-memory DATA_TRANSPORT=shmem
PING_SUMMARY status=OK received=10 expected=10
PING_LATENCY samples=10 p50_ns=... p95_ns=... p99_ns=...
```

### 4. Run ping/pong over RTPS/UDP

```bash
./scripts/run-network.sh --scenario ping --count 10
```

The same Java behavior now reports:

```text
RUN_MODE=network DATA_TRANSPORT=RTPS/UDP
PING_SUMMARY status=OK received=10 expected=10
PING_LATENCY samples=10 p50_ns=... p95_ns=... p99_ns=...
```

The first exchange can include association setup. Warmup is explicit and defaults
to zero. Set it for either launcher through the environment; warmup replies are
excluded from the reported statistics:

```bash
DDS_WARMUP_COUNT=5 ./scripts/run-network.sh --scenario ping --count 100
```

### 5. Run keyed telemetry

```bash
./scripts/run-shared-memory.sh --scenario telemetry --count 10 \
  --reliability reliable --history-depth 10 \
  --interval-ms 25 --stale-after-ms 100

./scripts/run-network.sh --scenario telemetry --count 10 \
  --reliability reliable --history-depth 10 \
  --interval-ms 25 --stale-after-ms 100
```

A successful run reports ten samples for one device, no sequence gaps, and a
stale state after the device stops:

```text
TELEMETRY_DEVICE_SUMMARY status=OK sent=10 expected=10
TELEMETRY_MONITOR_SUMMARY status=OK received=10 expected=10 devices=1 missing=0 stale=true
```

### 6. Run the complete local smoke gate

```bash
./scripts/smoke-test.sh
```

This checks both applications in both transports, a non-default domain, and an
intentional domain mismatch. It ends with:

```text
SMOKE_TEST status=OK scenarios=ping,telemetry transports=shmem,rtps_udp domains=42,7,mismatch
```

## Project map

| Path | Purpose |
| --- | --- |
| `dds-types/` | IDL, generated Java/JNI support, native library packaging, and QoS helpers |
| `ping-requester/` | Correlated request/reply loop and nearest-rank statistics |
| `ping-responder/` | Ping request reader and reply writer |
| `telemetry-device/` | Keyed telemetry publisher |
| `telemetry-monitor/` | Per-device values, gaps, and staleness |
| `docker/config/` | OpenDDS discovery and transport configuration |
| `docker/compose.*.yml` | Shared-memory and network topologies |
| `scripts/` | Build, launch, Java/JNI bootstrap, and smoke-test entry points |
| `compatibility/` | Independent Java 25/OpenDDS 3.34 binding probe |
| `docs/` | Learning guide, diagrams, design records, and troubleshooting |

## Learn how it works

Start with the [learning guide](docs/learning-guide.md). The diagram sources are:

- [runtime data flow](docs/diagrams/runtime-flow.mmd)
- [IDL, build, and JNI flow](docs/diagrams/build-and-jni.mmd)

Use [troubleshooting](docs/troubleshooting.md) when a build, match, transport,
or JNI load fails. Transport configuration details are in
[docker/config/README.md](docker/config/README.md).

Official references used by this project:

- [OpenDDS 3.34.0 Developer's Guide](https://opendds.readthedocs.io/en/latest-release/devguide/index.html)
- [OpenDDS Java Bindings](https://opendds.readthedocs.io/en/latest-release/devguide/java_bindings.html)
- [OpenDDS 3.34.0 Linux/macOS quick start](https://opendds.readthedocs.io/en/v3.34.0/devguide/quickstart/linux.html)
- [OpenDDS run-time configuration](https://opendds.readthedocs.io/en/latest-release/devguide/run_time_configuration.html)

## Measurement and scope limits

Latency is an application-level round trip measured with `System.nanoTime()`.
It includes Java, JNI, OpenDDS, serialization, container scheduling, and
transport work. Use it for controlled comparisons on the same machine, not as a
portable hardware benchmark.

The network topology uses two containers on one Docker host. It proves network
protocol behavior across a Docker bridge, but it does not prove cross-host
deployment. Cross-host addressing, firewall rules, multicast policy, DDS
Security, persistence, failover, and production observability remain outside
the current learning scope.
