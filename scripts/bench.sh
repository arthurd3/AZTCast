#!/usr/bin/env bash
# Measures what a ladder actually costs, so "faster" can be a number instead of a feeling.
#
#   ./scripts/bench.sh [--source FILE] [--variant baseline|threads|vaapi] [--runs N] [--vmaf]
#
# There was no way to answer "how long does a transcode take here" before this. The one metric that
# existed, aztcast.transcode, spans probe, encode, subtitles, poster and playlist writing together,
# so it could not tell an encoder change from a poster change.
#
# The command below deliberately mirrors FfmpegCommandBuilder rather than calling the application:
# a bench has to be able to run variants the application cannot emit yet, which is the entire point
# of having one. When the builder changes, this changes with it — they are checked against each
# other by running the app at DEBUG and diffing the logged command.
#
# Default source is the Sintel trailer: 1080p H.264 High, 52s, CC-BY Blender Foundation. Real
# content matters. testsrc2 compresses unrealistically and would flatter every encoder equally,
# which is the one thing a comparison must not do.
set -euo pipefail

SOURCE=""
VARIANT="baseline"
RUNS=1
WANT_VMAF=0
FORCE_THREADS=""
CONCURRENT=1
CACHE="${TMPDIR:-/tmp}/aztcast-bench"
SOURCE_URL="https://download.blender.org/durian/trailer/sintel_trailer-1080p.mp4"

while [ $# -gt 0 ]; do
  case "$1" in
    --source)  SOURCE="$2"; shift 2 ;;
    --variant) VARIANT="$2"; shift 2 ;;
    --runs)    RUNS="$2"; shift 2 ;;
    --vmaf)    WANT_VMAF=1; shift ;;
    --threads) FORCE_THREADS="$2"; shift 2 ;;
    --concurrent) CONCURRENT="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$CACHE"
if [ -z "$SOURCE" ]; then
  SOURCE="$CACHE/source.mp4"
  [ -f "$SOURCE" ] || { echo "Fetching the reference clip once into $SOURCE"; curl -fsSL -o "$SOURCE" "$SOURCE_URL"; }
fi
[ -f "$SOURCE" ] || { echo "no such source: $SOURCE" >&2; exit 1; }

# The ladder is planned from the source, exactly as LadderPlanner does it: rungs taller than 90% of
# the source are dropped, and the top rung is copied rather than encoded when the source is H.264
# (ADR-0018). Getting this wrong would benchmark a different amount of work than the app does.
probe() { ffprobe -v error -select_streams v:0 -show_entries "$1" -of default=noprint_wrappers=1:nokey=1 "$SOURCE" | head -1; }
HEIGHT=$(probe stream=height)
WIDTH=$(probe stream=width)
CODEC=$(probe stream=codec_name)
FPS=$(ffprobe -v error -select_streams v:0 -show_entries stream=r_frame_rate -of csv=p=0 "$SOURCE" | head -1 | awk -F/ '{printf "%.3f", $1/$2}')
DURATION=$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$SOURCE")
SEG=4
GOP=$(awk -v f="$FPS" -v s="$SEG" 'BEGIN{printf "%d", int(f*s+0.5)}')

RUNG_NAMES=(1080p 720p 480p 360p 240p)
RUNG_W=(1920 1280 854 640 426)
RUNG_H=(1080 720 480 360 240)
RUNG_K=(5000 3000 1500 900 500)

COPY_TOP=0
[ "$CODEC" = "h264" ] && COPY_TOP=1
CEILING=$(awk -v h="$HEIGHT" -v c="$COPY_TOP" 'BEGIN{ if (c) printf "%d", int(h*0.9+0.999); else printf "%d", h+1 }')

ENCODED_IDX=()
for i in "${!RUNG_NAMES[@]}"; do
  [ "${RUNG_H[$i]}" -le "$CEILING" ] && ENCODED_IDX+=("$i")
done
NRUNGS=${#ENCODED_IDX[@]}

# ---- the knob under test -------------------------------------------------------------------
# baseline: no -threads at all, which is what the application emits today. Each libx264 instance
#           then auto-sizes to min(1.5*cores, 16) and the ladder oversubscribes the box.
# threads:  a budget divided across the rungs, the Phase 1 arithmetic.
CORES=$(nproc)
case "$VARIANT" in
  baseline) THREADS_ARG="" ;;
  threads)
    RESERVE=$(awk -v c="$CORES" 'BEGIN{printf "%d", int(c*0.15+0.999)}')
    PER=$(awk -v c="$CORES" -v r="$RESERVE" -v n="$NRUNGS" 'BEGIN{v=int((c-r)/n); if(v<1)v=1; if(v>8)v=8; printf "%d", v}')
    THREADS_ARG="$PER" ;;
  vaapi) THREADS_ARG="" ;;
  *) echo "unknown variant: $VARIANT" >&2; exit 2 ;;
esac
# An explicit count overrides the formula, which is how the formula's constants get chosen at all.
[ -n "$FORCE_THREADS" ] && THREADS_ARG="$FORCE_THREADS"

echo "source     $(basename "$SOURCE")  ${WIDTH}x${HEIGHT} $CODEC ${FPS}fps ${DURATION%.*}s"
echo "ladder     copy-top=$COPY_TOP  encoded rungs=$NRUNGS (${RUNG_NAMES[*]:1:$NRUNGS})  gop=$GOP"
echo "variant    $VARIANT  cores=$CORES${THREADS_ARG:+  -threads $THREADS_ARG per rung}"
echo

build_command() {
  local out="$1"
  local -a cmd=(ffmpeg -hide_banner -nostdin -y -nostats -loglevel error)
  if [ "$VARIANT" = vaapi ]; then
    cmd+=(-hwaccel vaapi -hwaccel_output_format vaapi -hwaccel_device /dev/dri/renderD128)
  fi
  cmd+=(-i "$SOURCE")

  # The serial cascade the builder emits: each rung is scaled from the one above it rather than
  # from the source, which the builder's javadoc measures at 21% less CPU than parallel scalers.
  local graph="" prev="[0:v]" scaler="scale"
  [ "$VARIANT" = vaapi ] && scaler="scale_vaapi"
  local n=0
  for i in "${ENCODED_IDX[@]}"; do
    n=$((n+1))
    if [ "$n" -lt "$NRUNGS" ]; then
      graph+="${prev}${scaler}=w=${RUNG_W[$i]}:h=${RUNG_H[$i]},split=2[v$((n-1))out][chain$((n-1))];"
      prev="[chain$((n-1))]"
    else
      graph+="${prev}${scaler}=w=${RUNG_W[$i]}:h=${RUNG_H[$i]}[v$((n-1))out]"
    fi
  done
  cmd+=(-filter_complex "$graph")

  local vmap="" si=0
  if [ "$COPY_TOP" = 1 ]; then
    cmd+=(-map 0:v:0 "-c:v:$si" copy)
    vmap+="v:$si,agroup:aud,name:${HEIGHT}p "
    si=$((si+1))
  fi
  local enc="libx264"
  [ "$VARIANT" = vaapi ] && enc="h264_vaapi"
  n=0
  for i in "${ENCODED_IDX[@]}"; do
    local k=${RUNG_K[$i]}
    cmd+=(-map "[v${n}out]" "-c:v:$si" "$enc" "-profile:v:$si" main
          "-b:v:$si" "${k}k" "-maxrate:v:$si" "$((k*107/100))k" "-bufsize:v:$si" "$((k*3/2))k")
    [ "$VARIANT" != vaapi ] && cmd+=("-preset:v:$si" veryfast)
    [ -n "$THREADS_ARG" ] && cmd+=("-threads:v:$si" "$THREADS_ARG")
    cmd+=("-force_key_frames:v:$si" "expr:gte(t,n_forced*$SEG)" "-g:v:$si" "$GOP" "-keyint_min:v:$si" "$GOP")
    vmap+="v:$si,agroup:aud,name:${RUNG_NAMES[$i]} "
    si=$((si+1)); n=$((n+1))
  done

  cmd+=(-map a:0 "-c:a:0" copy -sn -dn
        -f hls -hls_time "$SEG" -hls_playlist_type vod -hls_flags independent_segments
        -hls_segment_type fmp4 -hls_fmp4_init_filename "%v_init.mp4"
        -hls_segment_filename "$out/%v_%03d.m4s"
        -var_stream_map "${vmap}a:0,agroup:aud,name:audio,default:yes"
        "$out/%v.m3u8")
  printf '%s\0' "${cmd[@]}"
}

total_wall=0; total_cpu=0; min_wall=""; min_cpu=""
for run in $(seq 1 "$RUNS"); do
  OUT="$CACHE/out-$VARIANT-$run"; rm -rf "$OUT"; mkdir -p "$OUT"
  mapfile -d '' -t CMD < <(build_command "$OUT")
  STATS="$CACHE/stats-$VARIANT-$run"
  /usr/bin/time -f "%e %U %S %M" -o "$STATS" "${CMD[@]}"
  read -r wall user sys rss < "$STATS"
  cpu=$(awk -v u="$user" -v s="$sys" 'BEGIN{printf "%.1f", u+s}')
  speed=$(awk -v d="$DURATION" -v w="$wall" 'BEGIN{printf "%.2f", d/w}')
  printf 'run %d   wall %6.1fs   cpu %8.1fs   x%-6s peak-rss %d MB\n' \
    "$run" "$wall" "$cpu" "$speed" "$((rss/1024))"
  total_wall=$(awk -v a="$total_wall" -v b="$wall" 'BEGIN{print a+b}')
  total_cpu=$(awk -v a="$total_cpu" -v b="$cpu" 'BEGIN{print a+b}')
  min_wall=$(awk -v m="$min_wall" -v v="$wall" 'BEGIN{ if (m=="" || v+0<m+0) print v; else print m }')
  min_cpu=$(awk -v m="$min_cpu" -v v="$cpu" 'BEGIN{ if (m=="" || v+0<m+0) print v; else print m }')
done
echo
awk -v w="$total_wall" -v c="$total_cpu" -v r="$RUNS" -v d="$DURATION" \
  'BEGIN{printf "mean     wall %6.1fs   cpu %8.1fs   x%.2f realtime\n", w/r, c/r, d/(w/r)}'
# The number to compare on. Anything else running on this machine can only add time, never remove
# it, so the fastest observation is the closest thing to a measurement of this configuration alone.
awk -v w="$min_wall" -v c="$min_cpu" -v d="$DURATION" \
  'BEGIN{printf "best     wall %6.1fs   cpu %8.1fs   x%.2f realtime\n", w, c, d/w}'

# VMAF, so the speed half of any claim has its quality half beside it. The distorted rung is scaled
# back up to the source resolution, which is how an ABR ladder is normally scored: the viewer sees
# it upscaled to their display, so that is the comparison that means something.
if [ "$WANT_VMAF" = 1 ]; then
  echo
  OUT="$CACHE/out-$VARIANT-$RUNS"
  for i in "${ENCODED_IDX[@]}"; do
    name=${RUNG_NAMES[$i]}
    [ -f "$OUT/$name.m3u8" ] || continue
    score=$(ffmpeg -hide_banner -loglevel error -i "$OUT/$name.m3u8" -i "$SOURCE" \
      -filter_complex "[0:v]scale=${WIDTH}:${HEIGHT}:flags=bicubic,setpts=PTS-STARTPTS[d];[1:v]setpts=PTS-STARTPTS[r];[d][r]libvmaf=n_threads=$CORES" \
      -f null - 2>&1 | grep -oE 'VMAF score: [0-9.]+' | awk '{print $3}')
    printf 'vmaf     %-6s %s\n' "$name" "${score:-n/a}"
  done
fi
