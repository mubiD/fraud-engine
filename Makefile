.PHONY: dev load-test prod stop logs ps build test k6-run k6-run-all grafana help

# Capture the env name when invoked as: make stop <env>
_STOP_ENV := $(filter dev load-test prod,$(MAKECMDGOALS))

# When "stop" is a goal, prevent make from also launching the env target
ifneq ($(filter stop,$(MAKECMDGOALS)),)
ifneq ($(_STOP_ENV),)
$(_STOP_ENV): ;
endif
endif

ENVS := dev load-test prod

# ── Environment launchers ────────────────────────────────────────────────────
# Each just starts that environment — one command, no extra steps. dev/load-test
# need nothing further; prod additionally bootstraps Kafka TLS certs and Vault's
# AppRole identity on first run (see scripts/deploy.sh) — still a single `make prod`.

dev:
	./scripts/deploy.sh dev

load-test:
	./scripts/deploy.sh load-test
	@echo ""
	@echo "  Grafana dashboard → http://localhost:3000"
	@echo "  (open it before running k6-run so you see metrics stream in live)"
	@echo ""

prod:
	./scripts/deploy.sh prod

# ── Teardown ─────────────────────────────────────────────────────────────────

stop:
	@if [ -z "$(_STOP_ENV)" ]; then \
	  echo "Usage: make stop <dev|load-test|prod>"; exit 1; fi
	docker compose -f docker/docker-compose.yml -f docker/docker-compose.$(_STOP_ENV).yml \
	  -p fraud-$(_STOP_ENV) down --remove-orphans

# ── Fake event streaming (local/standalone profiles only) ───────────────────
# Usage:
#   make stream ENV=dev COUNT=500
COUNT ?= 10

stream:
	@if [ -z "$(ENV)" ]; then \
	  echo "Usage: make stream ENV=<dev|load-test|prod> [COUNT=<n>]"; exit 1; fi
	$(eval APP_PORT := $(shell docker inspect --format='{{range $$p, $$b := .NetworkSettings.Ports}}{{if eq $$p "8080/tcp"}}{{(index $$b 0).HostPort}}{{end}}{{end}}' fraud-engine-$(ENV) 2>/dev/null))
	@if [ -z "$(APP_PORT)" ]; then echo "fraud-engine-$(ENV) is not running"; exit 1; fi
	curl -s -X POST "http://localhost:$(APP_PORT)/api/v1/standalone/stream?count=$(COUNT)" | python3 -m json.tool

# ── Image build (no startup) ─────────────────────────────────────────────────

build:
	docker build -f docker/Dockerfile -t fraud-engine:local .

# ── Observability ────────────────────────────────────────────────────────────

logs:
	@if [ -z "$(ENV)" ]; then \
	  echo "Usage: make logs ENV=<dev|load-test|prod>"; exit 1; fi
	docker compose -f docker/docker-compose.yml -f docker/docker-compose.$(ENV).yml \
	  -p fraud-$(ENV) logs -f fraud-engine

ps:
	docker ps --filter "name=fraud-" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# ── Load tests (LOAD-TEST environment only) ─────────────────────────────────

# Run a single k6 scenario inside the load-test environment.
# Metrics stream to InfluxDB in real time — open http://localhost:3000 to watch live.
# SCENARIO defaults to 01-baseline. Options: 01-baseline, 02-ramp, 03-spike, 04-fraud-rules
# RATE overrides the scenario's target concurrent load (transactions/sec it should handle);
# each scenario has its own sensible default (see load-tests/README.md's Capacity numbers)
# if RATE isn't given.
# Usage:
#   make k6-run
#   make k6-run SCENARIO=02-ramp
#   make k6-run SCENARIO=01-baseline RATE=500
SCENARIO ?= 01-baseline

k6-run:
	@echo "  Live dashboard → http://localhost:3000"
	docker compose -f docker/docker-compose.yml -f docker/docker-compose.load-test.yml \
	  -p fraud-load-test --profile k6 \
	  run --rm $(if $(RATE),-e RATE=$(RATE)) k6 run /scripts/scenarios/$(SCENARIO).js

# Run all four scenarios sequentially (mirrors load-tests/run-all.sh but fully containerised)
k6-run-all:
	@echo "  Live dashboard → http://localhost:3000"
	docker compose -f docker/docker-compose.yml -f docker/docker-compose.load-test.yml \
	  -p fraud-load-test --profile k6 \
	  run --rm k6 run /scripts/scenarios/01-baseline.js
	docker compose -f docker/docker-compose.yml -f docker/docker-compose.load-test.yml \
	  -p fraud-load-test --profile k6 \
	  run --rm k6 run /scripts/scenarios/02-ramp.js
	docker compose -f docker/docker-compose.yml -f docker/docker-compose.load-test.yml \
	  -p fraud-load-test --profile k6 \
	  run --rm k6 run /scripts/scenarios/03-spike.js
	docker compose -f docker/docker-compose.yml -f docker/docker-compose.load-test.yml \
	  -p fraud-load-test --profile k6 \
	  run --rm k6 run /scripts/scenarios/04-fraud-rules.js

# Open the Grafana dashboard (macOS)
grafana:
	open http://localhost:3000

# ── Unit / integration tests ─────────────────────────────────────────────────
# Plain `mvn`, not `./mvnw` — this repo has never had a Maven wrapper committed
# (no mvnw/mvnw.cmd/.mvn/), so these targets always failed with "No such file
# or directory" before this fix, found live 2026-09-09. Resolves JAVA_HOME/mvn
# from your shell, same as scripts/deploy.sh.
#
# test/test-integration need -Pconfluent: TransactionIntegrationTest exercises
# the real Confluent Protobuf wire format, and those classes are only on the
# classpath under that opt-in profile (pom.xml) — omitting it fails with
# ClassNotFoundException: io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer,
# found live 2026-09-09. test-unit doesn't need it (pure rule tests, no Kafka).

test:
	mvn test -Pconfluent

test-unit:
	mvn test -Dtest="**/engine/**"

test-integration:
	mvn test -Dtest="**/integration/**" -Pconfluent

# ── Help ─────────────────────────────────────────────────────────────────────

help:
	@echo ""
	@echo "  make dev          Start the DEV environment        (port 8081, pg 5433, kafka 9192)"
	@echo "  make load-test    Start the LOAD-TEST environment  (port 8084, pg 5436, kafka 9492)"
	@echo "  make prod         Start the PROD environment       (port 8085, pg 5437, kafka 9592)"
	@echo ""
	@echo "  make stop load-test       Tear down the LOAD-TEST environment"
	@echo "  make logs ENV=load-test   Tail fraud-engine logs for LOAD-TEST"
	@echo "  make stream ENV=dev COUNT=500   Stream 500 fake transactions through the rule engine"
	@echo "  make ps                   List all running fraud-* containers"
	@echo ""
	@echo "  make build                Build the Docker image locally (no containers)"
	@echo ""
	@echo "  make k6-run                                Run k6 baseline scenario against LOAD-TEST env"
	@echo "  make k6-run SCENARIO=02-ramp               Run a specific k6 scenario"
	@echo "  make k6-run SCENARIO=01-baseline RATE=500  Override the scenario's target concurrent load"
	@echo "  make k6-run-all                            Run all four k6 scenarios sequentially"
	@echo "  make grafana                               Open the Grafana dashboard in your browser"
	@echo ""
	@echo "  make test                 Run all tests (Testcontainers — no infra needed)"
	@echo "  make test-unit            Run unit tests only"
	@echo "  make test-integration     Run integration tests only"
	@echo ""
