#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

require_services() {
  local compose_file="$1"
  shift
  local services
  services="$(docker compose -f "$compose_file" config --services)" || {
    printf 'ERROR: invalid or missing Compose configuration: %s\n' "$compose_file" >&2
    return 1
  }
  local service
  for service in "$@"; do
    if ! grep -Fxq "$service" <<<"$services"; then
      printf 'ERROR: %s does not define required service %s\n' "$compose_file" "$service" >&2
      return 1
    fi
  done
}

require_ping_output() {
  local label="$1"
  local expected_transport="$2"
  shift 2
  local output
  if ! output="$("$@" 2>&1)"; then
    printf '%s\n' "$output" >&2
    printf 'ERROR: %s failed\n' "$label" >&2
    return 1
  fi
  printf '%s\n' "$output"
  if ! grep -Fq "$expected_transport" <<<"$output"; then
    printf 'ERROR: %s did not report selected transport: %s\n' "$label" "$expected_transport" >&2
    return 1
  fi
  if ! grep -Fq 'PING_SUMMARY status=OK received=10 expected=10' <<<"$output"; then
    printf 'ERROR: %s did not report exactly ten successful replies\n' "$label" >&2
    return 1
  fi
}

require_domain_mismatch() {
  local output
  if output="$(./scripts/run-network.sh --scenario ping --count 1 --timeout-seconds 3 \
      --requester-domain 43 --responder-domain 42 2>&1)"; then
    printf '%s\n' "$output" >&2
    printf '%s\n' 'ERROR: network domain mismatch unexpectedly succeeded' >&2
    return 1
  fi
  printf '%s\n' "$output"
  grep -Fq 'PING_SUMMARY status=TIMEOUT received=0 expected=1' <<<"$output" || {
    printf '%s\n' 'ERROR: domain mismatch did not report the expected ping timeout' >&2
    return 1
  }
}

require_services docker/compose.shared-memory.yml ping-lab telemetry-lab
require_services docker/compose.network.yml ping-requester ping-responder telemetry-device telemetry-monitor

./scripts/prepare-runtime.sh
export DDS_SKIP_BUILD=1

require_ping_output "shared-memory ping" \
  "RUN_MODE=shared-memory DATA_TRANSPORT=shmem" \
  ./scripts/run-shared-memory.sh --scenario ping --count 10 --timeout-seconds 30
require_ping_output "network ping" \
  "RUN_MODE=network DATA_TRANSPORT=RTPS/UDP" \
  ./scripts/run-network.sh --scenario ping --count 10 --timeout-seconds 30
require_domain_mismatch

printf '%s\n' 'SMOKE_TEST status=OK scenarios=ping transports=shmem,rtps_udp'
