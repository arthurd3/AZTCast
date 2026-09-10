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

# What ffmpeg can actually do matters as much as whether it exists, and the half
# that used to be checked was the wrong half. This script only ever looked for an
# H.264 *encoder*. The failure that shipped was a missing E-AC-3 *decoder* — the
# audio of essentially every AMZN WEB-DL — which cost a 756 MB download before it
# cost an error message.
#
# None of this fails the run. The service adapts to whatever it finds now: it
# picks an encoder from what is present, and copies or drops audio it cannot
# decode. This is here to explain in advance what it is going to have to do.

# The package that carries the missing pieces, named for whatever distro this is.
remedy() {
  if command -v dnf >/dev/null 2>&1; then
    if grep -qi fedora /etc/os-release 2>/dev/null; then
      echo "RPM Fusion: sudo dnf install ffmpeg libavcodec-freeworld  (and mesa-va-drivers-freeworld for VAAPI H.264)"
    else
      echo "sudo dnf install ffmpeg  (from RPM Fusion or EPEL)"
    fi
  elif command -v apt-get >/dev/null 2>&1; then
    echo "sudo apt install ffmpeg libavcodec-extra"
  elif command -v pacman >/dev/null 2>&1; then
    echo "sudo pacman -S ffmpeg"
  elif command -v zypper >/dev/null 2>&1; then
    echo "sudo zypper install ffmpeg-7  (from Packman)"
  elif command -v apk >/dev/null 2>&1; then
    echo "apk add ffmpeg"
  else
    echo "install a full ffmpeg build for your distribution"
  fi
}

if command -v ffmpeg >/dev/null 2>&1; then
  # Captured once rather than piped into grep -q repeatedly: grep -q exits on the
  # first match, ffmpeg then takes SIGPIPE, and under `set -o pipefail` that turns
  # a successful match into a failed pipeline.
  encoders=$(ffmpeg -hide_banner -encoders 2>/dev/null || true)
  decoders=$(ffmpeg -hide_banner -decoders 2>/dev/null || true)
  hwaccels=$(ffmpeg -hide_banner -hwaccels 2>/dev/null | tail -n +2 | tr -s '\n' ' ' || true)
  degraded=0

  echo
  echo "H.264 encoders available to ffmpeg:"
  chosen=""
  for candidate in libx264 libopenh264 h264_v4l2m2m; do
    if grep -qE "\b$candidate\b" <<<"$encoders"; then
      if [ -z "$chosen" ]; then
        chosen=$candidate
        printf '  \033[32m✓\033[0m %-14s (video-codec: auto will select this)\n' "$candidate"
      else
        printf '    %-14s also available\n' "$candidate"
      fi
    fi
  done
  if [ -z "$chosen" ]; then
    printf '  \033[31m✗\033[0m no H.264 encoder at all — nothing can be transcoded\n'
    printf '    %s\n' "$(remedy)"
    ok=1
  fi

  echo
  echo "Decoders for the codecs torrents actually carry:"
  missing=""
  for codec in h264 hevc av1 aac ac3 eac3 dts truehd opus flac; do
    if grep -qE "^ [A-Z.]{6} $codec\b" <<<"$decoders" || grep -qE "\(codec $codec\)" <<<"$decoders"; then
      printf '  \033[32m✓\033[0m %s\n' "$codec"
    else
      printf '  \033[33m!\033[0m %s — sources using it cannot be re-encoded here\n' "$codec"
      missing="$missing $codec"
      degraded=1
    fi
  done

  if [ -n "$missing" ]; then
    echo
    printf '  Missing:%s\n' "$missing"
    printf '  Audio in one of these is copied into the segments untouched (plays on Safari/iOS,\n'
    printf '  silent on Chrome) or dropped, per aztcast.streaming.ffmpeg.on-undecodable.\n'
    printf '  To decode it here instead: %s\n' "$(remedy)"
  fi

  echo
  echo "Hardware acceleration:"
  if [ -n "${hwaccels// /}" ]; then
    printf '  compiled in:%s\n' " $hwaccels"
    # Compiled in is not the same as working. h264_vaapi is listed on hosts whose
    # Mesa driver exposes no H.264 profile at all, and every attempt to use it
    # fails with "Function not implemented".
    if grep -qE '\bh264_vaapi\b' <<<"$encoders" && [ -e /dev/dri/renderD128 ]; then
      if ffmpeg -hide_banner -v error \
          -init_hw_device vaapi=probe:/dev/dri/renderD128 -filter_hw_device probe \
          -f lavfi -i testsrc2=s=320x240:r=25:d=0.1 -vf format=nv12,hwupload \
          -c:v h264_vaapi -frames:v 1 -f null - >/dev/null 2>&1; then
        printf '  \033[32m✓\033[0m h264_vaapi opens on /dev/dri/renderD128\n'
      else
        printf '  \033[33m!\033[0m h264_vaapi is listed but does not open — the driver exposes no H.264 profile\n'
        printf '    %s\n' "$(remedy)"
      fi
    fi
  else
    printf '  none compiled in\n'
  fi

  if [ "$degraded" = "1" ]; then
    echo
    printf '  \033[33m!\033[0m This build is usable but degraded. Nothing here fails the run.\n'
  fi
fi

exit $ok
