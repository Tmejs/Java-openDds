#!/usr/bin/env bash
set -Eeuo pipefail

if (( $# < 4 )); then
  printf 'Usage: %s <module> <mode> <transport> <main-class> [application args...]\n' "$0" >&2
  exit 2
fi

APP_MODULE="$1"
RUN_MODE="$2"
DATA_TRANSPORT="$3"
MAIN_CLASS="$4"
shift 4

: "${DDS_CONFIG_FILE:?DDS_CONFIG_FILE must name an OpenDDS configuration file}"
ROOT_DIR="${DDS_PROJECT_ROOT:-/workspace}"
OPENDDS_ROOT="${DDS_ROOT:-/opt/OpenDDS-3.34.0}"
CLASSPATH="$ROOT_DIR/$APP_MODULE/target/classes:$ROOT_DIR/dds-types/target/classes:$OPENDDS_ROOT/lib/*"
NATIVE_PLATFORM="$("$(dirname -- "$0")/native-platform.sh")"
NATIVE_PATH="$ROOT_DIR/dds-types/target/classes/native/$NATIVE_PLATFORM:$OPENDDS_ROOT/lib:$ACE_ROOT/lib"
JAVA_EXECUTABLE="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_EXECUTABLE="${JAVA_EXECUTABLE:-java}"
# On macOS, setting this inside the launcher avoids /usr/bin/env stripping it.
export DYLD_LIBRARY_PATH="$OPENDDS_ROOT/lib:$ACE_ROOT/lib:${DYLD_LIBRARY_PATH:-}"

printf 'RUN_MODE=%s DATA_TRANSPORT=%s role=%s discovery_config=%s\n' \
  "$RUN_MODE" "$DATA_TRANSPORT" "$MAIN_CLASS" "$DDS_CONFIG_FILE"

exec "$JAVA_EXECUTABLE" --enable-native-access=ALL-UNNAMED \
  -Djava.library.path="$NATIVE_PATH" \
  -cp "$CLASSPATH" \
  "$MAIN_CLASS" "$@" \
  -DCPSConfigFile "$DDS_CONFIG_FILE"
