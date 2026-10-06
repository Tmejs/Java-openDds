#!/usr/bin/env bash
set -uo pipefail

CHILD_PID=""

stop_child() {
  local exit_status="$1"
  trap - INT TERM
  if [[ -n "$CHILD_PID" ]] && kill -0 "$CHILD_PID" 2>/dev/null; then
    kill -TERM "$CHILD_PID" 2>/dev/null || true
    wait "$CHILD_PID" || true
  fi
  exit "$exit_status"
}

trap 'stop_child 130' INT
trap 'stop_child 143' TERM

/workspace/scripts/run-dds-java.sh "$@" &
CHILD_PID=$!
wait "$CHILD_PID"
application_status=$?
CHILD_PID=""
if (( application_status != 0 )); then
  exit "$application_status"
fi

printf 'ROLE_COMPLETE status=OK waiting_for=telemetry-monitor\n'
tail -f /dev/null &
CHILD_PID=$!
wait "$CHILD_PID"
