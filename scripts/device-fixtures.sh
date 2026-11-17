#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
output=app/src/androidTest/assets/generated
mkdir -p "$output/dash"
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i testsrc2=size=96x64:rate=24 \
  -f lavfi -i sine=frequency=440:sample_rate=44100 \
  -t 3 -c:v libx264 -preset ultrafast -g 24 -pix_fmt yuv420p -c:a aac "$output/direct.mp4"
ffmpeg -hide_banner -loglevel error -y -i "$output/direct.mp4" -c copy \
  -f dash -seg_duration 1 -use_template 0 -use_timeline 0 "$output/dash/stream.mpd"
ffmpeg -hide_banner -loglevel error -y -i "$output/direct.mp4" -c copy \
  -f hls -hls_time 1 -hls_playlist_type vod "$output/stream.m3u8"
