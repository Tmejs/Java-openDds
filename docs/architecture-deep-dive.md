# Architecture and integration deep dive

This is a code-reading guide for a Java developer who wants to build a similar
OpenDDS application from scratch. Start with the [quick start](../README.md),
then the [learning guide](learning-guide.md) for DDS vocabulary. This document
explains why the integration is assembled this way, which pieces are generated,
and the failure modes that were easy to miss during implementation. It describes
the pinned OpenDDS 3.34.0 and Java 25 build verified on Linux ARM64 containers.

## The system in one view

The repository is **one Maven reactor**, with a shared `dds-types` module and
four executable roles. It has **two deployment topologies**, rather than two
independent Java implementations. That matters: switching modes changes the
discovery and application-data transport configuration while leaving the IDL,
Java role behavior, and QoS code the same.

| Layer | Repository entry point | What it owns |
| --- | --- | --- |
| Data contract | [`Learning.idl`](../dds-types/src/main/idl/Learning.idl) | Request, reply, and keyed telemetry types |
| Type generation | [`Learning.mpc`](../dds-types/src/main/mpc/Learning.mpc), [`generate.sh`](../dds-types/scripts/generate.sh) | Generated Java types, C++ type support, JNI library |
| Build | [root POM](../pom.xml), [`dds-types` POM](../dds-types/pom.xml) | Maven module order and generated source inclusion |
| Native environment | [builder Dockerfile](../docker/opendds-builder/Dockerfile), [`run-dds-java.sh`](../scripts/run-dds-java.sh) | OpenDDS Java JARs, native libraries, JVM class and library paths |
| DDS roles | [`PingRequester`](../ping-requester/src/main/java/io/github/tmejs/opendds/ping/PingRequester.java), [`PingResponder`](../ping-responder/src/main/java/io/github/tmejs/opendds/ping/PingResponder.java), [`TelemetryDevice`](../telemetry-device/src/main/java/io/github/tmejs/opendds/telemetry/TelemetryDevice.java), [`TelemetryMonitor`](../telemetry-monitor/src/main/java/io/github/tmejs/opendds/telemetry/TelemetryMonitor.java) | Participants, topics, endpoints, sample handling, cleanup |
| Topology | [shared Compose](../docker/compose.shared-memory.yml), [network Compose](../docker/compose.network.yml), [INI files](../docker/config/README.md) | Process placement, discovery, and data transport |

Follow the [build/JNI diagram](diagrams/build-and-jni.mmd) from IDL to the JVM,
then the [runtime diagram](diagrams/runtime-flow.mmd) from writer to reader.

## 1. Build the contract before the application

`Learning.idl` declares three `@topic` structs inside the `Learning` module.
The annotation tells OpenDDS to generate type support for each struct. The
module becomes the `Learning` Java package. `PingRequest` and `PingReply` use
`sequenceNumber` for correlation. `TelemetrySample.device_id` has `@key`, so
samples with the same ID belong to one DDS instance. A Java class with the same
fields is not a substitute for this IDL contract: OpenDDS needs generated
serialization, type registration, and typed reader/writer bindings on both
sides of a topic.

`Learning.mpc` selects OpenDDS's Java type-support build and names the native
library `learning_types`. [`generate.sh`](../dds-types/scripts/generate.sh)
sources OpenDDS's environment, runs MPC (`mwc.pl`) and Make, then copies Java
sources into `target/generated-sources/opendds` and the `.so` into
`target/classes/native/linux-aarch64` or `linux-x86_64`. The `dds-types` POM
binds this script to Maven `generate-sources` and adds the generated directory
as a compiler source root. Other modules depend on `dds-types`, so Maven builds
the contract before the roles.

OpenDDS's `tao_idl`, `opendds_idl`, and `idl2jni` participate in this generation.
Some classes are generated Java, while native C++/JNI code is compiled into
`liblearning_types.so`. Do not edit either output: change the IDL or MPC input
and regenerate. The generator fingerprints those inputs, its own script,
`DDS_ROOT`, and architecture. When one changes, it removes old generated
sources, classes, and native output before rebuilding. This prevents an old
`.class` from making a removed IDL type appear to exist. The dedicated
[`test-idl-regeneration.sh`](../dds-types/scripts/test-idl-regeneration.sh)
tests an incremental type rename for precisely that failure mode.

The [builder image](../docker/opendds-builder/Dockerfile) compiles OpenDDS with
`./configure --java`, then installs four image-local Java JARs into Maven:
`OpenDDS_DCPS.jar`, `tao_java.jar`, `i2jrt.jar`, and `i2jrt_corba.jar`. The
project POM resolves those artifacts as `org.opendds` dependencies. These
coordinates are installed by **this image**; a host Maven installation does
not automatically have them. The image also provides the ACE, TAO, OpenDDS,
IDL, C++, and JNI toolchain used to build the same-version native library.

At runtime [`NativeTypeSupport.load()`](../dds-types/src/main/java/io/github/tmejs/opendds/types/NativeTypeSupport.java)
calls `System.loadLibrary("learning_types")` before creating generated type
support. [`run-dds-java.sh`](../scripts/run-dds-java.sh) puts the role and
`dds-types` classes plus OpenDDS JARs on the classpath, includes the generated
library and OpenDDS/ACE libraries in `java.library.path`, and passes
`--enable-native-access=ALL-UNNAMED` for Java 25. The builder sets
`LD_LIBRARY_PATH` for native dependencies of the loaded `.so`. A successful
Java compile therefore proves only one layer; runtime also needs compatible
Java classes, native code, native dependencies, architecture, and JVM version.

**Rebuild exercise:** change an IDL field name and run the Docker Maven command
in the [README](../README.md#2-build-and-verify-every-maven-module). The
generated type will change, and the full reactor is expected to fail until you
update every Java role that accesses the old field. Inspect
`dds-types/target/generated-sources/opendds/Learning/` and the native directory,
make the corresponding Java edits, then repeat the full build. Revert all
exercise changes before running the documented baseline scenarios. For a type
removal or rename, run the regeneration regression instead of trusting an
incremental compile.

## 2. Create DDS entities in dependency order

All four roles follow the same lifecycle, visible most compactly in
[`TelemetryDevice`](../telemetry-device/src/main/java/io/github/tmejs/opendds/telemetry/TelemetryDevice.java):

1. Load application JNI support and call `TheParticipantFactory.WithArgs`.
   The argument holder receives OpenDDS options such as `-DCPSConfigFile`.
2. Create a participant in the requested numeric domain.
3. Instantiate generated `TypeSupportImpl`, call `register_type`, and create a
   topic using `get_type_name()` and an agreed topic name.
4. Create a publisher or subscriber, then a writer or reader with the selected
   endpoint QoS. Narrow the generic endpoint with its generated `...Helper`.
5. Wait for the expected association where the role needs one, then write or
   take typed samples.
6. Delete contained entities, delete the participant, and call
   `TheServiceParticipant.shutdown()` in `finally`.

Domain, topic name, type name, and compatible QoS all affect whether endpoints
match. A participant can discover another participant without matching a
particular writer and reader. Registering the type does not publish a sample;
creating a topic does not create an endpoint. OpenDDS option parsing occurs
when the factory is initialized, so the CLI parser preserves `-DCPS...` options
and the launch wrapper appends the chosen `-DCPSConfigFile` before startup.
The roles supply `DEFAULT_RTPS` only when no explicit discovery/configuration
option was passed; the lab launchers always pass an INI file.

The code checks null entity returns and DDS return codes. It uses generated
helpers to narrow endpoints because the generic DDS APIs do not expose typed
`write` or `take_next_sample`. Native resources and threads survive ordinary
Java object reachability; the explicit cleanup order is part of correctness.
The long-running responder and monitor also install shutdown hooks so a
Compose termination signal can release their main wait and reach `finally`.

## 3. Understand the two transport arrangements

**Shared memory:** [shared Compose](../docker/compose.shared-memory.yml) runs
an InfoRepo process and both Java roles inside **one container**. The
[`shared-memory.ini`](../docker/config/shared-memory.ini) points discovery to
the InfoRepo IOR file and selects a `shmem` data transport. All processes
therefore share an IPC namespace. The InfoRepo helps them discover endpoints;
it does not carry application samples. `DCPSBit=0` agrees with the repository's
`-NOBITS` option. The container script waits for a nonempty IOR file before
starting Java and terminates sibling processes after the finite role finishes.

**Network:** [network Compose](../docker/compose.network.yml) runs each role in
its own container on one Docker bridge. Each role gets a dedicated
[`network-rtps-*.ini`](../docker/config/README.md) file. SPDP discovers
participants at explicit unicast `SpdpSendAddrs`; SEDP discovers endpoints with
`SedpMulticast=0`. `SpdpLocalAddress` binds the role's service name on UDP port
17910. The launcher also passes that service name with `-DCPSDefaultAddress`.
The global transport config separately selects `rtps_udp_data`, with
`transport_type=rtps_udp`, `use_multicast=0`, and `PortMode=probe` for
application samples. Thus discovery traffic and data traffic both use RTPS/UDP
in this mode, but they have different configuration responsibilities and ports.

The explicit peer list avoids relying on Docker bridge multicast. Service
names are resolvable inside this one Compose network; they are not advertised
addresses for a second host. The fixed discovery port is per container address,
not a host-published port. A domain change still uses the same explicit peer
configuration, and different domain IDs remain isolated. The experiment in
[`smoke-test.sh`](../scripts/smoke-test.sh) verifies a domain mismatch times
out even though both services remain network reachable.

The project first tried RTPS discovery with `shmem` data. On the pinned Java
runtime, a bidirectional ping matched in both directions but its first request
was not dispatched until participant shutdown. A one-way `shmem` exchange and
the maintained Java Messenger example did work. The working lab uses InfoRepo
discovery with `shmem` data; see the recorded observation in
[transport configuration notes](../docker/config/README.md#shared-memory-mode).
That is an observed compatibility choice for this version, not a general DDS
restriction. It illustrates why a match event alone cannot certify the
application-data path.

## 4. Follow one ping through the code

The [`PingRequester`](../ping-requester/src/main/java/io/github/tmejs/opendds/ping/PingRequester.java)
has a `PingRequest` writer and `PingReply` reader. The
[`PingResponder`](../ping-responder/src/main/java/io/github/tmejs/opendds/ping/PingResponder.java)
has the inverse pair. Both topics use reliable endpoint QoS. The requester
waits for its writer's publication match and its reader's subscription match
using status conditions and a `WaitSet`. Waiting on endpoint status is stronger
than sleeping for a guessed startup period, though it still does not guarantee
that a sample can cross the selected transport.

The requester registers an instance, writes one sequence, and waits for the
same sequence in the reply. The responder's reader listener takes valid
samples and writes a reply with the request sequence. The requester's listener
hands replies to a bounded queue; [`PingReplyWaiter`](../ping-requester/src/main/java/io/github/tmejs/opendds/ping/PingReplyWaiter.java)
ignores unrelated sequences without resetting the original deadline. Listener
callbacks run on middleware-managed threads, so errors are stored and surfaced
to the application thread. The responder uses `--count 0` in the labs to keep
responding until it receives a termination signal.

The timer starts just before `write` and stops after the correlated reply is
taken from the queue. Warmup replies are excluded from statistics. The
[`LatencyStatistics`](../ping-requester/src/main/java/io/github/tmejs/opendds/ping/LatencyStatistics.java)
class uses nearest-rank p50/p95/p99. A ten-sample p99 is just the maximum.
These timings include the requester Java loop, JNI, serialization, OpenDDS,
responder callback, return path, and scheduling. They are not raw network
latency or a promise that shared memory will always be faster.

## 5. Follow one telemetry sample

[`TelemetryDevice`](../telemetry-device/src/main/java/io/github/tmejs/opendds/telemetry/TelemetryDevice.java)
sets `device_id` before `register_instance`, waits for a reader match, then
publishes increasing sequences. `timestamp_epoch_millis` uses the device wall
clock as domain data. For a finite reliable run it waits for writer
acknowledgments before closing. That wait confirms reliable DDS acknowledgment,
not that the monitor rendered the sample in its own output.

[`TelemetryMonitor`](../telemetry-monitor/src/main/java/io/github/tmejs/opendds/telemetry/TelemetryMonitor.java)
reads on a DDS listener thread. It checks `SampleInfo.valid_data` before using
a sample: DDS can report lifecycle changes that are not a new value. The
listener updates synchronized [`TelemetryTracker`](../telemetry-monitor/src/main/java/io/github/tmejs/opendds/telemetry/TelemetryTracker.java)
state, while the main thread reads immutable `TelemetryView` snapshots. Each
device key has its own latest sequence and receive time. A jump from 1 to 4
adds two missing samples; duplicate or older sequences do not replace the
latest value. The printed received count is the number of valid samples taken,
which can differ from the latest sequence and gap total.

Freshness is computed from the monitor's `System.nanoTime()` at reception, so
device/monitor clock skew cannot falsely create transport latency. Once a
finite target is received, the monitor waits for all observed device keys to
become stale, prints the summary, and exits. `stale=true` means the monitor has not
received a newer value within its threshold; this example does not configure
DDS deadline or liveliness QoS.

[`EndpointQos`](../dds-types/src/main/java/io/github/tmejs/opendds/types/EndpointQos.java)
constructs every nested Java QoS policy object before asking OpenDDS to fill
the default writer/reader QoS holder. The JNI binding expects that mutable
object graph to exist. Only then does the code set reliability and
`KEEP_LAST(N)`. Ping fixes reliability to `RELIABLE` and depth 1 via
[`ReliableEndpointQos`](../dds-types/src/main/java/io/github/tmejs/opendds/types/ReliableEndpointQos.java);
telemetry exposes both controls on its CLI. Reliability is a requested/offered
compatibility policy. `KEEP_LAST` limits cached samples per keyed instance; it
does not persist history after a writer disappears. A finite best-effort lab
uses the publisher count as the monitor's receive target, so one lost datagram
can leave the monitor waiting until the outer timeout. That is a visible
experiment failure, not a proof that best effort is broken.

## 6. Reproduce the launch and failure sequence

The host launcher first calls [`prepare-runtime.sh`](../scripts/prepare-runtime.sh),
which builds the image if needed and runs `mvn -DskipTests package` in that
image. It then starts the selected Compose services. `--pull never` prevents a
similarly named registry image from silently replacing the local pinned build.
The source tree is mounted read-only into runtime containers; generated
`target/` outputs were created in the build step. The outer `timeout` is a
scenario deadline. Java association/reply waits use a shorter application
timeout, but several sequential waits can still consume the outer deadline.

Network telemetry has a lifecycle detail: Compose uses
`--abort-on-container-exit` to end a finite scenario. The publisher completes
before the monitor can observe staleness. Its service therefore starts through
[`run-dds-java-and-hold.sh`](../scripts/run-dds-java-and-hold.sh), which keeps
the publisher container alive after a successful Java exit until the monitor
finishes and Compose stops both services. Without that hold, Compose would
stop the monitor as soon as the publisher container exited.

For a fresh checkout, follow this fault-isolation order:

1. Run the [Java/OpenDDS compatibility probe](../compatibility/java25-opendds-3.34/README.md).
   If it fails, debug the pinned toolchain before application code.
2. Run the [Maven build](../README.md#2-build-and-verify-every-maven-module).
   If generation fails, inspect the first MPC/IDL/C++ error; if Java compile
   fails, inspect generated sources and dependency versions.
3. Start shared ping, then network ping. If `System.loadLibrary` fails, inspect
   the generated `.so`, `java.library.path`, `LD_LIBRARY_PATH`, and container
   architecture before DDS discovery settings.
4. If there is no `PING_READY`, compare domain, topic/type, offered/requested
   QoS, INI selection, and discovery peers. If it becomes ready but times out,
   inspect the responder and application transport settings.
5. Run telemetry in each mode. Compare `TELEMETRY_PUBLISHED` with
   `TELEMETRY_SAMPLE`, then check sequence gaps and staleness separately.
6. Run [`smoke-test.sh`](../scripts/smoke-test.sh). It checks both applications
   in both modes, non-default domain 7, and expected domain isolation.

The [troubleshooting guide](troubleshooting.md) maps common errors to exact
checks and commands. Keep the `RUN_MODE`, `DATA_TRANSPORT`, readiness, and
summary lines when comparing runs; they reveal which layer produced a result.

## 7. What this example has not established

The verified network path is between containers on **one Docker host**.
Cross-host operation requires explicit peer-reachable addresses, UDP port and
firewall policy, and its own verification. Linux x86-64 native packaging exists
but has not been executed in this repository. The example also does not cover
DDS Security, schema evolution between versions, durable storage, failover, or
production monitoring. Treat those as separate design and test tasks when
building your own system.
