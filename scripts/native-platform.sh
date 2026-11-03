#!/usr/bin/env bash
set -Eeuo pipefail

case "$(uname -s)" in
  Darwin) os=macos ;;
  Linux) os=linux ;;
  *) echo "error: unsupported native operating system: $(uname -s)" >&2; exit 1 ;;
esac

case "$(uname -m)" in
  arm64|aarch64) architecture=aarch64 ;;
  x86_64|amd64) architecture=x86_64 ;;
  *) echo "error: unsupported native architecture: $(uname -m)" >&2; exit 1 ;;
esac

printf '%s-%s\n' "$os" "$architecture"
