# Research brief 04: server-side image rendering for Dosegram

Status: research input for the Dosegram plan. Not an accepted decision. No product code was written.
Date of all checks: 2026-11-12.
Scope: the stack that turns health records into chat-ready images, animations and PDF reports on the Scala 3 / JVM backend.

## 0. Evidence conventions

Every claim about an external platform or library carries one of three marks.

| Mark | Meaning |
|---|---|
| VERIFIED | Read from the named primary source on 2026-11-12. The source URL is given in the row or in section 19. |
| MEASURED | Observed in a scratch experiment run during this research on the owner's development host (Linux x86-64, 16 hardware threads, Temurin 25.0.5, resvg 0.49.0 from the distribution, ffmpeg n9.0.2). Not measured in the production container. Section 5 gives the method. |
| ASSUMPTION | Not confirmed by a primary source or a measurement. Each assumption that matters is listed again in section 16 as a spike item. |

## 1. Recommendation in brief

1. Use SVG as the single intermediate format. A pure Scala function turns an immutable view model into a small scene tree, and a second pure function emits SVG text restricted to a fixed "Dosegram SVG profile 1" (section 7.3).
2. Rasterise that SVG with **resvg** (Rust, Apache-2.0 OR MIT, version 0.50.0 released 2026-11-11), run as a short-lived child process that reads SVG on standard input and writes PNG on standard output. Fonts are a pinned directory inside the image; system fonts are disabled.
3. Draw charts with in-repository chart primitives in the scene tree (ring, bar, line, calendar heatmap, timeline). Do not adopt a chart library.
4. Produce PDF reports with the **Typst** command-line compiler (Apache-2.0, 0.15.1) from a template plus a JSON data file, embedding the same SVG charts.
5. Produce the animated mood blob as a generated **TGS** sticker (gzip-compressed Lottie JSON written directly by the backend). Static PNG stays the accessible baseline.
6. Fallback stack if resvg fails its spike: a Java2D painter that interprets the same scene tree, with `java.awt.font.TextLayout` for text and `ImageIO` for PNG. PDF fallback: Apache PDFBox 3.0.8.

Reasons, with the rejected alternatives, are in section 6. The three findings that drove the choice:

- JSVG 2.2.0, the fastest pure-JVM SVG rasteriser, drew Arabic and Hebrew text unshaped and in the wrong order, and showed missing-glyph boxes where a font lacked digits (MEASURED). resvg drew the same file correctly.
- Java2D on Temurin 25.0.5 drew zero pixels for a colour emoji from Noto Color Emoji (MEASURED). resvg drew it in colour.
- resvg rendered a 1080 x 1350 card in 47 to 55 ms per process invocation with 18 MB peak resident memory, outside the JVM heap, and gave the same bytes on every run (MEASURED).

A platform change that affects this brief: Bot API 10.4 (2026-11-09) added in-message HTML widgets that accept inline SVG. Section 3.5 explains why they do not replace server images, and how the SVG profile keeps them available as a later enhancement.

## 2. Repository constraints read from the build

Source: `/home/worxbend/Projects/dosecord/build.mill`, `docker/Dockerfile`, `docs/DESIGN.md` sections 2 and 15, `docs/BOT_UX_PLAN.md` section 6, `scripts/license-gate.sh`.

| Fact | Value | Consequence for rendering |
|---|---|---|
| Language and build | Scala 3.8.4, Mill 1.1.9, `-Werror -Wunused:all` | Renderer is a separate Mill module. |
| Runtime | JDK 25 (Temurin), virtual threads | Foreign Function and Memory API is final and available. Process pipes need a small platform-thread pool (section 7.5). |
| Concurrency | Ox 1.0.6, direct style. No effect system. | Renderer port is a blocking function; bounded with Ox scopes. |
| Container | `eclipse-temurin:25-jre-alpine`, non-root, `-XX:+UseSerialGC -Xms32m -Xmx256m` | Alpine uses musl libc. Prebuilt glibc native libraries do not load without extra work. Heap is small, so large `BufferedImage` work competes with the bot. |
| Memory gates | 200 MB idle RSS, 300 MB peak RSS (DESIGN section 15) | Rendering outside the JVM heap is an advantage. |
| Licence gate | Maven dependencies must match `apache|mit|bsd|epl|cc0|public domain|isc|zlib|unicode` | LGPL and MPL Maven artifacts fail the gate. Native binaries and fonts are outside the script and need a separate notice file. |
| Existing design | DESIGN section 15 and BOT_UX_PLAN section 6 name "XChart 4.0.4 plus Java2D" as a candidate, "not a qualified production dependency". A `report-renderer` Mill module takes plain snapshots and returns bounded PNG bytes. | This brief replaces the candidate. The module boundary, the snapshot rule and the budgets stay. |
| Existing budgets | One concurrent render, queue of 32, PNG at most 2 MB, warm p95 at most 2 s over 100 samples, ack p95 under 1.5 s | Section 13 keeps these as outer limits and sets tighter targets. |
| Existing rule | "Text, alt and image derive from that same snapshot" | Section 7 keeps this as the first pipeline invariant. |
| Native image | A GraalVM native-image build is a later experiment | Section 15. |

## 3. Telegram constraints

### 3.1 Methods and limits

All rows VERIFIED against https://core.telegram.org/bots/api (Bot API 10.4, dated 2026-11-09) unless marked.

| Topic | Fact |
|---|---|
| `sendPhoto` size | "The photo must be at most 10 MB in size. The photo's width and height must not exceed 10000 in total. Width and height ratio must be at most 20." |
| `sendPhoto` caption | 0 to 1024 characters after entities parsing. `show_caption_above_media` places it above the image. |
| `sendPhoto` privacy flags | `has_spoiler` covers the photo with a spoiler animation. `protect_content` "protects the contents of the sent message from forwarding and saving". |
| Upload limits | Multipart upload: 10 MB for photos, 50 MB for other files. By HTTP URL: 5 MB for photos, 20 MB for other content. By `file_id`: "no limits". |
| `sendDocument` | Any file type up to 50 MB. Caption 0 to 1024 characters. `disable_content_type_detection` exists. Optional thumbnail: JPEG, under 200 kB, at most 320 px per side, only with multipart upload. By URL only PDF and ZIP work. |
| `sendAnimation` | "GIF or H.264/MPEG-4 AVC video without sound", up to 50 MB. Has `caption`, `has_spoiler`, `width`, `height`, `duration`, `thumbnail`. |
| `sendSticker` | Static WEBP, animated TGS or video WEBM. A new file can be uploaded by multipart. "Video and animated stickers can't be sent via an HTTP URL." The method has no caption parameter. `emoji` can be set for a just-uploaded sticker. |
| `sendMediaGroup` | 2 to 10 items. Photos and videos mix; documents group only with documents. |
| `editMessageMedia` | Replaces a photo, animation, document or video in place, and can replace a text message with media. |
| `sendChatAction` | `upload_photo` shows a "sending photo" status while the bot works. |
| `deleteMessage` | Only for messages sent less than 48 hours ago. |
| `getFile` | Bots download at most 20 MB. The download link is valid for at least one hour. |
| `PhotoSize` | A sent photo returns an array of sizes, each with `file_id`, `file_unique_id`, `width`, `height`, optional `file_size`. |
| Server-side photo size classes | Telegram defines server-resized photo size types bounded by 100, 320, 800, 1280 and 2560 px boxes (types `s`, `m`, `x`, `y`, `w`). VERIFIED at https://core.telegram.org/api/files. |
| Rich messages | `sendRichMessage` exists with block types that include tables, collages, slideshows and photos, and media referenced by `tg://photo?id=` links. Relevant because a native table can carry the text equivalent of a chart. Detail belongs to the bot UX brief. |

### 3.2 Sticker formats

VERIFIED at https://core.telegram.org/stickers.

| Format | Requirements |
|---|---|
| Static (PNG or WEBP) | One side exactly 512 px, the other 512 px or less. |
| Animated (TGS, Lottie based) | Canvas 512 x 512. Objects must not leave the canvas. At most 3 seconds. Must loop. At most 64 KB. 60 frames per second. The export guide forbids, among others: masks, layer effects, images, solids, texts, 3D layers, merge paths, star shapes, gradient strokes, repeaters, expressions and auto-bezier keys. |
| Video (WEBM) | VP9 codec, no audio. One side exactly 512 px. At most 3 seconds. Up to 30 frames per second. At most 256 KB. Looping recommended. |
| Custom emoji | Exactly 100 x 100 px, same formats. Using custom sets needs Telegram Premium. Not suitable as a core feature. |

### 3.3 `file_id` reuse

VERIFIED at https://core.telegram.org/bots/api#sending-files.

- A file already on Telegram servers is re-sent by passing its `file_id`; nothing is uploaded again.
- "It is not possible to change the file type when resending by file_id." A photo cannot be re-sent as a document.
- "Resending a photo by file_id will send all of its sizes."
- "file_id is unique for each individual bot and can't be transferred from one bot to another."
- "file_id uniquely identifies a file, but a file can have different valid file_ids even for the same bot." Use `file_unique_id` for diagnostics only, never as a send key.
- Thumbnails cannot be re-sent.

ASSUMPTION: a `file_id` stays valid indefinitely. The documentation does not state a lifetime. The design treats a rejected `file_id` as a cache miss and uploads again.

### 3.4 Photo compression

- ASSUMPTION: `sendPhoto` re-encodes the upload as JPEG and scales it so the longest side is at most 1280 px in the size clients normally display. The Bot API text does not say this. Community reports agree, and the 1280 px server size class exists (section 3.1), but neither proves the rule for bot uploads.
- ASSUMPTION: PNG transparency is lost on the photo path. Every card therefore has an opaque background.
- VERIFIED: `sendDocument` delivers the original bytes as a file. The cost is a file bubble with at most a small thumbnail instead of a full inline preview (the thumbnail rule is VERIFIED; the bubble appearance is an ASSUMPTION about clients).

Consequences used in section 8: canvases stay at or under 1280 px on the long side, text is large, thin lines are avoided, and a "full quality" copy goes out as a document only on request.

### 3.5 In-message widgets (Bot API 10.4)

VERIFIED at https://core.telegram.org/bots/widgets and the `sendWidget` reference.

| Property | Fact |
|---|---|
| What it is | One self-contained HTML document drawn inside the message bubble. Code is fixed at send time; only bot-written state changes. |
| Size | At most 32768 bytes of HTML and at most 4096 bytes after zlib level 9 compression. |
| Engine | QuickJS sandbox, not a WebView. No network access. No external fonts, scripts or images. |
| Graphics | Inline SVG is allowed: shapes, paths, `g`, `defs`, `symbol`, `use`, transforms, `viewBox`, `text`, `tspan`, linear and radial gradients, CSS animation of SVG properties. Not allowed: `clipPath`, `mask`, `pattern`, `marker`, filter primitives, SMIL animation, `foreignObject`, `<image>`. Canvas 2D is allowed with limits (4000 commands per task, 2048 x 2048 pixels in total). |
| Fonts | Generic families only (`system-ui`, `sans-serif`, and so on). `@font-face` is removed. Colour emoji and right-to-left text are supported. |
| Theme | Chat palette exposed as CSS variables; `prefers-color-scheme` follows the chat theme and switches live. `prefers-reduced-motion` is supported. |
| Bubble | 200 to 600 px wide; phones lay it out at about 320 to 430 px. Height 50 to 600 px. Nothing scrolls. |
| Availability | In production: private chat with the bot only, and the message cannot be forwarded. Groups, inline mode and forwards work only on the test server. |
| Accessibility | "Widgets have no accessibility semantics in this version." The guide says to always send a caption. |
| Old clients | "Apps that do not support widgets show the message as unsupported media with its caption." |
| Limits | 2000 DOM nodes, 8 MB memory, 400 CSS rules, 32 `@keyframes`. |

Decision: widgets do not replace server-rendered images in the first releases. Rationale: the feature is three days old; a client without support shows no picture at all; it has no accessibility semantics; it cannot be exported, shared or placed in a PDF; and it cannot be pinned to bundled fonts, so it cannot be golden-tested by the backend. Rejected alternative: make widgets the primary chart surface and drop the image pipeline.

What the design keeps open: the SVG profile in section 7.3 is a subset of the widget inline-SVG profile, so the same scene emitter can later feed a widget. The MEASURED size of the full sample card SVG is 8130 bytes raw and 1457 bytes after zlib level 9, well inside the 4096-byte limit.

### 3.6 What the bot cannot know

ASSUMPTION: the Bot API gives a bot no information about the user's chat theme (light or dark) for ordinary messages. The Mini App receives `colorScheme` and theme parameters, so the Mini App can report the scheme to the backend. Section 8.3 uses this.

## 4. Candidate comparison

### 4.1 Versions and licences

VERIFIED on 2026-11-12 from Maven Central metadata (`https://repo1.maven.org/maven2/<group>/<artifact>/maven-metadata.xml`), GitHub release pages, and Alpine package search for branch v3.24.

| Candidate | Latest version and date | Licence | Passes the Maven licence gate |
|---|---|---|---|
| resvg (`linebender/resvg`) | 0.50.0, 2026-11-11 | Apache-2.0 OR MIT | Not a Maven artifact. Permissive. |
| JSVG (`com.github.weisj:jsvg`) | 2.2.0, 2026-09-10 | MIT | Yes. No runtime dependencies; jar is 0.8 MB. |
| Apache Batik (`org.apache.xmlgraphics:batik-transcoder`) | 1.20, 2026-10-20 | Apache-2.0 | Yes. `batik-all` jar is 4.3 MB plus XML dependencies. |
| EchoSVG (`css4j/echosvg`, a Batik fork) | 2.5, 2026-05-20 | Apache-2.0 | Yes by licence. Maven coordinates not confirmed in this run. |
| librsvg (`rsvg-convert`) | 2.62.3 in Alpine 3.24 community | LGPL-2.1-or-later | Not a Maven artifact. Copyleft; acceptable only as a separate process. |
| Skiko (`org.jetbrains.skiko:skiko-awt`) | 0.156.1, 2026-11-04 | Apache-2.0 | Yes. Native runtime jar for linux-x64 is 8.5 MB. |
| Skija (`io.github.humbleui:skija-shared`) | 0.143.18, 2026-10-11 | Apache-2.0 | Yes. Native jar for linux-x64 is 11.2 MB. |
| Playwright for Java (`com.microsoft.playwright:playwright`) | 1.64.0, 2026-10-21 | Apache-2.0 | Yes, but it downloads browser binaries. |
| Chromium (Alpine package) | 156.0.8078.79 | BSD-3-Clause | Not Maven. Package is 145 MiB, installed size 315.5 MiB. |
| XChart (`org.knowm.xchart:xchart`) | 4.0.4, 2026-07-31 | Apache-2.0 | Yes. All four of its dependencies are marked optional in the 4.0.4 POM. |
| JFreeChart (`org.jfree:jfreechart`) | 1.5.6, 2025-05-21 | LGPL | No. |
| Apache ECharts | 6.1.0, 2026-05-19 | Apache-2.0 | JavaScript. Needs a JavaScript runtime on the server. |
| vl-convert (Vega-Lite renderer) | 1.9.0, 2026-01-20 | BSD-3-Clause | Rust binary that embeds a JavaScript engine. No JVM binding found (ASSUMPTION). |
| Typst | 0.15.1, 2026-07-17 | Apache-2.0 | Not Maven. Official static musl binaries exist for x86-64 and aarch64 (16.7 MB compressed). Alpine 3.24 packages 0.14.2. |
| Apache PDFBox (`org.apache.pdfbox:pdfbox`) | 3.0.8, 2026-07-15 | Apache-2.0 | Yes. |
| pdfbox-graphics2d (`de.rototor.pdfbox:graphics2d`) | 3.0.5, 2025-09-28 | Apache-2.0 | Yes. |
| openhtmltopdf fork (`io.github.openhtmltopdf`) | 1.1.113, 2026-11-12 | LGPL-2.1-or-later | No. |
| Apache FOP | 2.12, 2026-10-20 | Apache-2.0 | Yes. |
| JCodec (`org.jcodec:jcodec`) | 0.2.5, 2019-06-16 | BSD (FreeBSD) | Yes, but unmaintained for seven years. |
| ffmpeg (Alpine package) | 8.1.3 | GPL-2.0-or-later AND LGPL-2.1-or-later | Not Maven. Separate process only. |
| ThorVG | 1.1.3, 2026-10-15 | MIT | Native SVG and Lottie renderer. No JVM binding found (ASSUMPTION). |

### 4.2 Criteria matrix

Scale: good, fair, poor. A cell marked (M) rests on a local measurement, (V) on a verified source, (A) on an assumption.

| Criterion | Java2D scene DSL | SVG + JSVG | SVG + Batik | SVG + resvg | SVG + librsvg | Skia (Skiko or Skija) | Headless Chromium |
|---|---|---|---|---|---|---|---|
| Shape and gradient fidelity | good | good (M) | good (A) | good (M) | good (M) | good (A) | good |
| Latin and Cyrillic text | good (M) | good (M) | good (A) | good (M) | good (M) | good (A) | good |
| Arabic and Hebrew shaping and order | good with `TextLayout` (M) | poor: unshaped, logical order (M) | fair: own bidi code, not tested here (A) | good (M) | good (M) | good with SkParagraph (A) | good |
| Font fallback | poor: none for bundled fonts; logical fonts fall back through the host fontconfig (M) | poor: missing-glyph boxes (M) | fair (A) | fair: automatic but not controllable; picked the emoji font for digits (M) | good, but depends on host fontconfig (M) | good (A) | good |
| Colour emoji | poor: zero pixels drawn (M) | poor (same engine) | poor (same engine) (A) | good: CBDT emoji drawn in colour (M); COLRv0, COLRv1, sbix, CBDT and SVG tables supported (V) | good (A) | good (A) | good |
| Determinism | same bytes within one process over 200 runs (M); across hosts depends on JDK build and its FreeType (A) | same as Java2D | same as Java2D | same bytes on every run (M); upstream states pixel-identical output across platforms because it uses no system libraries (V) | depends on cairo, pango, fontconfig versions (A) | stable per build; cross-architecture identity not promised (A) | poor: changes with browser version |
| Cold start | 580 ms for first render in a fresh JVM (M) | same (M) | slower (A) | none in the JVM; 47 to 55 ms per process (M) | 85 to 89 ms per process (M) | native library load (A) | seconds; hundreds of MB |
| Warm speed, 1080 x 1350 card | not measured alone | 6.9 ms render p50, 13.6 ms p95, plus 32 ms PNG encode (M) | not measured | 47 to 55 ms total including process start, font load and PNG encode (M) | 85 to 89 ms (M) | not measured | 100 ms or more per screenshot (A) |
| Memory | 5.8 MB per 1080 x 1350 ARGB image on the 256 MB heap, plus encoder buffers | 15 MB heap in use after the run (M) | README of JSVG claims Batik uses about 50 times more (V, vendor claim) | 18 MB peak RSS in the child process, none on the JVM heap (M) | 39 MB peak RSS (M) | off-heap native (A) | 300 MB or more (A) |
| Native and container burden | none, but Alpine needs `fontconfig` and a font package for AWT to start (community reports, A) | same as Java2D | same as Java2D | one static binary of about 4 MB; must be built for musl because the 0.50.0 release binary is linked against glibc (M: `file` and `ldd` output) | Alpine package with cairo, pango, glib | prebuilt for glibc linux-x64 and linux-arm64 only; no musl artifact on Maven Central (V); community reports of a `libGL.so.1` load requirement (A) | 315.5 MiB installed on Alpine (V); Playwright lists only Debian and Ubuntu as supported Linux (V) |
| Licence | JDK | MIT | Apache-2.0 | Apache-2.0 OR MIT | LGPL-2.1-or-later | Apache-2.0 | BSD-3-Clause |
| GraalVM native-image | AWT in native images is a known problem area (A) | same | same, plus heavy reflection (A) | good: a child process needs nothing from the image (A) | good (A) | JNI library; untested (A) | good (separate process) |
| Security surface | small | small; static renderer, no scripting (V) | history of SSRF and code-execution CVEs fixed in 1.16 and 1.17 (V); must be hardened | small; memory-safe language, almost no `unsafe` (V, vendor claim) | C and Rust mix | large C++ | very large |

### 4.3 Notes per candidate

**Java2D with a scene DSL.** Works today with no new dependency. `TextLayout` and `drawString` shaped and ordered Arabic and Hebrew correctly (MEASURED). Three weaknesses were measured: no colour emoji, no font fallback for fonts loaded from files, and a 32 ms PNG encode that dominates the 7 ms render. A fourth is an assumption that needs a container test: AWT on the Alpine JRE needs `fontconfig` and at least one font package or the font manager throws (Alpine issue tracker and Adoptium reports). Pixel output is tied to the JDK's FreeType. The Temurin 25.0.2 linux-x64 archive on this host contains `lib/libfreetype.so` (MEASURED), so glibc builds bundle FreeType; the Alpine build was not inspected.

**SVG rasterised by JSVG.** Small, fast and dependency-free, and used by IntelliJ IDEA, NetBeans and Eclipse SWT for icons (V). It is built for icons. The measured text failures (no bidi reordering, no joining, no fallback) rule it out for user-entered text in any script beyond Latin, Cyrillic and Greek. `foreignObject` is unsupported; animation support is "limited and in an experimental state" (V).

**SVG rasterised by Batik.** Full SVG 1.1 with its own text layout. Heavy (4.3 MB plus XML stack), slow to start, and it has needed security fixes for server-side request forgery and code execution through crafted SVG (fixed in 1.16 and 1.17). Since Dosegram would only ever feed it SVG it generated itself, the risk is limited, but the weight buys nothing that resvg does not do better. EchoSVG is an actively developed fork with the same architecture.

**SVG rasterised by resvg.** Static SVG only, no scripting, no animation (V). Text is converted to paths with its own shaping and bidi; no system text libraries are used, which is why upstream can promise identical pixels across platforms (V). The command-line tool supports standard input and output, `--skip-system-fonts`, `--use-fonts-dir`, `--resources-dir`, a zoom factor, and `--query-all`, which prints the bounding box of every element that has an `id` (MEASURED: 8 ms for three text elements; widths agreed with JDK `TextLayout` advances to within 0.3 percent). Two pitfalls were measured and are designed around in section 7:

- Output changed when system fonts were allowed, because fallback picked different fonts. Fonts must be pinned.
- With Noto Color Emoji in the pinned set, resvg chose the emoji font for the digits in a Hebrew string, producing wide grey digits. Fallback order cannot be configured from the command line. The backend must assign a font family to every text run itself.

**librsvg.** Good output, but its text stack (pango, fontconfig, cairo) makes pixels depend on distribution package versions, and it is copyleft. It was slower and used more memory than resvg in the same test.

**Skia through Skiko or Skija.** The strongest drawing and text engine of the group, and Skiko publishes a Skottie module that can play Lottie files. Both projects publish glibc natives only. Using them means leaving Alpine (and redoing the time-zone data work the repository did for Alpine) or building Skia for musl. Either is a large cost for cards that are made of rectangles, arcs and text.

**Headless Chromium or Playwright.** HTML and CSS layout is the most productive authoring model, and fonts, emoji and right-to-left text just work. Rejected: a 300 MB browser beside a bot that is budgeted at 300 MB peak; pixels change with every browser update, so exact golden tests are impossible; Playwright does not list Alpine as supported; a browser is a large attack surface to run next to health data.

**Chart libraries.** XChart 4.0.4 has no mandatory dependencies and a permissive licence, and the repository already ran a 1200 x 900 fixture with it. It draws through Java2D, so it inherits the Java2D text and emoji limits, and its output has the generic look that BOT_UX_PLAN section 6 asks to avoid ("not a generic chart-library screenshot"). JFreeChart fails the licence gate. ECharts can emit an SVG string on a server without a DOM since 5.3.0 (`echarts.init(null, null, {renderer: 'svg', ssr: true, width, height})` then `renderToSVGString()`, VERIFIED), but that needs a JavaScript engine in or beside the JVM. vl-convert embeds one already but is another large native binary. The cards in section 8.1 need six chart shapes. Writing them as scene primitives is a few hundred lines, gives full control of the visual language, and adds no dependency.

**PDF.** See section 11.

**Animation.** See section 10.

## 5. Local measurements

Method. One SVG fixture of 8130 bytes at 1080 x 1350: gradient background, seven text elements (English, Ukrainian, Arabic, Hebrew), a progress ring drawn with `stroke-dasharray`, a mood blob path with a radial gradient and a face, and an 84-cell rounded-rectangle heatmap. JSVG was driven by a single Java file run with `-Djava.awt.headless=true -XX:+UseSerialGC -Xmx256m`, 1 cold render then 200 warm renders. resvg and rsvg-convert were run as processes and timed from the shell. Fonts: Noto Sans Regular and Bold, Noto Sans Arabic, Noto Sans Hebrew, Noto Color Emoji (10.7 MB, CBDT format), copied into one directory (12 MB in total).

| Measurement | Result |
|---|---|
| JSVG 2.2.0 cold render in a fresh JVM | 580 ms, plus 61 ms PNG encode |
| JSVG warm render | p50 6.9 ms, p95 13.6 ms |
| `ImageIO` PNG encode of the 1080 x 1350 image | p50 31.7 ms, p95 34.0 ms |
| JSVG PNG size | 111,556 bytes. JPEG at quality 0.9: 131,402 bytes. |
| JSVG determinism | 1 distinct SHA-256 over 200 renders in one process |
| JSVG heap in use after the run | 15.4 MB |
| JSVG Arabic and Hebrew | Unshaped, left-to-right order, missing-glyph boxes for digits absent from the Hebrew font |
| Java2D colour emoji | `Font.canDisplay` returned true; 0 non-white pixels were drawn |
| Java2D `TextLayout` | Correct Arabic joining and bidi order; per-run fonts through `AttributedString` worked; missing-glyph boxes when one bundled font lacked a character |
| resvg 0.49.0, system fonts | 87 to 91 ms; 92,162 bytes |
| resvg 0.49.0, pinned fonts with emoji font | 47 to 55 ms; identical hash across runs and across file and pipe input |
| resvg 0.49.0, pinned fonts without emoji font | 44 to 46 ms |
| resvg at zoom 2 (2160 x 2700) | 126 ms; 209,349 bytes |
| resvg peak resident memory | 18.4 MB |
| resvg `--query-all` | 8 ms; text widths 626.12, 644.69, 264.00 against JDK advances 626.12, 646.45, 264.00 |
| rsvg-convert 2.63.4 | 85 to 89 ms; 114,058 bytes; 38.9 MB peak resident memory |
| 48 blob frames at 512 x 512 through resvg | 573 ms in total (12 ms per process) |
| VP9 WebM with alpha, 2 s at 24 fps (ffmpeg libvpx-vp9, CRF 40) | 35,591 bytes, encoded in 911 ms |
| H.264 MP4, same frames on an opaque background (libx264, CRF 26) | 23,358 bytes, encoded in 152 ms |
| GIF, same frames | 1,260,991 bytes |
| resvg 0.50.0 release binary for linux-x86_64 | 4.2 MB, dynamically linked against glibc |

Limits of these numbers. They come from a fast desktop, not from a one-CPU container. The resvg under test was 0.49.0; the 0.50.0 release binary was inspected but not executed. Nothing was sent to Telegram. Treat every figure as an order of magnitude until the spike in section 16 repeats it in the final image.

## 6. Decision: recommended stack and fallback

### 6.1 Recommended

| Layer | Choice | Rationale | Rejected alternative |
|---|---|---|---|
| Intermediate format | SVG text in a restricted profile, emitted from a typed scene tree | One artefact serves the rasteriser, the PDF compiler, the Mini App (inline SVG) and, later, widgets. It can be golden-tested as text on any machine. | Drawing straight to a `Graphics2D`: output exists only as pixels, cannot be reused by the Mini App or PDF, and ties tests to one JDK build. |
| Rasteriser | resvg, pinned version, child process per render, SVG on stdin, PNG on stdout | Correct complex text and colour emoji; identical pixels on developer machines, CI and production; no load on the 256 MB heap; a crash or hang cannot take down reminder delivery; the JVM needs no AWT, no fontconfig and no native-access flag. | JSVG in process: seven times faster per render but wrong for right-to-left text and emoji (MEASURED). Speed is not the constraint; Telegram send limits are. |
| Integration mode | Child process, not an in-process binding | Fault and memory isolation matter more than 40 ms. | Binding the resvg C API through the Foreign Function and Memory API: removes process start cost, but a native fault would stop the whole bot, including reminders. Revisit only if measured throughput is insufficient. |
| Text measurement | A resvg `--query-all` pass over a measurement SVG | The measuring engine is the drawing engine, so fitted text cannot overflow. Keeps `java.desktop` out of the service. | Measuring with JDK `TextLayout`: agreed to within 0.3 percent but brings AWT start-up, fontconfig on Alpine and native-image trouble back. |
| Charts | Scene primitives written in the repository | Six shapes are needed; full control over a restrained visual language; no dependency. | XChart 4.0.4 composed through Java2D: inherits Java2D text limits and a generic look. |
| PDF | Typst CLI, template plus JSON data plus the same SVG charts | Tagged PDF by default, PDF/UA-1 and PDF/A support (VERIFIED), real typesetting, static musl binary. | PDFBox with hand-written layout: pure JVM but every page break, table and tag is manual work. |
| Animated mood blob | TGS generated as Lottie JSON by the backend, gzip-compressed | Vector, a few kilobytes, transparent so it suits light and dark chats, loops natively, needs no video encoder at run time. | WebM video sticker: needs ffmpeg with libvpx in the image or at build time. MP4 through `sendAnimation`: no transparency. |
| Asset storage | PostgreSQL `bytea` for short-lived bytes (as DESIGN section 15 already states) plus a `file_id` cache table | Assets are 50 to 300 KB and live for minutes. | Object storage (S3 or MinIO): a new service to run, secure and back up for kilobyte-sized, short-lived data. |

### 6.2 Fallback

If the resvg spike returns no-go (for example the musl build proves unreliable, or child processes are not permitted in the target environment):

- Rasteriser fallback: a `Java2DPainter` that interprets the same scene tree, using `TextLayout` for shaped and ordered text, explicit per-run fonts, and `ImageIO` PNG output. Known reductions: no colour emoji (user-entered emoji are drawn from a monochrome emoji font or removed), pixel goldens valid only inside the pinned container, `fontconfig` and one font package added to the image, about 6 MB of heap per render.
- PDF fallback: Apache PDFBox 3.0.8, drawing chart pages through pdfbox-graphics2d 3.0.5 from the same painter. Tagged output would be limited.
- Animation fallback: none. The product ships static images only.

The scene tree is the seam that makes the fallback cheap. It is defined now; the second interpreter is written only if needed.

### 6.3 What this changes in accepted documents

DESIGN section 15 and BOT_UX_PLAN section 6 name XChart 4.0.4 plus Java2D as a candidate. Adopting this brief means an addendum to the relevant ADR (ADR-005 carries the attachment boundary) and a rewrite of the M3.4a spike criteria. The module boundary (`report-renderer`, plain snapshot in, bounded PNG bytes out, nothing vendor-specific in `core` or `contracts`) is unchanged.

## 7. Render pipeline design

### 7.1 Stages

```
records --query--> Snapshot --pure--> ViewModel --pure--> Scene --pure--> SVG text
                                         |                                  |
                                         +--pure--> text equivalent         +--> Rasteriser port --> PNG bytes
                                                                            +--> Typst (PDF)
                                                                            +--> Mini App (inline SVG)
```

| Stage | Input | Output | Purity | Notes |
|---|---|---|---|---|
| 1. Snapshot | Database rows | Immutable, account-scoped snapshot with range, zone, as-of time and source revision | Query | Already specified as `StatsSnapshot` for medication. Each new domain (water, food, mood, sleep) gets its own snapshot type. |
| 2. View model | Snapshot, locale, theme, discreet flag, canvas kind | `CardSpec`: only display-ready values. Strings are already localised and formatted. | Pure | No template formats a number or a date. The text equivalent is produced here from the same values. |
| 3. Layout | `CardSpec`, text extents | `Scene`: positioned nodes | Pure given extents | Text extents come from a `TextMeasurer` port. Wrapping, truncation and ellipsis decisions happen here and are visible in the SVG golden. |
| 4. Emit | `Scene` | SVG text in profile 1 | Pure | All user text is XML-escaped here and nowhere else. |
| 5. Rasterise | SVG text, scale | PNG bytes | Port | resvg adapter. |
| 6. Publish | PNG bytes, text equivalent | Asset row and outbox row in one transaction | Existing M3.4b design | |
| 7. Deliver | Outbox row | `sendPhoto` and the stored `file_id` | Adapter | |

Invariants:

1. Image, caption and text view come from one view model. A number that appears in the image appears in the text.
2. Stages 2 to 4 perform no input or output and are deterministic. The same view model always yields the same SVG bytes.
3. Rendering happens after the interaction is acknowledged and outside any database transaction (existing rule).
4. A render failure or timeout sends the text equivalent. It never changes intake state and never marks a channel dead (existing rule).

### 7.2 Scene tree

A sketch of the contract, not product code. About ten node types are enough.

```scala
enum Node:
  case Group(children: List[Node], transform: Option[Transform])
  case Rect(box: Box, radius: Double, fill: Paint)
  case Circle(cx: Double, cy: Double, r: Double, fill: Paint, stroke: Option[Stroke])
  case Arc(cx: Double, cy: Double, r: Double, from: Double, sweep: Double, stroke: Stroke)
  case Path(d: PathData, fill: Paint, stroke: Option[Stroke])
  case Text(runs: List[TextRun], anchor: Point, align: Align, dir: Dir)

final case class TextRun(text: String, family: FontFamily, sizePx: Double, weight: Int, fill: Color)
```

`Paint` is a solid colour, a linear gradient or a radial gradient. Colours are theme tokens resolved in stage 2, not literals in templates.

### 7.3 Dosegram SVG profile 1

Allowed: `svg`, `g`, `defs`, `symbol`, `use` with fragment references, `rect`, `circle`, `ellipse`, `line`, `path`, `text`, `tspan`, `linearGradient`, `radialGradient`, `stop`, `transform`, `viewBox`, presentation attributes for fill, stroke, opacity, dash arrays and font properties.

Forbidden: `image`, external references of any kind, `foreignObject`, `script`, `style` elements, `clipPath`, `mask`, `pattern`, `marker`, filters, SMIL animation, `textPath`, system or generic font families.

Reasons. The forbidden list is the union of what resvg does not need, what widgets remove (section 3.5) and what creates a file or network access path. A profile linter runs in tests and, cheaply, before every rasterisation; a document outside the profile is a bug and is not rendered.

### 7.4 Text rules

1. Every text run carries an explicit font family from the pinned font pack. The emitter never writes a generic family and the rasteriser never chooses a font. This is required by the measured fallback pitfall in section 4.3.
2. A `FontItemiser` splits each string into runs by Unicode script (`java.lang.Character.UnicodeScript`, no AWT needed) and by emoji, and maps each run to a family. A character that no bundled font covers is replaced by U+FFFD and counted in the render report.
3. User text is sanitised before layout: control characters and the bidirectional override and isolate controls (U+202A to U+202E, U+2066 to U+2069) are removed, so a medication name cannot reorder neighbouring text.
4. Paragraph direction is a field of the view model, set from the locale. Right-to-left locales mirror the layout (ring on the right, labels right-aligned).
5. Fitting uses measured extents: at most two lines, then an ellipsis. Wrapping measures words as separate elements in one `--query-all` pass. At most three measurement passes per card.

### 7.5 Rasteriser adapter

- Command: `resvg --skip-system-fonts --use-fonts-dir /opt/dosegram/fonts --resources-dir /var/empty - -c`, plus `-z 2` when a 2x asset is requested.
- SVG goes to standard input and PNG comes from standard output. No temporary files exist, which satisfies the existing "no disk leak" gate.
- A hard timeout of 5 seconds, then `destroyForcibly`. Output is capped at the PNG size limit; more is a failure.
- Standard error is never logged verbatim, because a parser message could quote user text. The adapter logs exit code, duration, byte counts and the number of warning lines.
- ASSUMPTION: blocking reads on process pipes can pin a virtual thread's carrier. The adapter therefore runs on a dedicated pool of two platform threads, which is also the concurrency bound.
- ASSUMPTION: resvg does not fetch network resources. Profile 1 forbids `image` in any case, and `--resources-dir` points at an empty directory.

### 7.6 Cache key and `file_id` cache

Two classes of asset behave differently.

| Class | Examples | Key | Stored | Lifetime |
|---|---|---|---|---|
| Global | Mood blob states, milestone badges, onboarding and empty-state art | SHA-256 of the canonical inputs | `file_id` per bot and media kind; bytes can be rebuilt | Until the template, renderer or font revision changes |
| Personal | Daily and weekly cards, heatmaps, trends | HMAC-SHA-256 with a server key over the canonical inputs | Bytes in PostgreSQL until delivered; `file_id` for retry and for edits that return to a view already sent | Bytes: purged within one hour of the terminal state (existing rule). `file_id` row: 48 hours, matching the delete window. |

Canonical inputs, in this order: template identifier, template revision, SVG profile revision, renderer name and version, font pack revision, theme, locale, direction, scale, output format, and the canonical JSON of the view model. An HMAC is used for personal assets so the key reveals nothing about the content and cannot be recomputed from guessed medication names.

`file_id` cache table sketch: `asset_key`, `bot_id`, `media_kind` (photo, document, animation, sticker), `file_id`, `file_unique_id`, `width`, `height`, `byte_size`, `created_at`, `last_used_at`. `media_kind` is part of the key because a `file_id` cannot change type (VERIFIED). On `sendPhoto` success the adapter stores the `file_id` of the largest `PhotoSize`. A send that Telegram rejects for a bad file identifier deletes the row and falls back to upload.

Where the cache pays: retries after an unknown commit; `editMessageMedia` when a user switches between week and month and back; every global asset. Personal cards are otherwise unique per user and period, so the hit rate there is low and that is acceptable.

### 7.7 Module boundaries

| Module | Contains | Depends on |
|---|---|---|
| `contracts` | `AttachmentRef` (opaque asset id, MIME type, size, dimensions, hash, bounded alt text) | nothing vendor-specific |
| `core` | View-model builders and text equivalents per domain | `contracts` |
| `report-renderer` | Scene tree, layout, chart primitives, SVG emitter, profile linter, `TextMeasurer` and `Rasteriser` ports | `contracts` only |
| `infra` | resvg and Typst process adapters, asset store, `file_id` cache | `report-renderer` ports |
| Telegram adapter | Multipart upload, `file_id` reuse, caption assembly | `contracts` |

## 8. Image specification

### 8.1 Card catalogue

| Card | Chart primitive | Canvas | Send method | Class |
|---|---|---|---|---|
| Daily summary | Rows of status marks, one ring per tracked domain | 1080 x 1350 | `sendPhoto` | Personal |
| Weekly summary | Small multiples: adherence bars, hydration bars, mood strip, sleep bars | 1080 x 1350 | `sendPhoto` | Personal |
| Adherence heatmap | Calendar grid, weeks by weekdays | 1080 x 1080 or 1080 x 1350 | `sendPhoto` | Personal |
| Hydration ring | Ring with centre value | 1080 x 1080 | `sendPhoto` | Personal |
| Trend chart (mood, weight, sleep, water) | Line or bar with gaps shown as gaps | 1280 x 720 | `sendPhoto` | Personal |
| Sleep timeline | Horizontal bars per night on a clock axis | 1280 x 720 | `sendPhoto` | Personal |
| Mood blob, static | Parametric shape (section 9) | 1080 x 1080 | `sendPhoto` | Global |
| Mood blob, animated | Lottie shape layers | 512 x 512 | `sendSticker` | Global |
| Streak and milestone | Badge and number | 1080 x 1080 | `sendPhoto` | Global when it carries no personal data |
| Report | Multi-page document | A4 | `sendDocument` (PDF) | Personal |

Rules carried over from BOT_UX_PLAN section 6 and extended to the new domains: no composite health score; no causal claims between mood, food, sleep and medication; unknown is neutral; skipped is not shown in a failure colour; a denominator of zero is "No data", not 0 percent; gaps are drawn as gaps.

### 8.2 Canvas, format and legibility

| Rule | Value | Basis |
|---|---|---|
| Long side | At most 1280 px | Section 3.4 assumption: avoids a second resampling by Telegram. |
| Aspect ratios | 4:5, 1:1, 16:9 | All far inside the ratio limit of 20 (VERIFIED). |
| Upload format | PNG | The only lossy step is then Telegram's own (ASSUMPTION), not two in a row. |
| Background | Opaque, full-bleed | Transparency is not preserved on the photo path (ASSUMPTION). |
| Minimum text size | 40 px on a 1080 px-wide canvas | A phone shows the bubble at roughly a third of canvas width (ASSUMPTION), which gives about 13 px on screen. The 26 to 32 px labels in the research fixture are too small and must not be copied. |
| Minimum stroke | 4 px; no 1 px hairlines | Survives JPEG re-encoding. |
| Colour use | No pure saturated red or blue on dark surfaces for fine detail | Chroma subsampling in JPEG blurs them (ASSUMPTION about Telegram's encoder settings). |
| Size ceiling | 2 MB (existing gate). Expected 50 to 300 KB. | MEASURED: 92 to 112 KB for the fixture. |
| 2x assets | Only for the document path and PDF | A 2160 x 2700 photo would be scaled down again. |
| Full-quality copy | `sendDocument` with a neutral file name, on explicit request | Original bytes are delivered (VERIFIED). |

### 8.3 Light and dark variants

- Each template is drawn from a token set: surface, raised surface, primary text, secondary text, grid, accent, and one colour per status. Two token sets exist, light and dark. Templates never contain literal colours.
- Theme selection: an account setting with values auto, light and dark. In auto, the backend uses the colour scheme last reported by the Mini App. With no report, the default is dark. Rationale: a light image in a dark chat at night glares, while a dark image in a light chat is merely darker; reminders cluster in the morning and evening. Rejected alternative: always send both variants as an album, which doubles rendering, upload and chat clutter.
- Theme is part of the cache key.
- Contrast is checked by a lint on the scene, not by eye: at least 4.5:1 for text and 3:1 for meaningful graphics against their background, in both token sets.
- Stickers are transparent and need no variant, provided outlines keep 3:1 contrast against both a light and a dark chat background.

### 8.4 Fonts

| Purpose | Recommendation | Licence | Note |
|---|---|---|---|
| Latin, Cyrillic, Greek | Inter 4.1 | OFL-1.1 (VERIFIED from the Alpine package record) | ASSUMPTION: its Cyrillic covers Ukrainian fully. Tabular numerals exist as an OpenType feature; whether resvg honours `font-feature-settings` was not tested. If it does not, bake the feature into a static instance of the font at build time. |
| Arabic, Hebrew | Noto Sans Arabic, Noto Sans Hebrew | OFL-1.1 | 235 KB and 27 KB for the regular weights (MEASURED). |
| Other scripts | Further Noto Sans families, added per supported locale | OFL-1.1 | CJK families are tens of megabytes each. Add them only when a CJK locale ships. |
| Emoji in user text | Noto Color Emoji | OFL-1.1 | 10.7 MB (MEASURED). Used only for runs the itemiser marks as emoji. |
| Icons | In-repository vector paths as `symbol` elements | Project's own | Interface icons (pill, drop, moon, plate) are never emoji glyphs, so they stay consistent across fonts. |

Font files are vendored or fetched with pinned checksums at image build. They are not installed from distribution packages, because a package update changes pixels and breaks goldens. The font pack has a revision number that is part of every cache key.

### 8.5 Localisation

- All strings come from the message catalogue through the view model. Templates contain no words.
- Numbers, dates, units and plural forms are formatted in stage 2 with the account locale. Week start day and 12 or 24 hour time follow the locale or the account setting.
- Layouts tolerate text 40 percent longer than English. The fixture set includes a long-string locale (German or Ukrainian) for every template.
- Right-to-left locales set the direction field; the layout mirrors and the fixture set includes one such locale even before one ships, so the property is not lost.
- Units for water and food (millilitres or fluid ounces, kilojoules or kilocalories) are account settings resolved in stage 2.

### 8.6 Accessibility

- Every image has a text equivalent generated from the same view model. It goes in the caption when it fits in 1024 characters (VERIFIED limit) and otherwise in a text message sent immediately after, with a short caption that says so.
- The equivalent is content, not a description of appearance: "Water: 1.8 of 2.5 litres, 72 percent" rather than "a ring chart".
- A "Text view" control is always offered beside an image control (existing BOT_UX_PLAN rule).
- Stickers have no caption (VERIFIED). An animated mood blob is therefore always followed by a text message. The sticker's associated emoji is set as a minimal label (ASSUMPTION: clients expose it to assistive technology).
- Status is never encoded by colour alone: shape, pattern or a label accompanies it. Palettes are checked against the common colour-vision deficiencies.
- Animation is opt-in. The default mood status is a static image.

### 8.7 Privacy

Facts and assumptions:

- An image sent to a user is stored on Telegram's servers and in the chat history on every device of that user. ASSUMPTION: bot chats are ordinary cloud chats without end-to-end encryption.
- A bot can delete its own message only within 48 hours (VERIFIED). Deleting a message later is impossible, and ASSUMPTION: deleting a message does not guarantee that the stored file is erased.
- `protect_content` blocks forwarding and saving of the message (VERIFIED). It does not prevent a screenshot.
- `has_spoiler` hides the photo until tapped (VERIFIED). It is a preview control, not encryption.
- ASSUMPTION: photos appear in the chat's shared-media view and may be saved to the device gallery automatically, depending on client settings.

Rules for the design:

1. Discreet mode applies to images, captions, alt text and file names alike. In discreet mode a card shows no medication name; rows are labelled by time or by a user-chosen neutral label.
2. The default for images is a per-account setting. Recommendation: names shown only after the user turns it on once, with a plain explanation that images stay in the chat.
3. Personal images are sent with `protect_content` by default and with `has_spoiler` in discreet mode. Both are account settings.
4. File names are neutral and carry no account identifier, date of birth or medication name. PNG output carries no text metadata (ASSUMPTION for resvg; assert in a test).
5. Logs record the asset key, template identifier, sizes and timings. They never record view-model content, SVG text or the rasteriser's error output.
6. Rendered bytes are purged within one hour of the terminal state. `file_id` rows for personal assets are deleted after 48 hours.
7. Account erasure deletes asset and `file_id` rows and, for messages younger than 48 hours, attempts `deleteMessage`. The erasure confirmation states plainly that images already delivered remain in the user's chat until the user deletes them.
8. Images contain records only. No card states or implies advice, a dose recommendation or a diagnosis. Each report carries a short line such as "Recorded entries only. Not medical advice."

## 9. Parametric mood blob

### 9.1 Originality

The blob is generated from a mathematical description and the project's own palette. It must not reproduce the characters, shapes, colour quadrants or face sets of any existing application. A circle with two eyes and a curved mouth is generic; a recognisable character is not. The design below was derived for this brief and prototyped in the scratch experiment. This is a design position, not legal advice.

### 9.2 Inputs

| Input | Range | Source | Default |
|---|---|---|---|
| Valence `v` | -1 to 1 | The existing 1 to 10 mood level: `v = (level - 5.5) / 4.5` | none |
| Energy `e` | 0 to 1 | A new optional second answer in the check-in | 0.5 |
| Stability `s` | 0 to 1 | Spread of the last seven check-ins; optional | 0.5 |
| Theme | light or dark | Account setting | dark |

Inputs are quantised before rendering: 7 valence levels by 5 energy levels gives 35 states. With two themes that is 70 static images and 35 transparent stickers. Quantising makes every state a global asset that is rendered once, golden-tested and cached by `file_id` for all users. Rejected alternative: a per-user random seed for individuality, which would turn every blob into a personal asset and remove the cache.

### 9.3 Shape

The outline is a closed curve in polar form around the centre:

`r(theta) = R * (1 + sum over k = 2..5 of a_k * sin(k * theta + phi_k))`

- Amplitudes `a_k` are fixed per state from a seeded generator, so a state always has the same outline.
- Energy scales the total amplitude from about 0.03 (nearly a circle) to about 0.14 (clearly lobed) and shifts weight toward higher harmonics.
- Valence sets smoothness. The curve is sampled at 8 to 12 angles and converted to a closed cubic Bezier path through Catmull-Rom interpolation. Positive valence uses full tension (round lobes). Negative valence with high energy lowers the tension so lobes become more pointed. Negative valence with low energy keeps the curve smooth but applies a slump.
- Posture: vertical scale `1 + 0.10 * (e - 0.5)` and, for low energy, a wider base and a lowered top.
- Stability, when present, adds a second, lighter outline offset by a few pixels; low stability increases the offset. It is omitted in the first version if the input is not collected.

### 9.4 Colour

- Colours are defined in OKLCH and converted to sRGB hex at build time.
- Hue follows valence along one path of the project's choosing, from a cool blue-violet at the negative end through a neutral grey-teal to a warm amber at the positive end. The path is checked so that neighbouring levels differ in lightness as well as hue, for colour-vision deficiencies.
- Chroma rises with energy. Low energy is muted, not grey.
- The fill is a two-stop radial gradient with the highlight offset up and to the left. Radial gradients are inside profile 1 and inside the TGS feature set (gradient fills are not in the forbidden list; only gradient strokes are).
- Colour is never the only carrier of meaning: the face and the text label repeat it.

### 9.5 Face

| Feature | Rule |
|---|---|
| Eyes | Two ellipses. Vertical radius grows with energy (half-closed when low, open when high). |
| Mouth | One quadratic curve. Control-point offset is proportional to valence: upward curve for positive, flat near zero, a shallow downward curve for negative. Width grows slightly with energy. |
| Brows | Drawn only for negative valence with high energy: two short lines with a small inward tilt. |
| Restraint | No tears, wounds, sickness imagery or mockery. The most negative state looks tired or tense, not broken. |

### 9.6 Motion

- Loop length 3 seconds, the sticker maximum (VERIFIED). Every animated property completes a whole number of cycles in the loop so the join is invisible.
- Breathing: uniform scale of plus or minus 2 to 5 percent, one cycle per loop at low energy, two or three at high energy.
- Wobble: the harmonic phases advance by one full turn per loop, implemented as shape keyframes of the outline path at 8 to 12 key times.
- Blink: one blink per loop, eyes closing for about 100 ms.
- Negative valence reduces amplitude and uses slower easing. High energy adds a small vertical bounce.
- No flashing and no rapid strobing in any state.

### 9.7 Outputs

| Output | Use |
|---|---|
| Static PNG, 1080 x 1080, with the level label drawn in | Default reply to a mood check-in, through `sendPhoto` with the text equivalent as caption |
| Small blob symbol | Reused inside weekly cards as a seven-day mood strip |
| TGS sticker | Opt-in animated reply, followed by a text message |
| Inline SVG | Mini App mood picker and history |

## 10. Animated output

| Format | Telegram method | Limits | Measured or estimated size | Runtime dependency | Verdict |
|---|---|---|---|---|---|
| TGS (Lottie JSON, gzip) | `sendSticker` | 512 x 512, 3 s, 60 fps, 64 KB, must loop (VERIFIED) | ASSUMPTION: 2 to 6 KB for the blob (one gradient-filled shape with about ten path keyframes, three face shapes) | None; JSON and gzip are in the JDK | Recommended |
| WebM VP9 with alpha | `sendSticker` | 512 px side, 3 s, 30 fps, 256 KB (VERIFIED) | 35.6 KB for 2 s at 24 fps (MEASURED) | ffmpeg with libvpx | Reserve; if used, encode at build time, not in production |
| MP4 H.264 | `sendAnimation` | 50 MB; has caption and spoiler (VERIFIED) | 23.4 KB (MEASURED) | ffmpeg with libx264 | Only if a captioned, opaque animation is wanted |
| GIF | `sendAnimation` | 50 MB | 1.26 MB (MEASURED) | None | Rejected: 35 to 50 times larger for worse quality |
| Widget with CSS-animated SVG | `sendWidget` | Section 3.5 | 475 bytes compressed for one blob frame (MEASURED) | None | Later experiment |

Notes on TGS. The backend writes Lottie JSON directly: a shape layer with a path whose vertices are keyframed, a gradient fill, and three small shape layers for the face. The forbidden-feature list (section 3.2) is respected by construction, because the emitter can only write the allowed constructs. Rejected alternative: authoring in a motion-design tool and exporting, which gives 35 hand-made files that cannot follow a palette or parameter change.

The correctness of a generated TGS can only be confirmed in a Telegram client. That is a spike item. Until it passes, the product plan must not depend on animation.

Because the 35 states are global assets, animation is never rendered on an interactive path. All states are generated at build or first start and uploaded once.

## 11. PDF reports

Use: a shareable, printable record for the user to keep or to show to a clinician. It lists what the user recorded. It contains no interpretation.

| Option | Assessment |
|---|---|
| Typst CLI 0.15.1 (recommended) | Template file plus a JSON data file, compiled by a child process. Tagged PDF by default; PDF/UA-1 and PDF/A profiles selectable with `--pdf-standard`; compatible standards can be combined, except that PDF/UA-1 cannot be combined with PDF/A-4 or PDF 2.0 (VERIFIED). Embeds SVG, so the same chart SVG is reused as vector art. Static musl binary available (VERIFIED). ASSUMPTION: fonts are pinned with a font-path option and system fonts can be ignored; a creation-timestamp option makes output reproducible. |
| PDFBox 3.0.8 plus pdfbox-graphics2d 3.0.5 (fallback) | Pure JVM and permissive. Layout, pagination, tables and tagging are all manual. |
| Headless Chromium print | Rejected for the reasons in section 4.3. |
| openhtmltopdf | Rejected: LGPL fails the licence gate. |
| Apache FOP 2.12 | Rejected: XSL-FO authoring cost with no advantage over Typst. |

Design points:

- The PDF path reuses stages 1, 2 and 4 of the pipeline. Typst replaces stage 5.
- Data reaches Typst as a JSON file in a private temporary directory that is removed after the run, or through standard input if the spike confirms support. This is the one place where rendering touches the disk; the directory is on a memory-backed mount.
- Delivery is `sendDocument`. The file name is neutral. The caption states the period and that the file contains health records.
- PDF generation is a queued job with its own timeout (30 seconds) and never runs on the interactive path.
- The Typst binary may live only in the worker role's image if the service is split by role.
- Text in the PDF is real text, so the document is searchable and accessible. Chart figures carry alternative text from the view model.

## 12. Golden-image test strategy

| Layer | What is compared | Runs where | Catches |
|---|---|---|---|
| 1. SVG text golden | Exact SVG string per fixture, using the repository's existing golden-file helper | Every test run, any machine, no native tool | Layout, wrapping, colours, fonts chosen per run, escaping. Most regressions. |
| 2. Scene lints | Contrast ratios, minimum text size, text inside its slot (measured extents), node count, SVG size, profile whitelist | Every test run | Accessibility and safety regressions without a human looking. |
| 3. Property tests | For generated view models including hostile strings (markup, ampersands, bidi controls, 200-character names, emoji, zero and maximum values): output parses, stays in profile, nothing overflows, same input gives same bytes | Every test run | Injection and overflow. |
| 4. Pixel golden | SHA-256 of decoded RGBA pixels of the PNG from the pinned resvg binary and pinned font pack | CI job that uses the production image | Rasteriser, font and binary drift. |
| 5. Text-equivalent golden | Caption and text view per fixture | Every test run | The image and the words diverging. |
| 6. Visual review sheet | A contact sheet of all fixtures at phone width, light and dark, attached to the pull request | CI artefact | Taste and legibility, judged by an independent reviewer (existing requirement). |
| 7. Animation golden | Lottie JSON text golden, plus size and feature-whitelist assertions | Every test run | Sticker limit violations. |

Rules:

- Pixel comparison is exact. The rasteriser is deterministic (MEASURED on one host; VERIFIED as an upstream claim across platforms). If CI on a second CPU architecture shows differences, the documented contingency is a tolerance of one level per channel on at most 0.1 percent of pixels; it is not enabled by default.
- Pixels are hashed after decoding, not as PNG bytes, so a change in compression alone does not fail the test.
- Goldens are regenerated only by an explicit task, and a change to the resvg version or the font pack revision must regenerate them in the same change, under review.
- Fixture matrix per template: light and dark; English, one long-string locale, one right-to-left locale; empty, typical and extreme data; discreet on and off. That is 36 fixtures per template before pruning.
- A seeded failure (a deliberately wrong golden) proves the gate fails closed, as the repository already requires for new gates.

## 13. Performance budget

Outer limits already accepted in DESIGN section 15 are kept. Targets below are for the production container on one CPU and must be measured there.

| Step | Target p95 | Basis |
|---|---|---|
| Acknowledge the interaction | Under 1.5 s (existing gate); rendering starts after it | Existing |
| Snapshot query | 100 ms | To be measured per domain |
| View model, layout, SVG emission | 20 ms | Pure functions over small data |
| Text measurement passes | 3 passes of 30 ms | 8 ms MEASURED on the desktop |
| Rasterise a static card | 200 ms | 47 to 55 ms MEASURED on the desktop; four-fold allowance |
| Upload and send | 1 s | ASSUMPTION; network-bound |
| Tap to image visible | 2.5 s | Sum with margin. `sendChatAction(upload_photo)` covers the wait. |
| Hard timeout for one render | 5 s, then text fallback | |
| PDF report | 10 s typical, 30 s timeout | Not measured |

| Resource | Budget | Basis |
|---|---|---|
| Concurrent renders | 2 child processes | 18 MB RSS each MEASURED |
| Queue | 32 (existing) | |
| Renderer memory outside the JVM | 64 MB for both workers | Three-fold allowance |
| JVM heap attributable to rendering | Under 5 MB at any time | SVG text and PNG bytes only |
| PNG size | 300 KB expected, 2 MB limit | 92 to 112 KB MEASURED |
| Throughput per core | At least 5 cards per second | 20 per second MEASURED on the desktop |

Scale check. A weekly summary for 10,000 accounts at 5 cards per second takes about 33 minutes of one core, spread naturally by time zone and quiet hours. ASSUMPTION: Telegram's bulk send limits, not rendering, bound that job; the limits belong to the platform brief.

Overload behaviour (existing rule, restated): when the queue is full or a deadline passes, the user gets the accurate text equivalent at once. Image work never delays or blocks a medication reminder.

## 14. Container and supply chain

| Item | Plan |
|---|---|
| resvg binary | Built from the tagged source in a dedicated build stage for the musl target and copied into the runtime image; version and source checksum pinned. The published 0.50.0 binary links against glibc (MEASURED) and Alpine 3.24 has no resvg package (VERIFIED), so it cannot simply be downloaded or installed. ASSUMPTION: a build on Alpine with Rust 1.85 or newer yields a static binary. |
| Typst binary | Official static musl release, checksum pinned. Worker image only, if roles are split. |
| Fonts | Vendored or fetched by checksum into `/opt/dosegram/fonts`, read-only. |
| AWT | Not used by the service in the recommended stack. `fontconfig` is not needed. |
| ffmpeg | Not in the runtime image. |
| Size added to the image | About 5 MB for resvg, about 13 MB for fonts with the emoji font, about 45 MB for Typst (ASSUMPTION for the unpacked size). |
| Licence record | The Maven licence gate does not see binaries or fonts. Add a third-party notices file that lists resvg (Apache-2.0 OR MIT), Typst (Apache-2.0) and each font (OFL-1.1) with their licence texts, and a check that every file in the fonts directory is listed. |
| Sandboxing | Child processes run as the same non-root user with no network need. ASSUMPTION: a seccomp or no-new-privileges profile at the container level is sufficient; the security brief owns this. |
| Updates | ASSUMPTION: the repository's Renovate setup will not track these binaries without a custom rule. Add a scheduled check for new resvg and Typst releases; an update is a deliberate change that regenerates goldens. |

## 15. GraalVM native-image

| Stack | Outlook |
|---|---|
| Recommended (child processes, no AWT) | Favourable. Starting a process and reading pipes needs no special configuration (ASSUMPTION; standard `ProcessBuilder` use). The service has no `java.desktop` dependency to carry into the image. |
| Fallback (Java2D painter) | Unfavourable. AWT, fonts and `ImageIO` in a native image are a known source of build and run-time failures; public reports and vendor guides describe extra flags and bundled native libraries (ASSUMPTION about current GraalVM for JDK 25; not tested). Choosing the fallback probably ends the native-image experiment for the role that renders. |
| In-process resvg through the Foreign Function and Memory API | Possible in principle; not evaluated because it was rejected for isolation reasons. |

## 16. Spike list (go or no-go before design is frozen)

Each item is an assumption from this brief. The spike is one issue with a recorded verdict, in the style of the repository's existing spikes.

| No. | Question | Pass criterion |
|---|---|---|
| S1 | Does a musl build of the pinned resvg run in the final non-root Alpine image? | Renders the fixture set; binary is static; no network or display needed. |
| S2 | Are pixels identical between the developer host, CI and the production image, and between x86-64 and aarch64? | Equal pixel hashes for all fixtures, or a documented tolerance. |
| S3 | Do budgets hold on one CPU with `-Xmx256m`? | Section 13 targets over 100 samples; idle and peak RSS inside existing gates. |
| S4 | How does Telegram treat an uploaded PNG photo? | Record returned `PhotoSize` dimensions and byte sizes for 1080 x 1350, 1280 x 720 and 2160 x 2700 uploads; judge legibility on a phone in light and dark themes. |
| S5 | Does a generated TGS play on current Android, iOS and desktop clients? | All 35 states play and loop; each file is under 64 KB. |
| S6 | Does resvg honour OpenType tabular numerals through SVG attributes? | Digits align in a column, or the baked-font route is chosen. |
| S7 | Do process pipes behave on virtual threads, or is the platform-thread pool required? | Measured; decision recorded. |
| S8 | Does Typst accept pinned fonts, ignore system fonts and produce reproducible, tagged output with embedded SVG from the emitter? | Two runs give identical bytes; a PDF/UA-1 checker passes. |
| S9 | Does `file_id` reuse work for photo, sticker and document as designed, including after a bot restart? | Re-send by `file_id` succeeds; a wrong identifier falls back to upload. |
| S10 | Does PNG output carry any metadata? | No text chunks, no timestamps. |

A no-go on S1 to S3 switches to the fallback in section 6.2. A no-go on S5 removes animation from the plan. A no-go on S8 switches PDF to PDFBox. None of them blocks text statistics.

## 17. Not verified in this run

- Nothing was sent to Telegram. All client-side behaviour (compression, bubble size, screen-reader exposure, sticker playback) is assumption.
- The production container was not built. All timings are from a desktop.
- resvg 0.50.0 was not executed; 0.49.0 was. Pixel stability across resvg versions is unknown and is expected to break goldens on upgrade.
- Batik, EchoSVG, Skiko, Skija, XChart and Typst were not executed. Their rows rest on documentation and metadata.
- CJK, Indic and Thai text were not tested with any rasteriser.
- The fix version of the OpenJDK change for colour emoji on Linux (JDK-8269806) could not be read; the local result (no pixels on Temurin 25.0.5) is what the brief relies on.
- Whether the Alpine Temurin build bundles FreeType was not inspected.

## 18. Open questions

| No. | Question | Recommendation |
|---|---|---|
| Q1 | Accept a native child process (resvg) in a service that so far has only JVM code? | Yes. It is the only evaluated option that renders user text correctly in every script with colour emoji, and it removes AWT from the service. |
| Q2 | Replace the XChart candidate named in DESIGN section 15 with in-repository chart primitives? | Yes, recorded as an ADR addendum. |
| Q3 | Add an optional energy dimension to the mood check-in so the blob has two axes? | Yes, as an optional second tap with a default. Without it the blob varies only in valence. |
| Q4 | Default for medication names in images. | Hidden until the user enables them once, with an explanation that images remain in the chat. |
| Q5 | Default image theme when the Mini App has not reported one. | Dark. |
| Q6 | Send personal images with `protect_content` by default? | Yes, with an account setting to turn it off, because it also blocks the user from forwarding a card to a clinician; the PDF report is the intended sharing route. |
| Q7 | Ship animation in the first release? | No. Ship the static blob; add TGS after spike S5. |
| Q8 | Schedule a widget experiment (Bot API 10.4) for live cards such as today's hydration ring? | Yes, after the image pipeline is green, as a progressive enhancement with the image as fallback. |
| Q9 | Typeface: Inter plus Noto, or Noto Sans throughout? | Inter plus Noto, subject to spike S6. Noto Sans throughout is the simpler fallback. |
| Q10 | Which locales ship first? This fixes the font pack and the fixture matrix. | English and Ukrainian first; keep one right-to-left fixture locale in tests from the start. |
| Q11 | Should the PDF report target PDF/UA-1, PDF/A-2 or both? | Both, if spike S8 shows the combination works for the template. |

## 19. Sources

All accessed 2026-11-12.

Telegram:

- Bot API reference, version 10.4 (2026-11-09): https://core.telegram.org/bots/api
- Sending files and `file_id` rules: https://core.telegram.org/bots/api#sending-files
- Widgets guide: https://core.telegram.org/bots/widgets
- Sticker requirements: https://core.telegram.org/stickers
- Photo size types: https://core.telegram.org/api/files

Libraries and tools:

- resvg repository, README and changelog (0.50.0, licence, colour font support, reproducibility statement): https://github.com/linebender/resvg and https://github.com/linebender/resvg/releases
- JSVG repository and README (2.2.0, supported features, animation status): https://github.com/weisJ/jsvg
- Skiko repository and README (supported platforms): https://github.com/JetBrains/skiko
- Skiko artifacts on Maven Central (platform list): https://repo1.maven.org/maven2/org/jetbrains/skiko/
- Skija artifacts on Maven Central: https://repo1.maven.org/maven2/io/github/humbleui/
- Maven Central metadata used for versions and dates: `https://repo1.maven.org/maven2/<group path>/<artifact>/maven-metadata.xml` for `com.github.weisj:jsvg`, `org.apache.xmlgraphics:batik-transcoder`, `org.apache.xmlgraphics:fop`, `org.knowm.xchart:xchart`, `org.jfree:jfreechart`, `com.microsoft.playwright:playwright`, `org.apache.pdfbox:pdfbox`, `de.rototor.pdfbox:graphics2d`, `io.github.openhtmltopdf:openhtmltopdf-core`, `org.jcodec:jcodec`, `org.jetbrains.skiko:skiko-awt`, `io.github.humbleui:skija-shared`
- XChart 4.0.4 POM (optional dependencies): https://repo1.maven.org/maven2/org/knowm/xchart/xchart/4.0.4/xchart-4.0.4.pom
- EchoSVG: https://github.com/css4j/echosvg
- Typst releases and PDF reference: https://github.com/typst/typst/releases and https://typst.app/docs/reference/pdf/
- Typst 0.14.0 changelog (PDF/UA-1, tagging by default): https://typst.app/docs/changelog/0.14.0/
- Apache ECharts server-side rendering: https://echarts.apache.org/handbook/en/how-to/cross-platform/server/
- vl-convert: https://github.com/vega/vl-convert
- ThorVG: https://github.com/thorvg/thorvg
- Playwright for Java system requirements: https://playwright.dev/java/docs/intro
- Alpine Linux package search, branch v3.24 (resvg absent; rsvg-convert, typst, chromium, ffmpeg, fonts): https://pkgs.alpinelinux.org/packages?branch=v3.24

Community reports used only for assumptions:

- AWT on Alpine without fontconfig: https://gitlab.alpinelinux.org/alpine/aports/-/issues/12030
- Skiko failing to load without `libGL.so.1`: https://slack-chats.kotlinlang.org/t/2755594/in-the-past-compose-jb-worked-just-fine-on-a-linux-server-wi
- OpenJDK review thread for colour emoji on Linux (JDK-8269806): https://mail.openjdk.org/pipermail/client-libs-dev/2023-February/010687.html
- Batik security advisories (fixed in 1.16 and 1.17): https://security.gentoo.org/glsa/202401-11 and https://ubuntu.com/security/notices/USN-6117-1

Repository files read: `build.mill`, `.mill-version`, `docker/Dockerfile`, `docker/compose.yml`, `scripts/license-gate.sh`, `docs/DESIGN.md` (sections 2 and 15), `docs/BOT_UX_PLAN.md` (section 6), `docs/ROADMAP.md` (M3.4 and the UX packet table), `docs/adr/README.md`, `AGENTS.md`.
