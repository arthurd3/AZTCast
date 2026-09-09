#!/usr/bin/env bash
# Starts the API and the player for local development, and stops both on Ctrl-C.
#
# The player talks to the API through Vite's /api proxy, so the browser makes
# only same-origin requests.
set -euo pipefail
cd "$(dirname "$0")/.."

./scripts/check-prereqs.sh || {
  echo "Fix the above before starting." >&2
  exit 1
}

cleanup() {
  echo
  echo "Stopping…"
  # Kill the whole process group so Maven's forked JVM goes too.
  kill 0 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "Starting API on :8080 (local profile)…"
(cd streaming-api && ./mvnw -q spring-boot:run) &

echo "Waiting for the API…"
for _ in $(seq 1 90); do
  if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
    echo "API is up."
    break
  fi
  sleep 1
done

echo "Starting the player on :5173…"
(cd web-player && [ -d node_modules ] || npm ci --no-fund --no-audit; npm run dev) &

wait
