# ============================================================
# TBMQ Lightweight — Multi-arch Docker Image
# Supports: linux/amd64, linux/arm64
# ============================================================

# Stage 1: Build
# maven:3.9-eclipse-temurin-17 provides Maven 3.9 on eclipse-temurin JDK 17 (Ubuntu 24.04 Noble, glibc).
# NOTE: maven:3.9-eclipse-temurin-17-jammy does not exist as a published tag.
# The build stage OS does not matter — only the JAR is copied to the runtime stage.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy POM first for dependency layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -q -B

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -q -B

# Stage 2: Runtime
# WARNING: DO NOT change to alpine — RocksDB JNI requires glibc (musl incompatible).
# Alpine on ARM64 causes UnsatisfiedLinkError at startup. This is a hard block.
FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="ThingsBoard <info@thingsboard.io>"
LABEL description="TBMQ Lightweight — single-node MQTT broker with embedded storage"

WORKDIR /app

# curl is required for Docker HEALTHCHECK command (D-14)
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Copy the fat JAR from build stage
COPY --from=build /app/target/tbmq-lightweight.jar app.jar

# RocksDB persistent data directory — mount a Docker volume here (D-12, OPS-04)
# Without a volume mount, data is ephemeral and lost on container removal
VOLUME ["/data/rocksdb"]

# MQTT TCP port (Netty) and HTTP/Actuator port (Tomcat)
EXPOSE 1883 8083

# Health check using Spring Boot Actuator (D-14)
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -sf http://localhost:8083/actuator/health || exit 1

# JVM options:
# -XX:+UseContainerSupport: respect container memory limits (default in JDK 17 but explicit for clarity)
# -XX:MaxRAMPercentage=75.0: use 75% of container memory for JVM heap
# -Dio.netty.leakDetection.level=DISABLED: no leak detection overhead in production (Phase 7 enables PARANOID for soak tests)
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Dio.netty.leakDetection.level=DISABLED", \
    "-jar", "app.jar"]
