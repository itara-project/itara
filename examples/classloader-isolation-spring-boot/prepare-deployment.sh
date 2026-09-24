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
mkdir -p deployment/components/order
mkdir -p deployment/components/inventory
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
copy_itara_jar ../../java/itara-core/target/itara-core-${ITARA_VERSION:-0.1.0}.jar    deployment/lib/itara-core.jar
copy_itara_jar ../../java/itara-agent/target/itara-agent-${ITARA_VERSION:-0.1.0}.jar  deployment/agent/itara-agent.jar

echo "Collecting example artifacts and .itara metadata files..."
cp inventory-api/target/inventory-api-1.0-SNAPSHOT.jar          deployment/lib/inventory-api.jar
cp inventory-api/inventory-api.itara                            deployment/metafiles/
cp order-api/target/order-api-1.0-SNAPSHOT.jar                  deployment/lib/order-api.jar
cp order-api/order-api.itara                                    deployment/metafiles/
cp inventory-service/target/inventory-service-1.0-SNAPSHOT.jar  deployment/components/inventory/
cp inventory-service/inventory-service.itara                    deployment/metafiles/
cp order-service/target/order-service-1.0-SNAPSHOT.jar          deployment/components/order/
cp order-service/order-service.itara                            deployment/metafiles/

echo "Attempting to collect shared dependencies of the example service using versions ${TOMCAT_VERSION:-10.1.31} for tomcat and ${JAKARTA_VERSION:-2.1.1} for jakarta..."
JARS=(
  "jakarta/annotation/jakarta.annotation-api/${JAKARTA_VERSION:-2.1.1}/jakarta.annotation-api-${JAKARTA_VERSION:-2.1.1}.jar"
  "org/apache/tomcat/embed/tomcat-embed-core/${TOMCAT_VERSION:-10.1.31}/tomcat-embed-core-${TOMCAT_VERSION:-10.1.31}.jar"
  "org/apache/tomcat/embed/tomcat-embed-websocket/${TOMCAT_VERSION:-10.1.31}/tomcat-embed-websocket-${TOMCAT_VERSION:-10.1.31}.jar"
  "org/apache/tomcat/embed/tomcat-embed-el/${TOMCAT_VERSION:-10.1.31}/tomcat-embed-el-${TOMCAT_VERSION:-10.1.31}.jar"
)

for JAR in "${JARS[@]}"; do
  SRC="${HOME}/.m2/repository/${JAR}"
  FILENAME=$(basename "$JAR")
  echo "Copy ${FILENAME} from ${SRC} for ${JAR}"

  if [ -f "$SRC" ]; then
    cp "$SRC" deployment/lib/
    echo "  ✓ ${FILENAME}"
  else
    echo "  ✗ MISSING: ${FILENAME}."
    echo "  Make sure the versions are properly set to the dependencies you use via the JAKARTA_VERSION and TOMCAT_VERSION env vars!"
    exit 1
  fi
done

echo ""
echo "All libraries and artifacts collected. You can now run the example."