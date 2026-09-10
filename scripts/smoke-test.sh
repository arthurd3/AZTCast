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
header() { curl -sI "$1" | tr -d '\r' | grep -i "^$2:" | head -1 | cut -d' ' -f2-; }
# Counts matching headers — a duplicate Cache-Control is as wrong as a missing one.
header_count() { curl -sI "$1" | tr -d '\r' | grep -ci "^$2:" || true; }

echo "Smoke-testing $BASE"
check "health"                       200 "$(status "$BASE/actuator/health")"
check "openapi"                      200 "$(status "$BASE/v3/api-docs")"
check "unknown video -> 404"         404 "$(status "$BASE/api/v1/stream/00000000-0000-0000-0000-000000000000/master.m3u8")"
check "unknown job -> 404"           404 "$(status "$BASE/api/v1/videos/does-not-exist")"
check "blank magnet -> 400"          400 "$(status -X POST "$BASE/api/v1/videos" -H 'Content-Type: application/json' -d '{"magnetUrl":"  "}')"
check "missing magnet -> 400"        400 "$(status -X POST "$BASE/api/v1/videos" -H 'Content-Type: application/json' -d '{}')"
check "traversal via file -> 4xx"    400 "$(status "$BASE/api/v1/stream/aaaa/..%2F..%2Fetc%2Fpasswd")"
check "traversal via id -> 4xx"      400 "$(status "$BASE/api/v1/stream/..%2F..%2Fetc/passwd")"

# The internal location exists only to be reachable by nginx's own re-dispatch. If this
# ever answers anything but 404, the media root is world-readable through the front door.
check "internal media location sealed" 404 "$(status "$BASE/_media/any/720p_000.m4s")"

# A 404 here is the "still transcoding" signal, and 404 is heuristically cacheable. If an
# intermediary is allowed to store it, the client keeps being told "not ready" after it is.
check "not-found is not cacheable"  "no-store" "$(header "$BASE/api/v1/stream/00000000-0000-0000-0000-000000000000/master.m3u8" 'cache-control')"

check "storage reports free space"  200 "$(status "$BASE/api/v1/storage")"
check "deleting an unknown video -> 404" 404 "$(status -X DELETE "$BASE/api/v1/videos/00000000-0000-0000-0000-000000000000")"


# Delivery checks need a real video; skip them cleanly when the caller did not name one.
#   VIDEO_ID=<uuid> ./scripts/smoke-test.sh http://localhost:8000
if [ -n "${VIDEO_ID:-}" ]; then
  seg="$BASE/api/v1/stream/$VIDEO_ID/720p_000.m4s"
  echo
  echo "Delivery checks for $VIDEO_ID"
  check "segment served"             200 "$(status "$seg")"
  check "segment is immutable"       1   "$(curl -sI "$seg" | tr -d '\r' | grep -ci 'cache-control:.*immutable' || true)"
  check "exactly one Cache-Control"  1   "$(header_count "$seg" 'cache-control')"
  check "segment carries a validator" 1  "$(header_count "$seg" 'etag')"
  check "range honoured"             206 "$(status -H 'Range: bytes=0-1023' "$seg")"
  etag=$(header "$seg" 'etag')
  check "revalidation is a 304"      304 "$(status -H "If-None-Match: $etag" "$seg")"
  check "playlist is not immutable"  0   "$(curl -sI "$BASE/api/v1/stream/$VIDEO_ID/master.m3u8" | tr -d '\r' | grep -ci 'cache-control:.*immutable' || true)"
fi

echo
[ "$failures" -eq 0 ] && echo "All checks passed." || echo "$failures check(s) failed."
exit "$failures"
