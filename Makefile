.PHONY: dev int qa load prod stop logs ps build test load-test load-test-all grafana help

# Capture the env name when invoked as: make stop <env>
_STOP_ENV := $(filter dev int qa load prod,$(MAKECMDGOALS))

# When "stop" is a goal, prevent make from also launching the env target
ifneq ($(filter stop,$(MAKECMDGOALS)),)
ifneq ($(_STOP_ENV),)
$(_STOP_ENV): ;
endif
endif

ENVS := dev int qa load prod

# ── Environment launchers ────────────────────────────────────────────────────

dev:
	./scripts/deploy.sh dev

int:
	./scripts/deploy.sh int

qa:
	./scripts/deploy.sh qa

load:
	./scripts/deploy.sh load
	@echo ""
	@echo "  Grafana dashboard → http://localhost:3000"
	@echo "  (open it before running load-test so you see metrics stream in live)"
	@echo ""

prod:
	./scripts/deploy.sh prod

# ── Teardown ─────────────────────────────────────────────────────────────────

stop:
	@if [ -z "$(_STOP_ENV)" ]; then \
	  echo "Usage: make stop <dev|int|qa|load|prod>"; exit 1; fi
	docker compose -f docker-compose.yml -f docker-compose.$(_STOP_ENV).yml \
	  -p fraud-$(_STOP_ENV) down --remove-orphans --volumes

# ── Image build (no startup) ─────────────────────────────────────────────────

build:
	docker build -t fraud-engine:local .

# ── Observability ────────────────────────────────────────────────────────────

logs:
	@if [ -z "$(ENV)" ]; then \
	  echo "Usage: make logs ENV=<dev|int|qa|load|prod>"; exit 1; fi
	docker compose -f docker-compose.yml -f docker-compose.$(ENV).yml \
	  -p fraud-$(ENV) logs -f fraud-engine

ps:
	docker ps --filter "name=fraud-" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# ── Load tests (LOAD environment only) ──────────────────────────────────────

# Run a single k6 scenario inside the load environment.
# Metrics stream to InfluxDB in real time — open http://localhost:3000 to watch live.
# SCENARIO defaults to 01-baseline. Options: 01-baseline, 02-ramp, 03-spike, 04-fraud-rules
# Usage:
#   make load-test
#   make load-test SCENARIO=02-ramp
SCENARIO ?= 01-baseline

load-test:
	@echo "  Live dashboard → http://localhost:3000"
	docker compose -f docker-compose.yml -f docker-compose.load.yml \
	  -p fraud-load --profile k6 \
	  run --rm k6 run /scripts/scenarios/$(SCENARIO).js

# Run all four scenarios sequentially (mirrors load-tests/run-all.sh but fully containerised)
load-test-all:
	@echo "  Live dashboard → http://localhost:3000"
	docker compose -f docker-compose.yml -f docker-compose.load.yml \
	  -p fraud-load --profile k6 \
	  run --rm k6 run /scripts/scenarios/01-baseline.js
	docker compose -f docker-compose.yml -f docker-compose.load.yml \
	  -p fraud-load --profile k6 \
	  run --rm k6 run /scripts/scenarios/02-ramp.js
	docker compose -f docker-compose.yml -f docker-compose.load.yml \
	  -p fraud-load --profile k6 \
	  run --rm k6 run /scripts/scenarios/03-spike.js
	docker compose -f docker-compose.yml -f docker-compose.load.yml \
	  -p fraud-load --profile k6 \
	  run --rm k6 run /scripts/scenarios/04-fraud-rules.js

# Open the Grafana dashboard (macOS)
grafana:
	open http://localhost:3000

# ── Unit / integration tests ─────────────────────────────────────────────────

test:
	./mvnw test

test-unit:
	./mvnw test -Dtest="**/engine/**"

test-integration:
	./mvnw test -Dtest="**/integration/**"

# ── Help ─────────────────────────────────────────────────────────────────────

help:
	@echo ""
	@echo "  make dev          Start the DEV environment   (port 8081, pg 5433, kafka 9192)"
	@echo "  make int          Start the INT environment   (port 8082, pg 5434, kafka 9292)"
	@echo "  make qa           Start the QA  environment   (port 8083, pg 5435, kafka 9392)"
	@echo "  make load         Start the LOAD environment  (port 8084, pg 5436, kafka 9492)"
	@echo "  make prod         Start the PROD environment  (port 8085, pg 5437, kafka 9592)"
	@echo ""
	@echo "  make stop int             Tear down the INT environment"
	@echo "  make logs ENV=int         Tail fraud-engine logs for INT"
	@echo "  make ps                   List all running fraud-* containers"
	@echo ""
	@echo "  make build                Build the Docker image locally (no containers)"
	@echo ""
	@echo "  make load-test                      Run k6 baseline scenario against LOAD env"
	@echo "  make load-test SCENARIO=02-ramp     Run a specific k6 scenario"
	@echo "  make load-test-all                  Run all four k6 scenarios sequentially"
	@echo "  make grafana                        Open the Grafana dashboard in your browser"
	@echo ""
	@echo "  make test                 Run all tests (Testcontainers — no infra needed)"
	@echo "  make test-unit            Run unit tests only"
	@echo "  make test-integration     Run integration tests only"
	@echo ""
