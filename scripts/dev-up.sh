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

# A previous run that did not shut down cleanly still owns :8080, and the API's only
# answer to that is to fail forty seconds later with a stack trace about a bean.
./scripts/dev-down.sh || {
  echo "Could not clear the previous run." >&2
  exit 1
}

# Job control, in a script that has no terminal of its own. This is not cosmetic: it makes
# each background job the leader of its own process group, and that group is the only
# handle that reaches everything the job goes on to spawn. `spring-boot:run` always forks
# the application into a second JVM, and npm runs Vite as a grandchild, so signalling a
# job's own pid leaves both behind — which is how :8080 stays occupied for hours.
set -m

pids=()

cleanup() {
  # Disarmed first, before anything below can raise a signal. The previous version called
  # `kill 0`, which signals the whole process group — this shell included. With the trap
  # still armed, delivery re-entered cleanup at that very line and bash recursed until it
  # exhausted its stack: the screenful of "Stopping…" and the segfault make reported.
  #
  # Disarming alone would not have been enough. A disarmed SIGTERM reverts to its default
  # disposition, so `kill 0` would still kill this shell where it stands and everything
  # after it would be dead code. Both halves were needed: disarm, and stop killing 0.
  trap - EXIT INT TERM
  echo
  echo "Stopping…"
  local pid deadline
  local -a remaining=()
  # Every pid here leads its own process group, courtesy of `set -m`, so the negative pid
  # reaches Maven's forked JVM and npm's Vite too.
  for pid in "${pids[@]}"; do kill -TERM -- "-$pid" 2>/dev/null || true; done

  deadline=$((SECONDS + 8))
  while :; do
    remaining=()
    for pid in "${pids[@]}"; do
      # `if`, not `&&`: as the last statement of the loop body, a false test would become
      # the body's exit status and errexit would end the script mid-teardown.
      if kill -0 -- "-$pid" 2>/dev/null; then remaining+=("$pid"); fi
    done
    if [ ${#remaining[@]} -eq 0 ]; then break; fi
    if [ "$SECONDS" -ge "$deadline" ]; then break; fi
    sleep 0.2
  done
  for pid in "${remaining[@]}"; do kill -KILL -- "-$pid" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM

echo "Starting API on :8080 (local profile)…"
(cd streaming-api && exec ./mvnw -q spring-boot:run) &
api_pgid=$!
pids+=("$api_pgid")

echo "Waiting for the API…"
api_up=
for _ in $(seq 1 90); do
  if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
    api_up=1
    echo "API is up."
    break
  fi
  # A dead Maven means the API is never coming up, and sitting out the remaining eighty
  # seconds only delays the news. This loop used to fall through in silence, which is how
  # a port clash ended with the player running in front of nothing.
  if ! kill -0 -- "-$api_pgid" 2>/dev/null; then break; fi
  sleep 1
done

if [ -z "$api_up" ]; then
  echo "The API did not come up — see the output above. Not starting the player." >&2
  exit 1
fi

echo "Starting the player on :5173…"
(
  cd web-player
  # Three separate statements. As `[ -d node_modules ] || npm ci …; npm run dev` the line
  # bound as `(cd … && [ -d node_modules ]) || npm ci …`, so a failed cd ran the install in
  # the repository root — and the `;` started the dev server even when that install had
  # just failed, in a subshell whose parent never learned of it.
  if [ ! -d node_modules ]; then npm ci --no-fund --no-audit; fi
  exec npm run dev
) &
pids+=("$!")

# Either side exiting ends the session. A player with no backend behind it is not a
# development environment, and leaving it up is how a failed API goes unnoticed for hours.
wait -n
