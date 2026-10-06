#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCENARIO=""
COUNT=10
TIMEOUT_SECONDS=30
DOMAIN=42
REQUESTER_DOMAIN=""
RESPONDER_DOMAIN=""

while (( $# )); do
  case "$1" in
    --scenario) SCENARIO="${2:?--scenario requires ping or telemetry}"; shift 2 ;;
    --count) COUNT="${2:?--count requires a positive integer}"; shift 2 ;;
    --timeout-seconds) TIMEOUT_SECONDS="${2:?--timeout-seconds requires a positive integer}"; shift 2 ;;
    --domain) DOMAIN="${2:?--domain requires an integer}"; shift 2 ;;
    --requester-domain) REQUESTER_DOMAIN="${2:?--requester-domain requires an integer}"; shift 2 ;;
    --responder-domain) RESPONDER_DOMAIN="${2:?--responder-domain requires an integer}"; shift 2 ;;
    -h|--help)
      printf 'Usage: %s --scenario ping|telemetry [--count N] [--timeout-seconds N] [--domain N] [--requester-domain N --responder-domain N]\n' "$0"
      exit 0
      ;;
    *) printf 'ERROR: unknown option %s\n' "$1" >&2; exit 2 ;;
  esac
done

if [[ "$SCENARIO" != ping && "$SCENARIO" != telemetry ]]; then
  printf 'ERROR: --scenario must be ping or telemetry\n' >&2
  exit 2
fi
[[ -n "$REQUESTER_DOMAIN" ]] || REQUESTER_DOMAIN="$DOMAIN"
[[ -n "$RESPONDER_DOMAIN" ]] || RESPONDER_DOMAIN="$DOMAIN"
if ! [[ "$COUNT" =~ ^[1-9][0-9]*$ && "$TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ \
  && "$DOMAIN" =~ ^[0-9]+$ && "$REQUESTER_DOMAIN" =~ ^[0-9]+$ && "$RESPONDER_DOMAIN" =~ ^[0-9]+$ ]]; then
  printf 'ERROR: count, timeout, and domain must be positive integers (domain may be zero)\n' >&2
  exit 2
fi

if [[ "${DDS_SKIP_BUILD:-0}" != 1 ]]; then
  "$ROOT_DIR/scripts/prepare-runtime.sh"
fi

if [[ "$SCENARIO" == ping ]]; then
  run_services=(ping-requester ping-responder)
  exit_service=ping-requester
else
  run_services=(telemetry-device telemetry-monitor)
  exit_service=telemetry-monitor
fi
export DDS_COUNT="$COUNT" DDS_TIMEOUT_SECONDS="$TIMEOUT_SECONDS" DDS_DOMAIN="$DOMAIN"
export DDS_APPLICATION_TIMEOUT_SECONDS=$(( TIMEOUT_SECONDS > 5 ? TIMEOUT_SECONDS - 5 : 1 ))
export DDS_REQUESTER_DOMAIN="$REQUESTER_DOMAIN" DDS_RESPONDER_DOMAIN="$RESPONDER_DOMAIN"
printf 'RUN_MODE=network DATA_TRANSPORT=RTPS/UDP scenario=%s requester_domain=%s responder_domain=%s count=%s timeout_seconds=%s application_timeout_seconds=%s\n' \
  "$SCENARIO" "$REQUESTER_DOMAIN" "$RESPONDER_DOMAIN" "$COUNT" "$TIMEOUT_SECONDS" "$DDS_APPLICATION_TIMEOUT_SECONDS"
docker compose --project-name java-opendds-network \
  -f "$ROOT_DIR/docker/compose.network.yml" \
  up --no-build --pull never --abort-on-container-exit --exit-code-from "$exit_service" "${run_services[@]}"
