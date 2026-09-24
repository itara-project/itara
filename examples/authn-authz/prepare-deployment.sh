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
mkdir -p deployment/components/backend
mkdir -p deployment/components/gateway-a
mkdir -p deployment/components/gateway-b
mkdir -p deployment/metafiles
mkdir -p deployment/itara-libs

cp wiring-colocated.yaml   deployment/
cp wiring-distributed.yaml deployment/

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
copy_itara_jar ../../java/itara-core/target/itara-core-${ITARA_VERSION:-0.1.0}.jar                        deployment/lib/itara-core.jar
copy_itara_jar ../../java/itara-agent/target/itara-agent-${ITARA_VERSION:-0.1.0}.jar                      deployment/agent/itara-agent.jar
copy_itara_jar ../../java/itara-serializer-json/target/itara-serializer-json-${ITARA_VERSION:-0.1.0}.jar  deployment/itara-libs/
copy_itara_jar ../../java/itara-transport-http/target/itara-transport-http-${ITARA_VERSION:-0.1.0}.jar    deployment/itara-libs/

cp ../../java/itara-serializer-json/itara-serializer-json.itara  deployment/metafiles/
cp ../../java/itara-transport-http/itara-transport-http.itara    deployment/metafiles/

echo "Collecting example artifacts and .itara metadata files..."
cp auth-plugins/rule-table-authz/target/rule-table-authz-1.0-SNAPSHOT.jar        deployment/itara-libs/
cp auth-plugins/shared-secret-authn/target/shared-secret-authn-1.0-SNAPSHOT.jar  deployment/itara-libs/

cp auth-plugins/rule-table-authz/rule-table-authz.itara        deployment/metafiles/
cp auth-plugins/shared-secret-authn/shared-secret-authn.itara  deployment/metafiles/

cp backend/backend-api/target/backend-api-1.0-SNAPSHOT.jar        deployment/lib/
cp backend/backend-api/backend-api.itara                          deployment/metafiles/
cp gateway-a/gateway-a-api/target/gateway-a-api-1.0-SNAPSHOT.jar  deployment/lib/
cp gateway-a/gateway-a-api/gateway-a-api.itara                    deployment/metafiles/
cp gateway-b/gateway-b-api/target/gateway-b-api-1.0-SNAPSHOT.jar  deployment/lib/
cp gateway-b/gateway-b-api/gateway-b-api.itara                    deployment/metafiles/

cp backend/backend-impl/target/backend-impl-1.0-SNAPSHOT.jar        deployment/components/backend/
cp backend/backend-impl/backend-impl.itara                          deployment/metafiles/
cp gateway-a/gateway-a-impl/target/gateway-a-impl-1.0-SNAPSHOT.jar  deployment/components/gateway-a/
cp gateway-a/gateway-a-impl/gateway-a-impl.itara                    deployment/metafiles/
cp gateway-b/gateway-b-impl/target/gateway-b-impl-1.0-SNAPSHOT.jar  deployment/components/gateway-b/
cp gateway-b/gateway-b-impl/gateway-b-impl.itara                    deployment/metafiles/

echo ""
echo "All libraries and artifacts collected. You can now run the example."