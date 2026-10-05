#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${script_dir}/../.." && pwd)"
image_name="java-opendds-compatibility:java25-opendds-3.34"
clean_build=false

if [[ "${1:-}" == "--clean" && "$#" -eq 1 ]]; then
  clean_build=true
elif [[ "$#" -ne 0 ]]; then
  echo "usage: $0 [--clean]" >&2
  exit 2
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "error: Docker CLI is required" >&2
  exit 127
fi

build_args=(--pull --progress=plain)
if [[ "${clean_build}" == true ]]; then
  build_args+=(--no-cache)
fi

docker build "${build_args[@]}" \
  --file "${script_dir}/Dockerfile" \
  --tag "${image_name}" \
  "${repo_root}"

docker run --rm "${image_name}" bash -Eeuo pipefail -c '
  java --version
  java_major="$(java -XshowSettings:properties -version 2>&1 | awk -F"= " "/java.specification.version =/ { print \$2 }")"
  if [[ "${java_major}" != "25" ]]; then
    echo "error: expected Java 25, found ${java_major:-unknown}" >&2
    exit 1
  fi
  mvn --version
  export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-}"
  source setenv.sh
  cd java/tests/messenger
  if test_output="$(./run_test.pl 2>&1)"; then
    :
  else
    status=$?
    printf "%s\n" "${test_output}"
    exit "${status}"
  fi
  if ! grep -Fq "test PASSED." <<< "${test_output}"; then
    printf "%s\n" "${test_output}"
    echo "error: Messenger did not report a successful publisher/subscriber exchange" >&2
    exit 1
  fi
  echo "test PASSED."
'

echo "PASS: Java 25 with OpenDDS 3.34.0 generated, loaded, and exchanged samples through the maintained Java Messenger test."
