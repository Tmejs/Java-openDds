#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
module_dir="$(cd -- "${script_dir}/.." && pwd)"

if [[ -z "${DDS_ROOT:-}" || ! -f "${DDS_ROOT}/setenv.sh" ]]; then
  echo "error: DDS_ROOT must name the OpenDDS installation" >&2
  exit 1
fi

# OpenDDS's setup script appends to this variable; initialize it for strict mode.
export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-}"
# shellcheck source=/dev/null
source "${DDS_ROOT}/setenv.sh"

build_dir="${module_dir}/target/opendds"
java_sources="${module_dir}/target/generated-sources/opendds"
generation_fingerprint_file="${build_dir}/.generation-inputs.sha256"
generation_fingerprint="$(
  {
    printf 'DDS_ROOT=%s\n' "${DDS_ROOT}"
    printf 'architecture=%s\n' "$(uname -m)"
    sha256sum \
      "${module_dir}/src/main/idl/Learning.idl" \
      "${module_dir}/src/main/mpc/Learning.mpc" \
      "${BASH_SOURCE[0]}"
  } | sha256sum | awk '{print $1}'
)"

# MPC and javac do not reliably remove outputs for deleted generated types.
# A changed schema, MPC project, generator, OpenDDS installation, or host
# architecture therefore invalidates every derived output before regeneration.
if [[ ! -f "${generation_fingerprint_file}" ]] ||
  [[ "$(<"${generation_fingerprint_file}")" != "${generation_fingerprint}" ]]; then
  rm -rf "${build_dir}" "${java_sources}" \
    "${module_dir}/target/classes/Learning" "${module_dir}/target/classes/native"
fi
mkdir -p "${build_dir}"

for input_file in Learning.idl Learning.mpc; do
  case "${input_file}" in
    *.idl) source_file="${module_dir}/src/main/idl/${input_file}" ;;
    *.mpc) source_file="${module_dir}/src/main/mpc/${input_file}" ;;
  esac
  build_file="${build_dir}/${input_file}"
  if ! cmp -s "${source_file}" "${build_file}"; then
    cp "${source_file}" "${build_file}"
  fi
done
export_header="${build_dir}/learning_types_Export.h"
export_header_tmp="${export_header}.tmp"
"${ACE_ROOT}/bin/generate_export_file.pl" learning_types > "${export_header_tmp}"
if cmp -s "${export_header_tmp}" "${export_header}"; then
  rm "${export_header_tmp}"
else
  mv "${export_header_tmp}" "${export_header}"
fi

(
  cd "${build_dir}"
  "${ACE_ROOT}/bin/mwc.pl" -type gnuace
  make -j2
)

mkdir -p "${java_sources}"
if [[ ! -d "${build_dir}/Learning" ]]; then
  echo "error: MPC build did not produce generated Learning Java sources" >&2
  exit 1
fi
while IFS= read -r -d '' source_file; do
  relative_path="${source_file#"${build_dir}/"}"
  generated_file="${java_sources}/${relative_path}"
  mkdir -p "$(dirname -- "${generated_file}")"
  if ! cmp -s "${source_file}" "${generated_file}"; then
    cp "${source_file}" "${generated_file}"
  fi
done < <(find "${build_dir}/Learning" -type f -name '*.java' -print0)
while IFS= read -r -d '' generated_file; do
  relative_path="${generated_file#"${java_sources}/"}"
  if [[ ! -f "${build_dir}/${relative_path}" ]]; then
    rm "${generated_file}"
  fi
done < <(find "${java_sources}/Learning" -type f -name '*.java' -print0)

native_arch="$(uname -m)"
case "${native_arch}" in
  x86_64) native_arch="x86_64" ;;
  aarch64|arm64) native_arch="aarch64" ;;
  *)
    echo "error: unsupported Linux native-library architecture: ${native_arch}" >&2
    exit 1
    ;;
esac

native_dir="${module_dir}/target/classes/native/linux-${native_arch}"
mkdir -p "${native_dir}"
shopt -s nullglob
native_libraries=("${build_dir}"/liblearning_types.so*)
if ((${#native_libraries[@]} == 0)); then
  echo "error: MPC build did not produce liblearning_types.so" >&2
  exit 1
fi
cp -au "${native_libraries[@]}" "${native_dir}/"

printf '%s\n' "${generation_fingerprint}" > "${generation_fingerprint_file}"

echo "Generated Java type support under ${java_sources}"
echo "Packaged native type support under ${native_dir}"
