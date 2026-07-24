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

echo "==> [$ENV] Building JAR..."
mvn package -DskipTests -q

echo "==> [$ENV] Stopping existing containers..."
docker compose $COMPOSE_FILES -p "$PROJECT" down --remove-orphans --volumes

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
APP_PORT=$(docker inspect --format='{{range $p, $b := .NetworkSettings.Ports}}{{if eq $p "8080/tcp"}}{{(index $b 0).HostPort}}{{end}}{{end}}' "fraud-engine-${ENV}")
DB_PORT=$(docker inspect --format='{{range $p, $b := .NetworkSettings.Ports}}{{if eq $p "5432/tcp"}}{{(index $b 0).HostPort}}{{end}}{{end}}' "fraud-postgres-${ENV}" 2>/dev/null)

echo ""
echo "  ┌─────────────────────────────────────────────────────────┐"
echo "  │  fraud-engine [$ENV]                                     │"
echo "  ├─────────────────────────────────────────────────────────┤"
echo "  │  App        http://localhost:${APP_PORT}                        │"
echo "  │  Swagger    http://localhost:${APP_PORT}/swagger-ui.html        │"
echo "  ├─────────────────────────────────────────────────────────┤"
echo "  │  Database   localhost:${DB_PORT}  /  frauddb                   │"
echo "  │  User       fraud          Password  fraud              │"
echo "  │  Connect:   psql -h localhost -p ${DB_PORT} -U fraud frauddb   │"
echo "  ├─────────────────────────────────────────────────────────┤"
echo "  │  Shell      docker attach fraud-engine-${ENV}                 │"
echo "  │             (stream N fake events: type  stream 500)    │"
echo "  │             (detach without stopping: Ctrl+P then Ctrl+Q)│"
echo "  └─────────────────────────────────────────────────────────┘"
echo ""

# Open a new Terminal window tailing the app logs
osascript -e "tell application \"Terminal\" to do script \"echo 'fraud-engine-${ENV} logs'; docker logs -f fraud-engine-${ENV}\"" 2>/dev/null || \
  echo "  (tip: run  docker logs -f fraud-engine-${ENV}  to tail logs)"
