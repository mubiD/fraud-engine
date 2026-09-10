.PHONY: dev load-test prod stop logs ps build test k6-run k6-run-all grafana help

# On native Windows (invoked from PowerShell/cmd, not already inside Git Bash), GNU Make's own
# recipe-spawning code fails to quote a SHELL path containing spaces ("Program Files" always
# has one), so every recipe crashes with "bash: C:\Users\<name>: No such file or directory"
# (Make truncates the executable path at the first space) the moment the invoking user's own
# account name also has a space in it (e.g. "AMD Desktop PC"), found live 2026-09-09. Doesn't
# reproduce running the same script directly via sh.exe, or via $(shell ...) at parse time;
# it only affects recipe-line execution specifically. Fixed by resolving SHELL to Git Bash's own
# 8.3 "short" (space-free) path via cygpath, which is on PATH once Git for Windows is installed
# regardless of where it's installed to. No-op on macOS/Linux (make/sh are native there, no
# spaces-in-SHELL-path bug exists, and $(OS) isn't Windows_NT).
ifeq ($(OS),Windows_NT)
SHELL := $(shell cygpath -d "$(shell command -v sh)")
.SHELLFLAGS := -c
endif

# Second, related Windows bug: even with SHELL fixed above, GNU Make 4.x's own optimization
# skips spawning a shell entirely for a recipe line with no shell metacharacters — it parses
# the target script's shebang itself and execs "env bash <script path> args" directly,
# bypassing SHELL/.SHELLFLAGS and re-hitting the same unquoted-spacey-path bug (the script's
# own absolute path, not just the shell's). The "&& :" appended to every ./scripts/deploy.sh
# line below forces real shell routing (a shell metacharacter defeats the direct-exec
# fast path) while still propagating deploy.sh's real exit code: "&&" short-circuits so ":"
# (always exit 0) only runs after a genuine success; a bare ";" would silently swallow
# failures instead. Harmless no-op on macOS/Linux.

# Third Windows bug, independent of the two above: deploy.sh's own shebang is
# "#!/usr/bin/env bash", and when the OS/exec layer resolves "bash" via PATH search, Windows
# ships a legacy WSL launcher at C:\Windows\System32\bash.exe that's always found before Git
# Bash's real one (System32 is always first in the Machine PATH, ahead of anything a user adds
# to their own PATH), so machines with no WSL distro installed then fail with
# "execvpe(/bin/bash) failed: No such file or directory", found live 2026-09-09. Fixing PATH
# order isn't reliable (Machine PATH always wins), so instead every deploy.sh invocation below
# calls Git Bash's own bash.exe explicitly (derived from the already-resolved, already-short
# SHELL path, so it's just as space-safe) rather than letting the shebang do an ambiguous PATH
# search. BASH := bash on macOS/Linux is a no-op: invoking `bash script.sh` there behaves
# identically to letting the shebang run it.
ifeq ($(OS),Windows_NT)
BASH := $(subst sh.exe,bash.exe,$(SHELL))
else
BASH := bash
endif

# Capture the env name when invoked as: make stop <env>
_STOP_ENV := $(filter dev load-test prod,$(MAKECMDGOALS))

ENVS := dev load-test prod

# ── Environment launchers ────────────────────────────────────────────────────
# Each just starts that environment, one command, no extra steps. dev/load-test
# need nothing further; prod additionally bootstraps Kafka TLS certs and Vault's
# AppRole identity on first run (see scripts/deploy.sh), but it's still a single `make prod`.

dev:
	"$(BASH)" ./scripts/deploy.sh dev && :

load-test:
	"$(BASH)" ./scripts/deploy.sh load-test && :
	@echo ""
	@echo "  Grafana dashboard → http://localhost:3000"
	@echo "  (open it before running k6-run so you see metrics stream in live)"
	@echo ""

prod:
	"$(BASH)" ./scripts/deploy.sh prod && :

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
	@# Pretty-print if python3 is around, otherwise print raw JSON rather than fail outright.
	@# python3 isn't guaranteed on any of the three platforms this Makefile targets (never
	@# bundled on Windows, and no longer bundled by default on recent macOS either), found live
	@# 2026-09-09 testing on a Windows machine with neither python3 nor python on PATH.
	curl -s -X POST "http://localhost:$(APP_PORT)/api/v1/standalone/stream?count=$(COUNT)" | (python3 -m json.tool 2>/dev/null || cat)

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
# Metrics stream to InfluxDB in real time, open http://localhost:3000 to watch live.
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

# Open the Grafana dashboard, cross-platform. Was macOS-only ("open", found live 2026-09-09
# hitting "command not found" on both Windows and Linux). Windows has no "open"/"xdg-open" at
# all (and "start" is a cmd.exe builtin, not a real executable, so it wouldn't resolve under
# this Makefile's sh.exe SHELL either; explorer.exe is the portable equivalent and is a real
# PE binary on PATH), Linux uses xdg-open. explorer.exe returns a nonzero exit code on success
# for unrelated reasons, hence "|| :" so that's never mistaken for a real failure.
ifeq ($(OS),Windows_NT)
GRAFANA_OPEN := explorer.exe http://localhost:3000 || :
else ifeq ($(shell uname -s 2>/dev/null),Darwin)
GRAFANA_OPEN := open http://localhost:3000
else
GRAFANA_OPEN := xdg-open http://localhost:3000
endif

grafana:
	$(GRAFANA_OPEN)

# ── Unit / integration tests ─────────────────────────────────────────────────
# Plain `mvn`, not `./mvnw`: this repo has never had a Maven wrapper committed
# (no mvnw/mvnw.cmd/.mvn/), so these targets always failed with "No such file
# or directory" before this fix, found live 2026-09-09. Resolves JAVA_HOME/mvn
# from your shell, same as scripts/deploy.sh.
#
# test/test-integration need -Pconfluent: TransactionIntegrationTest exercises
# the real Confluent Protobuf wire format, and those classes are only on the
# classpath under that opt-in profile (pom.xml); omitting it fails with
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
	@echo "  make test                 Run all tests (Testcontainers, no infra needed)"
	@echo "  make test-unit            Run unit tests only"
	@echo "  make test-integration     Run integration tests only"
	@echo ""

# When "stop" is a goal, prevent make from also launching the env target as a second goal
# (e.g. "make stop dev" naming both "stop" and "dev"). Deliberately placed at the very end of
# the file, after every real target (dev/load-test/prod) is already defined: GNU Make resolves
# a target named in more than one rule by using whichever recipe was defined LAST, not first.
# This exact suppression used to sit right after _STOP_ENV near the top, before the real env
# targets, so its empty recipe was always the one silently overridden
# ("Makefile:NN: warning: overriding recipe for target 'dev'", "warning: ignoring old recipe"),
# never the other way around. Consequence, confirmed live: "make stop dev" ran `docker compose
# down` and then immediately redeployed dev again, rather than just stopping it. Found live
# 2026-09-09, pre-existing since before this Makefile ever actually ran (make itself didn't
# work at all until this session, so this bug had never been exercised before).
ifneq ($(filter stop,$(MAKECMDGOALS)),)
ifneq ($(_STOP_ENV),)
$(_STOP_ENV): ;
endif
endif
