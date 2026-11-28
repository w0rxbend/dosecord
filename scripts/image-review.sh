#!/usr/bin/env bash
# Image review (ROADMAP G4.7): draws every card of the review set with the
# pinned resvg 0.49.0 and writes, under out/image-review,
#   - contact-<light|dark|discreet>.png and each one seen in greyscale and
#     with deuteranopia and protanopia (contact-<variant>-<vision>.png);
#   - vision/<card>.png, the light and dark page in the four ways of seeing;
#   - greyscale-contrast.txt, the 3:1 greyscale check of every mark.
# It fails when resvg is missing or another version, when no image was
# written, or when a mark keeps under 3:1 in greyscale (after writing all).
# CI uploads the directory as the `image-review` artefact.
#
# Usage: scripts/image-review.sh   (resvg from DOSEGRAM_RESVG or the PATH;
# locally: eval "$(scripts/fetch-resvg.sh)" first)
set -euo pipefail
cd "$(dirname "$0")/.."

version="0.49.0"
out="out/image-review"
resvg="${DOSEGRAM_RESVG:-$(command -v resvg || true)}"

if [ -z "$resvg" ] || [ ! -x "$resvg" ]; then
  echo "image-review: resvg ${version} is not on the PATH and DOSEGRAM_RESVG is not set (scripts/fetch-resvg.sh)" >&2
  exit 1
fi
if ! "$resvg" --version | grep -qx "$version"; then
  echo "image-review: $resvg is not resvg ${version}" >&2
  exit 1
fi

rm -rf "$out"
mkdir -p "$out"
DOSEGRAM_RESVG="$resvg" ./mill report-renderer.resvg.test.imageReview

images="$(find "$out" -name '*.png' | wc -l)"
if [ "$images" -eq 0 ]; then
  echo "image-review: no image was written to $out" >&2
  exit 1
fi
echo "image-review: $images images and greyscale-contrast.txt in $out"
