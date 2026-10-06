#!/usr/bin/env bash
# Downloads Material Symbols Rounded, cut down to the icons the app uses
# (scripts/icons.txt, one ligature name per line), as two static fonts:
# outlined and filled. Run after adding an icon name to icons.txt:
#
#   android/scripts/fetch-icon-font.sh
#
# Google Fonts serves the subset; without a browser User-Agent it answers
# with TrueType files, which is what Android needs.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
fonts="$here/../app/src/main/res/font"
names="$(sort -u "$here/icons.txt" | grep -v '^$' | paste -sd, -)"

fetch() { # fill (0|1), output file
  local css url
  css="$(curl -fsS "https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,$1,0&icon_names=$names&display=block")"
  url="$(grep -oE 'https://fonts.gstatic.com[^)]+' <<<"$css")"
  curl -fsS -o "$fonts/$2" "$url"
  echo "$2: $(wc -c <"$fonts/$2") bytes"
}

fetch 0 material_symbols_rounded.ttf
fetch 1 material_symbols_rounded_filled.ttf
