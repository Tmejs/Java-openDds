#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
builder_image="${OPENDDS_BUILDER_IMAGE:-java-opendds-builder:25-3.34.0}"
temp_dir="$(mktemp -d)"
trap 'rm -rf "${temp_dir}"' EXIT

if ! docker image inspect "${builder_image}" >/dev/null 2>&1; then
  echo "error: builder image ${builder_image} is unavailable; build docker/opendds-builder first" >&2
  exit 1
fi

git -C "${repo_root}" archive HEAD | tar -x -C "${temp_dir}"
docker run --rm --volume "${temp_dir}:/workspace" --workdir /workspace \
  "${builder_image}" mvn --batch-mode --no-transfer-progress -pl dds-types -am clean package

idl_file="${temp_dir}/dds-types/src/main/idl/Learning.idl"
if ! sed 's/struct PingReply/struct RenamedReply/' "${idl_file}" > "${idl_file}.tmp"; then
  echo "error: could not prepare the renamed-IDL regression case" >&2
  exit 1
fi
mv "${idl_file}.tmp" "${idl_file}"

docker run --rm --volume "${temp_dir}:/workspace" --workdir /workspace \
  "${builder_image}" mvn --batch-mode --no-transfer-progress -pl dds-types -am package

artifact="dds-types/target/dds-types-0.1.0-SNAPSHOT.jar"
artifact_entries="$(docker run --rm --volume "${temp_dir}:/workspace:ro" --workdir /workspace \
  "${builder_image}" jar tf "${artifact}")"
if ! grep -qx 'Learning/RenamedReply.class' <<< "${artifact_entries}"; then
  echo "error: renamed IDL type is missing from the packaged JAR" >&2
  exit 1
fi
if grep -qx 'Learning/PingReply.class' <<< "${artifact_entries}"; then
  echo "error: removed IDL type PingReply remains in the packaged JAR" >&2
  exit 1
fi

echo "PASS: incremental IDL type renames remove stale generated classes"
