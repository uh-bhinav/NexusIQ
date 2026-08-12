# NexusIQ
#
# Targets for phases that are not yet implemented print a clear message rather
# than failing cryptically. See docs/IMPLEMENTATION/ROADMAP.md.

SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE := docker compose
ENV_FILE := .env

# Colours
C := \033[36m
G := \033[32m
Y := \033[33m
R := \033[31m
D := \033[2m
N := \033[0m

.PHONY: help setup env check up down restart clean logs ps verify \
        psql redis-cli topics migrate seed demo test test-unit test-e2e lint eval \
        _wait _wait_full backup restore

help: ## Show this help
	@echo ""
	@echo "  NexusIQ — make targets"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  $(C)%-12s$(N) %s\n", $$1, $$2}'
	@echo ""

# ---------------------------------------------------------------- setup

env: ## Create .env from .env.example if absent
	@if [ ! -f $(ENV_FILE) ]; then \
	  cp .env.example $(ENV_FILE); \
	  echo -e "$(G)Created .env from .env.example$(N)"; \
	  echo -e "$(Y)Set POSTGRES_PASSWORD and JWT_SECRET before starting.$(N)"; \
	else \
	  echo ".env already exists — leaving it alone"; \
	fi

check: ## Verify build prerequisites (java, maven, python, node, docker)
	@./scripts/check-prereqs.sh all

setup: env ## First-time setup: .env + prerequisite check
	@./scripts/check-prereqs.sh all || true
	@echo -e "$(D)Service dependencies are installed by their own phases.$(N)"

# ---------------------------------------------------------------- stack

up: ## Start the local stack
	@./scripts/check-prereqs.sh infra
	@test -f $(ENV_FILE) || { echo -e "$(R)No .env — run 'make env' first.$(N)"; exit 1; }
	$(COMPOSE) up -d
	@echo ""
	@echo -e "$(D)Waiting for health checks...$(N)"
	@$(MAKE) --no-print-directory _wait
	@$(MAKE) --no-print-directory ps

down: ## Stop the stack (volumes preserved)
	$(COMPOSE) down

restart: down up ## Restart the stack

clean: ## Stop and DESTROY all volumes (deletes every local document, chunk and decision)
	@echo -e "$(R)This deletes all local data (postgres + kafka volumes).$(N)"
	@read -p "Type 'yes' to continue: " a; [ "$$a" = "yes" ] || { echo "Aborted."; exit 1; }
	$(COMPOSE) down -v

logs: ## Tail all service logs
	$(COMPOSE) logs -f --tail=100

ps: ## Container status
	@$(COMPOSE) ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'

_wait:
	@for i in $$(seq 1 60); do \
	  unhealthy=$$($(COMPOSE) ps --format json 2>/dev/null \
	    | grep -c '"Health":"starting"' || true); \
	  [ "$$unhealthy" = "0" ] && break; \
	  sleep 3; \
	done

verify: ## Run the Phase 0 acceptance checks
	@./scripts/verify-stack.sh

# ---------------------------------------------------------------- access

psql: ## Open a psql shell
	@$(COMPOSE) exec postgres psql -U $${POSTGRES_USER:-nexusiq} -d $${POSTGRES_DB:-nexusiq}

redis-cli: ## Open a redis-cli shell
	@$(COMPOSE) exec redis redis-cli

topics: ## List Kafka topics
	@$(COMPOSE) exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

backup: ## Dump the running Postgres database to backups/
	@./scripts/backup.sh

restore: ## Restore Postgres from a backup — usage: make restore FILE=backups/nexusiq-....sql.gz
	@./scripts/restore.sh $(FILE)

# ---------------------------------------------------------------- phases

migrate: ## Apply Flyway migrations against the running Postgres
	@./scripts/check-prereqs.sh all
	@test -f $(ENV_FILE) || { echo -e "$(R)No .env — run 'make env' first.$(N)"; exit 1; }
	@set -a && . ./$(ENV_FILE) && set +a && \
	cd backend/spring-api && \
	./mvnw -q flyway:migrate \
	  -Dflyway.url="jdbc:postgresql://localhost:$${POSTGRES_EXPOSED_PORT:-5434}/$${POSTGRES_DB:-nexusiq}" \
	  -Dflyway.user="$${POSTGRES_USER:-nexusiq}" \
	  -Dflyway.password="$${POSTGRES_PASSWORD}"
	@echo -e "$(G)Migrations applied.$(N)"

seed: ## Load the sample enterprise corpus into a demo workspace (idempotent)
	@./scripts/seed.sh

demo: ## One-command demo bootstrap: full stack up (self-migrating), seeded, demo user ready
	@test -f $(ENV_FILE) || { echo -e "$(R)No .env — run 'make env' first.$(N)"; exit 1; }
	@echo -e "$(D)Starting the full stack (infrastructure + application services)...$(N)"
	$(COMPOSE) -f docker-compose.yml -f docker-compose.prod.yml up -d --build
	@echo ""
	@echo -e "$(D)Waiting for health checks...$(N)"
	@$(MAKE) --no-print-directory _wait_full
	@echo -e "$(D)spring-api applies Flyway migrations itself on boot (spring.flyway.enabled=true)$(N)"
	@echo -e "$(D)— no separate 'make migrate' step needed here; that target is for the host-run$(N)"
	@echo -e "$(D)dev loop (LOCAL_DEV.md), where spring-api isn't containerized.$(N)"
	@$(MAKE) --no-print-directory seed
	@echo ""
	@echo -e "$(G)Demo ready — see the credentials printed above.$(N)"

_wait_full:
	@for i in $$(seq 1 90); do \
	  unhealthy=$$($(COMPOSE) -f docker-compose.yml -f docker-compose.prod.yml ps --format json 2>/dev/null \
	    | grep -c '"Health":"starting"' || true); \
	  [ "$$unhealthy" = "0" ] && break; \
	  sleep 2; \
	done
	@$(COMPOSE) -f docker-compose.yml -f docker-compose.prod.yml ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'

test: ## Run all test suites (Java: *Test via Surefire + *IT via Failsafe, i.e. `mvn verify`)
	@ran=0; \
	if [ -f backend/spring-api/pom.xml ]; then (cd backend/spring-api && ./mvnw verify) || exit 1; ran=1; fi; \
	if [ -f ai-service/pyproject.toml ]; then (cd ai-service && uv run pytest) || exit 1; ran=1; fi; \
	if [ -f frontend/web/package.json ]; then (cd frontend/web && npm test) || exit 1; ran=1; fi; \
	[ $$ran -eq 1 ] || echo -e "$(Y)No test suites exist yet — services arrive in Phases 1-2.$(N)"

test-unit: ## Java unit tests only (fast, no Docker) — *Test classes via Surefire
	@cd backend/spring-api && ./mvnw test

test-e2e: ## Cross-service E2E spine test (Phase 10) — requires spring-api + ai-service already running, see docs/OPERATIONS/LOCAL_DEV.md "E2E testing"
	@if ! curl -sf http://localhost:8180/actuator/health >/dev/null 2>&1 || ! curl -sf http://localhost:8000/ready >/dev/null 2>&1; then \
		echo -e "$(R)spring-api (localhost:8180) and/or ai-service (localhost:8000) are not reachable.$(N)"; \
		echo -e "$(Y)This suite drives two already-running processes rather than starting them itself$(N)"; \
		echo -e "$(Y)(unlike 'make test', which is fully Testcontainers-managed). See docs/OPERATIONS/$(N)"; \
		echo -e "$(Y)LOCAL_DEV.md's 'E2E testing' section for the exact startup commands.$(N)"; \
		exit 1; \
	fi
	@cd tests/e2e && uv run pytest -v

lint: ## Run all linters
	@ran=0; \
	if [ -f ai-service/pyproject.toml ]; then (cd ai-service && uv run ruff check app tests && uv run mypy app) || exit 1; ran=1; fi; \
	if [ -f frontend/web/package.json ]; then (cd frontend/web && npm run lint) || exit 1; ran=1; fi; \
	[ $$ran -eq 1 ] || echo -e "$(Y)Nothing to lint yet.$(N)"

eval: ## Run the AI evaluation harness (Phase 10). PROVIDER=mock|gemini (default mock), CASE=EVAL-007 for a single case
	@set -a && . ./$(ENV_FILE) && set +a && \
	cd ai-service && \
	POSTGRES_HOST=localhost POSTGRES_PORT=$${POSTGRES_EXPOSED_PORT:-5434} \
	KAFKA_BOOTSTRAP_SERVERS=$${KAFKA_EXTERNAL_BOOTSTRAP:-localhost:29093} \
	REDIS_HOST=localhost REDIS_PORT=$${REDIS_EXPOSED_PORT:-6380} \
	OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:$${OTEL_GRPC_PORT:-4327} \
	NEXUSIQ_ENV=local STORAGE_LOCAL_PATH=/tmp/nexusiq-documents \
	uv run python -m app.evaluation.harness \
	  --provider $${PROVIDER:-mock} $${CASE:+--case $$CASE}
