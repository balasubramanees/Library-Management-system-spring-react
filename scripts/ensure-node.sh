#!/usr/bin/env bash
#
# Prints the directory that contains usable `node` and `npm` executables.
#
# Uses Node.js from PATH when it is 20.11+; otherwise downloads the latest Node.js 22 LTS
# build for this OS/CPU once into .tools/ (verified against nodejs.org's SHASUMS256.txt).
# This keeps `./start.sh` working on machines that only have Java installed.
#
# Supported: Linux (x64, arm64), macOS (x64, arm64), Windows via Git Bash / MSYS (x64, arm64).
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/.tools"
DIST="https://nodejs.org/dist/latest-v22.x"
MIN_MAJOR=20
MIN_MINOR=11

say() { echo "[start] $*" >&2; }

node_is_recent_enough() {
  local version major minor
  version="$("$1" -p 'process.versions.node' 2>/dev/null)" || return 1
  major="${version%%.*}"
  minor="$(echo "$version" | cut -d. -f2)"
  [ "$major" -gt "$MIN_MAJOR" ] || { [ "$major" -eq "$MIN_MAJOR" ] && [ "$minor" -ge "$MIN_MINOR" ]; }
}

# 1. Node already on PATH?
if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && node_is_recent_enough node; then
  dirname "$(command -v node)"
  exit 0
fi

# 2. Previously downloaded copy?
for candidate in "$TOOLS"/node-v*/bin "$TOOLS"/node-v*-win-*; do
  if [ -x "$candidate/node" ] || [ -x "$candidate/node.exe" ]; then
    node_exe="$candidate/node"; [ -x "$node_exe" ] || node_exe="$candidate/node.exe"
    if node_is_recent_enough "$node_exe"; then
      echo "$candidate"
      exit 0
    fi
  fi
done

# 3. Download.
case "$(uname -s)" in
  Linux*) os="linux"; ext="tar.xz" ;;
  Darwin*) os="darwin"; ext="tar.gz" ;;
  MINGW*|MSYS*|CYGWIN*) os="win"; ext="zip" ;;
  *) say "Unsupported OS $(uname -s): please install Node.js ${MIN_MAJOR}.${MIN_MINOR}+ manually."; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64) arch="x64" ;;
  aarch64|arm64) arch="arm64" ;;
  *) say "Unsupported CPU $(uname -m): please install Node.js ${MIN_MAJOR}.${MIN_MINOR}+ manually."; exit 1 ;;
esac

fetch() { # url -> stdout
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$1"
  elif command -v wget >/dev/null 2>&1; then wget -qO- "$1"
  else say "Neither curl nor wget is available; install Node.js manually."; exit 1
  fi
}

say "Node.js ${MIN_MAJOR}.${MIN_MINOR}+ not found; downloading Node.js 22 LTS into .tools/ (one-time)..."
mkdir -p "$TOOLS"
shasums="$(fetch "$DIST/SHASUMS256.txt")"
line="$(echo "$shasums" | grep -E " node-v[0-9.]+-${os}-${arch}\.${ext}\$" | head -n1 || true)"
if [ -z "$line" ]; then
  say "No Node.js build for ${os}-${arch} found at $DIST; install Node.js manually."
  exit 1
fi
expected="$(echo "$line" | awk '{print $1}')"
file="$(echo "$line" | awk '{print $2}')"
archive="$TOOLS/$file"
fetch "$DIST/$file" > "$archive"

if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$archive" | awk '{print $1}')"
else
  actual="$(shasum -a 256 "$archive" | awk '{print $1}')"
fi
if [ "$expected" != "$actual" ]; then
  rm -f "$archive"
  say "Node.js download failed checksum verification."
  exit 1
fi

case "$ext" in
  tar.xz) tar -xJf "$archive" -C "$TOOLS" ;;
  tar.gz) tar -xzf "$archive" -C "$TOOLS" ;;
  zip)
    if command -v unzip >/dev/null 2>&1; then
      unzip -q -o "$archive" -d "$TOOLS"
    else
      powershell.exe -NoProfile -Command "Expand-Archive -Force -LiteralPath '$(cygpath -w "$archive")' -DestinationPath '$(cygpath -w "$TOOLS")'"
    fi
    ;;
esac
rm -f "$archive"

folder="$TOOLS/${file%.$ext}"
# Linux/macOS archives keep executables in bin/; the Windows zip has them at the top level.
if [ -d "$folder/bin" ]; then echo "$folder/bin"; else echo "$folder"; fi
