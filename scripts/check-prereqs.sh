#!/usr/bin/env bash
# Fails loudly when a runtime dependency is missing.
#
# ffmpeg in particular used to be entirely undeclared: nothing installed it,
# nothing checked for it, and a missing binary only surfaced as an opaque error
# after a torrent had already finished downloading.
set -euo pipefail

ok=0

check() {
  local name=$1 cmd=$2 hint=$3
  if command -v "$cmd" >/dev/null 2>&1; then
    printf '  \033[32m✓\033[0m %-8s %s\n' "$name" "$($cmd --version 2>&1 | head -1)"
  else
    printf '  \033[31m✗\033[0m %-8s missing — %s\n' "$name" "$hint"
    ok=1
  fi
}

echo "Checking prerequisites…"
check java    java    "install a JDK 21 (Temurin, Corretto, or your distro's openjdk-21)"
check node    node    "install Node 20 or newer"
check npm     npm     "ships with Node"
check ffmpeg  ffmpeg  "install ffmpeg — the API shells out to it for every transcode"
check ffprobe ffprobe "ships with ffmpeg"

# The encoder matters as much as the binary: distributions that ship a
# patent-free ffmpeg carry libopenh264 rather than libx264, and the configured
# default is libx264.
if command -v ffmpeg >/dev/null 2>&1; then
  echo
  echo "H.264 encoders available to ffmpeg:"
  # Captured once rather than piped into grep -q twice: grep -q exits on the
  # first match, ffmpeg then takes SIGPIPE, and under `set -o pipefail` that
  # turns a successful match into a failed pipeline.
  encoders=$(ffmpeg -hide_banner -encoders 2>/dev/null || true)
  if grep -qE '\blibx264\b' <<<"$encoders"; then
    printf '  \033[32m✓\033[0m libx264 (the configured default)\n'
  else
    printf '  \033[33m!\033[0m libx264 NOT available — set aztcast.streaming.ffmpeg.video-codec\n'
    if grep -qE '\blibopenh264\b' <<<"$encoders"; then
      printf '    libopenh264 is available; the "local" profile already selects it.\n'
    fi
  fi
fi

exit $ok
