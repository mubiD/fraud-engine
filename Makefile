.PHONY: dev stop stream build logs ps test test-unit test-integration load-test grafana help

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
# own absolute path, not just the shell's). The "&& :" appended to the ./scripts/deploy.sh
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
# order isn't reliable (Machine PATH always wins), so instead the deploy.sh invocation below
# calls Git Bash's own bash.exe explicitly (derived from the already-resolved, already-short
# SHELL path, so it's just as space-safe) rather than letting the shebang do an ambiguous PATH
# search. BASH := bash on macOS/Linux is a no-op: invoking `bash script.sh` there behaves
# identically to letting the shebang run it.
ifeq ($(OS),Windows_NT)
BASH := $(subst sh.exe,bash.exe,$(SHELL))
else
BASH := bash
endif

# ── Java 21 auto-detection ────────────────────────────────────────────────────
# If the active JAVA_HOME already points to a Java 21 JDK, use it as-is.
# Otherwise, locate one in the standard OS install paths and export it for this
# make session only (no permanent change to the user's shell environment).
# On Windows the install path varies too widely to detect reliably, so we check
# and fail fast with a clear message instead.
ifeq ($(OS),Windows_NT)
  _JAVA_VER := $(shell cmd /c "\"$(JAVA_HOME)\bin\java.exe\" -version 2>&1" | findstr /i "version")
  ifeq ($(findstring "21.,$(_JAVA_VER)),)
    $(warning )
    $(warning ERROR: Java 21 is required but JAVA_HOME does not point to a Java 21 JDK.)
    $(warning        Set JAVA_HOME to your Java 21 install, for example:)
    $(warning          set JAVA_HOME=C:\Program Files\Amazon Corretto\jdk21.x.x_x)
    $(warning        Download: https://aws.amazon.com/corretto/)
    $(warning )
    $(error Java 21 not found)
  endif
else
  ifeq ($(shell uname),Darwin)
    # /usr/libexec/java_home is the macOS-canonical locator: returns the home path
    # of the requested version if installed, empty string if not.
    _JAVA21_HOME := $(shell /usr/libexec/java_home -v 21 2>/dev/null)
  else
    # Linux: check the paths used by Amazon Corretto, Eclipse Temurin, and the
    # default OpenJDK apt/dnf packages (amd64 and arm64 suffixes).
    _JAVA21_HOME := $(firstword $(wildcard \
        /usr/lib/jvm/java-21-amazon-corretto \
        /usr/lib/jvm/java-21-openjdk-amd64 \
        /usr/lib/jvm/java-21-openjdk-arm64 \
        /usr/lib/jvm/temurin-21 \
        /usr/lib/jvm/java-21))
  endif

  ifneq ($(_JAVA21_HOME),)
    # Found a Java 21 install that isn't the current JAVA_HOME — switch for this
    # make session only.
    ifneq ($(JAVA_HOME),$(_JAVA21_HOME))
      export JAVA_HOME := $(_JAVA21_HOME)
    endif
  else
    # No Java 21 found anywhere — bail early with a useful error rather than a
    # cryptic Maven source-compatibility failure deep in the build.
    $(warning )
    $(warning ERROR: Java 21 is required but could not be found.)
    $(warning        Install Amazon Corretto 21: https://aws.amazon.com/corretto/)
    $(warning        Then re-run make.)
    $(warning )
    $(error Java 21 not found)
  endif
endif

COMPOSE          := docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml -p fraud-dev
LOADTEST_COMPOSE := docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml -f docker/docker-compose.loadtest.yml -p fraud-dev

# Load-test tunables: override on the command line, e.g. make load-test scenario=03 vus=30 duration=5m
_LT_SCENARIO := $(or $(scenario),01)
_LT_VUS      := $(or $(vus),)
_LT_DURATION := $(or $(duration),)

# Capture the count argument when invoked as: make stream <n>
_STREAM_COUNT := $(filter-out stream,$(MAKECMDGOALS))

# ── Environment launcher ─────────────────────────────────────────────────────

dev:
	"$(BASH)" ./scripts/deploy.sh && :

# ── Teardown ─────────────────────────────────────────────────────────────────

stop:
	$(COMPOSE) down --remove-orphans

# ── Fake event streaming (local/standalone profile) ──────────────────────────
# Usage:
#   make stream
#   make stream 500
COUNT ?= $(if $(_STREAM_COUNT),$(_STREAM_COUNT),10)

stream:
	$(eval APP_PORT := $(shell docker inspect --format='{{range $$p, $$b := .NetworkSettings.Ports}}{{if eq $$p "8080/tcp"}}{{(index $$b 0).HostPort}}{{end}}{{end}}' fraud-engine-dev 2>/dev/null))
	@if [ -z "$(APP_PORT)" ]; then echo "fraud-engine-dev is not running"; exit 1; fi
	@# Pretty-print if python3 is around, otherwise print raw JSON rather than fail outright.
	@# python3 isn't guaranteed on all platforms this Makefile targets (never bundled on Windows,
	@# and no longer bundled by default on recent macOS either), found live 2026-09-09 testing
	@# on a Windows machine with neither python3 nor python on PATH.
	curl -s -X POST "http://localhost:$(APP_PORT)/api/v1/standalone/stream?count=$(COUNT)" | (python3 -m json.tool 2>/dev/null || cat)

# ── Image build (no startup) ─────────────────────────────────────────────────

build:
	docker build -f docker/Dockerfile -t fraud-engine:local .

# ── Observability ────────────────────────────────────────────────────────────

logs:
	$(COMPOSE) logs -f fraud-engine

ps:
	docker ps --filter "name=fraud-" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# ── Unit / integration tests ─────────────────────────────────────────────────
# Uses ./mvnw (Maven wrapper) — no separate Maven install required.
# The wrapper downloads the correct Maven version on first run and caches it
# in ~/.m2/wrapper. Resolves JAVA_HOME from the auto-detection block above.
#
# test/test-integration need -Pconfluent: TransactionIntegrationTest exercises
# the real Confluent Protobuf wire format, and those classes are only on the
# classpath under that opt-in profile (pom.xml); omitting it fails with
# ClassNotFoundException: io.confluent.kafka.serializers.protobuf.KafkaProtobufDeserializer,
# found live 2026-09-09. test-unit excludes only integration/** (the
# Testcontainers-based test needing Docker) and doesn't need -Pconfluent —
# every other test class compiles and runs without it.

test:
	./mvnw test -Pconfluent

test-unit:
	./mvnw test -Dtest='!**/integration/**'

test-integration:
	./mvnw test -Dtest="**/integration/**" -Pconfluent

# ── Load tests ───────────────────────────────────────────────────────────────
# Requires: make dev must already be running.
# InfluxDB + Grafana start automatically and remain up until make stop.

load-test:
	@STATUS=$$(docker inspect --format='{{.State.Health.Status}}' fraud-engine-dev 2>/dev/null); \
	 if [ "$$STATUS" != "healthy" ]; then \
	   echo "fraud-engine-dev is not running or not healthy — run make dev first"; exit 1; \
	 fi
	$(LOADTEST_COMPOSE) up -d influxdb grafana
	@SCRIPT=$$(ls "$(CURDIR)/load-tests/scenarios/$(_LT_SCENARIO)-"*.js 2>/dev/null | head -1); \
	 if [ -z "$$SCRIPT" ]; then \
	   echo "No scenario found for '$(_LT_SCENARIO)'. Available:"; \
	   ls load-tests/scenarios/*.js | xargs -n1 basename; \
	   exit 1; \
	 fi; \
	 echo "==> $$(basename $$SCRIPT)  vus=$(or $(_LT_VUS),default)  duration=$(or $(_LT_DURATION),default)"; \
	 docker run --rm \
	   --network fraud-dev_default \
	   -v "$(CURDIR)/load-tests:/load-tests" \
	   -e BASE_URL=http://fraud-engine-dev:8080 \
	   $(if $(_LT_VUS),-e VUS=$(_LT_VUS)) \
	   $(if $(_LT_DURATION),-e DURATION=$(_LT_DURATION)) \
	   grafana/k6 run \
	   --out influxdb=http://fraud-influxdb:8086/k6 \
	   "/load-tests/scenarios/$$(basename $$SCRIPT)"

grafana:
	@echo "Grafana k6 dashboard: http://localhost:3000/d/k6-fraud-engine"
	open http://localhost:3000/d/k6-fraud-engine 2>/dev/null || xdg-open http://localhost:3000/d/k6-fraud-engine 2>/dev/null || true

# ── Help ─────────────────────────────────────────────────────────────────────

help:
	@echo ""
	@echo "  make dev                  Start the dev environment  (port 8081, pg 5433, kafka 9192)"
	@echo "  make stop                 Tear down the dev environment"
	@echo "  make logs                 Tail fraud-engine logs"
	@echo "  make stream [n]           Stream n fake transactions through the rule engine (default 10)"
	@echo "  make ps                   List all running fraud-* containers"
	@echo "  make build                Build the Docker image locally (no containers)"
	@echo ""
	@echo "  make test                 Run all tests (Testcontainers, no infra needed)"
	@echo "  make test-unit            Run unit tests only"
	@echo "  make test-integration     Run integration tests only"
	@echo ""
	@echo "  make load-test            Run scenario 01 with defaults (requires make dev)"
	@echo "  make load-test scenario=N Run a specific scenario (01–05)"
	@echo "  make load-test scenario=N vus=20 duration=5m"
	@echo "  make grafana              Open Grafana k6 dashboard (http://localhost:3000/d/k6-fraud-engine)"
	@echo ""

# When "stream" is a goal with a positional count argument (e.g. "make stream 500"),
# prevent Make from trying to build the count value as a target. The outer guard ensures
# this suppressor only fires when "stream" is actually one of the requested goals —
# without it, $(filter-out stream,...) on any other invocation (e.g. "make dev") resolves
# to that target's own name and silently overrides its recipe with an empty one.
ifneq ($(filter stream,$(MAKECMDGOALS)),)
ifneq ($(_STREAM_COUNT),)
$(_STREAM_COUNT): ;
endif
endif
