#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="java-opendds-builder:25-3.34.0"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  docker build --file "$ROOT_DIR/docker/opendds-builder/Dockerfile" --tag "$IMAGE" "$ROOT_DIR"
fi

docker run --rm \
  --volume "$ROOT_DIR:/workspace" \
  --workdir /workspace \
  "$IMAGE" \
  mvn --batch-mode --no-transfer-progress -DskipTests package
