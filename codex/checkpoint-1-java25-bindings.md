# Checkpoint 1: Java 25 and OpenDDS Java Bindings

## Scope

Add a reproducible Linux Docker probe that verifies the proposed OpenDDS Java
binding toolchain before application modules are scaffolded.

## Verified environment and result

- Date: 2026-10-05
- Host Docker Engine: 28.3.3
- Container: `eclipse-temurin:25.0.4_7-jdk-noble`
- Container platform: Linux ARM64 (`aarch64`)
- Java: Eclipse Temurin 25.0.4+7
- Maven: Apache Maven 3.9.16 (archive SHA-512 validated in the image build)
- C++ compiler: GCC 13.3
- OpenDDS: 3.34.0
- ACE/TAO: 6.5.24

The no-cache Docker build succeeded with `./configure --java` and `make -j2`.
OpenDDS generated and compiled the Java bindings, JNI/native libraries, and
maintained Java tests. The maintained Messenger harness started a discovery
repository, publisher, and subscriber, loaded the native libraries, exchanged
and acknowledged messages, and returned `test PASSED.`. The finalized probe
entrypoint returned zero and printed its success marker.

## Commands and checks

- `bash -n compatibility/java25-opendds-3.34/run.sh` — passed.
- `./compatibility/java25-opendds-3.34/run.sh --clean` — clean build behavior
  was run during probe development; image build passed after correcting the
  official release tag to `v3.34.0`.
- `docker run --rm java-opendds-compatibility:java25-opendds-3.34 ...` —
  maintained Java Messenger test passed after initializing the `LD_LIBRARY_PATH`
  expected by OpenDDS's `setenv.sh`.
- `./compatibility/java25-opendds-3.34/run.sh` — passed; validated Java 25,
  Maven version, Messenger `test PASSED.` marker, and final success output.
- `git diff --check` — passed after final edits.

Non-fatal JDK 25 warnings were observed for legacy `finalize()` methods,
restricted native access via `System.loadLibrary`, and JNI calls made without
checking exceptions under `-Xcheck:jni`. They did not block build, load, or
sample exchange and are documented in the probe README.

## Review

Pending independent review before push.
