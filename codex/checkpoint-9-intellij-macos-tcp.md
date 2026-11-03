# Checkpoint 9: native macOS TCP and IntelliJ

## Scope

The Maven IDL/JNI generator now packages a macOS `.dylib` under the native
platform directory. The Java launcher selects its current OS/architecture
and sets the macOS dynamic-library path inside the launcher. A local TCP
configuration, Maven JAR installer, and IntelliJ run/debug guide provide a
Docker-free ping/pong path on macOS. The Linux Docker modes remain intact.

## Verification

- `scripts/test-native-platform.sh`: passed for Darwin and Linux on ARM64 and
  x86-64, plus rejection of an unsupported OS.
- `bash -n` on changed shell scripts and `git diff --check`: passed.
- Pinned Linux builder: `mvn clean verify` passed for all six modules.
- `scripts/smoke-test.sh`: passed ping and telemetry in shared-memory and
  RTPS/UDP modes, including domain-mismatch behavior.
- `dds-types/scripts/test-idl-regeneration.sh`: passed the IDL rename and
  stale-class removal regression.
- Native Apple Silicon OpenDDS 3.34.0 built with Temurin 25.0.4. The new
  installer populated the four `org.opendds` artifacts in host Maven.
- Native macOS `mvn clean verify`: passed all six modules; generated
  `dds-types/target/classes/native/macos-aarch64/liblearning_types.dylib` and
  loaded it in the generated-type test.
- Native direct-JVM test using the guide's class paths, VM options, and
  environment: InfoRepo plus TCP configuration exchanged ten ping replies;
  `PING_SUMMARY status=OK received=10 expected=10`.
- IntelliJ's GUI was not automated. Its Application configurations are
  documented from the verified direct-JVM invocation.

## Independent review

The reviewer found one Important guide issue: native Maven tests needed the
OpenDDS runtime library environment in the parent Maven process. The guide
now sources `setenv.sh` before Maven. A second fresh-cache issue removed
`--offline` from the dependency check. Re-review found no unresolved Critical
or Important findings.

## Platform limit

OpenDDS 3.34.0 shared-memory transport reports no platform support on this
macOS build. The native guide uses TCP; the shared-memory lab remains on Linux.
