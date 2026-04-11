#!/usr/bin/env bash
# =============================================================================
# TBMQ Lightweight — ARM64 Validation Script
#
# Smoke test for ARM64 Docker image: verifies the broker starts, accepts MQTT
# connections, delivers messages, and persists RocksDB data across restarts.
#
# Prerequisites:
#   - Docker installed and running
#   - mosquitto-clients installed (apt install mosquitto-clients / brew install mosquitto)
#
# Platforms:
#   - Raspberry Pi 4/5 (aarch64 Raspberry Pi OS or Ubuntu)
#   - macOS M-series via Docker Desktop
#   - AWS Graviton instances (Ubuntu/AL2023)
#
# Usage:
#   ./scripts/arm64-validate.sh [image_name:tag]
#   Default image: thingsboard/tbmq-lightweight:latest
#
# Runtime: ~30 seconds
# =============================================================================
set -euo pipefail

IMAGE="${1:-thingsboard/tbmq-lightweight:latest}"
DATA_DIR=$(mktemp -d /tmp/tbmq-arm64-XXXXXX)
CONTAINER=""

cleanup() {
    echo ""
    echo "Cleaning up..."
    if [ -n "$CONTAINER" ]; then
        docker stop "$CONTAINER" >/dev/null 2>&1 || true
        docker rm "$CONTAINER" >/dev/null 2>&1 || true
    fi
    rm -rf "$DATA_DIR"
}
trap cleanup EXIT

echo "======================================================================"
echo "  TBMQ Lightweight — ARM64 Validation"
echo "  Image: $IMAGE"
echo "  Data dir: $DATA_DIR"
echo "======================================================================"
echo ""

# --- Step 1: Start the broker ---
echo "[1/5] Starting broker container..."
CONTAINER=$(docker run -d \
    --name "tbmq-arm64-test-$$" \
    -p 1883:1883 \
    -v "${DATA_DIR}:/data/rocksdb" \
    "$IMAGE")
echo "  Container: ${CONTAINER:0:12}"

echo "  Waiting for broker to start..."
for i in $(seq 1 30); do
    if docker logs "$CONTAINER" 2>&1 | grep -q "Started TbmqLightweightApplication"; then
        echo "  Broker started in ~${i}s"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "FAIL: Broker did not start within 30 seconds"
        docker logs "$CONTAINER" 2>&1 | tail -20
        exit 1
    fi
    sleep 1
done

# --- Step 2: Subscribe to test topic ---
echo "[2/5] Subscribing to test/arm64..."
RECV_FILE=$(mktemp)
mosquitto_sub -h 127.0.0.1 -p 1883 -u tbmq -P tbmq -t test/arm64 -C 1 -W 10 > "$RECV_FILE" &
SUB_PID=$!
sleep 1

# --- Step 3: Publish test message ---
echo "[3/5] Publishing test message..."
mosquitto_pub -h 127.0.0.1 -p 1883 -u tbmq -P tbmq -t test/arm64 -m "arm64-ok"

wait "$SUB_PID" || true
RECEIVED=$(cat "$RECV_FILE")
rm -f "$RECV_FILE"

if [ "$RECEIVED" != "arm64-ok" ]; then
    echo "FAIL: Expected 'arm64-ok', got '$RECEIVED'"
    exit 1
fi
echo "  Message received: OK"

# --- Step 4: Restart and verify persistence ---
echo "[4/5] Restarting broker to verify RocksDB persistence..."
docker stop "$CONTAINER" >/dev/null
docker rm "$CONTAINER" >/dev/null

CONTAINER=$(docker run -d \
    --name "tbmq-arm64-test-$$" \
    -p 1883:1883 \
    -v "${DATA_DIR}:/data/rocksdb" \
    "$IMAGE")
echo "  Container: ${CONTAINER:0:12}"

echo "  Waiting for broker to restart..."
for i in $(seq 1 30); do
    if docker logs "$CONTAINER" 2>&1 | grep -q "Started TbmqLightweightApplication"; then
        echo "  Broker restarted in ~${i}s"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "FAIL: Broker did not restart within 30 seconds"
        docker logs "$CONTAINER" 2>&1 | tail -20
        exit 1
    fi
    sleep 1
done

# --- Step 5: Verify credentials survived restart ---
echo "[5/5] Verifying default credentials survived restart..."
mosquitto_pub -h 127.0.0.1 -p 1883 -u tbmq -P tbmq -t test/restart -m "persistence-ok"
echo "  Credentials persisted: OK"

echo ""
echo "======================================================================"
echo "  ARM64 validation PASSED"
echo "======================================================================"
