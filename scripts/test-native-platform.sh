#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
platform_script="${repo_root}/scripts/native-platform.sh"
temp_dir="$(mktemp -d)"
trap 'rm -rf "${temp_dir}"' EXIT

cat > "${temp_dir}/uname" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  -s) printf '%s\n' "$TEST_OS" ;;
  -m) printf '%s\n' "$TEST_ARCH" ;;
  *) exit 2 ;;
esac
EOF
chmod +x "${temp_dir}/uname"

assert_platform() {
  local os="$1" architecture="$2" expected="$3" actual
  actual="$(PATH="${temp_dir}:$PATH" TEST_OS="$os" TEST_ARCH="$architecture" "$platform_script")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'expected %s for %s/%s; got %s\n' "$expected" "$os" "$architecture" "$actual" >&2
    exit 1
  fi
}

assert_platform Darwin arm64 macos-aarch64
assert_platform Darwin x86_64 macos-x86_64
assert_platform Linux aarch64 linux-aarch64
assert_platform Linux x86_64 linux-x86_64

if PATH="${temp_dir}:$PATH" TEST_OS=FreeBSD TEST_ARCH=amd64 "$platform_script" >/dev/null 2>&1; then
  echo 'unsupported platform was accepted' >&2
  exit 1
fi

echo 'PASS: native platform selection'
