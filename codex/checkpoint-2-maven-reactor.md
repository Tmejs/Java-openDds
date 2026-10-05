# Checkpoint 2: Maven Reactor and OpenDDS Types

## Scope

Scaffold the Java 25 Maven reactor and add the first project-owned DDS schema,
generated Java type support, and architecture-specific JNI library packaging.

## Verified environment

- Builder image: `java-opendds-builder:25-3.34.0` based on
  `eclipse-temurin:25.0.4_7-jdk-noble`.
- JDK: Eclipse Temurin 25.0.4+7.
- Maven: Apache Maven 3.9.16.
- OpenDDS: 3.34.0, built with Java support; ACE/TAO 6.5.24.
- Tested container platform: Linux ARM64 (`aarch64`).

## Implementation and verification

- The Maven reactor discovers `dds-types`, `ping-requester`, `ping-responder`,
  `telemetry-device`, and `telemetry-monitor` in dependency order. Compiler
  release is 25; JUnit Jupiter and compiler/Surefire versions are centralized.
- The builder image matches the compatibility probe and installs the four
  OpenDDS Java runtime JARs into its image-local Maven repository, allowing
  regular versioned Maven dependencies to flow from `dds-types` to consumers.
- `Learning.idl` defines ping request/reply and keyed telemetry topics. MPC
  generates Java sources plus native type support under ignored `target/`
  output. Maven adds generated Java sources during `generate-sources`, compiles
  them, and packages the `.so` under the Linux architecture-specific resource
  directory.
- Before generation existed, the generated-type test failed at test compilation
  with `package Learning does not exist`, as expected.
- `docker build --file docker/opendds-builder/Dockerfile --tag java-opendds-builder:25-3.34.0 .` — passed.
- `mvn -q validate` inside the builder — passed for all five modules.
- `mvn -pl dds-types -am clean verify` inside the builder — passed. The JUnit
  tests verify generated field assignment and load
  `native/linux-aarch64/liblearning_types.so.3.34.0` with `System.load`.
- `mvn -pl ping-requester,telemetry-monitor -am verify` — passed, confirming
  reactor dependency order and the OpenDDS Maven artifacts are available to
  consuming modules.
- `jar tf dds-types/target/dds-types-0.1.0-SNAPSHOT.jar` — confirmed the
  generated `Learning/PingRequest.class` and both versioned and unversioned
  `liblearning_types.so` entries are packaged.
- Two consecutive `generate.sh` runs produced identical hashes for generated
  Java and native outputs; the second MPC/Make pass required no recompilation.
- Generated `target/` content is ignored and remains uncommitted.

Java 25 emits its restricted-native-access warning when the test calls
`System.load`; the library still loads successfully. Runtime launch guidance
should explain Java's native-access option alongside the required native
library search path.

## Implementation rulings

- The initial native output path was specified as `linux-x86_64`. Because the
  verified builder runs ARM64, packaging now supports `linux-x86_64` and
  `linux-aarch64`, selected from the builder's `uname -m`. Only ARM64 has been
  executed so far.
- `sequence` is a reserved IDL keyword. `tao_idl` reported a syntax error at
  that member. Renaming the schema property to Java-style `sequenceNumber`
  allowed IDL generation to proceed; the test and plan now use the generated
  property name.
- The generated C++ and JNI sources include an export header. An initial native
  compile failed because it was absent; the generator now creates
  `learning_types_Export.h` using OpenDDS's `generate_export_file.pl` before
  invoking Make.
- Passing `Learning.mpc` directly to `mwc.pl` produced “No workspace was
  defined.” MPC documents no-argument discovery of `.mpc` projects from the
  current directory, so the script runs there with `mwc.pl -type gnuace` and
  then invokes `make`.
- The OpenDDS Java runtime archives are installed into the builder image's
  local Maven repository, rather than declared with machine-specific
  `systemPath` dependencies. This preserves normal Maven transitivity to the
  application modules while keeping the OpenDDS version explicit.

## Review

Independent review covered `6de280b..f96d359`.

- Initial review found one Important issue: after an IDL type rename, obsolete
  generated Java classes could remain in the packaged JAR. No Critical findings.
- Added `dds-types/scripts/test-idl-regeneration.sh`. Before the fix, the test
  failed because `Learning/PingReply.class` remained after renaming the IDL
  type to `RenamedReply`.
- The generator now fingerprints the IDL, MPC project, generator script,
  OpenDDS installation path, and host architecture. A changed fingerprint
  clears derived MPC output, mirrored generated sources, compiled generated
  classes, and packaged native libraries before rebuilding.
- The incremental rename regression passed after the fix. The test verified
  that the JAR contains `Learning/RenamedReply.class` and excludes
  `Learning/PingReply.class`.
- Follow-up independent review reran the committed regression at `f96d359`
  and found no Critical, Important, or Minor findings. It also confirmed the
  generated-field examples in the plan use `sequenceNumber`.
- `mvn --batch-mode --no-transfer-progress clean verify` inside
  `java-opendds-builder:25-3.34.0` — passed for the complete six-module reactor.
- `git diff --check origin/main..HEAD`, `bash -n` on both generation scripts,
  and the independent review's whitespace/syntax checks — passed.

Review scope excluded x86-64 execution and application/transport behavior,
which are planned for later checkpoints.

## Post-review workspace hygiene correction

The first post-merge workspace check showed that `target/` did not ignore
nested module output in this Git setup. The rule is now `**/target/`, and
`git check-ignore -v` confirmed that root, all five module, and deeper nested
Maven target directories are ignored while source IDL, MPC, and POM files remain
visible. Independent review of the follow-up commit `83a5fb7` found no Critical,
Important, or Minor findings. `git diff --check` passed.
