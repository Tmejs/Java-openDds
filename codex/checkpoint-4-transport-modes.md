# Checkpoint 4: Shared-Memory and Network Transport Modes

## Scope

Run the checkpoint 3 ping requester and responder over two explicit OpenDDS
application-data transports: shared memory between JVMs in one container and
RTPS/UDP between separate services on a Docker bridge. Wire the future
telemetry roles into the same launch topology without adding placeholder
applications.

## Implementation

- Shared-memory mode starts a `DCPSInfoRepo`, responder JVM, and requester JVM
  in one container. Central discovery is separate from the single `shmem`
  application-data transport. The supervisor waits for the repository IOR,
  handles INT/TERM, reaps child processes, and returns application failures.
- Network mode runs requester and responder as separate Compose services.
  `DEFAULT_RTPS` performs discovery and a named `rtps_udp` instance carries
  application samples over the project-scoped Docker bridge. Each role supplies
  its Compose DNS name through `-DCPSDefaultAddress`; the verified bridge
  supports RTPS multicast discovery, so no static peer list is needed.
- Both public launchers validate scenario/count/timeout/domain inputs, build
  the runtime unless `DDS_SKIP_BUILD=1`, print the selected mode and transport,
  and use Compose `--exit-code-from` for the scenario's observed process.
- Each observed service has an in-container overall deadline. The requester
  timeout is shorter than that deadline so it can emit failure diagnostics and
  clean up before the container deadline. The shared supervisor tracks both
  JVM PIDs and the InfoRepo PID for ordered signal handling. The finite network
  telemetry device holds after a successful publish run so monitor completion,
  rather than publisher exit, determines success.
- The smoke gate validates required services, builds once, requires exactly 10
  replies in each ping topology, checks the printed transport label, and proves
  domain isolation with an expected network requester timeout.
- `docker/config/README.md` explains discovery versus data transport, the two
  topologies, the pinned shared-memory discovery decision, and interpretation
  limits for latency values.

## Shared-memory discovery ruling

The first configuration combined `DEFAULT_RTPS` discovery with `shmem`
application data. Both request and reply endpoints reported matches, but the
first request was not dispatched until participant teardown. The maintained
OpenDDS Java Messenger passed against the same pinned image and shared-memory
transport, and a one-way exchange using this project's generated type also
passed. QoS, Built-In Topics, startup delay, instance registration, listener
threading, and separate directional shmem instances were tested without fixing
the duplex behavior.

OpenDDS's maintained `SimpleLatency` ping/pong example uses centralized
InfoRepo discovery. Applying that architecture while retaining the same
project Java endpoints and `shmem` data transport passed 10 bidirectional
exchanges. The checkpoint therefore uses `DCPSInfoRepo` for shared-memory
discovery and records the RTPS/shmem result as an observed limitation of this
pinned OpenDDS 3.34.0 Java environment, not a general OpenDDS limitation.

## Verification

- The test-first `scripts/smoke-test.sh` failed before Compose files existed
  with an error naming `docker/compose.shared-memory.yml`.
- `./scripts/smoke-test.sh` — passed. Shared memory reported
  `PING_SUMMARY status=OK received=10 expected=10`; network RTPS/UDP reported
  the same exact count; requester domain 43 versus responder domain 42 exited
  non-zero with `status=TIMEOUT received=0 expected=1`.
- `mvn --batch-mode --no-transfer-progress clean verify` in
  `java-opendds-builder:25-3.34.0` — passed across all six reactor projects;
  all 10 current JUnit tests passed.
- `bash -n scripts/*.sh` — passed.
- Both Compose files passed `docker compose ... config --quiet`.
- All files in `scripts/` have their owner executable bit set.
- A forced shared-memory run with one million requested exchanges and a
  three-second deadline exited non-zero at the deadline and left no running
  Compose service.
- `git diff --check` — passed.

Telemetry end-to-end execution is deferred to checkpoint 5 because
`TelemetryDevice` and `TelemetryMonitor` are created there. This checkpoint
validates their Compose service declarations and launcher routing only.

## Review

Initial independent review found four Important issues and one Minor issue:
network telemetry could stop on normal device exit before monitor completion;
the shared supervisor did not track its foreground JVM; the scenario timeout
was not an overall deadline; the network roles did not explicitly select their
reachable addresses; and the smoke gate only checked a printed transport
label. The fixes add a successful-device hold wrapper, track both shared JVMs,
enforce in-container deadlines with diagnostic grace, pass Compose DNS names
as `DCPSDefaultAddress`, and verify each INI's `transport_type`. Follow-up
independent review is pending.
