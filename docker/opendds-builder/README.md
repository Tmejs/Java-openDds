# OpenDDS application builder image

This image is the Linux build environment used by the Maven reactor. Its JDK,
Maven, OpenDDS release, and ACE/TAO build match the verified compatibility
probe. It exposes `DDS_ROOT`, `ACE_ROOT`, `TAO_ROOT`, `MPC_ROOT`, `JAVA_HOME`,
and the native-library path expected by OpenDDS's `setenv.sh`.

Build from the repository root:

```bash
docker build \
  --file docker/opendds-builder/Dockerfile \
  --tag java-opendds-builder:25-3.34.0 \
  .
```

Run Maven with the repository mounted into the container:

```bash
docker run --rm \
  --volume "$PWD":/workspace \
  --workdir /workspace \
  java-opendds-builder:25-3.34.0 \
  mvn --batch-mode validate
```

The Docker build registers OpenDDS's four Java runtime JARs in the image's
local Maven repository. The `dds-types` module declares them as ordinary Maven
dependencies, so application modules inherit them through the reactor without
machine-specific `systemPath` entries. The Maven coordinates map the files
`OpenDDS_DCPS.jar`, `tao_java.jar`, `i2jrt.jar`, and `i2jrt_corba.jar` to
`org.opendds:opendds-dcps`, `org.opendds:tao-java`, `org.opendds:i2jrt`, and
`org.opendds:i2jrt-corba`, all at the OpenDDS version shown in the POM.
`dds-types/scripts/generate.sh` invokes MPC and GNU Make inside this image and
keeps generated Java sources, generated build files, and the native
type-support library under `dds-types/target/`.

The native library is packaged under `native/linux-x86_64/` or
`native/linux-aarch64/`, based on the builder architecture. The checked probe
has verified ARM64 so far; the x86-64 path is implemented but has not yet been
run on that architecture.
