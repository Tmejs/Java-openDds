# Checkpoint 7: architecture learning guide

## Scope

Added `docs/architecture-deep-dive.md` and linked it from the root README.
The guide traces the IDL and Maven/JNI build, DDS entity lifecycles, shared
memory and network discovery/transport arrangements, ping and telemetry paths,
QoS setup, container lifetime, and a practical failure-isolation sequence.
No application or transport code changed.

## Local verification (2026-10-08)

Environment: Docker Desktop on macOS, Linux ARM64 container, Java 25.0.4+7,
Maven 3.9.16, OpenDDS 3.34.0.

| Check | Result |
| --- | --- |
| `docker run --rm --volume /Users/matrzad/.codex/worktrees/opendds-transport-modes/java-opendds:/workspace --workdir /workspace java-opendds-builder:25-3.34.0 mvn --batch-mode --no-transfer-progress clean verify` | PASS; all six reactor projects built, unit tests passed |
| `./scripts/smoke-test.sh` | PASS; shared and network ping/telemetry, domain 7, expected domain mismatch |
| `./compatibility/java25-opendds-3.34/run.sh` | PASS; maintained Java Messenger exchange |
| `./dds-types/scripts/test-idl-regeneration.sh` | PASS; incremental type rename removed stale classes |
| Local relative Markdown target check for `docs/architecture-deep-dive.md` | PASS; no missing file targets |

The builder emitted a GNU Make clock-skew warning during the fresh Docker mount
build, but Maven completed successfully. Runtime and build claims in the new
guide remain limited to the verified Linux ARM64 container. Cross-host RTPS
and Linux x86-64 execution were not tested.

## Independent review

The first independent review found two Important documentation issues: the IDL
field-rename exercise had implied a full reactor build would succeed without
updating Java callers, and the network telemetry section omitted the Compose
publisher-container hold needed for the monitor's stale observation. Both were
corrected. A Minor wording issue about all observed device keys becoming stale
was also corrected. The reviewer re-checked the fixes against the source and
reported no remaining Critical or Important findings.
