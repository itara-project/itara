#!/usr/bin/env bash
# ci/run-demo-http.sh
# Runs the demo in HTTP topology across two JVMs.
#
# Sequence:
#   1. Start calculator JVM in background (inbound HTTP listener on port 8081)
#   2. Wait until it signals readiness via log output
#   3. Start gateway JVM in foreground (calls calculator over HTTP)
#   4. If gateway exits 0, the test passes
#   5. Kill calculator JVM in cleanup regardless of outcome

set -euo pipefail

AGENT=itara-agent/target/itara-agent-1.0-SNAPSHOT.jar
COMMON=itara-common/target/itara-common-1.0-SNAPSHOT.jar
CALC_API=itara-demo/calculator-api/target/calculator-api-1.0-SNAPSHOT.jar
CALC_IMPL=itara-demo/calculator-component/target/calculator-component-1.0-SNAPSHOT.jar
GW_API=itara-demo/gateway-api/target/gateway-api-1.0-SNAPSHOT.jar
GW_IMPL=itara-demo/gateway-component/target/gateway-component-1.0-SNAPSHOT.jar
CALC_CONFIG=itara-demo/wiring-http-calculator.yaml
GW_CONFIG=itara-demo/wiring-http-gateway.yaml

CALC_LOG=/tmp/itara-calculator.log
CALC_PID=""

# ── Setup: build libs dir with transport and observability jar ───────────────────────────────
LIBS_DIR=itara-libs
mkdir -p "$LIBS_DIR"
cp itara-transport-http/target/itara-transport-http-*.jar "$LIBS_DIR/"
cp itara-observability-logging/target/itara-observability-logging-*.jar "$LIBS_DIR/"
echo "[CI] Libs dir prepared: $LIBS_DIR"
ls -l "$LIBS_DIR"

# Always kill the calculator JVM on exit, success or failure
cleanup() {
  if [ -n "$CALC_PID" ] && kill -0 "$CALC_PID" 2>/dev/null; then
    echo "[CI] Stopping calculator JVM (pid $CALC_PID)..."
    kill "$CALC_PID" 2>/dev/null || true
    wait "$CALC_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# ── Step 1: Start calculator JVM in background ─────────────────────────────
echo "[CI] Starting calculator JVM..."

java \
  -Ditara.lib.dir=$LIBS_DIR \
  -Ditara.config=$CALC_CONFIG \
  -javaagent:$AGENT \
  -cp "$COMMON:$CALC_API:$CALC_IMPL" \
  io.itara.runtime.ItaraMain \
  > "$CALC_LOG" 2>&1 &

CALC_PID=$!
echo "[CI] Calculator JVM started with pid $CALC_PID"

# ── Step 2: Wait for the calculator to be ready ────────────────────────────
# The agent prints "[Itara] Agent ready." when startup is complete.
# Poll the log file for up to 30 seconds.

echo "[CI] Waiting for calculator to be ready..."

READY=false
for i in $(seq 1 30); do
  if grep -q "\[Itara\] Agent ready" "$CALC_LOG" 2>/dev/null; then
    READY=true
    break
  fi
  # Also check if the process died unexpectedly
  if ! kill -0 "$CALC_PID" 2>/dev/null; then
    echo "[CI] ERROR: Calculator JVM died unexpectedly. Log:"
    cat "$CALC_LOG"
    exit 1
  fi
  sleep 1
done

if [ "$READY" = false ]; then
  echo "[CI] ERROR: Calculator JVM did not become ready within 30 seconds. Log:"
  cat "$CALC_LOG"
  exit 1
fi

echo "[CI] Calculator is ready."

# ── Step 3: Run gateway JVM in foreground ─────────────────────────────────
echo "[CI] Starting gateway JVM..."

java \
  -Ditara.lib.dir=$LIBS_DIR \
  -Ditara.config=$GW_CONFIG \
  -javaagent:$AGENT \
  -cp "$COMMON:$CALC_API:$GW_API:$GW_IMPL" \
  demo.gateway.component.DemoMain

echo "[CI] HTTP topology demo completed successfully."
# cleanup() kills the calculator on exit
