#!/usr/bin/env bash
# Usage: ./scripts/deploy.sh [dev|prod]   (or: make dev / make prod)
#
# Tears down running containers, rebuilds the image from the current working
# tree, and starts fresh containers. Postgres data volume is preserved across
# deploys.
#
# Prerequisite: Docker only. The JAR is compiled inside the image build
# (multi-stage docker/Dockerfile), so no host JDK/Maven is needed.
#
# Build-time secrets (passed as BuildKit secrets, never stored in image layers):
#   MAVEN_SETTINGS_FILE  settings.xml with your Maven mirror/credentials.
#                        Defaults to ~/.m2/settings.xml when that file exists.
#   CORP_CA_FILE         PEM root CA of a TLS-inspecting proxy (e.g. Zscaler), if any.
#                        Not auto-detected; export it yourself, e.g. on macOS:
#                        security find-certificate -a -c "Zscaler Root CA" -p \
#                          /Library/Keychains/System.keychain > ~/.corp-ca.pem

set -euo pipefail

# Docker CLI may not be on PATH when Make spawns a non-login, non-interactive bash
# (no .zshrc sourced). Search common install locations: Rancher Desktop, Docker Desktop,
# and Homebrew. First match wins; no-op if docker is already on PATH.
if ! command -v docker &>/dev/null; then
  for _d in \
      "$HOME/.rd/bin" \
      "/usr/local/bin" \
      "/opt/homebrew/bin" \
      "/Applications/Docker.app/Contents/Resources/bin"; do
    if [[ -x "$_d/docker" ]]; then
      export PATH="$_d:$PATH"
      break
    fi
  done
fi
if ! command -v docker &>/dev/null; then
  echo "ERROR: docker not found. Ensure Rancher Desktop or Docker Desktop is running and on PATH."
  exit 1
fi

ENV="${1:-dev}"

# Build JAR locally to avoid Docker network issues reaching Maven Central.
# If JAR doesn't exist or is stale, rebuild it.
if [[ ! -f "target/fraud-rule-engine-*.jar" ]]; then
  echo "==> Building JAR locally with Maven..."
  mvn -B -q clean package -DskipTests -P"confluent" || {
    echo "ERROR: Local Maven build failed. Ensure Maven is installed and network access to Maven Central is available."
    exit 1
  }
  echo "==> JAR built successfully."
else
  echo "==> Using pre-built JAR from target/"
fi

if [[ -z "${MAVEN_SETTINGS_FILE:-}" && -f "$HOME/.m2/settings.xml" ]]; then
  export MAVEN_SETTINGS_FILE="$HOME/.m2/settings.xml"
fi
[[ -n "${CORP_CA_FILE:-}" ]] && export CORP_CA_FILE

case "$ENV" in
  prod)
    COMPOSE_FILES="-f docker/docker-compose.yml -f docker/docker-compose.prod.yml"
    PROJECT="fraud-prod"
    APP_CONTAINER="fraud-engine-prod"
    ;;
  dev|*)
    COMPOSE_FILES="-f docker/docker-compose.yml -f docker/docker-compose.dev.yml"
    PROJECT="fraud-dev"
    APP_CONTAINER="fraud-engine-dev"
    ;;
esac

echo "==> [$ENV] Stopping existing containers..."
docker compose $COMPOSE_FILES -p "$PROJECT" down --remove-orphans

echo "==> [$ENV] Building image..."
# Temporarily remove 'target' from .dockerignore so the JAR can be copied into the image
DOCKERIGNORE_BACKUP=".dockerignore.bak.$$"
cp .dockerignore "$DOCKERIGNORE_BACKUP"
sed -i.tmp '/^target$/d' .dockerignore 2>/dev/null
trap "mv \"$DOCKERIGNORE_BACKUP\" .dockerignore && rm -f .dockerignore.tmp 2>/dev/null" EXIT

docker compose $COMPOSE_FILES -p "$PROJECT" build fraud-engine

echo "==> [$ENV] Starting containers..."
docker compose $COMPOSE_FILES -p "$PROJECT" up -d

echo "==> [$ENV] Waiting for app to become healthy..."
RETRIES=120
# Determine app port based on environment (dev: 8081, prod: 8080)
APP_HEALTH_PORT=8080
[[ "$ENV" == "dev" ]] && APP_HEALTH_PORT=8081
while true; do
  # Check Docker health status (what load-test also checks)
  STATUS=$(docker inspect --format='{{.State.Health.Status}}' "$APP_CONTAINER" 2>/dev/null || true)
  if [[ "$STATUS" == "healthy" ]]; then
    break
  fi
  if [[ "$STATUS" == "unhealthy" ]]; then
    echo "ERROR: $APP_CONTAINER reported unhealthy."
    docker compose $COMPOSE_FILES -p "$PROJECT" logs fraud-engine
    exit 1
  fi

  RETRIES=$((RETRIES - 1))
  if [[ $RETRIES -le 0 ]]; then
    echo "ERROR: $APP_CONTAINER health check timeout after 120s."
    docker compose $COMPOSE_FILES -p "$PROJECT" logs fraud-engine
    exit 1
  fi
  sleep 1
done

echo "==> [$ENV] Deployed successfully."

# Retry logic for docker inspect (Docker Desktop on macOS has race conditions)
_docker_inspect_with_retry() {
  local container=$1
  local port=$2
  local max_retries=5
  local retry=0
  while [[ $retry -lt $max_retries ]]; do
    result=$(docker inspect --format="{{range \$p, \$b := .NetworkSettings.Ports}}{{if eq \$p \"${port}/tcp\"}}{{(index \$b 0).HostPort}}{{end}}{{end}}" "$container" 2>/dev/null || true)
    if [[ -n "$result" ]]; then
      echo "$result"
      return 0
    fi
    retry=$((retry + 1))
    [[ $retry -lt $max_retries ]] && sleep 0.1
  done
  echo "" # Return empty if all retries fail
  return 0
}

# Try docker inspect to get actual port, but fallback to known defaults (dev: 8081, prod: 8080)
# docker inspect may timeout if container not fully ready, so defaults are safe
APP_PORT=$(_docker_inspect_with_retry "$APP_CONTAINER" "8080")
[[ -z "$APP_PORT" ]] && APP_PORT=$([ "$ENV" = "dev" ] && echo "8081" || echo "8080")

DB_CONTAINER="fraud-postgres-${ENV}"
DB_PORT=$(_docker_inspect_with_retry "$DB_CONTAINER" "5432")
[[ -z "$DB_PORT" ]] && DB_PORT="5432"

echo ""
if [[ "$ENV" == "prod" ]]; then
echo "  ┌─────────────────────────────────────────────────────────┐"
echo "  │  fraud-engine [prod]                                    │"
echo "  ├─────────────────────────────────────────────────────────┤"
echo "  │  App        http://localhost:${APP_PORT}                        │"
echo "  │  Swagger    http://localhost:${APP_PORT}/swagger-ui.html        │"
echo "  ├─────────────────────────────────────────────────────────┤"
echo "  │  Database   localhost:${DB_PORT}  /  frauddb                   │"
echo "  │  User       fraud          Password  fraud              │"
echo "  │  Connect:   psql -h localhost -p ${DB_PORT} -U fraud frauddb   │"
echo "  ├─────────────────────────────────────────────────────────┤"
echo "  │  Mock IDP   http://localhost:9000                       │"
echo "  │  Get token: curl -s -X POST                            │"
echo "  │    http://localhost:9000/default/token                  │"
echo "  │    -d 'grant_type=client_credentials&                   │"
echo "  │        client_id=demo&client_secret=demo'               │"
echo "  │    | jq -r .access_token                                │"
echo "  └─────────────────────────────────────────────────────────┘"
else
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
echo "  │  Stream     POST /api/v1/standalone/stream?count=500    │"
echo "  │             make stream 500                              │"
echo "  └─────────────────────────────────────────────────────────┘"
fi
echo ""

# Optional: tail logs (osascript on macOS may fail; always show the tip)
echo "  (tip: run  docker logs -f ${APP_CONTAINER}  to tail logs)"
