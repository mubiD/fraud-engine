#!/usr/bin/env bash
# Usage: ./scripts/deploy.sh   (or: make dev)
#
# Tears down running dev containers, rebuilds the image from the current
# working tree, and starts fresh containers. Postgres data volume is
# preserved across deploys.
#
# Prerequisite: a local JDK 21 + Maven on PATH (in addition to Docker). The
# JAR is built on the host, not inside the image (see docker/Dockerfile's
# header comment for why: Confluent's Maven repo needs auth not available in
# a plain build container). Resolves JAVA_HOME/mvn from your existing shell
# environment; it does not attempt to auto-detect a JDK 21 install.

set -euo pipefail

COMPOSE_FILES="-f docker/docker-compose.yml -f docker/docker-compose.dev.yml"
PROJECT="fraud-dev"

echo "==> [dev] Building JAR..."
./mvnw package -DskipTests -q

echo "==> [dev] Stopping existing containers..."
docker compose $COMPOSE_FILES -p "$PROJECT" down --remove-orphans

echo "==> [dev] Building image..."
docker compose $COMPOSE_FILES -p "$PROJECT" build fraud-engine

echo "==> [dev] Starting containers..."
docker compose $COMPOSE_FILES -p "$PROJECT" up -d

echo "==> [dev] Waiting for app to become healthy..."
RETRIES=30
while true; do
  STATUS=$(docker inspect --format='{{.State.Health.Status}}' "fraud-engine-dev" 2>/dev/null || true)
  [[ "$STATUS" == "healthy" ]] && break
  if [[ "$STATUS" == "unhealthy" ]]; then
    echo "ERROR: fraud-engine-dev reported unhealthy."
    docker compose $COMPOSE_FILES -p "$PROJECT" logs fraud-engine
    exit 1
  fi
  RETRIES=$((RETRIES - 1))
  if [[ $RETRIES -le 0 ]]; then
    echo "ERROR: fraud-engine-dev did not become healthy in time."
    docker compose $COMPOSE_FILES -p "$PROJECT" logs fraud-engine
    exit 1
  fi
  sleep 5
done

echo "==> [dev] Deployed successfully."
APP_PORT=$(docker inspect --format='{{range $p, $b := .NetworkSettings.Ports}}{{if eq $p "8080/tcp"}}{{(index $b 0).HostPort}}{{end}}{{end}}' "fraud-engine-dev")
DB_PORT=$(docker inspect --format='{{range $p, $b := .NetworkSettings.Ports}}{{if eq $p "5432/tcp"}}{{(index $b 0).HostPort}}{{end}}{{end}}' "fraud-postgres-dev" 2>/dev/null)

echo ""
echo "  ┌─────────────────────────────────────────────────────────┐"
echo "  │  fraud-engine [dev]                                     │"
echo "  ├─────────────────────────────────────────────────────────┤"
echo "  │  App        http://localhost:${APP_PORT}                        │"
echo "  │  Swagger    http://localhost:${APP_PORT}/swagger-ui.html        │"
echo "  ├─────────────────────────────────────────────────────────┤"
echo "  │  Database   localhost:${DB_PORT}  /  frauddb                   │"
echo "  │  User       fraud          Password  fraud              │"
echo "  │  Connect:   psql -h localhost -p ${DB_PORT} -U fraud frauddb   │"
echo "  ├─────────────────────────────────────────────────────────┤"
echo "  │  Stream     POST /api/v1/standalone/stream?count=500      │"
echo "  │             make stream 500                                │"
echo "  └─────────────────────────────────────────────────────────┘"
echo ""

# Open a new Terminal window tailing the app logs
osascript -e "tell application \"Terminal\" to do script \"echo 'fraud-engine-dev logs'; docker logs -f fraud-engine-dev\"" 2>/dev/null || \
  echo "  (tip: run  docker logs -f fraud-engine-dev  to tail logs)"
