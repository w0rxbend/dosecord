# Spike note: render pipeline (G1.2), measured on the development host

Slice: G1.2, the spikes S1, S2, S3, S6, S7 and S10 of research brief 04 section 16, run as a measured decision while
building `report-renderer` (G1.9). Date: 2026-11-13. Documents read: research brief 04, VISUALS_AND_ANALYTICS sections
3 to 6, ROADMAP G1.2 and G1.9.

Verdict: **go for resvg**. The Java2D fallback of brief 04 section 6.2 is not built. What is still open is listed in
section 6; none of it changes the choice of rasteriser.

## 1. Environment

| Item | Value |
|---|---|
| Host | Linux x86-64, AMD Ryzen 7 5700X, 16 hardware threads. Other build agents ran on the same host during every measurement (load average 17 to 39), so the times are upper bounds for this machine |
| JVM | Temurin 25.0.2 (the Mill-provisioned build JDK) |
| resvg, host | 0.49.0 from the distribution, linked against glibc |
| resvg, container | 0.49.0 built from the crate with `cargo install resvg --version 0.49.0 --locked` in `rust:1-alpine` (rustc 1.100.0), then run in `alpine:3` |
| Fonts | The pinned pack in `report-renderer/fonts`: IBM Plex Sans 400, 500, 600, IBM Plex Sans Condensed 500, Geologica 600. System fonts disabled |
| Fixtures | The synthetic view models of `report-renderer/test` (`Samples`), 30 fixtures, 96 rendered variants |

The pinned version is 0.49.0, the version that was executed. Brief 04 names 0.50.0 as the latest release; it was not
executed here, and an upgrade regenerates the pixel goldens in the same change.

## 2. Results against the targets

| Spike | Question | Measured | Target | Verdict |
|---|---|---|---|---|
| S1 | Does a musl build run in a non-root Alpine container? | Built in 80 s. The binary is 5.7 MB, `static-pie linked`. It rendered 8 fixtures in `alpine:3` with `--user 65534:65534 --network none --read-only`, SVG on standard input, PNG on standard output | Renders the fixtures; static; no network or display | Go |
| S2 | Are pixels identical between host and container? | 8 of 8 fixtures: equal SHA-256 of the decoded RGBA pixels, and equal PNG bytes, between the glibc host binary and the musl container binary | Equal decoded-pixel hashes | Go for host against container on x86-64. CI and aarch64 were not measured |
| S3 | Do the budgets hold on one CPU with `-Xmx256m`? | JVM pinned to one core with `taskset -c 0`, `-XX:+UseSerialGC -Xms32m -Xmx256m`, the child process on the same core: rasterise p50 53.4 ms, p95 102.7 ms, maximum 199.9 ms over 700 samples; scene and SVG p95 at most 7.3 ms; cold first render 760 ms; heap in use after the run 4.2 MB | p95 rasterise at most 200 ms | Go. Idle RSS of the whole service against the 200 MB gate needs the image build and is open |
| S6 | Tabular numerals | The digits 0 to 9 have one advance width (600 of 1000 units) in all four IBM Plex Sans files, so columns align without an OpenType feature. Geologica digits are proportional; it is used for hero numerals and titles only | Digits align in a column | Go without the feature. A test asserts it |
| S7 | Process pipes on virtual threads | The adapter serves both pipes from Ox forks (virtual threads) and waits on the process with a timeout. 700 renders, the 96-variant fixture run and the fault tests completed without a stall on JDK 25. A hung process and a process that never reads its input are killed at the timeout | Measured; decision recorded | Go on virtual threads. No platform-thread pool is added; a semaphore bounds concurrency at two child processes |
| S10 | PNG metadata | The chunks of a rendered card are `IHDR`, `IDAT`, `IEND` only. A permanent test asserts that no `tEXt`, `zTXt`, `iTXt`, `tIME` or `eXIf` chunk exists | No text chunks, no timestamps | Go |

## 3. Render time per image

Sixteen cores available, 100 warm samples per template, dark theme, after one cold render. "Scene and SVG" is view
model to SVG text inside the JVM; "rasterise" is process start, font loading, rendering and PNG encoding in the child,
measured from the JVM.

| Template | Scene and SVG p50 | Scene and SVG p95 | Rasterise p50 | Rasterise p95 | Rasterise max | SVG bytes | PNG bytes |
|---|---|---|---|---|---|---|---|
| Mood card | 0.65 ms | 1.69 ms | 38.5 ms | 41.3 ms | 45.2 ms | 1,584 | 67,649 |
| Daily summary | 1.14 ms | 1.65 ms | 49.3 ms | 54.5 ms | 57.8 ms | 7,259 | 123,468 |
| Adherence calendar | 0.84 ms | 1.52 ms | 47.4 ms | 53.0 ms | 85.0 ms | 11,593 | 104,157 |
| Per-medication strip | 0.69 ms | 0.95 ms | 41.2 ms | 43.4 ms | 47.9 ms | 7,727 | 74,029 |
| Hydration vessel | 0.29 ms | 0.41 ms | 34.2 ms | 37.0 ms | 37.3 ms | 2,367 | 54,928 |
| Weekly report | 1.03 ms | 1.50 ms | 47.9 ms | 52.2 ms | 59.6 ms | 18,770 | 109,209 |
| Milestone | 0.16 ms | 0.29 ms | 28.4 ms | 31.1 ms | 33.1 ms | 4,184 | 24,806 |
| All, 700 samples | | | 41.4 ms | 51.0 ms | | | |

| Other measurement | Value |
|---|---|
| Cold first render in a fresh JVM (scene, SVG, rasterise) | 247 ms on 16 cores, 760 ms on one core |
| Peak resident memory of one resvg child (weekly card) | 18.2 MB, outside the JVM heap |
| JVM heap in use after 700 renders | 8.3 MB with the default heap, 4.2 MB with `-Xmx256m` |
| Largest PNG of the 96 fixture variants | under 130 KB; the limit is 2 MB |
| Table text measurer against resvg `--query-all` | within 2 percent for six sample strings in five faces, asserted by a test |

## 4. Decisions taken from the measurements

| Decision | Rationale | Rejected alternative |
|---|---|---|
| resvg as a child process per render, pinned to 0.49.0 | Every target is met with a margin; the musl build runs unprivileged without network and gives the host's pixels | The Java2D painter: kept as the documented fallback, not built |
| Text is measured from a committed table of advance widths cut from the pinned font files | Layout and SVG goldens then need no native tool and are identical on every machine. The measured difference to the rasteriser's own extents is under 2 percent, and every fitted string has slack | A `--query-all` pass per card: one more process start per render and goldens that need the binary |
| Forks on virtual threads, a semaphore of two | S7 showed no stall; the descendants of a timed-out process are killed first so no pipe stays open | A dedicated pool of two platform threads |
| Characters outside Latin, Greek and Cyrillic are drawn as one U+FFFD per run | The pack holds no Arabic, Hebrew or emoji font yet, and the rasteriser must never choose a font | Letting resvg fall back: the brief measured wrong glyphs for that case |

## 5. How to repeat

```
./mill report-renderer.resvg.test.runMain dosegram.render.resvg.measureRenders 100
./mill report-renderer.resvg.test.contactSheet
```

The one-core run starts the same main class with `taskset -c 0 java -XX:+UseSerialGC -Xms32m -Xmx256m` on the test
classpath. The container comparison renders the committed SVG goldens with both binaries and compares
`magick <file> rgba:- | sha256sum`.

## 6. Not measured

- The production image was not built: no resvg build stage in `docker/Dockerfile`, no font copy, no
  `scripts/rss-check.sh 200` with rendering. S3 is therefore a host measurement under the production JVM flags.
- Pixel identity in CI and on aarch64 (the second half of S2).
- resvg 0.50.0.
- Nothing was sent to Telegram: spikes S4, S5 and S9 of the brief need a bot token and devices.
- `./mill report-renderer.resvg.test.contactSheet` reported "Subprocess failed" on some runs under heavy host load after
  it had written every file; the same main class started directly with `java` returned exit code 0. The cause was not
  found.
