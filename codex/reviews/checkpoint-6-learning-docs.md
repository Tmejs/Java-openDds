# Checkpoint 6: Learning Documentation

## Scope

Turn the verified Java/OpenDDS implementation into a guided learning project
for a mid-level Java developer. Add a root quick start, an encounter-order DDS
and JNI guide, runtime and build diagrams, focused experiments, and
symptom-based troubleshooting. Keep every claim within the same-host Docker
behavior verified by this repository.

The checkpoint started from synchronized `main` at
`9f50bf14b9cd6b8f2c33bf3f370416cf3d6d699f` on branch
`codex/checkpoint-6-learning-docs`.

## Documentation delivered

- `README.md` provides the pinned baseline, Docker-only prerequisites,
  compatibility probe, full Maven verification, four scenario launches,
  expected markers, project map, official references, and scope limits.
- `docs/learning-guide.md` introduces IDL, keyed instances, DDS entities,
  discovery, transport, Java/JNI generation, ping timing, telemetry freshness,
  reliability, history, shutdown, and exercises in the order a developer meets
  them.
- `docs/diagrams/runtime-flow.mmd` separates participants, exact topic names,
  discovery, and application-data transport.
- `docs/diagrams/build-and-jni.mmd` traces IDL through OpenDDS generation,
  generated Java and C++, the application JAR and shared library, Maven, the
  JVM, and native OpenDDS.
- `docs/troubleshooting.md` maps build, JAR, JNI, association, transport,
  UDP, timeout, latency, telemetry, and cleanup symptoms to checks and
  corrections.
- `dds-types/scripts/test-idl-regeneration.sh` now inspects the generated JAR
  inside the pinned builder. The documented exercise therefore keeps the root
  promise that no host JDK is required.

## Verification environment

| Component | Observed value |
| --- | --- |
| Host | macOS Darwin 24.6.0, ARM64 |
| Docker client/server | 28.3.3 / 28.3.3 |
| Docker Compose | v2.39.2-desktop.1 |
| Container OS/architecture | Linux 6.10.14-linuxkit, aarch64 |
| Java | Eclipse Temurin 25.0.4+7 LTS |
| Maven | 3.9.16 |
| OpenDDS | 3.34.0 at `/opt/OpenDDS-3.34.0` |
| ACE/TAO | 6.5.24 |

## Verification commands and results

### Documentation and image

- `git diff --check`: passed.
- Static documentation check: all local Markdown links resolved and all fenced
  code blocks were balanced.
- Independent review additionally checked all 25 relative links, parsed all 29
  fenced Bash blocks with `bash -n`, confirmed both launcher help outputs,
  and checked unique Mermaid node identifiers.
- `docker build --file docker/opendds-builder/Dockerfile --tag
  java-opendds-builder:25-3.34.0 .`: passed using the pinned, cached layers.
- The documented `ldd` command ran in the builder and resolved the application
  JNI library plus its OpenDDS, ACE, TAO, C++, GCC, C, and loader dependencies.

### Clean Maven reactor

The exact README command was run:

```bash
docker run --rm \
  --volume "$PWD:/workspace" \
  --workdir /workspace \
  java-opendds-builder:25-3.34.0 \
  mvn --batch-mode --no-transfer-progress clean verify
```

It passed all six reactor projects and all 21 JUnit tests. After review fixes,
the same clean gate was rerun at implementation HEAD `2e6994a` and passed in
43.394 seconds.

The test JVM prints a Java native-access warning because Surefire does not add
`--enable-native-access=ALL-UNNAMED`. The runtime launcher does add it. The
warning did not fail the build.

### Java/OpenDDS compatibility

`./compatibility/java25-opendds-3.34/run.sh` passed. It reported Java
25.0.4+7, Maven 3.9.16, Linux aarch64, the maintained Java Messenger
`test PASSED.` marker, and the repository's final compatibility `PASS:`
marker.

### Complete runtime smoke gate

`./scripts/smoke-test.sh` passed before review and was rerun after all review
fixes. The final run verified:

- shared-memory ping: 10/10 replies;
- RTPS/UDP ping in domain 42: 10/10 replies;
- RTPS/UDP ping in domain 7: 10/10 replies;
- expected domain mismatch, requester 43 and responder 42: timeout with 0/1;
- shared-memory telemetry: 10 sent, 10 received, one device, no gaps, stale;
- RTPS/UDP telemetry: 10 sent, 10 received, one device, no gaps, stale;
- clean scenario shutdown and the final `SMOKE_TEST status=OK` marker.

The final machine-local latency samples were:

| Scenario | p50 | p95 | p99 |
| --- | ---: | ---: | ---: |
| shared-memory ping, domain 42 | 0.393916 ms | 7.470417 ms | 7.470417 ms |
| RTPS/UDP ping, domain 42 | 0.763791 ms | 230.466250 ms | 230.466250 ms |
| RTPS/UDP ping, domain 7 | 0.918125 ms | 229.554833 ms | 229.554833 ms |

These ten-sample values record the verification machine, not a transport
benchmark. With nearest-rank statistics and ten samples, p95 and p99 both select
the maximum. The large network tail in this run illustrates why the guide asks
for warmup, larger counts, repeated trials, and controlled machine load.

A separate documented warmup run used:

```bash
DDS_WARMUP_COUNT=5 ./scripts/run-network.sh --scenario ping --count 10
```

It passed with `PING_READY ... warmup=5`, ten measured replies, and a responder
summary of 15 replies. The measured sample reported p50 0.606708 ms and p95/p99
1.142458 ms.

### Incremental IDL regeneration

`./dds-types/scripts/test-idl-regeneration.sh` passed. It performed a clean
type-support build, renamed the `PingReply` IDL topic type in a temporary
archived checkout, rebuilt without `clean`, and confirmed
`RenamedReply.class` replaced `PingReply.class`.

After moving `jar tf` into the builder container, the regression was rerun
with a host `jar` shim that exits 97. The script still exited 0 with:

```text
PASS: incremental IDL type renames remove stale generated classes
```

This proves the documented exercise does not require a host JDK.

## Independent review

The first independent review found no Critical issues and three Important
documentation issues:

1. Reliability and History were both described as association compatibility
   policies.
2. The guide said both ping roles explicitly wait for association, while only
   the requester performs the two endpoint checks.
3. The incremental generation exercise proposed a field rename, which cannot
   demonstrate removed type-class cleanup.

It also found two Minor accuracy issues: the diagram used `Telemetry topic`
instead of the exact `TelemetrySample` name, and the troubleshooting guide
attributed image-provided `LD_LIBRARY_PATH` to the Java launch wrapper.

All five were corrected. Follow-up review confirmed those corrections and
found one further Important portability issue: the regeneration helper used the
host `jar` executable despite the Docker-only prerequisites. The helper now
runs `jar tf` inside the builder container, and the failing-host-`jar` test
passed.

Final independent re-review at `2e6994a` reran the focused regeneration
test, shell syntax, `git diff --check main..HEAD`, links, and fenced command
checks. No Critical or Important findings remain.

## Limits

- Runtime verification covers Linux ARM64 containers on one Docker Desktop
  host. Linux x86-64 packaging exists but remains unexecuted.
- The network topology proves RTPS/UDP across one Docker bridge, not cross-host
  routing, advertised-address, firewall, multicast, or relay behavior.
- DDS Security, persistent durability, failover, production deployment, schema
  evolution strategy, and production observability remain outside this
  checkpoint.
- Shared-process output can interleave on the Compose stream. Machine checks
  use stable summary substrings rather than treating cosmetic interleaving as a
  failure.
