# OpenDDS transport configurations

OpenDDS treats discovery and application-data transport as separate choices.
Discovery tells participants which readers and writers exist. The configured
data transport carries the `PingRequest` and `PingReply` samples after those
endpoints match.

## Shared-memory mode

[`shared-memory.ini`](shared-memory.ini) uses a central `DCPSInfoRepo` for
discovery and one `shmem` transport instance for application data. The launcher
starts the repository and both Java roles inside the same container, so all
three processes share the container's IPC namespace. `DCPSBit=0` matches the
repository's `-NOBITS` mode; this lab does not use Built-In Topic inspection.

The project initially tested `DEFAULT_RTPS` discovery with `shmem` data. On the
pinned OpenDDS 3.34.0 Java runtime, both directions reported matched, but the
first ping sample was not dispatched until a participant shut down. The same
generated type and `shmem` transport worked for a one-way Java exchange, and
OpenDDS's maintained Java Messenger example also passed. The central discovery
layout then passed the bidirectional test and matches the discovery style used
by OpenDDS's `SimpleLatency` ping/pong example. This is a recorded compatibility
decision for this pinned environment, not a general claim that RTPS discovery
and shared memory cannot be combined.

## Network mode

The four `network-rtps-*.ini` files use RTPS discovery and an `rtps_udp`
application-data transport. Each file belongs to one Compose service so its
`SpdpLocalAddress` can use that service's stable DNS name. Compose also passes
the same peer-reachable name as `-DCPSDefaultAddress`, and OpenDDS resolves it
to the service's bridge address before probing the RTPS-defined UDP ports.

Docker bridge multicast behavior varies by host. Each scenario therefore lists
its two service names in `SpdpSendAddrs`, giving SPDP deterministic unicast
destinations on fixed port `17910`; each role binds that port on its own named
bridge address, so the arrangement works with any supported DDS domain.
`SedpMulticast=0` keeps endpoint discovery on unicast as well. These settings
affect RTPS discovery. Application data independently uses the `rtps_udp_data`
transport; its `use_multicast=0` setting also selects unicast.

## Ping reliability QoS

The ping requester and responder use reliable QoS for both request and reply
endpoints. With best-effort QoS, discovery can report matched endpoints before
the first UDP sample has a usable path, so losing that sample makes a healthy
scenario look like a timeout. Reliable delivery lets OpenDDS acknowledge and
retransmit that sample, which is the appropriate behavior for this measurement
tool.

OpenDDS Java's mutable QoS holders must contain every nested policy object
before the JNI call fills in the publisher or subscriber defaults.
`ReliableEndpointQos` follows the maintained Java Messenger example: it creates
that complete object graph, asks OpenDDS for the defaults, and changes only the
reliability kind. This avoids copying a large set of default values into the
application and keeps the request and reply policies compatible.

Both modes use DDS domain IDs as an isolation boundary. Endpoints with different
domain IDs do not match; the smoke test demonstrates this by expecting the
network requester to time out.

Run the ping scenario from the repository root:

```bash
./scripts/run-shared-memory.sh --scenario ping --count 10
./scripts/run-network.sh --scenario ping --count 10
./scripts/smoke-test.sh
```

The launchers print `RUN_MODE`, `DATA_TRANSPORT`, and the selected domains so a
captured result identifies the topology that produced it. `--timeout-seconds`
is an overall scenario deadline enforced inside the observed container. The
builder image is local to this project, so Compose is instructed never to pull
that image from a registry. The ping requester's association/reply timeout is
five seconds shorter (or one second for very short runs), which usually leaves
time to print diagnostics
and shut down. Each DDS association and reply wait gets that application
timeout separately, so the outer scenario deadline remains authoritative and
can interrupt a later wait.
Latency values include Java, JNI, OpenDDS, discovery state, scheduling, and
container overhead. Reliable RTPS setup can make the first measured exchange
an outlier; use `--warmup-count` when comparing steady-state results. Compare
runs on the same host rather than treating them as a hardware-independent
benchmark.

## Telemetry QoS and freshness experiment

The telemetry topic is keyed by `device_id`. A writer registers that key and
publishes an increasing sequence number together with a wall-clock timestamp,
temperature, and humidity. The monitor keeps independent state for every key.
A forward jump from sequence 1 to 4 adds two missing samples; duplicate and
older samples do not replace the displayed value.

Freshness deliberately uses `System.nanoTime()` at the reader. Comparing the
device's wall clock with the monitor's wall clock would mix transport delay
with clock skew. The device timestamp remains part of the sample for domain
meaning, while `age_ms` answers the local operational question: how long has
this monitor gone without a newer sample?

Both endpoints accept two explicit QoS controls:

- `--reliability reliable|best-effort` selects the DDS Reliability policy.
  Reliable is the default baseline and waits for acknowledgements before the
  finite publisher exits. Best effort permits loss and is useful for observing
  sequence-gap behavior under load or disruption.
- `--history-depth N` selects `KEEP_LAST` with depth `N`. This bounds the
  per-instance history retained by an endpoint. It does not make volatile data
  durable and cannot recover a best-effort sample that was already lost.

Run the reliable baseline in either topology:

```bash
./scripts/run-shared-memory.sh --scenario telemetry --count 10 \
  --reliability reliable --history-depth 10 \
  --interval-ms 25 --stale-after-ms 100

./scripts/run-network.sh --scenario telemetry --count 10 \
  --reliability reliable --history-depth 10 \
  --interval-ms 25 --stale-after-ms 100
```

Change both endpoints together through the launcher for a best-effort trial:

```bash
./scripts/run-network.sh --scenario telemetry --count 10 \
  --reliability best-effort --history-depth 10
```

For a finite `--count`, the monitor waits until it receives that many samples,
then keeps running until the last value becomes stale. Its final summary reports
the received count, number of device keys, accumulated sequence gaps, and stale
state. The Compose scenario uses one device key (`device-1`).
