#!/usr/bin/env bash
# Builds the motion clips from the stills with ffmpeg. Everything here is regenerable:
# change a slide or a screenshot, re-run, and the clips follow.
#
#   ./video-assets/src/make_motion.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=video-assets/motion
mkdir -p "$OUT"
FF="ffmpeg -y -loglevel error"

# A slow push-in on a still: 1920x1080, 30fps, fading in and out. $1 image, $2 seconds, $3 output.
push_in() {
  local img="$1" secs="$2" out="$3" frames=$(( $2 * 30 ))
  $FF -loop 1 -i "$img" -filter_complex \
    "scale=3840:-1,zoompan=z='min(1.001+0.00035*on,1.09)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=${frames}:s=1920x1080:fps=30,\
     fade=t=in:st=0:d=0.6,fade=t=out:st=$(echo "$secs - 0.6" | bc):d=0.6,format=yuv420p" \
    -t "$secs" -c:v libx264 -preset slow -crf 18 -r 30 "$out"
  echo "  $out (${secs}s)"
}

echo "motion:"
push_in video-assets/slides/01-title.png     6 "$OUT/01-logo-reveal.mp4"
push_in video-assets/slides/00-citi.png      6 "$OUT/02-built-at-citi.mp4"
push_in video-assets/slides/10-credits.png   8 "$OUT/05-credits.mp4"
push_in video-assets/slides/03-one-console.png 6 "$OUT/03-protocol-wall.mp4"

# A live beat: the request in flight, then the response arriving. Both frames are real captures
# taken 8 seconds apart during one run (see src/seq/), cross-dissolved so the response lands.
before=video-assets/src/seq/rest-before.png
after=video-assets/src/seq/rest-after.png
if [[ -f "$before" && -f "$after" ]]; then
  $FF -loop 1 -t 2.0 -i "$before" -loop 1 -t 3.0 -i "$after" -filter_complex \
    "[0:v]scale=1920:-1,zoompan=z='min(1.001+0.0004*on,1.04)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=60:s=1920x1080:fps=30[a];\
     [1:v]scale=1920:-1,zoompan=z='min(1.02+0.0004*on,1.06)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=90:s=1920x1080:fps=30[b];\
     [a][b]xfade=transition=fade:duration=0.35:offset=1.65,fade=t=in:st=0:d=0.4,fade=t=out:st=4.2:d=0.4,format=yuv420p" \
    -t 4.6 -c:v libx264 -preset slow -crf 18 -r 30 "$OUT/06-request-response.mp4"
  echo "  $OUT/06-request-response.mp4 (4.6s)"
fi

# The product montage: eight screens, each pushed in slowly, cross-dissolved into one clip.
SHOTS=(01-one-console 02-rest-client 03-sql-workbench 04-kafka 05-mongodb 06-ibmmq 07-graphql 08-s3)
PER=4          # seconds on screen per shot
XF=0.6         # cross-dissolve length
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
i=0
for s in "${SHOTS[@]}"; do
  $FF -loop 1 -i "video-assets/screenshots/$s.png" -filter_complex \
    "scale=3840:-1,zoompan=z='min(1.001+0.0004*on,1.06)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$((PER*30)):s=1920x1080:fps=30,format=yuv420p" \
    -t "$PER" -c:v libx264 -preset fast -crf 18 -r 30 "$tmp/$i.mp4"
  i=$((i+1))
done

# Chain the cross-dissolves: each clip starts XF seconds before the previous one ends.
inputs=(); filter=""; prev="0:v"; offset=0
for ((n=0; n<i; n++)); do inputs+=(-i "$tmp/$n.mp4"); done
for ((n=1; n<i; n++)); do
  offset=$(echo "$offset + $PER - $XF" | bc)
  label="x$n"
  filter+="[$prev][$n:v]xfade=transition=fade:duration=$XF:offset=$offset[$label];"
  prev="$label"
done
filter="${filter%;}"
$FF "${inputs[@]}" -filter_complex "$filter" -map "[$prev]" -c:v libx264 -preset slow -crf 18 -r 30 \
    "$OUT/04-product-montage.mp4"
echo "  $OUT/04-product-montage.mp4"
