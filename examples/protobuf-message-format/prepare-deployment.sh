#!/usr/bin/env bash
# prepare-deployment.sh
#
# Builds the example and copies all artifacts into the
# appropriate directory for deployment.
#
# Prerequisite: build the java components of the project (mvn install -f ../../java/pom.xml)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Building example..."
mvn clean install

echo "Preparing clean directory structure..."
rm -rf deployment

mkdir -p deployment/lib
mkdir -p deployment/agent
mkdir -p deployment/metafiles
mkdir -p deployment/itara-libs

cp wiring.yaml deployment/

echo "Collecting Itara libraries and .itara metadata files using version ${ITARA_VERSION:-0.1.0}..."
copy_itara_jar() {
    local SRC="$1"
    local DEST="$2"
    
    if [ -f "$SRC" ]; then
      cp "$SRC" "$DEST"
    else
      echo "  ✗ MISSING: ${FILENAME}."
      echo "  Make sure the versions are properly set to the dependencies you use via the ITARA_VERSION env var!"
      exit 1
    fi
}

copy_itara_jar ../../java/itara-core/target/itara-core-${ITARA_VERSION:-0.1.0}.jar                                deployment/lib/itara-core.jar
copy_itara_jar ../../java/itara-agent/target/itara-agent-${ITARA_VERSION:-0.1.0}.jar                              deployment/agent/itara-agent.jar
copy_itara_jar ../../java/itara-serializer-protobuf/target/itara-serializer-protobuf-${ITARA_VERSION:-0.1.0}.jar  deployment/lib/
copy_itara_jar ../../java/itara-serializer-json/target/itara-serializer-json-${ITARA_VERSION:-0.1.0}.jar          deployment/itara-libs/
copy_itara_jar ../../java/itara-transport-http/target/itara-transport-http-${ITARA_VERSION:-0.1.0}.jar            deployment/itara-libs/

SRC="${HOME}/.m2/repository/com/google/protobuf/protobuf-java/${PROTOBUF_VERSION:-3.25.3}/protobuf-java-${PROTOBUF_VERSION:-3.25.3}.jar"
if [ -f "$SRC" ]; then
    cp "$SRC" deployment/lib/
else
    echo "  ✗ MISSING: ${SRC}."
    echo "  Make sure the protobuf version is properly set to the dependency you use via the PROTOBUF_VERSION env var!"
    exit 1
fi

cp ../../java/itara-serializer-protobuf/itara-serializer-protobuf.itara  deployment/metafiles/
cp ../../java/itara-serializer-json/itara-serializer-json.itara          deployment/metafiles/
cp ../../java/itara-transport-http/itara-transport-http.itara            deployment/metafiles/

echo "Collecting example artifacts and .itara metadata files..."
cp calculator/calculator-api/calculator-api.itara     deployment/metafiles/
cp gateway/gateway-api/gateway-api.itara              deployment/metafiles/
cp calculator/calculator-impl/calculator-impl.itara   deployment/metafiles/
cp gateway/gateway-impl/gateway-impl.itara            deployment/metafiles/

echo ""
echo "All libraries and artifacts collected. You can now run the example."