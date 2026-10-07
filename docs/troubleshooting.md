# Troubleshooting the Java OpenDDS labs

Start from the first symptom that matches. Preserve the launcher header and
application summary when asking for help; they identify the selected topology,
domain, QoS, and failure stage.

## Establish a known baseline

From the repository root, confirm Docker and Compose are available:

```bash
docker version
docker compose version
```

Confirm the builder image exists:

```bash
docker image inspect java-opendds-builder:25-3.34.0
```

If it does not, build it:

```bash
docker build \
  --file docker/opendds-builder/Dockerfile \
  --tag java-opendds-builder:25-3.34.0 \
  .
```

Run the complete reactor before debugging runtime behavior:

```bash
docker run --rm \
  --volume "$PWD:/workspace" \
  --workdir /workspace \
  java-opendds-builder:25-3.34.0 \
  mvn --batch-mode --no-transfer-progress clean verify
```

Then run the complete scenario gate:

```bash
./scripts/smoke-test.sh
```

## Symptom: Java and OpenDDS compatibility is uncertain

**Check**

```bash
./compatibility/java25-opendds-3.34/run.sh
```

The probe must show Java 25, Maven, a successful maintained Messenger exchange,
and the final `PASS:` marker.

**Correction**

Use the pinned Dockerfiles. Do not mix an arbitrary host OpenDDS build, JDK,
generated JAR, or native library with project artifacts. Use `--clean` when
diagnosing a stale Docker layer.

## Symptom: Maven cannot resolve an OpenDDS JAR

Typical errors name `org.opendds:opendds-dcps`, `tao-java`, `i2jrt`, or
`i2jrt-corba`.

**Check**

Make sure Maven runs inside `java-opendds-builder:25-3.34.0`. That image
installs the four OpenDDS JARs in its local Maven repository during its build:

```bash
docker run --rm java-opendds-builder:25-3.34.0 mvn --version
```

**Correction**

Rebuild the application builder image from this checkout. Host Maven does not
automatically have these pinned artifacts.

## Symptom: IDL generation fails

**Check**

Read the first error emitted by
[`dds-types/scripts/generate.sh`](../dds-types/scripts/generate.sh). Confirm
the command runs inside the builder, where `DDS_ROOT`, `ACE_ROOT`,
`TAO_ROOT`, `MPC_ROOT`, `JAVA_HOME`, Perl, a C++ compiler, and GNU Make
are configured.

```bash
test -f dds-types/src/main/idl/Learning.idl
test -f dds-types/src/main/mpc/Learning.mpc
```

**Correction**

Rebuild the builder image, then rerun `mvn clean verify`. Edit IDL and MPC
inputs rather than files under `target/`.

## Symptom: `UnsatisfiedLinkError` names `learning_types`

Java reached `System.loadLibrary("learning_types")` but could not load the
application JNI library or one of its dependencies.

**Check**

```bash
find dds-types/target -name 'liblearning_types.so' -print
sed -n '1,220p' scripts/run-dds-java.sh
```

The wrapper must include the generated native directory in
`java.library.path` and the OpenDDS native directories in
`LD_LIBRARY_PATH`.

**Correction**

Launch through the repository scripts. Do not copy only the application JAR to
another image. Rebuild in the target Linux architecture so the JVM and `.so`
architecture match.

## Symptom: `UnsatisfiedLinkError` names OpenDDS, ACE, or TAO

**Check**

The application `.so` was found, but a native dependency was not. Inspect
dependencies inside the Linux builder:

```bash
docker run --rm \
  --volume "$PWD:/workspace" \
  --workdir /workspace \
  java-opendds-builder:25-3.34.0 \
  bash -lc 'ldd dds-types/target/classes/native/linux-*/liblearning_types.so'
```

**Correction**

Use the same pinned environment that built the library. Ensure
`LD_LIBRARY_PATH` contains the OpenDDS, ACE, and TAO library directories
before Java starts. The provided launcher does this.

## Symptom: endpoints never match

Typical messages say the requester is waiting for a request reader or reply
writer, or the telemetry device is waiting for a monitor.

**Check**

1. Compare the printed domains on both roles.
2. Confirm both sides use the same topic name and generated type.
3. Confirm requested and offered reliability are compatible.
4. Confirm both sides loaded the intended configuration file.
5. In shared-memory mode, confirm the InfoRepo started and produced an IOR.
6. In network mode, confirm both named Compose services are running.

Inspect the resolved Compose model:

```bash
docker compose -f docker/compose.network.yml config
docker compose -f docker/compose.shared-memory.yml config
```

**Correction**

Pass one `--domain` value for a normal run and let the launcher apply QoS to
both endpoints. Use the scenario launcher instead of starting one role with a
configuration file from another scenario.

The following mismatch is expected to time out:

```bash
./scripts/run-network.sh --scenario ping --count 1 \
  --timeout-seconds 3 --requester-domain 43 --responder-domain 42
```

## Symptom: endpoints match but no samples arrive

Discovery success does not prove the application-data path.

**Check**

The launcher must identify the intended transport:

```text
RUN_MODE=shared-memory DATA_TRANSPORT=shmem
```

or:

```text
RUN_MODE=network DATA_TRANSPORT=RTPS/UDP
```

Read [`docker/config/README.md`](../docker/config/README.md) and the selected
INI file. In network mode, distinguish SPDP/SEDP discovery settings from the
`rtps_udp_data` application transport. In shared-memory mode, all Java roles
must share one container IPC namespace.

**Correction**

Use the matching Compose file and launcher. Separate containers do not share
memory unless their IPC arrangement explicitly permits it. Do not combine one
role's shared-memory configuration with the other role's RTPS/UDP configuration.

## Symptom: OpenDDS reports an unavailable UDP address or port

**Check**

Network INI files bind `SpdpLocalAddress` to stable Compose service names and
use explicit `SpdpSendAddrs`. Confirm names in the selected INI match
`docker/compose.network.yml`:

```bash
docker compose -f docker/compose.network.yml config --services
docker compose --project-name java-opendds-network \
  -f docker/compose.network.yml ps
```

**Correction**

Stop a stale project, then rerun the launcher:

```bash
docker compose --project-name java-opendds-network \
  -f docker/compose.network.yml down --remove-orphans
```

For cross-host work, choose peer-reachable advertised addresses and open the
relevant RTPS UDP ports. Same-host Compose service names do not generalize to
another machine.

## Symptom: ping times out

**Check**

Find the last stage reached:

- no `PING_READY`: association did not complete;
- `PING_READY` but no reply: inspect responder output and transport;
- some replies, then timeout: inspect sequence correlation and system load;
- intentional domain mismatch: timeout is the expected result.

The launcher timeout is an outer scenario deadline. The application uses a
slightly shorter association/reply timeout so it can usually report a summary
and clean up.

**Correction**

Rerun the reliable default with a longer outer timeout:

```bash
./scripts/run-network.sh --scenario ping --count 10 --timeout-seconds 60
```

Do not compare latency from a failing or resource-starved run.

## Symptom: latency results vary widely

**Check**

Confirm count, transport, host, resource allocation, and background load are
the same. With ten samples, nearest-rank p95 and p99 are both the maximum.

**Correction**

Run repeated trials, keep warmup enabled, increase `--count`, and compare
distributions on the same machine. The metric includes Java, JNI, OpenDDS,
serialization, container scheduling, and responder work.

## Symptom: telemetry reports missing samples

**Check**

Read each `TELEMETRY` line and its sequence. A jump from sequence `n` to
`n + k` contributes `k - 1` missing samples. Confirm whether the run selected
`best-effort` or `reliable`.

**Correction**

Reproduce with the reliable baseline:

```bash
./scripts/run-network.sh --scenario telemetry --count 10 \
  --reliability reliable --history-depth 10 \
  --interval-ms 25 --stale-after-ms 100
```

For best effort, gaps are an allowed observation. A finite monitor waits for
the configured receive count, so loss can make the outer scenario timeout
return non-zero.

## Symptom: telemetry never becomes stale

**Check**

The monitor uses local monotonic time since the last received sample. Confirm
the device stopped, `--count` is finite, and `--stale-after-ms` is lower
than the outer timeout margin.

**Correction**

Use the verified baseline: 25 ms interval, 100 ms stale threshold, and
30-second outer timeout. Do not calculate freshness from the device wall clock.

## Symptom: a container or native process remains after failure

**Check**

```bash
docker compose --project-name java-opendds-shared \
  -f docker/compose.shared-memory.yml ps
docker compose --project-name java-opendds-network \
  -f docker/compose.network.yml ps
```

**Correction**

```bash
docker compose --project-name java-opendds-shared \
  -f docker/compose.shared-memory.yml down --remove-orphans
docker compose --project-name java-opendds-network \
  -f docker/compose.network.yml down --remove-orphans
```

Then run `./scripts/smoke-test.sh` to confirm lifecycle cleanup.

## Escalate with useful evidence

Include:

- the exact command;
- the `RUN_MODE` and `DATA_TRANSPORT` header;
- Java, Maven, OpenDDS, OS, and container architecture;
- the first native or OpenDDS error, not only the final timeout;
- application readiness and summary lines;
- whether the compatibility probe, `mvn clean verify`, and smoke test pass.
