##!/bin/bash
# Collects OTel jars from local Maven repository into itara-demo/otel-libs/
# Run from the itara-demo directory before starting docker compose.

set -e

OTEL_VERSION="1.38.0"
M2="${HOME}/.m2/repository/io/opentelemetry"
OUT="./otel-libs"

mkdir -p "$OUT"

# Core OTel jars needed for autoconfigure + OTLP export
JARS=(
  "opentelemetry-api/${OTEL_VERSION}/opentelemetry-api-${OTEL_VERSION}.jar"
  "opentelemetry-context/${OTEL_VERSION}/opentelemetry-context-${OTEL_VERSION}.jar"
  "opentelemetry-sdk/${OTEL_VERSION}/opentelemetry-sdk-${OTEL_VERSION}.jar"
  "opentelemetry-sdk-common/${OTEL_VERSION}/opentelemetry-sdk-common-${OTEL_VERSION}.jar"
  "opentelemetry-sdk-trace/${OTEL_VERSION}/opentelemetry-sdk-trace-${OTEL_VERSION}.jar"
  "opentelemetry-sdk-metrics/${OTEL_VERSION}/opentelemetry-sdk-metrics-${OTEL_VERSION}.jar"
  "opentelemetry-sdk-logs/${OTEL_VERSION}/opentelemetry-sdk-logs-${OTEL_VERSION}.jar"
  "opentelemetry-sdk-extension-autoconfigure/${OTEL_VERSION}/opentelemetry-sdk-extension-autoconfigure-${OTEL_VERSION}.jar"
  "opentelemetry-sdk-extension-autoconfigure-spi/${OTEL_VERSION}/opentelemetry-sdk-extension-autoconfigure-spi-${OTEL_VERSION}.jar"
  "opentelemetry-exporter-otlp/${OTEL_VERSION}/opentelemetry-exporter-otlp-${OTEL_VERSION}.jar"
  "opentelemetry-exporter-otlp-common/${OTEL_VERSION}/opentelemetry-exporter-otlp-common-${OTEL_VERSION}.jar"
  "opentelemetry-exporter-common/${OTEL_VERSION}/opentelemetry-exporter-common-${OTEL_VERSION}.jar"
  "opentelemetry-api-incubator/${OTEL_VERSION}-alpha/opentelemetry-api-incubator-${OTEL_VERSION}-alpha.jar"
  "opentelemetry-exporter-sender-jdk/${OTEL_VERSION}/opentelemetry-exporter-sender-jdk-${OTEL_VERSION}.jar"
  "opentelemetry-exporter-sender-okhttp/${OTEL_VERSION}/opentelemetry-exporter-sender-okhttp-${OTEL_VERSION}.jar"
)

echo "[otel-libs] Collecting OTel ${OTEL_VERSION} jars from Maven local repo..."

for JAR in "${JARS[@]}"; do
  SRC="${M2}/${JAR}"
  FILENAME=$(basename "$JAR")
  if [ -f "$SRC" ]; then
    cp "$SRC" "${OUT}/${FILENAME}"
    echo "  ✓ ${FILENAME}"
  else
    echo "  ✗ MISSING: ${FILENAME}. The script will attempt to download it. Please rerun the script to copy it to its place afterwards!"
    echo "    Running: mvn dependency:get -Dartifact=io.opentelemetry:${FILENAME%-*}:${OTEL_VERSION}"
    mvn dependency:get -Dartifact=io.opentelemetry:${FILENAME%-*}:${OTEL_VERSION}
  fi
done

# The OTLP exporter also needs protobuf and grpc jars.
# These are pulled transitively — check your .m2 if missing.
# Easiest fix: run 'mvn test' in itara-observability-otel first,
# which pulls all transitive deps into .m2.

echo ""
echo "[otel-libs] Done. Contents of ${OUT}:"
ls -la "$OUT"
