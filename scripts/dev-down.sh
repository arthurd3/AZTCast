#!/usr/bin/env bash
# Clears whatever a previous local development run left behind, so the next one can bind
# its ports.
#
# What this hunts is unreachable through any handle the previous run could have left.
# `spring-boot:run` always forks the application into a second JVM, and when its Maven
# parent dies without running its shutdown hook that JVM is reparented to init in a
# session of its own: no pid file, no process group and no parent link connects it to the
# shell that starts the next run. The two handles that do survive are the port it holds
# and what its command line says it is, so those are what this uses.
#
# The narrow scope is deliberate. This is a developer machine with unrelated containers
# and JVMs on it, and `pkill java` would be an act of vandalism. Nothing is signalled
# here unless it names *this* checkout.
set -euo pipefail
cd "$(dirname "$0")/.."

# The physical path: /proc/<pid>/cwd comes back with symlinks already resolved, so a
# logical path would never compare equal to it.
REPO=$(pwd -P)

API_PORT=${API_PORT:-8080}
WEB_PORT=${DEV_WEB_PORT:-5173}
# Long enough for a graceful Tomcat shutdown and for the torrent session to close its
# files. Cutting it short is how var/ ends up holding a half-written segment.
GRACE=${DEV_DOWN_GRACE:-10}

# Never signal the shell running this, or anything it runs inside. Under `make dev` that
# chain is dev-down.sh → dev-up.sh → make → the terminal's own shell, and every one of
# them has this repository as its working directory.
protected=" $$ $PPID "
walk=$PPID
while [ -n "$walk" ] && [ "$walk" -gt 1 ] 2>/dev/null; do
  walk=$(ps -o ppid= -p "$walk" 2>/dev/null | tr -d ' ') || break
  [ -n "$walk" ] || break
  protected+="$walk "
done
is_protected() { case "$protected" in *" $1 "*) return 0 ;; esac; return 1; }

# Three gates in series, and the order is what makes this safe rather than merely
# effective.
is_ours() {
  local pid=$1 cwd cmd comm
  [ -O "/proc/$pid" ] || return 1

  # The name gate comes first, and it is not hygiene. Without it, any shell whose command
  # line merely mentions this checkout — a grep, a `cd`, the editor's own session —
  # satisfies the signature gate below and gets signalled along with the real targets.
  comm=$(cat "/proc/$pid/comm" 2>/dev/null) || return 1
  case "$comm" in
    java|node|ffmpeg) ;;
    # npm rewrites its own argv, so the wrapper around Vite is named "npm run dev" rather
    # than "node". Matching only the three binaries above would kill Vite and leave its
    # parent behind.
    npm*) ;;
    *) return 1 ;;
  esac

  cwd=$(readlink "/proc/$pid/cwd" 2>/dev/null) || return 1
  case "$cwd" in "$REPO" | "$REPO"/*) ;; *) return 1 ;; esac

  # npm and ffmpeg do not name the repository in their arguments — "npm run dev" is the
  # whole command line, and ffmpeg is only ever running here because the API launched it
  # — so for those two the working directory settles it.
  case "$comm" in npm* | ffmpeg) return 0 ;; esac

  cmd=$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null) || return 1
  case "$cmd" in
    *com.azt.streaming.StreamingApiApplication*) return 0 ;; # the forked application JVM
    *classworlds*) return 0 ;;                               # the Maven launcher mvnw exec'd into
    *"$REPO/web-player"*) return 0 ;;                        # Vite, which node names by absolute path
  esac
  return 1
}

# Whoever holds the port is authoritative — it is what will actually make the next bind
# fail, whatever it claims to be. The `sport` filter matches both address families, and
# that matters here: the API opens the v6 wildcard and Vite listens on v6 loopback only,
# so a grep for an IPv4 address would find neither.
port_pids() { ss -ltnHp "sport = :$1" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | sort -u; }
port_busy() { [ -n "$(ss -ltnH "sport = :$1" 2>/dev/null)" ]; }

echo "Clearing a previous run…"

declare -a targets=() foreign=()
seen=" "
add_target() {
  case "$seen" in *" $1 "*) return ;; esac
  seen+="$1 "
  targets+=("$1")
}

# The ports first, since they are the reason this script exists at all.
for port in "$API_PORT" "$WEB_PORT"; do
  port_busy "$port" || continue
  named=0
  for pid in $(port_pids "$port"); do
    named=1
    is_protected "$pid" && continue
    if is_ours "$pid"; then add_target "$pid"; else foreign+=("$port:$pid"); fi
  done
  # ss only names the owner of sockets we own; root or another user holding the port
  # leaves us knowing it is busy and nothing else.
  [ "$named" -eq 1 ] || foreign+=("$port:?")
done

# Then the stragglers that hold no port at all: a Maven launcher whose application JVM
# already failed to bind, or a JVM still seeding a torrent. They cost bandwidth and CPU
# rather than a port, which is why nothing has ever noticed them.
for entry in /proc/[0-9]*; do
  pid=${entry#/proc/}
  is_protected "$pid" && continue
  is_ours "$pid" && add_target "$pid"
done

if [ ${#foreign[@]} -gt 0 ]; then
  for item in "${foreign[@]}"; do
    port=${item%%:*}
    pid=${item##*:}
    if [ "$pid" = "?" ]; then
      printf '  \033[31m✗\033[0m %-8s :%s is held by another user — root can see by what\n' "port" "$port"
    else
      printf '  \033[31m✗\033[0m %-8s :%s is held by %s (pid %s), which is not from this checkout\n' \
        "port" "$port" "$(ps -o comm= -p "$pid" 2>/dev/null || echo '?')" "$pid"
    fi
  done
  if [ -z "${FORCE_PORTS:-}" ]; then
    echo "Nothing was signalled. Stop it yourself, or re-run with FORCE_PORTS=1." >&2
    exit 2
  fi
  printf '  \033[33m!\033[0m %-8s FORCE_PORTS=1 — taking those down too\n' "port"
  for item in "${foreign[@]}"; do
    pid=${item##*:}
    [ "$pid" = "?" ] || add_target "$pid"
  done
fi

if [ ${#targets[@]} -eq 0 ]; then
  printf '  \033[32m✓\033[0m %-8s nothing left over\n' "dev"
else
  for pid in "${targets[@]}"; do
    printf '  \033[33m!\033[0m %-8s stopping pid %s (up %s) %s\n' "dev" "$pid" \
      "$(ps -o etime= -p "$pid" 2>/dev/null | tr -d ' ' || echo '?')" \
      "$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null | cut -c1-54)"
  done

  # By pid, not by process group. A leftover Vite is often still inside a live `make dev`'s
  # process group, and signalling that group would take make and the terminal's foreground
  # job down with it. dev-up.sh's own teardown may kill by group precisely because it
  # created those groups; this script may not assume anything of the sort.
  #
  # SIGTERM rather than SIGKILL, and that distinction is the whole ballgame for Maven: the
  # plugin destroys the application JVM from a JVM shutdown hook, and SIGKILL is the one
  # signal that skips shutdown hooks. Killing Maven outright is how a JVM ends up holding
  # :8080 for nine hours after its parent is gone. The escalation below is safe only
  # because it holds the forked JVM's pid too and kills it directly.
  kill -TERM "${targets[@]}" 2>/dev/null || true

  deadline=$((SECONDS + GRACE))
  alive=()
  while :; do
    alive=()
    for pid in "${targets[@]}"; do
      # `if`, not `&&`: as the last statement of the loop body a false test would be the
      # body's exit status, and errexit would end the script here.
      if kill -0 "$pid" 2>/dev/null; then alive+=("$pid"); fi
    done
    if [ ${#alive[@]} -eq 0 ]; then break; fi
    if [ "$SECONDS" -ge "$deadline" ]; then break; fi
    sleep 0.2
  done

  if [ ${#alive[@]} -gt 0 ]; then
    printf '  \033[33m!\033[0m %-8s %s did not stop within %ss — killing\n' "dev" "${alive[*]}" "$GRACE"
    kill -KILL "${alive[@]}" 2>/dev/null || true
    sleep 0.3
  fi
fi

# A port is free when nothing is LISTENing on it, which is not the same as having no
# sockets at all. Connections that were open when the API died linger in TIME_WAIT for a
# minute as (port, peer) tuples; they never block a fresh listener — Tomcat binds with
# SO_REUSEADDR — and waiting them out would add that minute to every single start.
wait_port_free() {
  local port=$1 deadline=$((SECONDS + 10))
  while port_busy "$port"; do
    if [ "$SECONDS" -ge "$deadline" ]; then return 1; fi
    sleep 0.2
  done
}

rc=0
for port in "$API_PORT" "$WEB_PORT"; do
  if wait_port_free "$port"; then
    printf '  \033[32m✓\033[0m %-8s :%s free\n' "port" "$port"
  else
    printf '  \033[31m✗\033[0m %-8s :%s still bound after the kill\n' "port" "$port"
    rc=1
  fi
done

# The containers contend for nothing here — compose leaves the API's port unpublished and
# puts the player on ${WEB_PORT:-8000} — so this only mentions them. They do join the same
# swarms as a local run, which is worth knowing and not worth acting on unasked. The
# compose file declares `name: aztcast`, so this can never see anyone else's stack.
if command -v docker >/dev/null 2>&1; then
  running=$(docker compose -f deploy/docker-compose.yml ps --status running -q 2>/dev/null | grep -c . || true)
  if [ "${running:-0}" -gt 0 ]; then
    printf '  \033[33m!\033[0m %-8s %s AZTCast container(s) still up — `make down` if you want them gone\n' \
      "compose" "$running"
  fi
fi

exit $rc
