#!/bin/bash
# Assembles the iPhone/web version: shared UI from the Android app + web-specific files.
set -e
OUT=${1:-/root/web-dist}
rm -rf "$OUT" && mkdir -p "$OUT"
cp /root/rb/assets/www/app.js /root/rb/assets/www/style.css "$OUT/"
cp /root/web/*.html /root/web/*.js /root/web/*.json /root/web/*.png "$OUT/"
echo "built $OUT"
