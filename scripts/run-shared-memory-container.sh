#!/usr/bin/env bash
set -uo pipefail

SCENARIO="${DDS_SCENARIO:-${1:-}}"
DOMAIN="${DDS_DOMAIN:-42}"
COUNT="${DDS_COUNT:-10}"
TIMEOUT_SECONDS="${DDS_TIMEOUT_SECONDS:-30}"
APPLICATION_TIMEOUT_SECONDS="${DDS_APPLICATION_TIMEOUT_SECONDS:-25}"
WARMUP_COUNT="${DDS_WARMUP_COUNT:-0}"
TELEMETRY_INTERVAL_MS="${DDS_TELEMETRY_INTERVAL_MS:-25}"
STALE_AFTER_MS="${DDS_STALE_AFTER_MS:-100}"
RELIABILITY="${DDS_RELIABILITY:-reliable}"
HISTORY_DEPTH="${DDS_HISTORY_DEPTH:-10}"
PRIMARY_PID=""
SECONDARY_PID=""
REPO_PID=""
INFO_REPO_DIR="/tmp/opendds-shared"
INFO_REPO_IOR="$INFO_REPO_DIR/repo.ior"
INFO_REPO_LOG="$INFO_REPO_DIR/repo.log"

stop_process() {
  local variable_name="$1"
  local process_status=0
  local process_pid="${!variable_name}"
  if [[ -n "$process_pid" ]] && kill -0 "$process_pid" 2>/dev/null; then
    kill -TERM "$process_pid" 2>/dev/null || true
    wait "$process_pid" || process_status=$?
  elif [[ -n "$process_pid" ]]; then
    wait "$process_pid" || process_status=$?
  fi
  printf -v "$variable_name" '%s' ""
  return "$process_status"
}

wait_process() {
  local variable_name="$1"
  local process_status=0
  local process_pid="${!variable_name}"
  if [[ -n "$process_pid" ]]; then
    wait "$process_pid" || process_status=$?
  fi
  printf -v "$variable_name" '%s' ""
  return "$process_status"
}

stop_repo() {
  local repo_status=0
  if [[ -n "$REPO_PID" ]] && kill -0 "$REPO_PID" 2>/dev/null; then
    kill -TERM "$REPO_PID" 2>/dev/null || true
    wait "$REPO_PID" || repo_status=$?
  elif [[ -n "$REPO_PID" ]]; then
    wait "$REPO_PID" || repo_status=$?
  fi
  REPO_PID=""
  return "$repo_status"
}

on_signal() {
  local exit_status="$1"
  trap - INT TERM
  stop_process PRIMARY_PID || true
  stop_process SECONDARY_PID || true
  stop_repo || true
  exit "$exit_status"
}

trap 'on_signal 130' INT
trap 'on_signal 143' TERM

run_role() {
  scripts/run-dds-java.sh "$@"
}

start_repo() {
  mkdir -p "$INFO_REPO_DIR"
  rm -f "$INFO_REPO_IOR" "$INFO_REPO_LOG"
  "$DDS_ROOT/bin/DCPSInfoRepo" -NOBITS -o "$INFO_REPO_IOR" >"$INFO_REPO_LOG" 2>&1 &
  REPO_PID=$!
  for _ in {1..100}; do
    if [[ -s "$INFO_REPO_IOR" ]]; then
      printf 'DISCOVERY_SERVICE=DCPSInfoRepo ior=%s\n' "$INFO_REPO_IOR"
      return 0
    fi
    if ! kill -0 "$REPO_PID" 2>/dev/null; then
      cat "$INFO_REPO_LOG" >&2
      printf 'ERROR: DCPSInfoRepo exited before publishing its IOR\n' >&2
      wait "$REPO_PID" || true
      REPO_PID=""
      return 1
    fi
    sleep 0.1
  done
  cat "$INFO_REPO_LOG" >&2
  printf 'ERROR: timed out waiting for DCPSInfoRepo IOR\n' >&2
  stop_repo || true
  return 1
}

start_repo || exit $?

case "$SCENARIO" in
  ping)
    run_role ping-responder shared-memory shmem \
      io.github.tmejs.opendds.ping.PingResponder \
      --domain "$DOMAIN" --count 0 &
    SECONDARY_PID=$!
    run_role ping-requester shared-memory shmem \
      io.github.tmejs.opendds.ping.PingRequester \
      --domain "$DOMAIN" --count "$COUNT" --warmup-count "$WARMUP_COUNT" \
      --timeout-seconds "$APPLICATION_TIMEOUT_SECONDS" &
    PRIMARY_PID=$!
    wait_process PRIMARY_PID
    requester_status=$?
    stop_process SECONDARY_PID
    responder_status=$?
    if (( requester_status == 0 && responder_status != 0 && responder_status != 143 )); then
      stop_repo || true
      exit "$responder_status"
    fi
    stop_repo || true
    exit "$requester_status"
    ;;
  telemetry)
    timeout --signal=TERM "${TIMEOUT_SECONDS}s" \
      scripts/run-dds-java.sh telemetry-monitor shared-memory shmem \
      io.github.tmejs.opendds.telemetry.TelemetryMonitor \
      --domain "$DOMAIN" --count "$COUNT" --stale-after-ms "$STALE_AFTER_MS" \
      --reliability "$RELIABILITY" --history-depth "$HISTORY_DEPTH" &
    SECONDARY_PID=$!
    run_role telemetry-device shared-memory shmem \
      io.github.tmejs.opendds.telemetry.TelemetryDevice \
      --domain "$DOMAIN" --device-id device-1 --interval-ms "$TELEMETRY_INTERVAL_MS" \
      --count "$COUNT" --timeout-seconds "$APPLICATION_TIMEOUT_SECONDS" \
      --reliability "$RELIABILITY" --history-depth "$HISTORY_DEPTH" &
    PRIMARY_PID=$!
    wait_process PRIMARY_PID
    device_status=$?
    if (( device_status != 0 )); then
      stop_process SECONDARY_PID || true
      stop_repo || true
      exit "$device_status"
    fi
    wait_process SECONDARY_PID
    monitor_status=$?
    stop_repo || true
    exit "$monitor_status"
    ;;
  *)
    printf 'ERROR: expected DDS_SCENARIO=ping or telemetry, got %s\n' "$SCENARIO" >&2
    stop_repo || true
    exit 2
    ;;
esac
