#!/usr/bin/env bash
# Image render check (ROADMAP G1.9, ADR-022): the app image carries resvg and
# the pinned font pack, and the two draw a line of text in each font role to a
# PNG inside the image, with system fonts switched off as the app runs them.
#
# Usage: scripts/image-render-check.sh [image]   (default dosegram:ci)
set -euo pipefail

image="${1:-dosegram:ci}"
svg='<svg xmlns="http://www.w3.org/2000/svg" width="400" height="200"><rect width="400" height="200" fill="#0f222b"/>'
svg+='<text x="16" y="60" font-family="Geologica" font-weight="600" font-size="48" fill="#e8f0f4">Today 12</text>'
svg+='<text x="16" y="120" font-family="IBM Plex Sans" font-weight="500" font-size="40" fill="#e8f0f4">Water 1.4 l</text>'
svg+='<text x="16" y="180" font-family="IBM Plex Sans Condensed" font-weight="500" font-size="40" fill="#e8f0f4">Item 1</text></svg>'

# The variables are expanded inside the container, where the image sets them.
signature="$(printf '%s' "$svg" | docker run --rm -i --entrypoint sh "$image" -c \
  'test "$("$RESVG_PATH" --version)" = 0.49.0 &&
   "$RESVG_PATH" --skip-system-fonts --use-fonts-dir "$RENDER_FONTS_DIR" --resources-dir "$(mktemp -d)" - -c | head -c 8 | od -An -tx1' \
  | tr -d ' \n')"

if [ "$signature" != "89504e470d0a1a0a" ]; then
  echo "image-render-check: FAIL — no PNG from resvg in $image" >&2
  exit 1
fi
echo "image-render-check: PASS — resvg 0.49.0 and the font pack draw in $image"
