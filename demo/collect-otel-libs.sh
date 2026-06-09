#!/bin/bash
# Collects OTel jars from local Maven repository into itara-demo/otel-libs/
# Run from the itara-demo directory before starting docker compose.
#
# If a jar is missing from the local Maven repo, this script will attempt
# to download it via mvn dependency:get before copying.
#
# Tip: running 'mvn install' in java/ first will pull all transitive
# dependencies into .m2, making this script faster and more reliable.

set -e

OTEL_VERSION="1.38.0"
M2="${HOME}/.m2/repository/io/opentelemetry"
OUT="./otel-libs"

mkdir -p "$OUT"

# Core OTel jars needed for autoconfigure + OTLP export.
# Note: opentelemetry-exporter-sender-okhttp is intentionally excluded —
# it conflicts with the JDK sender and is not needed.
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
)

copy_jar() {
  local JAR="$1"
  local SRC="${M2}/${JAR}"
  local FILENAME
  FILENAME=$(basename "$JAR")

  if [ -f "$SRC" ]; then
    cp "$SRC" "${OUT}/${FILENAME}"
    echo "  ✓ ${FILENAME}"
  else
    echo "  ✗ MISSING: ${FILENAME} — attempting download..."
    ARTIFACT_ID="${FILENAME%-*}"
    mvn dependency:get -Dartifact="io.opentelemetry:${ARTIFACT_ID}:${OTEL_VERSION}" -q
    if [ -f "$SRC" ]; then
      cp "$SRC" "${OUT}/${FILENAME}"
      echo "  ✓ ${FILENAME} (downloaded)"
    else
      echo "  ✗ FAILED: ${FILENAME} not found after download attempt."
      echo "    Try running 'mvn install' in java/ first to populate the local Maven repo."
      exit 1
    fi
  fi
}

echo "[otel-libs] Collecting OTel ${OTEL_VERSION} jars from Maven local repo..."

for JAR in "${JARS[@]}"; do
  copy_jar "$JAR"
done

echo ""
echo "[otel-libs] Done. Contents of ${OUT}:"
ls -la "$OUT"
