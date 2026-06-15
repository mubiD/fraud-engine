#!/usr/bin/env bash
# Usage: ./scripts/deploy.sh <env>
# Environments: dev | int | qa | load | prod
#
# Tears down the running containers for the given environment, rebuilds the
# image from the current working tree, and starts fresh containers.
# Postgres data volume is preserved across deploys.

set -euo pipefail

ENV="${1:-}"
VALID_ENVS=("dev" "int" "qa" "load" "prod")

if [[ -z "$ENV" ]]; then
  echo "Usage: $0 <env>  (dev | int | qa | load | prod)"
  exit 1
fi

VALID=false
for e in "${VALID_ENVS[@]}"; do [[ "$e" == "$ENV" ]] && VALID=true; done
if [[ "$VALID" == false ]]; then
  echo "Unknown environment: $ENV"
  echo "Valid options: ${VALID_ENVS[*]}"
  exit 1
fi

COMPOSE_FILES="-f docker-compose.yml -f docker-compose.${ENV}.yml"
PROJECT="fraud-${ENV}"

echo "==> [$ENV] Stopping existing containers..."
docker compose $COMPOSE_FILES -p "$PROJECT" down --remove-orphans

echo "==> [$ENV] Building image..."
docker compose $COMPOSE_FILES -p "$PROJECT" build --no-cache fraud-engine

echo "==> [$ENV] Starting containers..."
docker compose $COMPOSE_FILES -p "$PROJECT" up -d

echo "==> [$ENV] Waiting for app to become healthy..."
RETRIES=30
until docker inspect --format='{{.State.Health.Status}}' "fraud-engine-${ENV}" 2>/dev/null | grep -q "healthy"; do
  RETRIES=$((RETRIES - 1))
  if [[ $RETRIES -le 0 ]]; then
    echo "ERROR: fraud-engine-${ENV} did not become healthy in time."
    docker compose $COMPOSE_FILES -p "$PROJECT" logs fraud-engine
    exit 1
  fi
  sleep 5
done

echo "==> [$ENV] Deployed successfully."
echo "    App: http://localhost:$(docker inspect --format='{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "fraud-engine-${ENV}")"
