# Run and debug ping/pong from IntelliJ on macOS

This path runs the Java requester and responder as normal IntelliJ **Application**
configurations. A local OpenDDS InfoRepo discovers them, and OpenDDS `tcp`
transports their samples over loopback TCP/IP. No Docker Compose process is
needed. OpenDDS 3.34.0 shared-memory transport reports no platform support on
macOS; the Linux container remains the shared-memory lab.

## 1. Install the matching native toolchain

Use an ARM64 JDK on Apple Silicon or an x86-64 JDK on an Intel Mac. The JDK,
OpenDDS libraries, and generated `learning_types` library must have the same
architecture. Install Java 25, Maven, Apple Command Line Tools, and Perl.
Select Java 25 in IntelliJ's **Project SDK** and Maven **Runner JRE**.

Build OpenDDS 3.34.0 from its release source in a persistent directory outside
this repository. For example, after extracting the official release archive:

```bash
export JAVA_HOME=/absolute/path/to/jdk-25
export DDS_ROOT=/absolute/path/to/OpenDDS-3.34.0
cd "$DDS_ROOT"
./configure --java="$JAVA_HOME"
make -j4
```

The explicit JDK path matters on macOS: `./configure --java` alone may find
`/usr/bin/java` rather than the JDK headers. OpenDDS's
[3.34 quick start](https://opendds.readthedocs.io/en/v3.34.0/devguide/quickstart/linux.html)
describes its source build and `setenv.sh`. Use a local build; the four Java
JARs are not published to Maven Central at these coordinates.

Install those JARs into **the same local Maven repository IntelliJ uses**:

```bash
cd /absolute/path/to/Java-openDds
export DDS_ROOT=/absolute/path/to/OpenDDS-3.34.0
./scripts/install-opendds-maven.sh
mvn -pl dds-types dependency:resolve -DincludeGroupIds=org.opendds
```

If IntelliJ uses a custom Maven `settings.xml` or local repository, use that
same Maven configuration for the installer. Reload the Maven project in
IntelliJ; `org.opendds:opendds-dcps`, `tao-java`, `i2jrt`, and `i2jrt-corba`
should then appear under Maven dependencies.

## 2. Generate and compile this project

In a terminal, build once with the same JDK and OpenDDS installation:

```bash
cd /absolute/path/to/Java-openDds
export JAVA_HOME=/absolute/path/to/jdk-25
export DDS_ROOT=/absolute/path/to/OpenDDS-3.34.0
export DYLD_LIBRARY_PATH="${DYLD_LIBRARY_PATH:-}"
source "$DDS_ROOT/setenv.sh"
mvn clean verify
```

The `dds-types` Maven phase runs OpenDDS MPC and `make`, generating Java type
support and `dds-types/target/classes/native/macos-aarch64/` (or
`macos-x86_64/`) with `liblearning_types.dylib`. It may take several minutes.
Afterward, reload the Maven project in IntelliJ so it sees
`dds-types/target/generated-sources/opendds`. IntelliJ's own **Build Project**
does not replace this IDL/JNI generation when `Learning.idl` changes; rerun
`mvn verify` and reload Maven after such changes. If you invoke Maven from
inside IntelliJ, set `DDS_ROOT` and `DYLD_LIBRARY_PATH` (to the same two
OpenDDS library directories shown below) in its Maven runner environment.

## 3. Start local discovery

Leave this terminal open while running the Java processes:

```bash
export DDS_ROOT=/absolute/path/to/OpenDDS-3.34.0
export DYLD_LIBRARY_PATH="${DDS_ROOT}/lib:${DDS_ROOT}/ACE_wrappers/lib"
rm -f /tmp/java-opendds-native-repo.ior
"$DDS_ROOT/bin/DCPSInfoRepo" -NOBITS -o /tmp/java-opendds-native-repo.ior
```

Wait until `/tmp/java-opendds-native-repo.ior` exists. The checked-in
[`native-macos-tcp.ini`](../config/native-macos-tcp.ini) points both Java
processes to that file. InfoRepo is the **discovery service**; the application
data transport is `tcp`, as selected in the INI file. This is different from
the Docker network lab, which uses RTPS discovery and RTPS/UDP data.

## 4. Create two IntelliJ Application configurations

Open **Run → Edit Configurations**, add two **Application** configurations,
and set the fields below. Replace `<repo>`, `<dds>`, and `<arch>` with absolute
paths and either `aarch64` or `x86_64`. Use the module whose name ends with
`ping-responder` or `ping-requester`; IntelliJ may prefix it with the root
project name. Use Java 25 for both.

| Field | Responder | Requester |
| --- | --- | --- |
| Main class | `io.github.tmejs.opendds.ping.PingResponder` | `io.github.tmejs.opendds.ping.PingRequester` |
| Use classpath of module | `ping-responder` | `ping-requester` |
| Working directory | `<repo>` | `<repo>` |
| Program arguments | `--domain 42 --count 0 -DCPSConfigFile <repo>/config/native-macos-tcp.ini` | `--domain 42 --count 10 --timeout-seconds 10 -DCPSConfigFile <repo>/config/native-macos-tcp.ini` |
| VM options | `--enable-native-access=ALL-UNNAMED -Djava.library.path=<repo>/dds-types/target/classes/native/macos-<arch>:<dds>/lib:<dds>/ACE_wrappers/lib` | Same |
| Environment variables | `DYLD_LIBRARY_PATH=<dds>/lib:<dds>/ACE_wrappers/lib` | Same |

Set **Allow multiple instances** if IntelliJ prevents the two configurations
from running together. Start the responder first; its console should print
`RESPONDER_READY`. Then run or debug the requester. A successful ten-ping run
prints `PING_SUMMARY status=OK received=10 expected=10` and latency statistics.
Breakpoints in either Java application work with IntelliJ's **Debug** action.
Keep the responder running while debugging the requester; a long breakpoint
pause may require a larger `--timeout-seconds` value.

IntelliJ's [Application configuration reference](https://www.jetbrains.com/help/idea/run-debug-configuration-java-application.html)
explains its module classpath, working directory, VM options, and environment
variable fields. Enter the native paths in the **run configuration**, not only
in a terminal: an IntelliJ window opened from Finder may not inherit shell
environment variables.

## Common failures

- `org.opendds` dependencies are red: install the four JARs in IntelliJ's
  Maven repository, then reload Maven.
- `Learning.*` classes are missing: run `mvn verify` with `DDS_ROOT` set, then
  reload Maven. Check the `dds-types` generator's first error.
- `UnsatisfiedLinkError: learning_types`: check architecture and the
  `macos-<arch>` directory in `java.library.path`.
- `process_directive failed` for `tcp` or `repository`: check
  `DYLD_LIBRARY_PATH` in **each** IntelliJ Application configuration. On macOS,
  an intermediate `/usr/bin/env` launcher can strip this variable.
- `participant creation failed` or a requester timeout: check that InfoRepo
  is still running, its IOR file exists, both configurations use the same
  domain and INI, and the responder has started.
