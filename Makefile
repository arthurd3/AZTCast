# AZTCast — common tasks. `make help` lists them.

SHELL := /bin/bash
COMPOSE := docker compose -f deploy/docker-compose.yml

# Reaches scripts/dev-down.sh, which refuses to signal a process from outside this
# checkout unless this is set: `make dev FORCE_PORTS=1`.
export FORCE_PORTS

.DEFAULT_GOAL := help
.PHONY: help check dev dev-down api web build engine test bench lint format up down logs clean

help: ## List available targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

check: ## Verify local prerequisites (Java 21, Node, ffmpeg)
	@./scripts/check-prereqs.sh

dev: ## Run the API and the player together for local development
	@./scripts/dev-up.sh

dev-down: ## Stop a leftover local dev run (frees :8080 and :5173)
	@./scripts/dev-down.sh

api: ## Run the API alone (local profile, port 8080)
	cd streaming-api && ./mvnw spring-boot:run

web: ## Run the player alone (Vite dev server, port 5173)
	cd web-player && npm run dev

build: ## Build both applications
	cd streaming-api && ./mvnw -B clean package
	cd web-player && npm ci && npm run build

# Not part of `build` or `test`, deliberately. The engine needs libtorrent, which is
# packaged on Debian and not everywhere, so it builds in its container rather than on
# a contributor's machine — and `make test` stays runnable by someone who never touches
# it. Until this target existed the only way to find out whether the C++ still compiled
# was to run all of `make up`.
engine: ## Build the sandboxed torrent engine image (needs Docker)
	docker build -t aztcast/torrent-engine:dev torrent-engine

test: ## Run the API test suite, including the ArchUnit rules
	cd streaming-api && ./mvnw -B clean verify

# ARGS is forwarded, so: make bench ARGS="--vmaf --runs 5"
bench: ## Measure a transcode ladder on this machine (ARGS="--vmaf")
	@./scripts/bench.sh $(ARGS)

lint: ## Lint and format-check the player
	cd web-player && npx eslint . && npx prettier --check .

format: ## Reformat the player sources
	cd web-player && npx prettier --write .

up: ## Build and start the full stack in containers (http://localhost:8000)
	$(COMPOSE) up --build -d

down: ## Stop the stack
	$(COMPOSE) down

logs: ## Follow container logs
	$(COMPOSE) logs -f

clean: ## Remove build output and downloaded media
	cd streaming-api && ./mvnw -B -q clean
	rm -rf streaming-api/var web-player/dist web-player/node_modules torrent-engine/build
