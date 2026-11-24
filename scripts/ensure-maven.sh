#!/usr/bin/env bash
#
# Prints the path of a usable Maven executable. Uses `mvn` from PATH when it is recent enough,
# otherwise downloads Apache Maven once into .tools/ (checksum-verified). This replaces the
# Maven Wrapper, whose jar would be a binary file in the repository.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="3.9.9"
TOOLS="$ROOT/.tools"
HOME_DIR="$TOOLS/apache-maven-$VERSION"
URL="https://archive.apache.org/dist/maven/maven-3/$VERSION/binaries/apache-maven-$VERSION-bin.tar.gz"

if command -v mvn >/dev/null 2>&1; then
  current="$(mvn -v 2>/dev/null | sed -n 's/^Apache Maven \([0-9.]*\).*/\1/p' | head -n1)"
  # Spring Boot 3.5 needs Maven 3.6.3+
  if [ -n "$current" ] && [ "$(printf '%s\n3.6.3\n' "$current" | sort -V | head -n1)" = "3.6.3" ]; then
    command -v mvn
    exit 0
  fi
fi

if [ ! -x "$HOME_DIR/bin/mvn" ]; then
  echo "[start] Maven not found; downloading Apache Maven $VERSION into .tools/ ..." >&2
  mkdir -p "$TOOLS"
  archive="$TOOLS/maven.tar.gz"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$URL" -o "$archive"
    expected="$(curl -fsSL "$URL.sha512" | awk '{print $1}')"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$URL" -O "$archive"
    expected="$(wget -qO- "$URL.sha512" | awk '{print $1}')"
  else
    echo "[start] Neither curl nor wget is available to download Maven. Install Maven 3.6.3+ and retry." >&2
    exit 1
  fi
  if command -v sha512sum >/dev/null 2>&1; then
    actual="$(sha512sum "$archive" | awk '{print $1}')"
  else
    actual="$(shasum -a 512 "$archive" | awk '{print $1}')"
  fi
  if [ "$expected" != "$actual" ]; then
    rm -f "$archive"
    echo "[start] Maven download failed checksum verification." >&2
    exit 1
  fi
  tar -xzf "$archive" -C "$TOOLS"
  rm -f "$archive"
fi
echo "$HOME_DIR/bin/mvn"
