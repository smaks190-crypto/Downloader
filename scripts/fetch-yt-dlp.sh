#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n '1p' yt-dlp.lock)
expected=$(sed -n '2p' yt-dlp.lock)
[[ "$version" =~ ^[0-9]{4}\.[0-9]{2}\.[0-9]{2}$ && "$expected" =~ ^[a-f0-9]{64}$ ]]
destination="app/build/generated/ytDlp/raw/ytdlp"
mkdir -p "$(dirname "$destination")"
part="${destination}.tmp"
trap 'rm -f "$part"' EXIT
curl --fail --silent --show-error --location --retry 3 --max-time 90 \
  "https://github.com/yt-dlp/yt-dlp/releases/download/$version/yt-dlp" -o "$part"
echo "$expected  $part" | sha256sum --check
mv "$part" "$destination"
