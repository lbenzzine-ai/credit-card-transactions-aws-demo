#!/usr/bin/env bash
#
# Runs the local-only Kafka Streams velocity-check demo. Requires a local
# Kafka broker — start one first with:
#   docker compose up -d
#
# This demo runs entirely on your machine against local Kafka. No AWS
# resources are touched and no AWS cost is incurred — completely separate
# from the rest of this project's AWS pipeline.
#
# Usage:
#   docker compose up -d
#   ./run-kafka-demo.sh
#
# Stop the broker when done: docker compose down

set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
STATE_DIR="kafka-streams-state"

# Clear leftover local state from previous runs, so each run shows one clean
# velocity window instead of accumulating counts across runs.
if [ -d "${STATE_DIR}" ]; then
  echo "-- Clearing previous run's local state (${STATE_DIR})"
  rm -rf "${STATE_DIR}"
fi

mvn compile exec:exec \
  -Dexec.executable="java" \
  -Dexec.args="-Dkafka.bootstrap=${BOOTSTRAP} -classpath %classpath com.cardco.kafka.VelocityCheckDemo"
