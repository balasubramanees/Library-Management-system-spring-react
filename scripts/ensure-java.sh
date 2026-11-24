#!/usr/bin/env bash
#
# Prints a JAVA_HOME for a JDK 21+.
#
# Order: $JAVA_HOME (if 21+) -> `java` on PATH (if 21+) -> a JDK previously downloaded into
# .tools/ -> download the latest Eclipse Temurin 21 JDK (free OpenJDK build from adoptium.net)
# for this OS/CPU into .tools/ once, verified against the SHA-256 file Adoptium publishes next
# to every release asset.
#
# Supported: Linux (x64, aarch64), macOS (x64, aarch64), Windows via Git Bash / MSYS (x64, aarch64).
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/.tools"
JDK_DIR="$TOOLS/jdk21"
MIN_MAJOR=21

say() { echo "[start] $*" >&2; }

java_major() { # java executable -> major version (e.g. 21; 1 for Java 8)
  "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n1
}

is_recent_enough() {
  local major
  major="$(java_major "$1" 2>/dev/null || true)"
  [ -n "$major" ] && [ "$major" -ge "$MIN_MAJOR" ]
}

home_of() { # java executable -> its java.home
  "$1" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.home = //p' | tr -d '\r' | head -n1
}

find_java_in() { # dir -> path of bin/java(.exe) inside it, if any
  find "$1" -type f \( -path '*/bin/java' -o -path '*/bin/java.exe' \) 2>/dev/null | head -n1
}

# 1. JAVA_HOME already points to a suitable JDK?
for exe in "${JAVA_HOME:-/nonexistent}/bin/java" "${JAVA_HOME:-/nonexistent}/bin/java.exe"; do
  if [ -x "$exe" ] && is_recent_enough "$exe"; then
    echo "$JAVA_HOME"
    exit 0
  fi
done

# 2. `java` on PATH?
if command -v java >/dev/null 2>&1 && is_recent_enough java; then
  home="$(home_of java)"
  if [ -n "$home" ]; then
    command -v cygpath >/dev/null 2>&1 && home="$(cygpath -u "$home")"
    echo "$home"
    exit 0
  fi
fi

# 3. Previously downloaded JDK?
if [ -d "$JDK_DIR" ]; then
  exe="$(find_java_in "$JDK_DIR")"
  if [ -n "$exe" ] && is_recent_enough "$exe"; then
    dirname "$(dirname "$exe")"
    exit 0
  fi
fi

# 4. Download Eclipse Temurin 21.
case "$(uname -s)" in
  Linux*) os="linux"; ext="tar.gz" ;;
  Darwin*) os="mac"; ext="tar.gz" ;;
  MINGW*|MSYS*|CYGWIN*) os="windows"; ext="zip" ;;
  *) say "Unsupported OS $(uname -s): please install JDK ${MIN_MAJOR}+ manually."; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64) arch="x64" ;;
  aarch64|arm64) arch="aarch64" ;;
  *) say "Unsupported CPU $(uname -m): please install JDK ${MIN_MAJOR}+ manually."; exit 1 ;;
esac
command -v curl >/dev/null 2>&1 || { say "curl is needed to download a JDK; install JDK ${MIN_MAJOR}+ manually."; exit 1; }

say "JDK ${MIN_MAJOR}+ not found; downloading Eclipse Temurin ${MIN_MAJOR} (~200 MB) into .tools/ (one-time)..."
api="https://api.adoptium.net/v3/binary/latest/${MIN_MAJOR}/ga/${os}/${arch}/jdk/hotspot/normal/eclipse"
# The API redirects to the release asset on GitHub; Adoptium publishes <asset>.sha256.txt next to it.
url="$(curl -fsSL -o /dev/null -w '%{url_effective}' "$api")"
expected="$(curl -fsSL "${url}.sha256.txt" | awk '{print $1}')"
[ -n "$expected" ] || { say "Could not fetch the JDK checksum; install JDK ${MIN_MAJOR}+ manually."; exit 1; }

mkdir -p "$TOOLS"
archive="$TOOLS/temurin-jdk.${ext}"
curl -fL --progress-bar "$url" -o "$archive"

if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$archive" | awk '{print $1}')"
else
  actual="$(shasum -a 256 "$archive" | awk '{print $1}')"
fi
if [ "$expected" != "$actual" ]; then
  rm -f "$archive"
  say "JDK download failed checksum verification."
  exit 1
fi

staging="$TOOLS/jdk-extract"
rm -rf "$staging" "$JDK_DIR"
mkdir -p "$staging"
if [ "$ext" = "zip" ]; then
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$archive" -d "$staging"
  else
    powershell.exe -NoProfile -Command "Expand-Archive -Force -LiteralPath '$(cygpath -w "$archive")' -DestinationPath '$(cygpath -w "$staging")'"
  fi
else
  tar -xzf "$archive" -C "$staging"
fi
rm -f "$archive"
mv "$staging" "$JDK_DIR"

# Linux/Windows: jdk-21.x/bin/java; macOS: jdk-21.x/Contents/Home/bin/java
exe="$(find_java_in "$JDK_DIR")"
[ -n "$exe" ] || { say "Downloaded JDK has an unexpected layout; install JDK ${MIN_MAJOR}+ manually."; exit 1; }
chmod +x "$exe" 2>/dev/null || true
dirname "$(dirname "$exe")"
