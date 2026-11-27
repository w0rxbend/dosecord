#!/usr/bin/env bash
# Puts the pinned rasterisers of the pixel tests (ADR-022) in one directory, so
# the standard gate can run those tests locally instead of skipping them:
#   - resvg 0.49.0, the release binary for linux x86_64 that CI installs, with
#     the same checksum (.github/workflows/ci.yml);
#   - dosegram-raster, built by scripts/build-raster-worker.sh from the
#     checksummed resvg 0.49.0 source tarball (cargo, Rust 1.85 or later).
# The network is needed once; both downloads are cached and checked.
#
# Usage: scripts/fetch-resvg.sh [output-dir]   (default out/resvg)
# Then run the tests with the environment it prints, for example:
#   eval "$(scripts/fetch-resvg.sh)" && ./mill __.test
set -euo pipefail
cd "$(dirname "$0")/.."

version="0.49.0"
checksum="216637e75e909e53e3098b0875cd5f7da2fdc8b4359771b4a29b539afff9d3f5"
url="https://github.com/linebender/resvg/releases/download/v${version}/resvg-linux-x86_64.tar.gz"
output_dir="${1:-out/resvg}"
cache_dir="${XDG_CACHE_HOME:-$HOME/.cache}/dosegram"
tarball="${cache_dir}/resvg-${version}-linux-x86_64.tar.gz"

if [ "$(uname -s)-$(uname -m)" != "Linux-x86_64" ]; then
  echo "fetch-resvg: the pinned release binary is for Linux x86_64 only" >&2
  exit 1
fi

mkdir -p "$cache_dir" "$output_dir"
if [ ! -f "$tarball" ]; then
  curl -fsSL -o "${tarball}.part" "$url"
  mv "${tarball}.part" "$tarball"
fi
echo "${checksum}  ${tarball}" | sha256sum -c --quiet - >&2
tar xzf "$tarball" -C "$output_dir" resvg
chmod 0755 "$output_dir/resvg"
"$output_dir/resvg" --version | grep -qx "$version"

if [ ! -x "$output_dir/dosegram-raster" ] || ! "$output_dir/dosegram-raster" --version | grep -qx "$version"; then
  scripts/build-raster-worker.sh "$output_dir" >&2
fi

dir="$(cd "$output_dir" && pwd)"
echo "export PATH=\"${dir}:\$PATH\""
echo "export DOSEGRAM_RESVG=\"${dir}/resvg\""
echo "export DOSEGRAM_RASTER_WORKER=\"${dir}/dosegram-raster\""
echo "export DOSEGRAM_REQUIRE_RESVG=true"
