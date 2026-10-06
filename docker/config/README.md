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

[`network-rtps.ini`](network-rtps.ini) uses `DEFAULT_RTPS` discovery and an
`rtps_udp` application-data transport. Compose runs each Java role as a
separate service on the `dds-lab` bridge and passes its stable Compose DNS name
as `-DCPSDefaultAddress`. OpenDDS resolves that explicit, peer-reachable name to
the service's bridge address and probes the RTPS-defined UDP port. RTPS
discovery multicast worked on the verified Docker bridge, so this configuration
needs no static peer list. Application data is unicast because
`use_multicast=0` applies to the `rtps_udp_data` transport instance.

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
ping requester's association/reply timeout is five seconds shorter (or one
second for very short runs), which usually leaves time to print diagnostics
and shut down. Each DDS association and reply wait gets that application
timeout separately, so the outer scenario deadline remains authoritative and
can interrupt a later wait.
Latency values include Java, JNI, OpenDDS, discovery state, scheduling, and
container overhead; use them to compare runs on the same host rather than as a
hardware-independent benchmark.
