#!/usr/bin/env bash
# ci/run-demo-direct.sh
# Runs the demo in direct (colocated) topology.
# Both gateway and calculator run in the same JVM.
# The gateway main exits after making a call — if it exits 0, the test passes.

set -euo pipefail

AGENT=itara-agent/target/itara-agent-1.0-SNAPSHOT.jar
COMMON=itara-common/target/itara-common-1.0-SNAPSHOT.jar
#OTEL=itara-observability-otel/target/itara-observability-otel-1.0-SNAPSHOT.jar
CALC_API=itara-demo/calculator-api/target/calculator-api-1.0-SNAPSHOT.jar
CALC_IMPL=itara-demo/calculator-component/target/calculator-component-1.0-SNAPSHOT.jar
GW_API=itara-demo/gateway-api/target/gateway-api-1.0-SNAPSHOT.jar
GW_IMPL=itara-demo/gateway-component/target/gateway-component-1.0-SNAPSHOT.jar
CONFIG=itara-demo/wiring-direct.yaml

# ── Setup: build libs dir with transport, serializer and observability jars ───────────────────────────────
LIBS_DIR=itara-libs
mkdir -p "$LIBS_DIR"
cp itara-transport-http/target/itara-transport-http-*.jar "$LIBS_DIR/"
cp itara-observability-logging/target/itara-observability-logging-*.jar "$LIBS_DIR/"
cp itara-serializer-json/target/itara-serializer-json-*.jar "$LIBS_DIR/"
#cp itara-observability-otel/target/itara-observability-otel-*.jar "$LIBS_DIR/"
echo "[CI] Libs dir prepared: $LIBS_DIR"
ls -l "$LIBS_DIR"

echo "[CI] Starting direct topology demo..."

java \
  -Ditara.lib.dir=$LIBS_DIR \
  -Ditara.config=$CONFIG \
  -javaagent:$AGENT \
  -cp "$COMMON:$CALC_API:$CALC_IMPL:$GW_API:$GW_IMPL" \
  demo.gateway.component.DemoMain

echo "[CI] Direct topology demo completed successfully."
