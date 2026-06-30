#!/usr/bin/env bash
# collect-libs.sh
#
# Copies all runtime dependencies into the demo lib directories.
# Run this from the demo/ directory after building everything.
#
# Prerequisites:
#   mvn install -f ../java/pom.xml
#   cargo build --release (in ../rust/)
#   mvn install -f pom.xml (in demo/)
#   cargo build --release (in demo/payment/)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Collecting Java SPI libraries and .itara metadata files..."
mkdir -p libs
cp ../java/itara-observability-logging/target/itara-observability-logging-1.0-SNAPSHOT.jar         libs/
cp ../java/itara-serializer-json/target/itara-serializer-json-1.0-SNAPSHOT.jar                     libs/
cp ../java/itara-transport-http/target/itara-transport-http-1.0-SNAPSHOT.jar                       libs/
cp ../java/itara-transport-kafka/target/itara-transport-kafka-1.0-SNAPSHOT.jar                     libs/
cp ../java/itara-failure-semantics-builtin/target/itara-failure-semantics-builtin-1.0-SNAPSHOT.jar libs/
cp flaky-transport/target/flaky-transport-1.0-SNAPSHOT.jar                                         libs/
mkdir -p metafiles
cp ../java/itara-observability-logging/itara-observability-logging.itara         metafiles/
cp ../java/itara-observability-otel/itara-observability-otel.itara               metafiles/
cp ../java/itara-serializer-json/itara-serializer-json.itara                     metafiles/
cp ../java/itara-transport-http/itara-transport-http.itara                       metafiles/
cp ../java/itara-transport-kafka/itara-transport-kafka.itara                     metafiles/
cp ../java/itara-failure-semantics-builtin/itara-failure-semantics-builtin.itara metafiles/
cp inventory/inventory-api/inventory-api.itara                                   metafiles/
cp inventory/inventory-component/inventory-component.itara                       metafiles/
cp fulfilment/fulfilment-api/fulfilment-api.itara                                metafiles/
cp fulfilment/fulfilment-component/fulfilment-component.itara                    metafiles/
cp notification/notification-api/notification-api.itara                          metafiles/
cp notification/notification-component/notification-component.itara              metafiles/
cp order/order-api/order-api.itara                                               metafiles/
cp order/order-component/order-component.itara                                   metafiles/
cp payment/java/payment-api/payment-api.itara                                    metafiles/
cp order-events/order-events.itara                                               metafiles/
cp fulfilment-events/fulfilment-events.itara                                     metafiles/
cp flaky-transport/flaky-transport.itara                                         metafiles/
echo "  Java libraries collected in demo/libs/ and .itara metadata files in demo/metafiles/"

echo "Collecting Rust SPI libraries and .itara metadata files..."
mkdir -p payment/itara-libs
cp ../rust/target/release/libitara_context_handler.so        payment/itara-libs/
cp ../rust/target/release/libitara_observability_logging.so  payment/itara-libs/
cp ../rust/target/release/libitara_observability_otel.so     payment/itara-libs/
cp ../rust/target/release/libitara_transport_http.so         payment/itara-libs/
cp ../rust/itara-context-handler/itara_context_handler.itara             payment/itara-libs/
cp ../rust/itara-observability-logging/itara_observability_logging.itara payment/itara-libs/
cp ../rust/itara-observability-otel/itara_observability_otel.itara       payment/itara-libs/
cp ../rust/itara-transport-http/itara_transport_http.itara               payment/itara-libs/
cp payment/payment-component/payment_component.itara                     payment/itara-libs/
cp payment/payment-component/payment_component.itara                     metafiles/
cp payment/payment-api/payment_api.itara                                 payment/itara-libs/
cp payment/target/release/libpayment_api.so                              payment/itara-libs/
cp payment/target/release/libpayment_component.so                        payment/itara-libs/
echo "  Rust libraries collected in demo/payment/itara-libs/"

echo ""
echo "All libraries collected. You can now run the demo."
