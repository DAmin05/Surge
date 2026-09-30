# Surge developer entry points. Run `make help`.
SHELL := /bin/bash
.DEFAULT_GOAL := help

PY_SERVICES := payment reconciler chaos

.PHONY: help
help: ## Show targets
	@grep -hE '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  \033[36m%-17s\033[0m %s\n", $$1, $$2}'

## ---------------------------------------------------------------- stack
.PHONY: keys rotate-keys up check down nuke ps logs seed smoke saga-storm audit faults
# Keys are generated inside the keygen container so they end up owned by the uid the
# services run as (10001), on Linux as well as on Docker Desktop.
keys: ## Generate token signing keys into secrets/ (only if absent)
	docker compose run --rm --no-deps keygen

rotate-keys: ## Add a new signing key per token type; the old one stays accepted
	docker compose run --rm --no-deps --entrypoint /bin/sh keygen /keys.sh --rotate /secrets/jwt

up: ## Build and start the whole stack, then wait until it is healthy
	docker compose up -d --build
	scripts/check-stack.sh

check: ## Week-0 exit check: every service healthy, every one-shot job exited 0
	scripts/check-stack.sh

down: ## Stop the stack (keeps data volumes)
	docker compose down

nuke: ## Stop the stack and delete all data volumes
	docker compose down -v --remove-orphans

ps: ## Show service status
	docker compose ps -a

logs: ## Follow logs (make logs S=order)
	docker compose logs -f $(S)

seed: ## Seed an event (SECTIONS=10 ROWS=20 SEATS_PER_ROW=50); prints its id
	@docker compose exec -T postgres psql -qtA -U orders_owner -d $${POSTGRES_DB:-surge} \
	  -v sections=$(or $(SECTIONS),10) -v rows=$(or $(ROWS),20) -v seats_per_row=$(or $(SEATS_PER_ROW),50) \
	  < infra/postgres/seed.sql

smoke: ## End-to-end check against the running stack: hold, race, checkout, saga, events
	scripts/smoke.sh

saga-storm: ## Buyers vs. a faulty Payment; all orders must end terminal, 0 violations
	@echo "Use a short payment timeout: PAYMENT_TIMEOUT=PT10S PIN_GRACE=PT10S make up"
	PAYMENT_TIMEOUT=$${PAYMENT_TIMEOUT:-PT10S} scripts/saga-storm.sh

audit: ## Run every invariant now and print the report
	@docker compose exec -T inventory curl -s -X POST http://reconciler:8001/audit | python3 -m json.tool

faults: ## Show or set Payment faults (make faults SET='{"failure_rate":0.4}')
	@docker compose exec -T inventory curl -s $(if $(SET),-X PUT -H 'Content-Type: application/json' -d '$(SET)',) http://payment:8000/faults; echo

## ---------------------------------------------------------------- build & test
.PHONY: test test-java test-rust test-python lint images images-multiarch
test: test-java test-rust test-python ## Run every test suite

test-java: ## Java: build + tests (Testcontainers needs Docker)
	./gradlew build

test-rust: ## Rust: fmt, clippy, tests
	cd gateway && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test

test-python: ## Python: ruff + pytest for each service
	@for s in $(PY_SERVICES); do \
	  echo "== $$s"; \
	  (cd $$s && uv sync --locked -q && uv run ruff check . && uv run ruff format --check . && uv run pytest -q) || exit 1; \
	done

images: ## Build all images for the current platform
	docker buildx bake -f docker-bake.hcl --load

images-multiarch: ## Build amd64+arm64 images and push (REGISTRY=... TAG=...)
	PLATFORMS=linux/amd64,linux/arm64 docker buildx bake -f docker-bake.hcl --push
