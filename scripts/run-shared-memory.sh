#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCENARIO=""
COUNT=10
TIMEOUT_SECONDS=30
DOMAIN=42
RELIABILITY="reliable"
HISTORY_DEPTH=10
STALE_AFTER_MS=100
TELEMETRY_INTERVAL_MS=25

while (( $# )); do
  case "$1" in
    --scenario) SCENARIO="${2:?--scenario requires ping or telemetry}"; shift 2 ;;
    --count) COUNT="${2:?--count requires a positive integer}"; shift 2 ;;
    --timeout-seconds) TIMEOUT_SECONDS="${2:?--timeout-seconds requires a positive integer}"; shift 2 ;;
    --domain) DOMAIN="${2:?--domain requires an integer}"; shift 2 ;;
    --reliability) RELIABILITY="${2:?--reliability requires reliable or best-effort}"; shift 2 ;;
    --history-depth) HISTORY_DEPTH="${2:?--history-depth requires a positive integer}"; shift 2 ;;
    --stale-after-ms) STALE_AFTER_MS="${2:?--stale-after-ms requires a positive integer}"; shift 2 ;;
    --interval-ms) TELEMETRY_INTERVAL_MS="${2:?--interval-ms requires a non-negative integer}"; shift 2 ;;
    -h|--help)
      printf 'Usage: %s --scenario ping|telemetry [--count N] [--timeout-seconds N] [--domain N] [--reliability reliable|best-effort] [--history-depth N] [--stale-after-ms N] [--interval-ms N]\n' "$0"
      exit 0
      ;;
    *) printf 'ERROR: unknown option %s\n' "$1" >&2; exit 2 ;;
  esac
done

if [[ "$SCENARIO" != ping && "$SCENARIO" != telemetry ]]; then
  printf 'ERROR: --scenario must be ping or telemetry\n' >&2
  exit 2
fi
if ! [[ "$COUNT" =~ ^[1-9][0-9]*$ && "$TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ \
  && "$DOMAIN" =~ ^[0-9]+$ ]]; then
  printf 'ERROR: count and timeout must be positive integers; domain may be zero\n' >&2
  exit 2
fi
if [[ "$RELIABILITY" != reliable && "$RELIABILITY" != best-effort ]]; then
  printf 'ERROR: reliability must be reliable or best-effort\n' >&2
  exit 2
fi
if ! [[ "$HISTORY_DEPTH" =~ ^[1-9][0-9]*$ && "$STALE_AFTER_MS" =~ ^[1-9][0-9]*$ \
  && "$TELEMETRY_INTERVAL_MS" =~ ^[0-9]+$ ]]; then
  printf 'ERROR: history depth and stale threshold must be positive; interval must be non-negative\n' >&2
  exit 2
fi

if [[ "${DDS_SKIP_BUILD:-0}" != 1 ]]; then
  "$ROOT_DIR/scripts/prepare-runtime.sh"
fi

if [[ "$SCENARIO" == ping ]]; then
  service=ping-lab
else
  service=telemetry-lab
fi
APPLICATION_TIMEOUT_SECONDS=$(( TIMEOUT_SECONDS > 5 ? TIMEOUT_SECONDS - 5 : 1 ))
export DDS_SCENARIO="$SCENARIO" DDS_COUNT="$COUNT" DDS_TIMEOUT_SECONDS="$TIMEOUT_SECONDS"
export DDS_APPLICATION_TIMEOUT_SECONDS="$APPLICATION_TIMEOUT_SECONDS" DDS_DOMAIN="$DOMAIN"
export DDS_RELIABILITY="$RELIABILITY" DDS_HISTORY_DEPTH="$HISTORY_DEPTH"
export DDS_STALE_AFTER_MS="$STALE_AFTER_MS" DDS_TELEMETRY_INTERVAL_MS="$TELEMETRY_INTERVAL_MS"
printf 'RUN_MODE=shared-memory DATA_TRANSPORT=shmem scenario=%s domain=%s count=%s timeout_seconds=%s application_timeout_seconds=%s reliability=%s history_depth=%s stale_after_ms=%s interval_ms=%s\n' \
  "$SCENARIO" "$DOMAIN" "$COUNT" "$TIMEOUT_SECONDS" "$APPLICATION_TIMEOUT_SECONDS" \
  "$RELIABILITY" "$HISTORY_DEPTH" "$STALE_AFTER_MS" "$TELEMETRY_INTERVAL_MS"
docker compose --project-name java-opendds-shared \
  -f "$ROOT_DIR/docker/compose.shared-memory.yml" \
  up --no-build --pull never --abort-on-container-exit --exit-code-from "$service" "$service"
