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

ini_value() {
  local config_file="$1"
  local section="$2"
  local key="$3"
  awk -F= -v wanted_section="$section" -v wanted_key="$key" '
    {
      line = $0
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", line)
      if (line == "[" wanted_section "]") {
        in_section = 1
        next
      }
      if (line ~ /^\[/) {
        in_section = 0
      }
      if (in_section) {
        candidate = $1
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", candidate)
        if (candidate == wanted_key) {
          value = substr($0, index($0, "=") + 1)
          gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
          print value
          exit
        }
      }
    }
  ' "$config_file"
}

require_transport_config() {
  local config_file="$1"
  local expected_config="$2"
  local expected_instance="$3"
  local expected_type="$4"
  local selected_config selected_instance selected_type
  selected_config="$(ini_value "$config_file" common DCPSGlobalTransportConfig)"
  selected_instance="$(ini_value "$config_file" "config/$selected_config" transports)"
  selected_type="$(ini_value "$config_file" "transport/$selected_instance" transport_type)"
  if [[ "$selected_config" != "$expected_config" || "$selected_instance" != "$expected_instance" \
      || "$selected_type" != "$expected_type" ]]; then
    printf 'ERROR: %s selects config=%s instance=%s transport_type=%s; expected %s/%s/%s\n' \
      "$config_file" "$selected_config" "$selected_instance" "$selected_type" \
      "$expected_config" "$expected_instance" "$expected_type" >&2
    return 1
  fi
}

require_ini_value() {
  local config_file="$1"
  local section="$2"
  local key="$3"
  local expected="$4"
  local actual
  actual="$(ini_value "$config_file" "$section" "$key")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'ERROR: %s [%s] %s=%s; expected %s\n' \
      "$config_file" "$section" "$key" "$actual" "$expected" >&2
    return 1
  fi
}

compose_service_environment_value() {
  local compose_file="$1"
  local service="$2"
  local key="$3"
  docker compose -f "$compose_file" config | awk -v wanted_service="$service" -v wanted_key="$key" '
    $0 == "  " wanted_service ":" {
      in_service = 1
      in_environment = 0
      next
    }
    in_service && $0 ~ /^  [^ ]/ {
      in_service = 0
      in_environment = 0
    }
    in_service && $0 == "    environment:" {
      in_environment = 1
      next
    }
    in_environment && $0 ~ /^    [^ ]/ {
      in_environment = 0
    }
    in_environment {
      line = $0
      sub(/^      /, "", line)
      separator = index(line, ":")
      candidate = substr(line, 1, separator - 1)
      if (candidate == wanted_key) {
        value = substr(line, separator + 1)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        gsub(/^"|"$/, "", value)
        print value
      }
    }
  '
}

require_service_environment() {
  local compose_file="$1"
  local service="$2"
  local key="$3"
  local expected="$4"
  local actual
  actual="$(compose_service_environment_value "$compose_file" "$service" "$key")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'ERROR: %s service %s has %s=%s; expected %s\n' \
      "$compose_file" "$service" "$key" "$actual" "$expected" >&2
    return 1
  fi
}

require_ping_output() {
  local label="$1"
  local expected_transport="$2"
  local expected_domain="$3"
  shift 3
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
  if [[ -n "$expected_domain" ]] && ! grep -Fq "PING_READY domain=$expected_domain" <<<"$output"; then
    printf 'ERROR: %s did not run the requester in domain %s\n' "$label" "$expected_domain" >&2
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
for role in ping-requester ping-responder telemetry-device telemetry-monitor; do
  require_service_environment docker/compose.network.yml "$role" DDS_CONFIG_FILE \
    "/workspace/docker/config/network-rtps-$role.ini"
done
require_transport_config docker/config/shared-memory.ini shared_memory shared_memory_data shmem
for role in ping-requester ping-responder telemetry-device telemetry-monitor; do
  config_file="docker/config/network-rtps-$role.ini"
  require_transport_config "$config_file" network_rtps rtps_udp_data rtps_udp
  require_ini_value "$config_file" common DCPSDefaultDiscovery network_rtps_discovery
  require_ini_value "$config_file" rtps_discovery/network_rtps_discovery SedpMulticast 0
  require_ini_value "$config_file" rtps_discovery/network_rtps_discovery SpdpLocalAddress "$role:17910"
done
require_ini_value docker/config/network-rtps-ping-requester.ini \
  rtps_discovery/network_rtps_discovery SpdpSendAddrs ping-requester:17910,ping-responder:17910
require_ini_value docker/config/network-rtps-ping-responder.ini \
  rtps_discovery/network_rtps_discovery SpdpSendAddrs ping-requester:17910,ping-responder:17910
require_ini_value docker/config/network-rtps-telemetry-device.ini \
  rtps_discovery/network_rtps_discovery SpdpSendAddrs telemetry-device:17910,telemetry-monitor:17910
require_ini_value docker/config/network-rtps-telemetry-monitor.ini \
  rtps_discovery/network_rtps_discovery SpdpSendAddrs telemetry-device:17910,telemetry-monitor:17910

./scripts/prepare-runtime.sh
export DDS_SKIP_BUILD=1

require_ping_output "shared-memory ping" \
  "RUN_MODE=shared-memory DATA_TRANSPORT=shmem" \
  "" \
  ./scripts/run-shared-memory.sh --scenario ping --count 10 --timeout-seconds 30
require_ping_output "network ping" \
  "RUN_MODE=network DATA_TRANSPORT=RTPS/UDP" \
  42 \
  ./scripts/run-network.sh --scenario ping --count 10 --timeout-seconds 30
require_ping_output "network non-default-domain ping" \
  "RUN_MODE=network DATA_TRANSPORT=RTPS/UDP" \
  7 \
  ./scripts/run-network.sh --scenario ping --count 10 --timeout-seconds 30 --domain 7
require_domain_mismatch

printf '%s\n' 'SMOKE_TEST status=OK scenarios=ping transports=shmem,rtps_udp domains=42,7,mismatch'
