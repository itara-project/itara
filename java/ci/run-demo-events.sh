#!/usr/bin/env bash
# ci/run-demo-events.sh
# Runs the events demo across two JVMs connected via Kafka.
#
# Sequence:
#   1. Build libs dir with transport, serializer and observability jars
#   2. Start consumer JVM in background (Kafka listener)
#   3. Wait until it signals readiness via log output
#   4. Start producer JVM in background (HTTP inbound + Kafka outbound)
#   5. Wait until it signals readiness
#   6. Fire a curl to the producer's HTTP endpoint
#   7. Wait briefly for the consumer to process the message
#   8. Check consumer log for expected output
#   9. Exit 0 on success, 1 on any failure
#
# Prerequisite: Kafka must be available on localhost:9092
# In CI this is provided by the GitHub Actions kafka service container.

set -euo pipefail

AGENT=itara-agent/target/itara-agent-1.0-SNAPSHOT.jar
COMMON=itara-common/target/itara-common-1.0-SNAPSHOT.jar

EVENTS_API=itara-demo-events/order-event-api/target/order-event-api-1.0-SNAPSHOT.jar
PAYMENT_EVENTS_API=itara-demo-events/payment-event-api/target/payment-event-api-1.0-SNAPSHOT.jar
PRODUCER_API=itara-demo-events/order-producer-api/target/order-producer-api-1.0-SNAPSHOT.jar
PRODUCER_IMPL=itara-demo-events/order-producer-component/target/order-producer-component-1.0-SNAPSHOT.jar
CONSUMER_API=itara-demo-events/order-consumer-api/target/order-consumer-api-1.0-SNAPSHOT.jar
CONSUMER_IMPL=itara-demo-events/order-consumer/target/order-consumer-1.0-SNAPSHOT.jar

WIRING=itara-demo-events/wiring-events-local.yaml

CONSUMER_LOG=/tmp/itara-events-consumer.log
PRODUCER_LOG=/tmp/itara-events-producer.log
CONSUMER_PID=""
PRODUCER_PID=""

# ── Setup: libs dir ───────────────────────────────────────────────────────────

LIBS_DIR=itara-libs
mkdir -p "$LIBS_DIR"
cp itara-transport-http/target/itara-transport-http-*.jar      "$LIBS_DIR/"
cp itara-transport-kafka/target/itara-transport-kafka-*.jar    "$LIBS_DIR/"
cp itara-serializer-json/target/itara-serializer-json-*.jar    "$LIBS_DIR/"
cp itara-observability-logging/target/itara-observability-logging-*.jar "$LIBS_DIR/"
echo "[CI] Libs dir prepared: $LIBS_DIR"

META_DIR=itara-metafiles
mkdir -p "$META_DIR"
cp itara-transport-http/itara-transport-http.itara             "$META_DIR/"
cp itara-transport-kafka/itara-transport-kafka.itara           "$META_DIR/"
cp itara-serializer-json/itara-serializer-json.itara           "$META_DIR/"
cp itara-observability-logging/itara-observability-logging.itara "$META_DIR/"
cp itara-demo-events/order-event-api/order-event-api.itara     "$META_DIR/"
cp itara-demo-events/payment-event-api/payment-event-api.itara     "$META_DIR/"
cp itara-demo-events/order-producer-api/order-producer-api.itara "$META_DIR/"
cp itara-demo-events/order-producer-component/order-producer-component.itara "$META_DIR/"
cp itara-demo-events/order-consumer-api/order-consumer-api.itara "$META_DIR/"
cp itara-demo-events/order-consumer/order-consumer.itara "$META_DIR/"
echo "[CI] Meta dir prepared: $META_DIR"

# ── Cleanup ───────────────────────────────────────────────────────────────────

cleanup() {
    if [ -n "$PRODUCER_PID" ] && kill -0 "$PRODUCER_PID" 2>/dev/null; then
        echo "[CI] Stopping producer JVM (pid $PRODUCER_PID)..."
        kill "$PRODUCER_PID" 2>/dev/null || true
        wait "$PRODUCER_PID" 2>/dev/null || true
    fi
    if [ -n "$CONSUMER_PID" ] && kill -0 "$CONSUMER_PID" 2>/dev/null; then
        echo "[CI] Stopping consumer JVM (pid $CONSUMER_PID)..."
        kill "$CONSUMER_PID" 2>/dev/null || true
        wait "$CONSUMER_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ── Step 1: Start consumer JVM ────────────────────────────────────────────────

echo "[CI] Starting consumer JVM..."

java \
  -Ditara.lib.dir=$LIBS_DIR \
  -Ditara.config=$WIRING \
  -Ditara.nodes="orderConsumerNode" \
  -Ditara.metadata.dir=$META_DIR \
  -javaagent:$AGENT \
  -cp "$COMMON:$EVENTS_API:$PAYMENT_EVENTS_API:$CONSUMER_API:$CONSUMER_IMPL" \
  io.itara.runtime.ItaraMain \
  > "$CONSUMER_LOG" 2>&1 &

CONSUMER_PID=$!
echo "[CI] Consumer JVM started with pid $CONSUMER_PID"

# ── Step 2: Wait for consumer to be ready ────────────────────────────────────

echo "[CI] Waiting for consumer to be ready..."
READY=false
for i in $(seq 1 30); do
    if grep -q "\[Itara\] agent ready" "$CONSUMER_LOG" 2>/dev/null; then
        READY=true
        break
    fi
    if ! kill -0 "$CONSUMER_PID" 2>/dev/null; then
        echo "[CI] ERROR: Consumer JVM died unexpectedly. Log:"
        cat "$CONSUMER_LOG"
        exit 1
    fi
    sleep 1
done

if [ "$READY" = false ]; then
    echo "[CI] ERROR: Consumer JVM did not become ready within 30 seconds. Log:"
    cat "$CONSUMER_LOG"
    exit 1
fi
echo "[CI] Consumer is ready."

# ── Step 3: Start producer JVM ────────────────────────────────────────────────

echo "[CI] Starting producer JVM..."

java \
  -Ditara.lib.dir=$LIBS_DIR \
  -Ditara.config=$WIRING \
  -Ditara.nodes="orderProducerNode" \
  -Ditara.metadata.dir=$META_DIR \
  -javaagent:$AGENT \
  -cp "$COMMON:$EVENTS_API:$PAYMENT_EVENTS_API:$PRODUCER_API:$PRODUCER_IMPL" \
  io.itara.runtime.ItaraMain \
  > "$PRODUCER_LOG" 2>&1 &

PRODUCER_PID=$!
echo "[CI] Producer JVM started with pid $PRODUCER_PID"

# ── Step 4: Wait for producer to be ready ────────────────────────────────────

echo "[CI] Waiting for producer to be ready..."
READY=false
for i in $(seq 1 30); do
    if grep -q "\[Itara\] agent ready" "$PRODUCER_LOG" 2>/dev/null; then
        READY=true
        break
    fi
    if ! kill -0 "$PRODUCER_PID" 2>/dev/null; then
        echo "[CI] ERROR: Producer JVM died unexpectedly. Log:"
        cat "$PRODUCER_LOG"
        exit 1
    fi
    sleep 1
done

if [ "$READY" = false ]; then
    echo "[CI] ERROR: Producer JVM did not become ready within 30 seconds. Log:"
    cat "$PRODUCER_LOG"
    exit 1
fi
echo "[CI] Producer is ready."

# ── Step 5: Fire a test event ─────────────────────────────────────────────────

echo "[CI] Firing test event via HTTP..."
curl -sf -X POST http://localhost:8081/itara/order-producer/placeOrder \
     -H "Content-Type: application/json" \
     -H "x-itara-dispatch-key: order-producer-001" \
     -d '["cust-ci-001", 99.99]'
echo "[CI] HTTP call completed."

# ── Step 6: Wait for consumer to process the message ─────────────────────────

echo "[CI] Waiting for consumer to process the event..."
RECEIVED=false
for i in $(seq 1 30); do
    if grep -q "Received order-placed event" "$CONSUMER_LOG" 2>/dev/null; then
        RECEIVED=true
        break
    fi
    sleep 1
done

if [ "$RECEIVED" = false ]; then
    echo "[CI] ERROR: Consumer did not process the event within 30 seconds."
    echo "[CI] Producer log:"
    cat "$PRODUCER_LOG"
    echo "[CI] Consumer log:"
    cat "$CONSUMER_LOG"
    exit 1
fi

echo "[CI] Events demo completed successfully."
echo "[CI] Producer log:"
cat "$PRODUCER_LOG"
echo "[CI] Consumer log:"
cat "$CONSUMER_LOG"