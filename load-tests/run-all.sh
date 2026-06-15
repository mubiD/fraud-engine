#!/bin/bash
# Runs all k6 scenarios sequentially and saves HTML summary reports.
# Usage:
#   ./run-all.sh                          # targets localhost:8080
#   BASE_URL=http://remote:8080 ./run-all.sh

set -e

BASE_URL=${BASE_URL:-http://localhost:8080}
RESULTS_DIR="results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "Target: $BASE_URL"
echo "Results: $RESULTS_DIR"
echo ""

run_scenario() {
  local name=$1
  local file=$2
  echo "==> Running: $name"
  k6 run \
    --env BASE_URL="$BASE_URL" \
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
