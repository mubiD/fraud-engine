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
docker compose $COMPOSE_FILES -p "$PROJECT" build fraud-engine

echo "==> [$ENV] Starting containers..."
docker compose $COMPOSE_FILES -p "$PROJECT" up -d

echo "==> [$ENV] Waiting for app to become healthy..."
RETRIES=30
while true; do
  STATUS=$(docker inspect --format='{{.State.Health.Status}}' "$APP_CONTAINER" 2>/dev/null || true)
  [[ "$STATUS" == "healthy" ]] && break
  if [[ "$STATUS" == "unhealthy" ]]; then
    echo "ERROR: $APP_CONTAINER reported unhealthy."
    docker compose $COMPOSE_FILES -p "$PROJECT" logs fraud-engine
    exit 1
  fi
  RETRIES=$((RETRIES - 1))
  if [[ $RETRIES -le 0 ]]; then
    echo "ERROR: $APP_CONTAINER did not become healthy in time."
    docker compose $COMPOSE_FILES -p "$PROJECT" logs fraud-engine
    exit 1
  fi
  sleep 5
done

echo "==> [$ENV] Deployed successfully."
APP_PORT=$(docker inspect --format='{{range $p, $b := .NetworkSettings.Ports}}{{if eq $p "8080/tcp"}}{{(index $b 0).HostPort}}{{end}}{{end}}' "$APP_CONTAINER")
DB_CONTAINER="fraud-postgres-${ENV}"
DB_PORT=$(docker inspect --format='{{range $p, $b := .NetworkSettings.Ports}}{{if eq $p "5432/tcp"}}{{(index $b 0).HostPort}}{{end}}{{end}}' "$DB_CONTAINER" 2>/dev/null)

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

# Open a new Terminal window tailing the app logs
osascript -e "tell application \"Terminal\" to do script \"echo '${APP_CONTAINER} logs'; docker logs -f ${APP_CONTAINER}\"" 2>/dev/null || \
  echo "  (tip: run  docker logs -f ${APP_CONTAINER}  to tail logs)"
