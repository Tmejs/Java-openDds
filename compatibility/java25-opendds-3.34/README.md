# Java 25 / OpenDDS 3.34.0 compatibility probe

This isolated image checks the proposed Java/OpenDDS pair before the project
build is shaped around it. The image compiles OpenDDS with Java JNI bindings,
then runs OpenDDS's maintained Java Messenger test. The test starts a discovery
repository, publisher, and subscriber; a zero exit status means the subscriber
received and acknowledged the publisher's messages.

## Pinned inputs

- Base image: `eclipse-temurin:25.0.4_7-jdk-noble` (Linux, Ubuntu 24.04 Noble,
  Eclipse Temurin JDK 25.0.4+7).
- OpenDDS: `3.34.0`, downloaded from its official GitHub release asset and built
  from source with `./configure --java` and `make -j2`.
- Apache Maven: `3.9.16`, downloaded from Apache's official distribution site
  and verified against its published SHA-512 file. Maven is included to capture
  the exact Java build-tool environment that later checkpoints will use.
- Native build prerequisites: GCC C++ compiler, GNU Make, Perl, `curl`, and CA
  certificates. The OpenDDS release build includes its ACE/TAO dependency setup.

The official OpenDDS Docker quick start describes prebuilt OpenDDS images for
the C++ Messenger sample; it does not establish that a release image has Java
bindings enabled or includes JDK 25. This probe therefore builds the versioned
OpenDDS source release in the versioned Temurin JDK image.

## Run

From the repository root:

```bash
./compatibility/java25-opendds-3.34/run.sh
```

The script builds the probe image, checks that the container's Java
specification version is exactly 25, prints Maven's version, sources OpenDDS's
`setenv.sh`, and runs `java/tests/messenger/run_test.pl`. It exits non-zero if
image build, Java version validation, or the publisher/subscriber test fails.
The final `PASS` line is printed only after the harness reports `test PASSED.`
Use `./compatibility/java25-opendds-3.34/run.sh --clean` to force a fresh
no-cache image build; this full build is intentionally slower because it
compiles OpenDDS and ACE/TAO from source.

## Build and runtime artifacts

The official Java test is used to avoid inventing a separate IDL/JNI example
before its supported setup is understood. It generates and compiles its
Messenger type support as part of the OpenDDS build/test setup. OpenDDS runtime
JARs and native libraries are under `/opt/OpenDDS-3.34.0/lib` and
`/opt/OpenDDS-3.34.0/ACE_wrappers/lib`; Messenger-specific classes and native
type support are built under `java/tests/messenger`. The observed generated
type-support JAR is
`/opt/OpenDDS-3.34.0/java/tests/messenger/messenger_idl/messenger_idl_test.jar`;
the native OpenDDS Java library is `libOpenDDS_DCPS_Java.so.3.34.0` under
`/opt/OpenDDS-3.34.0/lib`. The maintained test controls its own Java class
path and native library path. The generated Messenger native type-support
library is
`/opt/OpenDDS-3.34.0/java/tests/messenger/messenger_idl/libmessenger_idl_test.so.3.34.0`
(with an unversioned symlink beside it); the test adds that generated library
directory to its native search path through `PerlACE::add_lib_path(...)`. The
probe prints the concise success markers on
successful runs; if the harness fails, it prints the captured full test output
to help diagnose the failure. The maintained test can also be run directly
inside the image to inspect its complete JDK/JNI diagnostics.

## Compatibility result

**Passed on Linux ARM64** using Docker Engine 28.3.3, Eclipse Temurin
25.0.4+7, Apache Maven 3.9.16, GCC 13.3, and OpenDDS 3.34.0 with ACE/TAO
6.5.24. On 2026-10-05, a clean no-cache image build ran `./configure --java`
and `make -j2`; OpenDDS generated and compiled its Java/JNI bindings, native
libraries, and maintained Java tests. The test then successfully started
`DCPSInfoRepo`, `TestPublisher`, and `TestSubscriber`; it loaded native
libraries and printed `test PASSED.` after the subscriber received and
acknowledged messages. The finalized `run.sh` was then run against that
clean-built image and exited 0 with the same explicit success marker.

Java 25 reports non-fatal warnings during the build and run: legacy
`finalize()` use is marked for removal; JNI code triggers `-Xcheck:jni`
warnings about exception checks; and `System.loadLibrary` triggers a warning
that future Java releases will require native access to be enabled explicitly.
These warnings did not prevent binding compilation, native loading, or data
exchange. Preserve them as a learning/troubleshooting note when integrating
the generated types into Maven.

## References

- [OpenDDS 3.34.0 Linux build and Java Messenger quick start](https://opendds.readthedocs.io/en/v3.34.0/devguide/quickstart/linux.html)
- [OpenDDS 3.34.0 Java bindings guide](https://opendds.readthedocs.io/en/v3.34.0/devguide/java_bindings.html)
- [OpenDDS 3.34.0 Docker quick start](https://opendds.readthedocs.io/en/v3.34.0/devguide/quickstart/docker.html)
- [Apache Maven 3.9.16 download and checksum](https://maven.apache.org/download.cgi)
