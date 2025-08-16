#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CLUSTER_DIR="$ROOT_DIR/build/demo-cluster"
PIDS_FILE="$CLUSTER_DIR/pids.txt"

kill_by_pattern() {
  local pattern="$1"
  local label="$2"
  echo "Searching for $label processes (pattern: $pattern)"

  local pids=""
  if command -v pgrep >/dev/null 2>&1; then
    pids="$(pgrep -f "$pattern" 2>/dev/null | tr '\n' ' ')"
  else
    pids="$(ps aux | grep -E "$pattern" | grep -v grep | awk '{print $2}' | tr '\n' ' ')"
  fi

  if [[ -z "$pids" ]]; then
    echo "No $label processes found."
    return
  fi

  echo "Found $(echo "$pids" | wc -w | awk '{print $1}') $label process(es): $pids"
  echo "Sending SIGTERM to $label..."
  kill $pids 2>/dev/null || true
  sleep 1

  # Re-check and SIGKILL leftovers
  if command -v pgrep >/dev/null 2>&1; then
    pids="$(pgrep -f "$pattern" 2>/dev/null | tr '\n' ' ')"
  else
    pids="$(ps aux | grep -E "$pattern" | grep -v grep | awk '{print $2}' | tr '\n' ' ')"
  fi

  if [[ -n "$pids" ]]; then
    echo "Force killing remaining $label process(es): $pids"
    kill -9 $pids 2>/dev/null || true
  fi
}

echo "Killing TickLoom server and client Java processes..."
kill_by_pattern "java.*tickloom-server-all\\.jar" "server"
kill_by_pattern "java.*tickloom-client-all\\.jar" "client"

# Also clean up any PIDs tracked by run scripts (best-effort)
if [[ -f "$PIDS_FILE" ]]; then
  echo "Also killing PIDs from $PIDS_FILE (best effort)"
  xargs -r kill < "$PIDS_FILE" 2>/dev/null || true
  rm -f "$PIDS_FILE"
fi

echo "Done."


