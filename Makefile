# AZTCast — common tasks. `make help` lists them.

SHELL := /bin/bash
COMPOSE := docker compose -f deploy/docker-compose.yml

.DEFAULT_GOAL := help
.PHONY: help check dev api web build test lint format up down logs clean

help: ## List available targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

check: ## Verify local prerequisites (Java 21, Node, ffmpeg)
	@./scripts/check-prereqs.sh

dev: ## Run the API and the player together for local development
	@./scripts/dev-up.sh

api: ## Run the API alone (local profile, port 8080)
	cd streaming-api && ./mvnw spring-boot:run

web: ## Run the player alone (Vite dev server, port 5173)
	cd web-player && npm run dev

build: ## Build both applications
	cd streaming-api && ./mvnw -B clean package
	cd web-player && npm ci && npm run build

test: ## Run the API test suite, including the ArchUnit rules
	cd streaming-api && ./mvnw -B clean verify

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
	rm -rf streaming-api/var web-player/dist web-player/node_modules
