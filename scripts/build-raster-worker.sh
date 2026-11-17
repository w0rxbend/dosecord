#!/usr/bin/env bash
# Builds the long-lived rasteriser worker `dosegram-raster` (report-renderer/worker,
# ADR-022 addendum of 2026-11-15) the way docker/Dockerfile does: inside the
# extracted resvg 0.49.0 release tarball, whose vendored crates make the build
# offline and whose checksum covers all of the code. Needs cargo (Rust 1.85 or
# later) and, on the first run, the network to fetch the tarball once.
#
# Usage: scripts/build-raster-worker.sh [output-dir]   (default out/raster-worker)
# Then:  DOSEGRAM_RASTER_WORKER=<output-dir>/dosegram-raster for the tests, or
#        RENDER_WORKER_PATH=<output-dir>/dosegram-raster for the app.
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"

version="0.49.0"
checksum="a1b167d575d0be44e303c76b46fe0aefc5c5748a46979ebae6a7dc3276c2ce8c"
url="https://github.com/linebender/resvg/releases/download/v${version}/resvg-${version}.tar.xz"
output_dir="${1:-out/raster-worker}"
cache_dir="${XDG_CACHE_HOME:-$HOME/.cache}/dosegram"
tarball="${cache_dir}/resvg-${version}.tar.xz"

mkdir -p "$cache_dir" "$output_dir"
if [ ! -f "$tarball" ]; then
  curl -fsSL -o "${tarball}.part" "$url"
  mv "${tarball}.part" "$tarball"
fi
echo "${checksum}  ${tarball}" | sha256sum -c --quiet -

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
tar xJf "$tarball" -C "$work"
cp -r "$root/report-renderer/worker" "$work/resvg-${version}/dosegram-raster"
(cd "$work/resvg-${version}/dosegram-raster" && cargo build --quiet --profile production --locked --offline)
install -m 0755 "$work/resvg-${version}/dosegram-raster/target/production/dosegram-raster" "$output_dir/dosegram-raster"
"$output_dir/dosegram-raster" --version | grep -qx "$version"
echo "built $output_dir/dosegram-raster"
