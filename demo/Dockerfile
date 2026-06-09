FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y libssl3 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Jars are mounted at runtime via docker compose volumes
# This image is intentionally lean — just the JVM runtime

ENTRYPOINT ["java"]
