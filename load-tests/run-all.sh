#!/bin/bash
# Runs all k6 scenarios sequentially and saves HTML summary reports.
#
# Scenarios produce real Confluent-Protobuf messages via k6/x/kafka (xk6-kafka) — this needs
# a k6 binary with that extension built in (stock `k6` from a package manager does NOT have
# it). `make load-test-all` runs the fully-containerised equivalent
# (mostafamoradian/xk6-kafka image) and is the primary, documented way to run this suite —
# see ../README.md. Use this script directly only if you have your own xk6-kafka binary on PATH.
#
# Usage:
#   ./run-all.sh                          # targets localhost:8080 / localhost Kafka+SR
#   BASE_URL=http://remote:8080 KAFKA_BOOTSTRAP_SERVERS=remote:9092 SCHEMA_REGISTRY_URL=http://remote:8081 ./run-all.sh

set -e

BASE_URL=${BASE_URL:-http://localhost:8080}
KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS:-localhost:9492}
SCHEMA_REGISTRY_URL=${SCHEMA_REGISTRY_URL:-http://localhost:8091}
RESULTS_DIR="results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "Target: $BASE_URL"
echo "Kafka: $KAFKA_BOOTSTRAP_SERVERS / Schema Registry: $SCHEMA_REGISTRY_URL"
echo "Results: $RESULTS_DIR"
echo ""

run_scenario() {
  local name=$1
  local file=$2
  echo "==> Running: $name"
  k6 run \
    --env BASE_URL="$BASE_URL" \
    --env KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
    --env SCHEMA_REGISTRY_URL="$SCHEMA_REGISTRY_URL" \
    --out json="$RESULTS_DIR/${name}.json" \
    --summary-export "$RESULTS_DIR/${name}-summary.json" \
    "$file"
  echo ""
}

run_scenario "01-baseline"   scenarios/01-baseline.js
run_scenario "02-ramp"       scenarios/02-ramp.js
run_scenario "03-spike"      scenarios/03-spike.js
run_scenario "04-fraud-rules" scenarios/04-fraud-rules.js

echo "All scenarios complete. Results saved to $RESULTS_DIR/"
