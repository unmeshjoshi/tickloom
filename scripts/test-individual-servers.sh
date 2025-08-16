#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/build"
CLUSTER_DIR="$BUILD_DIR/demo-cluster"
DATA_DIR="$BUILD_DIR/demo-data"
SERVER_JAR="$ROOT_DIR/build/libs/tickloom-server-all.jar"
CLIENT_JAR="$ROOT_DIR/build/libs/tickloom-client-all.jar"
JAVA_CMD="java"
# Prefer macOS JDK 21 if available
if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  J21_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
  if [[ -n "$J21_HOME" && -x "$J21_HOME/bin/java" ]]; then
    JAVA_CMD="$J21_HOME/bin/java"
  fi
fi
# Fallback to JAVA_HOME
if [[ "$JAVA_CMD" == "java" && -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
fi
PIDS_FILE="$CLUSTER_DIR/pids.txt"
CONFIG_FILE="$CLUSTER_DIR/config.yaml"

NODES=5
BASE_PORT=20080
SLEEP_START=2
KEY="indiv-key"
VALUE="indiv-value"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [--nodes N] [--base-port PORT]

Starts a cluster and verifies GET from each server individually after a SET.

Options:
  --nodes N       Number of servers to start (default: 5)
  --base-port P   Base TCP port for servers (default: 20080)
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --nodes)
      NODES="${2:-}"
      shift 2;;
    --base-port)
      BASE_PORT="${2:-}"
      shift 2;;
    -h|--help)
      usage; exit 0;;
    *) echo "Unknown option: $1" >&2; usage; exit 1;;
  esac
done

mkdir -p "$CLUSTER_DIR" "$DATA_DIR"
rm -f "$PIDS_FILE"

if [[ ! -f "$SERVER_JAR" || ! -f "$CLIENT_JAR" ]]; then
  echo "Building shaded jars..."
  "$ROOT_DIR/gradlew" -q shadowJar shadowClientJar
fi

echo "Generating config: $CONFIG_FILE (nodes=$NODES basePort=$BASE_PORT)"
{
  echo "processConfigs:"
  for ((i=1; i<=NODES; i++)); do
    port=$((BASE_PORT + i - 1))
    echo "  - processId: \"server-$i\""
    echo "    ip: \"127.0.0.1\""
    echo "    port: $port"
  done
} > "$CONFIG_FILE"

echo "Starting servers..."
for ((i=1; i<=NODES; i++)); do
  id="server-$i"
  log="$CLUSTER_DIR/$id.test.log"
  datadir="$DATA_DIR/$id"
  mkdir -p "$datadir"
  (nohup "$JAVA_CMD" -jar "$SERVER_JAR" \
    --config "$CONFIG_FILE" \
    --id "$id" \
    --data "$datadir" \
    --timeout 10 \
    > "$log" 2>&1 & echo $! >> "$PIDS_FILE") &
done

cleanup() {
  echo "Stopping servers..."
  if [[ -f "$PIDS_FILE" ]]; then xargs -r kill < "$PIDS_FILE" 2>/dev/null || true; fi
}
trap cleanup EXIT

echo "Waiting $SLEEP_START s for servers to initialize..."
sleep "$SLEEP_START"

# Build replicas CSV for initial SET (use all; primary is server-1)
replicas_csv=""
for ((i=1; i<=NODES; i++)); do
  if [[ -z "$replicas_csv" ]]; then replicas_csv="server-$i"; else replicas_csv="$replicas_csv,server-$i"; fi
done

echo "SET ($KEY -> $VALUE) via server-1"
"$JAVA_CMD" -jar "$CLIENT_JAR" --config "$CONFIG_FILE" --id client-1 --replicas "$replicas_csv" --set "$KEY" --value "$VALUE" --deadline-ms 10000 > "$CLUSTER_DIR/client-set.out"

status=0
for ((i=1; i<=NODES; i++)); do
  primary="server-$i"
  echo "GET $KEY via $primary ..."
  raw_output=$("$JAVA_CMD" -jar "$CLIENT_JAR" --config "$CONFIG_FILE" --id client-1 --replicas "$primary" --get "$KEY" --deadline-ms 10000 2>&1)
  if printf "%s\n" "$raw_output" | grep -Fxq "$VALUE"; then
    echo "[$primary] OK"
  else
    # Fallback: show last non-empty line for debugging
    last_line=$(printf "%s\n" "$raw_output" | awk 'NF{p=$0} END{print p}')
    echo "[$primary] FAIL (got: $last_line)" >&2
    status=1
  fi
done

exit $status


