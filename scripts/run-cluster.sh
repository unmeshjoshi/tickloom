#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/build"
CLUSTER_DIR="$BUILD_DIR/demo-cluster"
DATA_DIR="$BUILD_DIR/demo-data"
SERVER_JAR="$ROOT_DIR/build/libs/tickloom-server-all.jar"
CLIENT_JAR="$ROOT_DIR/build/libs/tickloom-client-all.jar"
JAVA_CMD="java"
# Fallback to JAVA_HOME
if [[ "$JAVA_CMD" == "java" && -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
fi
PIDS_FILE="$CLUSTER_DIR/pids.txt"
CONFIG_FILE="$CLUSTER_DIR/config.yaml"

NODES=5
BASE_PORT=18080
SLEEP_START=2
FACTORY_FQCN="com.tickloom.algorithms.replication.quorum.QuorumReplicaProcessFactory"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [--nodes N] [--base-port PORT] [--factory FQCN]

Starts N quorum replica servers on localhost and runs a demo client SET/GET.

Options:
  --nodes N       Number of servers to start (default: 5)
  --base-port P   Base TCP port for servers (default: 18080)
  --factory FQCN  Fully qualified ProcessFactory class (default: ${FACTORY_FQCN})
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --nodes)
      NODES="${2:-}"
      shift 2
      ;;
    --base-port)
      BASE_PORT="${2:-}"
      shift 2
      ;;
    --factory)
      FACTORY_FQCN="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

# Normalize common shorthand/mistyped factory names to the correct FQCN
if [[ "$FACTORY_FQCN" == "com.tickloom.QuorumReplicaProcessFactory" || "$FACTORY_FQCN" == "com.tickloom.QuorumProcessFactory" ]]; then
  echo "[run-cluster] Normalizing factory FQCN '$FACTORY_FQCN' -> 'com.tickloom.algorithms.replication.quorum.QuorumReplicaProcessFactory'" >&2
  FACTORY_FQCN="com.tickloom.algorithms.replication.quorum.QuorumReplicaProcessFactory"
fi

mkdir -p "$CLUSTER_DIR" "$DATA_DIR"
rm -f "$PIDS_FILE"

echo "Building shaded server and client jars..."
"$ROOT_DIR/gradlew" -q shadowJar shadowClientJar

if [[ ! -f "$SERVER_JAR" || ! -f "$CLIENT_JAR" ]]; then
  echo "Shaded jars not found. Build failed?" >&2
  exit 1
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
  log="$CLUSTER_DIR/$id.log"
  datadir="$DATA_DIR/$id"
  mkdir -p "$datadir"
  echo "  -> $id (log: $log)"
  (nohup "$JAVA_CMD" -jar "$SERVER_JAR" \
    --config "$CONFIG_FILE" \
    --id "$id" \
    --data "$datadir" \
    --timeout 10 \
    --factory "$FACTORY_FQCN" \
    > "$log" 2>&1 & echo $! >> "$PIDS_FILE") &
done

trap 'echo "Stopping servers..."; if [[ -f "$PIDS_FILE" ]]; then xargs -r kill < "$PIDS_FILE" 2>/dev/null || true; fi' EXIT

echo "Waiting $SLEEP_START s for servers to initialize..."
sleep "$SLEEP_START"

replicas_csv=""
for ((i=1; i<=NODES; i++)); do
  if [[ -z "$replicas_csv" ]]; then
    replicas_csv="server-$i"
  else
    replicas_csv="$replicas_csv,server-$i"
  fi
done

echo "Running client: SET then GET"
echo "  Replicas: $replicas_csv"

set -x
"$JAVA_CMD" -jar "$CLIENT_JAR" --config "$CONFIG_FILE" --id client-1 --replicas "$replicas_csv" --set mykey --value myvalue --deadline-ms 10000
"$JAVA_CMD" -jar "$CLIENT_JAR" --config "$CONFIG_FILE" --id client-1 --replicas "$replicas_csv" --get mykey --deadline-ms 10000
set +x

echo "Demo complete. Logs in $CLUSTER_DIR. Servers will now be stopped."


