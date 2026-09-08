#!/usr/bin/env bash
# Exercises the API contract against a running instance.
#   ./scripts/smoke-test.sh [base-url]   (default http://localhost:8080)
set -euo pipefail
BASE=${1:-http://localhost:8080}
failures=0

check() {
  local label=$1 expected=$2 actual=$3
  if [ "$actual" = "$expected" ]; then
    printf '  \033[32m✓\033[0m %-52s %s\n' "$label" "$actual"
  else
    printf '  \033[31m✗\033[0m %-52s got %s, want %s\n' "$label" "$actual" "$expected"
    failures=$((failures + 1))
  fi
}

status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "Smoke-testing $BASE"
check "health"                       200 "$(status "$BASE/actuator/health")"
check "openapi"                      200 "$(status "$BASE/v3/api-docs")"
check "unknown video -> 404"         404 "$(status "$BASE/api/v1/stream/00000000-0000-0000-0000-000000000000/master.m3u8")"
check "unknown job -> 404"           404 "$(status "$BASE/api/v1/videos/does-not-exist")"
check "blank magnet -> 400"          400 "$(status -X POST "$BASE/api/v1/videos" -H 'Content-Type: application/json' -d '{"magnetUrl":"  "}')"
check "missing magnet -> 400"        400 "$(status -X POST "$BASE/api/v1/videos" -H 'Content-Type: application/json' -d '{}')"
check "traversal via file -> 4xx"    400 "$(status "$BASE/api/v1/stream/aaaa/..%2F..%2Fetc%2Fpasswd")"
check "traversal via id -> 4xx"      400 "$(status "$BASE/api/v1/stream/..%2F..%2Fetc/passwd")"

echo
[ "$failures" -eq 0 ] && echo "All checks passed." || echo "$failures check(s) failed."
exit "$failures"
