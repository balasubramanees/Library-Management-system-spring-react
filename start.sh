#!/usr/bin/env bash
#
# Library Management - build and launch the whole system with one command.
#
#   ./start.sh          Spring Boot microservices + React micro-frontends  -> http://localhost:3000
#   ./start.sh --mock   frontends only, against an in-memory mock gateway (no JVM needed)
#
# Every run is a clean start: all state is in memory, logs are recreated, nothing is carried over.
# Requirements: internet access on the first run. Java 21+, Node.js 20.11+ and Maven are used from
# JAVA_HOME/PATH when present, otherwise downloaded once into .tools/ (checksum-verified).
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

MODE="full"
case "${1:-}" in
  "") ;;
  --mock) MODE="mock" ;;
  -h|--help) sed -n '3,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
esac

log() { printf '\033[1;36m[start]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[start] %s\033[0m\n' "$*" >&2; exit 1; }

# --- prerequisites ----------------------------------------------------------------------------
# Node.js: use the installed one (20.11+) or download a private copy into .tools/ once.
NODE_DIR="$(scripts/ensure-node.sh)" || die "Node.js 20.11+ is required and could not be downloaded automatically. Install it from https://nodejs.org and retry."
export PATH="$NODE_DIR:$PATH"
log "Using Node.js $(node -v) from $NODE_DIR"

if [ "$MODE" = "full" ]; then
  # Java: use JAVA_HOME / PATH (21+) or download Eclipse Temurin 21 into .tools/ once.
  JAVA_HOME="$(scripts/ensure-java.sh)" || die "JDK 21+ is required and could not be downloaded automatically. Install it (e.g. https://adoptium.net) and retry."
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
  log "Using Java $(java -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n1) from $JAVA_HOME"
fi

# --- frontend dependencies -------------------------------------------------------------------
if ! (cd frontend && node -e "require.resolve('esbuild'); require.resolve('react'); require.resolve('react-dom/client')" >/dev/null 2>&1); then
  log "Installing frontend dependencies (npm install)..."
  (cd frontend && npm install --no-audit --no-fund --loglevel=error)
fi

# --- build -------------------------------------------------------------------------------------
# The backend is rebuilt only when a source/pom file is newer than the service jars.
backend_up_to_date() {
  local jars=(backend/user-service/target/user-service.jar backend/library-service/target/library-service.jar backend/gateway/target/gateway.jar)
  local oldest="" jar
  for jar in "${jars[@]}"; do
    [ -f "$jar" ] || return 1
    if [ -z "$oldest" ] || [ "$jar" -ot "$oldest" ]; then oldest="$jar"; fi
  done
  [ -z "$(find backend -type f \( -name '*.java' -o -name '*.yml' -o -name 'pom.xml' -o -name '*.imports' \) -not -path '*/target/*' -newer "$oldest" -print -quit)" ]
}

if [ "$MODE" = "full" ]; then
  if backend_up_to_date; then
    log "Backend is up to date (no source changes since the last build); skipping Maven build."
  else
    MVN="$(scripts/ensure-maven.sh)"
    log "Building backend services with Maven (the first run downloads dependencies and can take a few minutes)..."
    "$MVN" -B -q -f backend/pom.xml -DskipTests package || die "Backend build failed. Run '$MVN -f backend/pom.xml package' to see details."
  fi
fi

log "Building micro-frontends..."
(cd frontend && node scripts/build.mjs)

# --- run -----------------------------------------------------------------------------------------
rm -rf logs && mkdir -p logs
if [ "$MODE" = "mock" ]; then
  log "Starting frontends with the mock gateway (no JVM)..."
  exec node frontend/scripts/dev-mock.mjs
fi
exec node scripts/start-all.mjs
