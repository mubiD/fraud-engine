#!/usr/bin/env bash
# Usage: ./scripts/deploy.sh <env>
# Environments: dev | load-test | prod
#
# Tears down the running containers for the given environment, rebuilds the
# image from the current working tree, and starts fresh containers.
# Postgres data volume is preserved across deploys.
#
# Prerequisite: a local JDK 21 + Maven on PATH (in addition to Docker). The
# JAR is built on the host, not inside the image — see docker/Dockerfile's
# header comment for why (Confluent's Maven repo needs auth not available in
# a plain build container). This resolves JAVA_HOME/mvn from your existing
# shell environment; it does not attempt to auto-detect a JDK 21 install.
#
# `make <env>` (and this script directly) is meant to be one command that just
# starts the environment — including prod, which additionally bootstraps Kafka
# TLS certs and Vault's AppRole identity on first run, below, rather than the
# multi-step manual dance docker-compose.prod.yml's header comment used to
# require (generate certs, start Vault, copy out role/secret IDs by hand,
# restart fraud-engine). Real secrets (KAFKA_*_PASSWORD, DB_PASSWORD) still
# default to local-only placeholder values if not already exported — override
# them yourself for anything beyond local verification.

set -euo pipefail

ENV="${1:-}"
VALID_ENVS=("dev" "load-test" "prod")

if [[ -z "$ENV" ]]; then
  echo "Usage: $0 <env>  (dev | load-test | prod)"
  exit 1
fi

VALID=false
for e in "${VALID_ENVS[@]}"; do [[ "$e" == "$ENV" ]] && VALID=true; done
if [[ "$VALID" == false ]]; then
  echo "Unknown environment: $ENV"
  echo "Valid options: ${VALID_ENVS[*]}"
  exit 1
fi

COMPOSE_FILES="-f docker/docker-compose.yml -f docker/docker-compose.${ENV}.yml"
PROJECT="fraud-${ENV}"

echo "==> [$ENV] Building JAR..."
# load-test/prod run the real Kafka Protobuf serializer/deserializer (application.yml),
# which needs the opt-in `confluent` Maven profile (pom.xml) — only `dev` (SPRING_PROFILES_ACTIVE=local,
# JSON serialisation) can build from Maven Central alone. Building load-test/prod without this flag
# produces a jar that fails to start at all (ClassNotFoundException on
# io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer) — found live 2026-09-08.
if [[ "$ENV" == "dev" ]]; then
  mvn package -DskipTests -q
else
  mvn package -DskipTests -q -Pconfluent
fi

echo "==> [$ENV] Stopping existing containers..."
docker compose $COMPOSE_FILES -p "$PROJECT" down --remove-orphans

# ── PROD-only bootstrap: Kafka TLS certs + Vault AppRole identity ──────────────
# Runs before the image build so the app never starts against a half-configured
# Kafka/Vault. Both steps are idempotent (gen-kafka-certs.sh skips existing certs
# via its own check; scripts/vault-init.sh re-unseals rather than re-initialising
# once Vault has already been set up) — safe to run on every deploy, not just the
# first one.
if [[ "$ENV" == "prod" ]]; then
  export KAFKA_SSL_KEYSTORE_PASSWORD="${KAFKA_SSL_KEYSTORE_PASSWORD:-changeit}"
  export KAFKA_SSL_TRUSTSTORE_PASSWORD="${KAFKA_SSL_TRUSTSTORE_PASSWORD:-changeit}"
  export KAFKA_ADMIN_PASSWORD="${KAFKA_ADMIN_PASSWORD:-changeit-admin}"
  export KAFKA_CLIENT_PASSWORD="${KAFKA_CLIENT_PASSWORD:-changeit-client}"
  export DB_PASSWORD="${DB_PASSWORD:-fraud}"
  if [[ "${KAFKA_SSL_KEYSTORE_PASSWORD}" == "changeit" ]]; then
    echo "==> [prod] No KAFKA_*_PASSWORD/DB_PASSWORD exported — using local-verification-only"
    echo "    defaults (changeit / changeit-admin / changeit-client / fraud). Export real"
    echo "    values yourself before this is anything other than a local dry run."
  fi

  if [[ ! -f "certs/prod/kafka1.keystore.p12" ]]; then
    echo "==> [prod] Generating Kafka TLS certs (certs/prod/, gitignored)..."
    ./scripts/gen-kafka-certs.sh
  else
    echo "==> [prod] Kafka TLS certs already present, skipping generation."
  fi

  echo "==> [prod] Starting Postgres, Kafka, Vault and mock-oidc (not fraud-engine yet — it"
  echo "    needs VAULT_ROLE_ID/VAULT_SECRET_ID from vault-init, read below)..."
  docker compose $COMPOSE_FILES -p "$PROJECT" up -d postgres kafka1 kafka2 kafka3 vault vault-init mock-oidc

  echo "==> [prod] Waiting for vault-init to finish (initialises + unseals Vault, creates the"
  echo "    fraud-engine AppRole)..."
  RETRIES=30
  while true; do
    STATUS=$(docker inspect --format='{{.State.Status}}' "fraud-vault-init-prod" 2>/dev/null || true)
    if [[ "$STATUS" == "exited" ]]; then
      EXIT_CODE=$(docker inspect --format='{{.State.ExitCode}}' "fraud-vault-init-prod")
      if [[ "$EXIT_CODE" != "0" ]]; then
        echo "ERROR: vault-init exited with code $EXIT_CODE."
        docker compose $COMPOSE_FILES -p "$PROJECT" logs vault-init
        exit 1
      fi
      break
    fi
    RETRIES=$((RETRIES - 1))
    if [[ $RETRIES -le 0 ]]; then
      echo "ERROR: vault-init did not finish in time."
      docker compose $COMPOSE_FILES -p "$PROJECT" logs vault-init
      exit 1
    fi
    sleep 3
  done

  echo "==> [prod] Reading AppRole credentials vault-init wrote to the vault_init volume..."
  VOLUME_NAME="${PROJECT}_vault_init_prod"
  export VAULT_ROLE_ID
  export VAULT_SECRET_ID
  # MSYS_NO_PATHCONV scoped to just these two calls — Git Bash/MSYS on Windows rewrites the
  # in-container "/data/init-output.txt" path as a Windows path otherwise, corrupting it;
  # exporting it script-wide instead broke mvn's own path handling. Found live 2026-09-08.
  VAULT_ROLE_ID=$(MSYS_NO_PATHCONV=1 docker run --rm -v "${VOLUME_NAME}:/data:ro" alpine:3.20 \
    sh -c "grep '^Role ID' /data/init-output.txt | awk '{print \$NF}'")
  VAULT_SECRET_ID=$(MSYS_NO_PATHCONV=1 docker run --rm -v "${VOLUME_NAME}:/data:ro" alpine:3.20 \
    sh -c "grep '^Secret ID' /data/init-output.txt | awk '{print \$NF}'")
  if [[ -z "$VAULT_ROLE_ID" || -z "$VAULT_SECRET_ID" ]]; then
    echo "ERROR: could not read VAULT_ROLE_ID/VAULT_SECRET_ID from the vault_init volume."
    exit 1
  fi
  echo "    Got AppRole credentials (role-id ${VAULT_ROLE_ID:0:8}...)."
fi

echo "==> [$ENV] Building image..."
docker compose $COMPOSE_FILES -p "$PROJECT" build --no-cache fraud-engine

echo "==> [$ENV] Starting containers..."
docker compose $COMPOSE_FILES -p "$PROJECT" up -d

echo "==> [$ENV] Waiting for app to become healthy..."
RETRIES=30
while true; do
  STATUS=$(docker inspect --format='{{.State.Health.Status}}' "fraud-engine-${ENV}" 2>/dev/null || true)
  [[ "$STATUS" == "healthy" ]] && break
  if [[ "$STATUS" == "unhealthy" ]]; then
    echo "ERROR: fraud-engine-${ENV} reported unhealthy."
    docker compose $COMPOSE_FILES -p "$PROJECT" logs fraud-engine
    exit 1
  fi
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
if [[ "$ENV" == "dev" ]]; then
  echo "  ├─────────────────────────────────────────────────────────┤"
  echo "  │  Stream     POST /api/v1/standalone/stream?count=500      │"
  echo "  │             make stream ENV=${ENV} COUNT=500                    │"
fi
echo "  └─────────────────────────────────────────────────────────┘"
echo ""

# Open a new Terminal window tailing the app logs
osascript -e "tell application \"Terminal\" to do script \"echo 'fraud-engine-${ENV} logs'; docker logs -f fraud-engine-${ENV}\"" 2>/dev/null || \
  echo "  (tip: run  docker logs -f fraud-engine-${ENV}  to tail logs)"
