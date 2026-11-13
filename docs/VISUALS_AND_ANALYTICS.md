# Dosegram: visuals and analytics specification

Status: In force since 2026-11-13: the owner approved the plan and roadmap slice G0.2 promoted this document from `docs/dosegram/` to `docs/` ([ADR-014](adr/ADR-014.md)). Where the text below says "proposal" or "proposed", read it as the adopted specification; an open question keeps the status its own section gives it. Dosecord documents named as inputs (`docs/DESIGN.md`, the old `docs/ROADMAP.md`, `docs/BOT_UX_PLAN.md`) are now under `docs/archive/`. It does not claim that anything is implemented.
Date: 2026-11-12.
Scope: the product's visual status language. That covers the visual identity, the mood blob system, the catalogue of generated images and the PDF report, the metric definitions behind every number, the insight engine, the Mini App dashboard that mirrors the same metrics, and the schedule of proactive reports.

Sibling documents, referred to by name: PRODUCT_SPEC.md (feature scope and domain model), BOT_UX.md (chat flows, commands, copy), MINI_APP.md (screens, navigation, API use), ARCHITECTURE.md (modules, storage, queues, security), ROADMAP.md (delivery order). Where this document needs a decision that belongs to a sibling, it states its requirement and names the sibling.

## 0. How to read this document

### 0.1 Evidence labels

| Label | Meaning |
|---|---|
| VERIFIED | A statement about an external platform, library or font that was read in a primary source. The source URL and the date checked are given in section 5.1 or section 14. Most platform facts were verified by the research briefs on 2026-11-12 and are cited through them. |
| ASSUMPTION | A statement about external behaviour that no primary source or measurement confirms. Every assumption that a requirement depends on is listed in section 12 with a way to confirm it. |
| MEASURED | Observed in the scratch experiment of research brief 04 on the owner's development host. Not measured in the production container. |
| COMPUTED | A number produced in this run on 2026-11-12 by converting the stated OKLCH values to sRGB and running the palette validator of the `dataviz` skill (OKLab distance times 100, colour-vision deficiency simulated with the Machado, Oliveira and Fernandes 2009 model at severity 1.0). It is reproducible from the token values in section 3.3. |
| PROPOSED | A value chosen by this specification (a size, a threshold, a default). It is a requirement once the owner accepts the document, and it is configuration, not a constant. |

### 0.2 Requirement identifiers

Requirements carry identifiers of the form `VA-<area>-<number>`. Each is written so that a test, a lint or a review checklist can decide whether it holds. Section 11 maps the identifiers to test layers.

### 0.3 Domain keys

This document uses six domain keys: `medication`, `water`, `food`, `mood`, `sleep`, `body` (user-entered measurements such as weight). PRODUCT_SPEC.md owns the list. If it differs there, the colour assignment in section 3.3 follows the key, not the position.

## 1. Summary

### 1.1 Decisions

| # | Decision | Rationale | Rejected alternative |
|---|---|---|---|
| D1 | The visual language is built from one geometric unit, the cell (a rounded square that stands for one scheduled or recorded thing), and one organic shape, the mood blob. Everything else is flat. | Medication tracking is a grid of slots by nature (blister packs, pill organisers, calendars). One shared unit makes a reminder card, a strip, a heatmap and a milestone badge read as one system. Keeping a single expressive element makes it memorable. | A tile dashboard with rings, scores and gradients per domain, which is the common health-application look and would read as an imitation of it. |
| D2 | Server-rendered images are the baseline for every visual sent in chat. The same scene tree feeds PNG, PDF and the Mini App. | Research brief 04 section 6 and research brief 02 section 12: images are the only surface every Telegram client shows identically. | Widget-first or rich-message-first cards. |
| D3 | The mood blob is a parametric shape with 35 discrete states (7 valence levels by 5 energy levels) plus one "no entry" state. Its shape, colour and face depend on the mood inputs only. | Quantised states are global assets that are rendered once and cached by `file_id` for all users (research brief 04 section 9.2). Tying the face to mood only keeps the record truthful and prevents shaming. | A blob whose expression also reacts to missed doses or an unmet water goal. |
| D4 | Daily overall status is shown as a "day blob": the mood blob surrounded by a segmented halo, one segment per tracked domain, each filled by progress toward the user's own goal. | It answers "how is today going" in one picture without a composite score and without changing the blob's expression. | A single 0 to 100 daily score (rejected by research brief 03 section 10.2 as false precision). |
| D5 | Medication status is encoded by cell shape first and by one hue (lightness steps) second. No red and no green-versus-red contrast is used for dose outcomes. | "Unknown is neutral; skipped is not shown in a failure colour" is an inherited rule (BOT_UX_PLAN section 6). Shape survives JPEG recompression, greyscale printing and colour-vision deficiency. | Traffic-light status colours. |
| D6 | Five domain hues in a fixed assignment: Spruce (medication), Tide (water), Rye (food), Beet (mood), Dusk (sleep). The body domain has no hue and is drawn in neutral ink. | The five hues pass all-pairs separation for colour-vision deficiency and a 3:1 contrast minimum in both themes (COMPUTED, section 3.3). A sixth hue could not pass. | Six or more hues, which fail the all-pairs gate. |
| D7 | Hydration is drawn as a vessel (a vertical meter), not a ring. | A single ratio against a limit is a meter. A vessel shows "above the goal" as a plain number without wrapping, and it is not the ring that every hydration application uses. | A closed ring. |
| D8 | No Dosegram score exists in any domain. Headline numbers are counts, durations, percentages of the user's own goal, and regularity. | Research briefs 03 and 05: scores in the market depend on sensors. | Sleep score, energy score, health score, diet score. |
| D9 | Consistency is shown as a weekly count per non-medication domain ("5 of 7 days"), never as a run of consecutive days. Nothing resets, nothing can end, and no counter, run or milestone exists for medication in any form, including "record keeping" (PRODUCT_SPEC section 8.14, R-GAM-1 to R-GAM-6; ROADMAP conflict X-24). | A reward for tapping an answer pays for false records (research brief 03 section 6.2), and that holds for "answered" as much as for "taken". A weekly count cannot be lost. | A consecutive-day run with a grace rule, including a medication record-keeping run in which Skip counts; and the usual losable streak on "all doses taken". Both were in the first draft of this document and are withdrawn. |
| D10 | Insights are deterministic comparisons inside one user's own records, shown with group sizes, never with causal or clinical wording. No medication measure (whether, when or how much was taken, or how punctually a dose was answered) appears on either side of a cross-domain insight (PRODUCT_SPEC R-INS-9; ROADMAP conflict X-23). | Any sentence that links intake to mood, sleep or a measurement reads as evidence of treatment effect and moves toward medical-device territory (research brief 03 section 8), in either direction. | "On days you took all doses your mood was higher"; and dose punctuality as an outcome ("after shorter nights, morning doses were answered late"), which the first draft of this document allowed and which is withdrawn. |
| D11 | The Mini App draws its charts on Dosegram's own panel surface and takes page chrome (background, text, buttons) from the Telegram theme. | Chart colours are validated against known surfaces. A user-customised Telegram theme can have any background colour. | Drawing data marks directly on `--tg-theme-bg-color`. |
| D12 | Typefaces: IBM Plex Sans for text and Geologica for hero numerals and card titles, with IBM Plex Sans Arabic and Hebrew for right-to-left scripts. | Both are OFL-1.1 with Cyrillic (VERIFIED, section 14). Plex has a width axis, which gives a condensed instance for long names before truncation, and has sibling Arabic and Hebrew families of the same design. | Inter plus Noto (the recommendation of research brief 04 Q9); it remains the fallback. See open question Q3. |
| D13 | A reminder is always a text message and never carries an image (BOT_UX D5, ARCHITECTURE TG-30, PRODUCT_SPEC section 3.1; ROADMAP conflict X-27). The reminder card IMG-01 is withdrawn (section 6.2). | A reminder must be readable in a notification preview, and its state machine is built on `editMessageText` with a 4096-character body, which a photo message with a 1024-character caption cannot follow (BOT_UX section 9.5). Image work can then never delay a reminder. | A pre-rendered generic reminder image sent by `file_id` (form R1) and an opt-in personal reminder card (form R2), both in the first draft of this document. |
| D14 | Proactive reports are opt-in, capped, merged by precedence (monthly over weekly over daily) and paused automatically when ignored. | Six domains can produce enough messages to get the bot muted, which also silences medication reminders (research brief 03 section 4.2). | One scheduled report per domain. |

### 1.2 Where the owner's direction meets a verified constraint

| Owner's wish | Constraint | What this specification does instead |
|---|---|---|
| Sleep tracker based on the user's "last seen" | The Bot API and the Mini App API expose no presence data (VERIFIED, P14). | Sleep visuals take three evidence tiers as an input and draw each differently: a passive "quiet window" is drawn hatched and labelled "at most", never as a duration (section 6, IMG-12; section 7.7). |
| "The bot should do everything" including analytics and dashboards | Self-reported data cannot support sensor-style scores, and interpretation of health values is medical-device territory in the EU and the US (research brief 03 section 8). | Counts, durations, own-goal percentages, regularity and descriptive comparisons only. No score, no threshold, no advice (sections 7 and 8). |
| Infographics and status images in chat | Photos are limited to 10 MB and are probably recompressed to about 1280 px (P1 VERIFIED, P7 ASSUMPTION). Captions are limited to 1024 characters (P2). A bot can delete its messages only within 48 hours (P6). Bot chats are not end-to-end encrypted (P13). | Fixed canvases of at most 1280 px, large type, no hairlines, a text equivalent for every image, discreet mode, and a separate consent before the first image is sent into the chat (section 5). |
| A "mood check blob" | Stickers have no caption and animated stickers are limited to 512 by 512 px, 3 seconds and 64 KB (P9, P10). | Static PNG is the default reply. The animated form is opt-in, later, and always followed by a text message (section 4.6). |
| Reminder images | About 30 messages per second per bot and 1 per second per chat (P11). | D13. |

## 2. Principles of the visual status language

| ID | Principle | Test |
|---|---|---|
| VA-PR-01 | One snapshot, all representations. The image, its caption, the text view, the PDF figure and the Mini App chart for the same request derive from one immutable view model. | For every fixture, each number in the SVG text nodes also appears in the text equivalent. |
| VA-PR-02 | Records, not judgements. An image states what was recorded. It never states or implies advice, a diagnosis, a dose recommendation or a comparison with a clinical norm. | The forbidden-phrase lint (section 8.6) passes over every string that can appear in an image, caption or PDF. |
| VA-PR-03 | Unknown is neutral and a gap is a gap. A missing record is drawn as an empty or dotted mark. It is never drawn as zero, never interpolated, and never coloured as a failure. | Fixtures with missing days show no mark at value zero and no line across the gap. |
| VA-PR-04 | A denominator of zero renders "No data", not 0 percent. | Fixture with an empty period. |
| VA-PR-05 | No composite score in any domain and no cross-domain score. | No template has a slot for one. Review checklist. |
| VA-PR-06 | Status and identity are never carried by colour alone. Shape, position, an icon or a label repeats it. | Scene lint: every mark with a status or domain role has a non-colour channel recorded in the scene metadata. Greyscale fixture review. |
| VA-PR-07 | Own baseline only. Comparisons are with the user's own earlier periods or the user's own goal. No population norms, no percentiles, no "people like you". | No template or insight rule reads data from another account. |
| VA-PR-08 | No shaming. No sad or damaged character, no red for missed or skipped, no "you lost", no countdown to a loss. | Forbidden-phrase lint, palette lint (the `critical` token is not allowed in any image template), review checklist. |
| VA-PR-09 | Every image has a text equivalent that is content, not a description of appearance. | Text-equivalent golden per fixture. |
| VA-PR-10 | Legible after Telegram processing. Text and strokes meet the minimums in section 3.5 at the canvas sizes in section 5.3. | Scene lint on minimum text size and stroke width. Spike S4 of research brief 04. |
| VA-PR-11 | Deterministic output. The same view model, theme, locale and renderer revision always yield the same SVG bytes. | Property test (research brief 04 section 12, layer 3). |
| VA-PR-12 | Image work never delays or blocks a medication reminder, never changes intake state, and falls back to the text equivalent on failure or timeout. | Fault-injection test on the render queue. |

## 3. Visual identity brief

### 3.1 Concept: cells and one blob

The subject is a person's day, divided into things that happen at a time: a tablet at 08:00, a glass of water, a meal, a night's sleep. The physical world of medication already has a visual vocabulary for this: the blister pack, the weekly pill organiser, the wall calendar. All three are grids of cells that are either full, empty or opened.

Dosegram's identity takes that grid as its only geometric idea. A **cell** is a rounded square. A scheduled dose is a cell. A day in a calendar is a cell. A milestone of 30 days is 30 cells. Cells are flat, have no shadow and no gradient, and change only by fill, outline and a small set of marks.

Against the grid stands one organic element, the **mood blob**. It is the only shape with a gradient, a face and motion. It is used for mood and nowhere else.

The identity is original to this project. It must not reproduce the characters, score rings, tile dashboards, colour quadrants, face sets or illustration styles of Samsung Health, Apple Health, Google Health, Daylio, How We Feel, Finch or any other product. A rounded square and a circle with two eyes are generic; a recognisable character or layout is not. This is a design position, not legal advice.

| ID | Requirement |
|---|---|
| VA-ID-01 | Every data mark in an image is a cell, a bar with a cell-radius end, a line, a dot, or the blob. No pie, no donut, no ring gauge, no 3D, no pictorial character other than the blob. |
| VA-ID-02 | Gradients appear only inside the blob fill. All other fills are flat token colours. |
| VA-ID-03 | No drop shadow, glow or blur anywhere. Depth is expressed by the three neutral surfaces only. (Filters are also outside SVG profile 1, research brief 04 section 7.3.) |
| VA-ID-04 | No template contains a literal colour, a literal font name or a literal word. Colours are tokens, fonts are roles, words come from the message catalogue. |

### 3.2 Shape language

Units are pixels on a canvas 1080 px wide. Other canvases scale the same values by `canvas width / 1080`, with the minimums of section 3.5 applied after scaling.

| Element | Geometry | Notes |
|---|---|---|
| Cell | Square with corner radius equal to 28 percent of its side. Minimum side 44 px. | Drawn as a `rect` with `rx`. |
| Cell gap | 12 px between cells in a strip, 16 px in a calendar. Never less than 8 px. | The gap is surface colour, which separates neighbours without an outline. |
| Bar | Width at least 28 px. The data end has the cell radius (capped at half the width); the baseline end is square. | Bars always start at zero. |
| Line | 6 px, round joins and caps. Minimum 4 px. | Lines break at gaps in the data. |
| Dot | Radius at least 10 px, with a 4 px ring in the surface colour when it overlaps a line. | |
| Track | The unfilled part of a meter or halo, drawn in the `grid` token. | |
| Panel | A container rectangle in the `panel` token with corner radius 40 px. | Plots sit on a panel or directly on the surface. |
| Hatch | Parallel 4 px strokes at 45 degrees, 16 px apart, computed as explicit line segments clipped analytically to the inset rectangle of the mark. | `pattern` and `clipPath` are outside SVG profile 1, so the layout stage computes the segments. Hatch means "uncertain" or "estimate" everywhere. |
| Dotted outline | 4 px stroke, dash 4 and gap 12 with round caps. | Means "no record" or "not yet". |
| Blob | Section 4. | The only curve-outlined shape. |
| Corner safe zone | Nothing meaningful within 48 px of a canvas corner. | Telegram clients round the corners of photo bubbles (ASSUMPTION A3). |

Medication status glyphs. These are the normative encodings for a dose occurrence wherever one is drawn (reminder card, strip, daily card, PDF legend, Mini App).

| Status (domain term) | Glyph | Fill | Second channel |
|---|---|---|---|
| Taken on time | Solid cell | `spruce.base` | Full square |
| Taken late | Solid cell with the top trailing corner cut by a 45 degree chamfer of 30 percent of the side | `spruce.late` | The cut corner |
| Skipped | Hollow cell, 6 px outline | Outline `ink2`, no fill | Hollow |
| Missed (no answer before the window closed) | Empty cell with a centred horizontal dash, 6 px, 40 percent of the side | Dash `ink3`, dotted outline `grid` | Dash |
| Unknown (delivery not evidenced, excluded from adherence) | Hatched cell | Hatch `ink3` | Hatch |
| Open (due or upcoming) | Dotted outline only | `ink3` | Dotted |
| As-needed log (PRN) | Solid circle of the cell's inscribed diameter | `spruce.base` | Circle instead of square |
| No dose scheduled | Nothing | | Empty slot |

"Trailing corner" is the top right corner in left-to-right layouts and the top left corner in right-to-left layouts.

| ID | Requirement |
|---|---|
| VA-ID-05 | The eight glyphs above are distinguishable in a greyscale rendering of the legend fixture at 360 px display width. Reviewed on the contact sheet. |
| VA-ID-06 | Skipped and missed never use `attention` or `critical` tokens, in any template. Palette lint. |
| VA-ID-07 | A legend that names every glyph present appears on any image that shows more than one medication status. |

### 3.3 Colour

All colours are defined in OKLCH and converted to sRGB at build time. The hex values below are the output of that conversion in this run (COMPUTED). A change to any value must re-run the validator and regenerate the goldens.

#### 3.3.1 Neutral tokens

| Token | Role | Light | Dark | OKLCH source (light / dark) |
|---|---|---|---|---|
| `surface` | Card background, full bleed | `#eef5f8` | `#0f222b` | 0.965 0.008 230 / 0.24 0.030 230 |
| `panel` | Plot wells, chips, legend boxes | `#ffffff` | `#071820` | 1 0 0 / 0.20 0.028 230 |
| `grid` | Tracks, axis rules, dotted outlines of empty slots | `#cdd6db` | `#2f434d` | 0.87 0.012 230 / 0.37 0.030 230 |
| `ink` | Primary text, hero numerals | `#13242e` | `#e8f0f4` | 0.25 0.030 235 / 0.95 0.010 230 |
| `ink2` | Secondary text, hollow glyph outlines, body-domain lines | `#435561` | `#b1c0c8` | 0.44 0.030 235 / 0.80 0.020 230 |
| `ink3` | Captions inside the image, axis labels, dashes, hatches | `#5e6e78` | `#8b9ba3` | 0.53 0.025 235 / 0.68 0.022 230 |
| `attention` | Refill warning accent and system notices only | `#c9690a` | `#ea942f` | 0.62 0.16 55 / 0.74 0.15 65 |
| `critical` | Destructive actions in the Mini App only. Not allowed in any image. | `#b71824` | `#f66d67` | 0.50 0.19 25 / 0.70 0.17 25 |

The light surface is a cool porcelain and the dark surface is a deep blue-green, not a cream and not a near-black. In light theme the panel is lighter than the surface; in dark theme it is darker. In both it is the well of highest contrast.

Contrast (WCAG 2 ratio, COMPUTED): `ink` 14.4 on light surface and 14.2 on dark surface; `ink2` 7.0 and 8.8; `ink3` 4.8 and 5.7; on panels all three are higher. `attention` is 3.5 on the light surface and 6.8 on the dark surface, so in light theme it is used for marks and for text of at least 56 px only.

#### 3.3.2 Domain hues

| Domain | Name | Light base | Dark base | Used for |
|---|---|---|---|---|
| `medication` | Spruce | `#0d9675` | `#2cab88` | Dose cells, adherence bars and heatmaps |
| `water` | Tide | `#02558f` | `#0072be` | Vessel fill, hydration bars |
| `food` | Rye | `#955907` | `#9e5d00` | Meal marks, macro bar steps |
| `mood` | Beet | `#940f52` | `#bf3f75` | The mood series where mood sits beside other domains as one coloured series. The blob itself uses the valence ramp of section 4.4. |
| `sleep` | Dusk | `#8a66d9` | `#9d7aef` | Sleep bars and regularity marks |
| `body` | none | `ink2` | `ink2` | Measurement lines, always single-series |

Validation results (COMPUTED, 2026-11-12, all five hues, all-pairs mode, which is the mode for small multiples where any two colours can be neighbours):

| Check | Light, on `panel` | Dark, on `panel` |
|---|---|---|
| Lightness band | pass (0.43 to 0.77) | pass (0.48 to 0.67) |
| Chroma floor 0.10 | pass | pass |
| Worst pair under simulated protanopia or deuteranopia | 11.7 (Rye and Spruce); target 8 or more | 9.1 (Beet and Spruce); target 8 or more |
| Worst pair, normal vision | 18.3 (Beet and Rye); floor 15 | 16.7 (Beet and Rye); floor 15 |
| Contrast against the panel | 3.73 or more | 3.46 or more |
| Contrast against the `surface` | 3.38 or more | 3.13 or more |

The colours follow the domain, never the position in a chart. A view that hides one domain does not repaint the others.

Each domain hue has an eight-step ramp for tints and ordinal steps (steps 100 to 800, light to dark):

| Ramp | 100 | 200 | 300 | 400 | 500 | 600 | 700 | 800 |
|---|---|---|---|---|---|---|---|---|
| Spruce | `#c2f1df` | `#91ddc2` | `#78c3a9` | `#32b08d` | `#0d9675` | `#007c60` | `#04614b` | `#004937` |
| Tide | `#d0e8ff` | `#a0d0fe` | `#7ab7f1` | `#3e9eef` | `#1e85d4` | `#086cb3` | `#02558f` | `#003f6d` |
| Rye | `#ffdebf` | `#fabc80` | `#dfa367` | `#d6810c` | `#b56d07` | `#955907` | `#774502` | `#5a3200` |
| Beet | `#ffd9e4` | `#ffb0ca` | `#ee90b1` | `#e76498` | `#cb4b80` | `#b03068` | `#940f52` | `#72013d` |
| Dusk | `#e6e0ff` | `#cec0fe` | `#b6a1f5` | `#a17ff5` | `#8a66d9` | `#734dbe` | `#5d34a4` | `#48198a` |

Derived tokens:

| Token | Light | Dark | Use |
|---|---|---|---|
| `spruce.late` | `#78c3a9` (Spruce 300) | `#038265` | Taken-late cells |
| `spruce.heat.1` to `spruce.heat.4` | `#78c3a9`, `#32b08d`, `#007c60`, `#004937` | `#04614b`, `#038265`, `#29a987`, `#8bd6bc` | Adherence heatmap, lowest to highest. In dark theme the anchor flips: the lightest step is the highest value. |

The heat steps pass the ordinal checks in both themes (COMPUTED): monotone lightness, at least 0.06 lightness between neighbours, and the step nearest the surface at 2.06:1 (light) and 2.43:1 (dark) against the panel, above the 2:1 floor.

| ID | Requirement |
|---|---|
| VA-CO-01 | The palette validator is run in CI on the five domain bases in all-pairs mode for both themes and on the heat steps in ordinal mode. A hard fail fails the build. |
| VA-CO-02 | Data marks are drawn on `surface` or `panel` only. |
| VA-CO-03 | No text is drawn on a domain-coloured fill. Values and labels sit beside or below the mark in `ink`, `ink2` or `ink3`. |
| VA-CO-04 | Text never wears a domain colour. A coloured mark or icon next to the text carries the identity. |
| VA-CO-05 | Scene lint: text contrast at least 4.5:1 and meaningful graphics at least 3:1 against their actual background, in both themes, for every fixture. On the tinted mood backgrounds of section 4.4 the lowest text contrast is `ink3` at 4.6:1 in light theme and 5.3:1 in dark theme (COMPUTED). |
| VA-CO-06 | Two domains appear in one plot only as small multiples with their own axes. No dual-axis chart exists in any template. |
| VA-CO-07 | A sequential or ordinal encoding uses one hue. A diverging encoding is used only for mood valence (section 4.4). No rainbow scale. |

#### 3.3.3 Theme selection for images

A bot cannot learn the chat theme of the user for ordinary messages (ASSUMPTION A1). The rule of research brief 04 section 8.3 is adopted: the account setting `image_theme` has the values `auto`, `light` and `dark`; `auto` uses the colour scheme last reported by the Mini App; with no report the default is dark. Theme is part of every cache key.

### 3.4 Typography

| Role | Family | Weights | Use |
|---|---|---|---|
| Display | Geologica (OFL-1.1; Latin, Cyrillic, Greek; VERIFIED, section 14) | 600 | Hero numerals and card titles, 64 px and larger |
| Text | IBM Plex Sans (OFL-1.1; Latin, Cyrillic, Greek; width and weight axes; VERIFIED) | 400, 500, 600 | Everything else |
| Text, condensed | IBM Plex Sans at the condensed width instance | 500 | The first fitting step for long medication names and long locale strings, before any truncation |
| Arabic script | IBM Plex Sans Arabic (OFL-1.1; VERIFIED) | 400, 600 | Runs the itemiser marks as Arabic |
| Hebrew script | IBM Plex Sans Hebrew (OFL-1.1; VERIFIED) | 400, 600 | Runs the itemiser marks as Hebrew |
| Emoji in user text | Noto Color Emoji (OFL-1.1, research brief 04 section 8.4) | | Emoji runs only |

Rationale for the choice against the research recommendation of Inter plus Noto: the width axis gives a designed condensed face for the hardest fitting problem in this product (user-entered names in a 40 percent longer locale); the Arabic and Hebrew siblings keep one design across scripts; and Geologica gives the numerals, which are the content of most cards, a character of their own. Rejected alternative: Inter plus Noto Sans, which stays the documented fallback if spike S6 of research brief 04 or the glyph-coverage test fails.

Static instances are cut from the variable fonts at build time. Whether resvg honours OpenType tabular numerals through SVG attributes is unverified (ASSUMPTION A6); if it does not, the tabular feature is baked into the static instances. Whether each family covers every letter of the Ukrainian alphabet is an ASSUMPTION (A7) that the font-pack test confirms.

Type scale on a 1080 px canvas (size / line height, in px):

| Step | Size | Family and weight | Use |
|---|---|---|---|
| `hero` | 160 / 168 | Display 600 | The one headline number of a card |
| `title` | 64 / 72 | Display 600 | Card title |
| `value` | 56 / 64 | Text 600, tabular numerals | Row values |
| `body` | 44 / 56 | Text 400 or 500 | Row labels, sentences |
| `label` | 40 / 48 | Text 500 | Axis labels, legends, footers. This is the minimum size. |

| ID | Requirement |
|---|---|
| VA-TY-01 | No text is smaller than 40 px on a 1080 px-wide canvas or 44 px on a 1280 px-wide canvas (which is displayed narrower relative to its width). Scene lint. |
| VA-TY-02 | All numerals that align in a column use tabular figures. Golden fixture with a column of values 1, 11, 111. |
| VA-TY-03 | Sentence case everywhere. No all-capitals labels and no letter-spaced labels. Catalogue lint on the image namespace. |
| VA-TY-04 | Fitting order for a string that exceeds its slot: condensed width, then a second line (two lines at most), then an ellipsis. The font size is never reduced below the step's size. The decision is visible in the SVG golden. |
| VA-TY-05 | The font-pack test asserts that every character of every shipped locale's alphabet, the digits and the punctuation used by the catalogue are covered by the assigned family. An uncovered character fails the build. |
| VA-TY-06 | Every text run carries an explicit family from the font pack. The emitter never writes a generic family (research brief 04 section 7.4). |

### 3.5 Layout system and minimums

| Property | Value on a 1080 px canvas |
|---|---|
| Base unit | 8 px. All positions and sizes are multiples of 4 px. |
| Outer margin | 64 px |
| Gutter between blocks | 32 px |
| Card structure, top to bottom | Header (title, period), body (one dominant plot or list), fact line (coverage and exclusions), footer (the non-advice line and the legend) |
| Alignment | Start-aligned (left in left-to-right locales). Hero numerals start-aligned with their label below. Centred layout only on blob cards and milestone cards. |
| Minimum stroke | 4 px |
| Minimum cell side | 44 px |
| Minimum text | 40 px (VA-TY-01) |
| Maximum series in one plot | 4 |
| Maximum rows in a list card | 6; more rows paginate into further images or are summarised as "and N more" |

Every card has exactly one dominant element. A card whose content needs two dominant plots is two cards.

The footer of every personal card contains the catalogue string `image.footer.records_only` (English: "Recorded entries only. Not medical advice.") in `label` size.

| ID | Requirement |
|---|---|
| VA-LA-01 | Scene lint asserts the minimum stroke, cell and text sizes and that every text box lies inside its slot using measured extents. |
| VA-LA-02 | Every personal card template contains the footer string. Golden. |
| VA-LA-03 | Each template declares its maximum row and series counts, and a property test with larger inputs shows pagination or summarising, never overflow or silent truncation of data. |

### 3.6 Icons

Icons are in-repository vector paths on a 24-unit grid with a 2-unit stroke and round caps, stored as `symbol` elements. They are drawn at 56 px or larger so the stroke is at least 4 px. They are never emoji glyphs and never copied from an icon set of another product.

| Icon | Shape | Domain or use |
|---|---|---|
| `cell-split` | Rounded square with a horizontal score line | Medication |
| `drop` | Drop outline | Water |
| `plate` | Circle with an inner arc | Food |
| `blob-mini` | Small three-lobed outline | Mood |
| `crescent` | Crescent | Sleep |
| `mark-line` | Short horizontal line with a dot | Body |
| `bell`, `calendar`, `refill` (cell with a return arrow), `clock`, `lock` | Outline forms | Reminder, period, refill, time, discreet mode |

In multi-domain views the icon and the written domain name always accompany the domain colour (VA-PR-06).

### 3.7 Coexistence with the Telegram theme

| Surface | Rule |
|---|---|
| Photo in chat | Opaque, full-bleed `surface` background, because transparency is probably lost on the photo path (ASSUMPTION A2). The theme follows section 3.3.3. The card does not imitate a Telegram bubble, button or system dialog (Bot Developer Terms 5.3, P15). |
| Sticker (transparent) | No theme variant. The blob outline is a double stroke: an inner 6 px stroke in `#6a7e8a` and an outer 4 px stroke in `#f3f6f8` at 512 px canvas. The inner colour measures 4.2:1 against white and 4.3:1 against an assumed Telegram dark background `#0e1621` (COMPUTED against ASSUMPTION A4). |
| Mini App chrome | Page background, text, hints, links, buttons, section backgrounds and separators come from the `--tg-theme-*` variables (VERIFIED, P16). Light or dark token set is chosen by `Telegram.WebApp.colorScheme` and switched on `themeChanged`. |
| Mini App charts | Drawn on a Dosegram `panel` card with a 1 px-equivalent `grid` border, so chart colours sit on a validated background whatever the user's Telegram theme is (D11). |
| Mini App buttons | The Telegram `MainButton`, `SecondaryButton` and `BackButton` are used for primary, secondary and back actions. Dosegram does not draw look-alikes of them. |
| Mini App destructive text | `--tg-theme-destructive-text-color` when present, else `critical`. |

| ID | Requirement |
|---|---|
| VA-TG-01 | The Mini App reports `colorScheme` to the backend on launch and on `themeChanged`; the value is stored as the account's last reported scheme and is the only input to `image_theme = auto`. |
| VA-TG-02 | With a synthetic Telegram theme of extreme colours (pure black, pure white, saturated magenta background), every Mini App chart still passes the contrast lint, because charts sit on `panel`. Visual test. |

### 3.8 Motion

Static images are the default and the baseline. The animated blob (section 4.6) is opt-in. In the Mini App, motion answers a user action only (a bar growing when a period is switched, the blob morphing when the mood picker moves) and is removed when `prefers-reduced-motion` is set. Nothing flashes, pulses for attention or loops in the Mini App outside the blob.

### 3.9 Words inside images

Images carry few words: a title, a period, labels, one fact line and the footer. Rules:

| ID | Requirement |
|---|---|
| VA-WR-01 | Sentences in images use the verbs record, log, show, count and compare with your own past. The forbidden-phrase lint of section 8.6 applies to the image namespace of the catalogue. |
| VA-WR-02 | A period is always stated with its dates and the account time zone when the zone differs from the zone at the previous report, for example "2 to 8 Nov". |
| VA-WR-03 | Product-authored text never names a disease or a condition. User-entered names appear only at privacy level L2 or L3 (section 5.4). |
| VA-WR-05 | The words for dose states inside images, captions and the PDF are the user-facing status vocabulary of BOT_UX section 22.4 (taken, skipped, snoozed, "not confirmed" for `missed`, "no record" for `unknown`, "Item 1" onward as discreet labels). This document does not define status words of its own; where an example in section 6 differs, BOT_UX section 22.4 wins. |
| VA-WR-04 | An empty state says what is missing and how to add it, in one sentence, for example "No water logged this week. Tap Water in the menu to add a glass." It does not apologise and does not scold. |

## 4. The mood blob system

### 4.1 Purpose and limits

The blob is the reply to a mood check-in, the mood mark in every report, the mood picker in the Mini App and the centre of the day blob. It expresses what the user said about their mood. It is not a mascot: it has no name, no story, no needs, and it does not react to what the user did or did not do.

### 4.2 Mood model

| Input | Type | Source | Default | Effect on the blob |
|---|---|---|---|---|
| Valence `v` | Real, -1 to 1 | The mood check-in. PRODUCT_SPEC.md owns the input scale; the mappings for a five-step tap and for the inherited 1 to 10 level are given below. | none; without it the state is "no entry" | Colour, smoothness, mouth, brows, slump |
| Energy `e` | Real, 0 to 1 | Optional second tap of the check-in | 0.5 | Lobe amplitude, harmonic weight, posture, eye opening, chroma, motion rate |
| Tags | At most 5 per check-in, from one list the user can edit (PRODUCT_SPEC R-MOO-3). There is no fixed catalogue of feeling and context words | Optional third step | none | None on the blob. Shown as text under it on the personal card and used by the insight engine as grouping factors. |
| Note | Free text | Optional | none | Never drawn in any image. |
| Stability `s` | Real, 0 to 1 | Derived from the spread of the last 7 daily means (section 7.6) | not drawn | Not drawn in the first release. Reserved; see section 4.9. |

Quantisation. Valence is quantised to seven levels V1 to V7 with equal-width bins on [-1, 1]: the level is `min(7, floor((v + 1) * 3.5) + 1)`. Energy is quantised to five levels E1 to E5: `min(5, floor(e * 5) + 1)`.

| Input scale | Mapping to `v` | Levels reached |
|---|---|---|
| Five-step tap (recommended for the mandatory first tap, research brief 03 section 3.4) | Steps 1 to 5 map to -1, -0.5, 0, 0.5, 1 | V1, V2, V4, V6, V7 |
| Inherited level 1 to 10 (`MoodLevel` in `contracts`) | `v = (level - 5.5) / 4.5` | 1 and 2 to V1; 3 to V2; 4 to V3; 5 and 6 to V4; 7 to V5; 8 to V6; 9 and 10 to V7 |
| Period average (weekly and monthly blobs, section 4.8) | Mean of daily means, used directly | All seven |

The seven levels exist so that averages have intermediate states (V3 and V5) that a single five-step tap never produces.

Level names. PRODUCT_SPEC R-MOO-1 and R-MOO-2 own the labels of the captured steps, and one vocabulary is used on the chat buttons, inside the image, in the caption, in the text equivalent and in the Mini App. The five captured valence steps and the five energy steps therefore use the PRODUCT_SPEC catalogue keys. Only the two intermediate levels V3 and V5, which a single check-in never produces, have names of their own, and those names appear only on averaged states (section 4.8). The first draft of this document used a second vocabulary ("Rough" to "Great", "Drained" to "Wired"); it is withdrawn.

| Valence level | Captured step | Key | English default | Energy level | Key | English default |
|---|---|---|---|---|---|---|
| V1 | 1 | `mood.valence.1` | Very unpleasant | E1 | `mood.energy.1` | Very low |
| V2 | 2 | `mood.valence.2` | Unpleasant | E2 | `mood.energy.2` | Low |
| V3 | none (averages only) | `mood.avg.v3` | Slightly unpleasant | E3 | `mood.energy.3` | Medium |
| V4 | 3 | `mood.valence.3` | Neutral | E4 | `mood.energy.4` | High |
| V5 | none (averages only) | `mood.avg.v5` | Slightly pleasant | E5 | `mood.energy.5` | Very high |
| V6 | 4 | `mood.valence.4` | Pleasant | | | |
| V7 | 5 | `mood.valence.5` | Very pleasant | | | |

Captions follow PRODUCT_SPEC R-MOO-1: the valence label, then the energy label when energy was given ("Pleasant. Energy: high."). Stored values are valence 1 to 5 and energy 1 to 5 (ROADMAP conflict X-01); the seven-level geometry is a drawing device, not a stored scale.

### 4.3 State set

| Set | Count | Class | Notes |
|---|---|---|---|
| Mood states | 35 (V1 to V7 by E1 to E5) | Global asset | State identifier `blob.v<1-7>.e<1-5>` |
| No entry | 1 | Global asset | `blob.none`: outline only, no face, neutral fill |
| Static PNG variants | 36 states by 2 themes by shipped locales (the level label is drawn in) | Global | A label-free variant per theme (72 files) is also produced for reuse inside cards and for locales not yet shipped |
| Sticker variants | 35 animated stickers, transparent, no label | Global | Later, after spike S5 of research brief 04 |

Rejected alternative: continuous parameters or a per-user seed. Either would make every blob a personal asset with no cache and no exhaustive golden test.

### 4.4 Parameter mapping

All geometry is relative to the base radius `R`. On the 1080 by 1080 mood card `R = 300 px` and the centre is at (540, 500).

#### Shape

The outline is the closed curve `r(theta) = R * (1 + sum for k = 2..5 of a_k * sin(k * theta + phi_k))`, with `a_k = A(e) * w_k(e)`.

| Energy | Total amplitude `A` | Harmonic weights `w_2, w_3, w_4, w_5` | Sample count | Vertical scale | Base widening |
|---|---|---|---|---|---|
| E1 | 0.030 | 0.70, 0.20, 0.10, 0.00 | 8 | 0.95 | 6 percent |
| E2 | 0.055 | 0.55, 0.25, 0.15, 0.05 | 8 | 0.975 | 3 percent |
| E3 | 0.080 | 0.40, 0.30, 0.20, 0.10 | 10 | 1.00 | 0 |
| E4 | 0.110 | 0.30, 0.30, 0.25, 0.15 | 12 | 1.025 | 0 |
| E5 | 0.140 | 0.20, 0.25, 0.30, 0.25 | 12 | 1.05 | 0 |

- Phases `phi_k` are drawn from a SplitMix64 generator seeded with the 64-bit FNV-1a hash of the ASCII string `dg-blob-1:<state id>`. A state therefore has the same outline in every build and every implementation.
- The curve is sampled at the stated number of equally spaced angles and converted to a closed cubic Bezier path through Catmull-Rom interpolation.
- "Base widening" scales the horizontal radius of the lower half by the stated percentage, blending linearly from the equator to the bottom.

| Valence | Curve tension | Slump | Meaning carried |
|---|---|---|---|
| V1, V2 with E4 or E5 | 0.55 (lobes become pointed) | none | Tense |
| V1, V2 with E1 to E3 | 1.0 (round) | Top lowered by 8 percent (V1) or 4 percent (V2) of `R` | Heavy |
| V3 to V7 | 1.0 (round) | none | Even to buoyant |

#### Colour

Fill colours by state (COMPUTED from OKLCH; rows are valence, columns are energy):

| | E1 | E2 | E3 | E4 | E5 |
|---|---|---|---|---|---|
| V1 | `#3f3d64` | `#3e3a70` | `#3e367a` | `#3e3383` | `#3f2e8d` |
| V2 | `#48577b` | `#415689` | `#3c5593` | `#37539d` | `#3251a7` |
| V3 | `#597288` | `#4f7391` | `#477398` | `#3d74a0` | `#3374a7` |
| V4 | `#7d8c8e` | `#798e90` | `#758f92` | `#719093` | `#6d9195` |
| V5 | `#93a98e` | `#8dac86` | `#88ae80` | `#83b07a` | `#7eb273` |
| V6 | `#bcbe92` | `#bdc080` | `#bec171` | `#bfc161` | `#c0c24e` |
| V7 | `#ead6a5` | `#f1d48e` | `#f7d37b` | `#fdd265` | `#fed25d` |

OKLCH sources per valence level (lightness, base chroma, hue): V1 0.38, 0.130, 285; V2 0.46, 0.125, 266; V3 0.54, 0.090, 245; V4 0.63, 0.035, 205; V5 0.71, 0.090, 140; V6 0.79, 0.120, 110; V7 0.88, 0.135, 88. Chroma is the base chroma times 0.50, 0.70, 0.85, 1.00, 1.15 for E1 to E5, reduced where needed to stay inside sRGB.

The path runs from a deep indigo through slate blue and grey-teal to sage, straw and warm gold. Lightness rises monotonically with valence by 0.08 or more per level, so the order survives greyscale and colour-vision deficiency.

Honest limit (COMPUTED): neighbouring valence levels are not reliably separable by colour alone. The worst neighbouring pair measures 6.8 under simulated colour-vision deficiency and 8.2 with normal vision, inside the band where a second encoding is mandatory. The face, the shape and the written level name are that second encoding and are never omitted (VA-BL-04).

| Colour part | Rule |
|---|---|
| Fill | Two-stop radial gradient, centre offset to 35 percent from the start edge and 30 percent from the top, radius 1.1 `R`. Outer stop: the state colour. Inner stop: lightness plus 0.10, chroma times 0.75, same hue. |
| Outline on cards | 8 px stroke in `ink2` of the card theme. It separates the fill from the background where their contrast is low (the lightest states on light theme, the darkest on dark theme). |
| Face ink | `#e8f0f4` on V1 to V3, `#13242e` on V4 to V7. Minimum contrast against the fill is 4.3:1 (COMPUTED). |
| No-entry fill | `#d8dfe3` (light), `#23363f` (dark), flat, with a dotted outline in `ink3`. |

#### Face

Positions are relative to the blob centre, x toward the end edge and y downward, in units of `R`.

| Feature | Rule |
|---|---|
| Eyes | Two ellipses at (-0.28, -0.12) and (0.28, -0.12). Horizontal radius 0.055. Vertical radius 0.055 times the eye-opening factor: 0.35, 0.55, 0.75, 0.90, 1.00 for E1 to E5. |
| Mouth | One quadratic curve from (-0.20 * m, 0.22) to (0.20 * m, 0.22), where the width factor `m` is 0.85, 0.92, 1.00, 1.08, 1.15 for E1 to E5. The control point is at (0, 0.22 + c) with `c` = -0.10, -0.07, -0.035, 0, 0.04, 0.08, 0.12 for V1 to V7. Positive `c` bends the middle down, which reads as a smile. Stroke 0.035, round caps. |
| Brows | Only for V1 and V2 with E4 or E5: two straight strokes of length 0.14 above the eyes at y = -0.24, tilted 12 degrees inward. Stroke 0.03. |
| Restraint | No tears, sweat drops, wounds, bandages, thermometers, sickness colours, closed "dead" eyes or mockery in any state. The lowest state looks tired or tense, not broken. |

#### Background and ground

| Part | Rule |
|---|---|
| Card background | A flat tint that depends on valence only. Light: `#eeeffc`, `#eaf0fd`, `#e7f2fc`, `#e3f4f6`, `#eaf3e8`, `#f0f1e4`, `#f5f0e3` for V1 to V7. Dark: `#222234`, `#1c2435`, `#152634`, `#0b292c`, `#1b2818`, `#252611`, `#2b230f`. `ink` keeps at least 13:1 on all of them (COMPUTED). No-entry uses `surface`. |
| Ground mark | A flat ellipse under the blob in `grid` colour, centred at y = 1.18 `R`, height 0.06 `R`. Width is 1.5, 1.4, 1.3, 1.15, 1.0 times `R` for E1 to E5. A lower-energy blob sits heavier on the ground; a higher-energy blob appears lifted. |
| Tags | Up to three tag words in `body` size under the label, on the personal card only. |

### 4.5 State sketches

The sketches are schematic and are not the normative geometry; the tables above are. They show how the two axes read.

```
 blob.v1.e1  Rough, drained            blob.v1.e5  Rough, wired
 slumped, wide base, eyes 35% open     pointed lobes, brows, eyes open
 indigo, muted  #3f3d64                indigo, saturated  #3f2e8d

        .--------.                         /\__      __/\
     .-'          '-.                     /    '-..-'    \
    /    _     _     \                   <   \ O    O /   >
   |       .--.       |                   \     ____     /
  /                    \                  /_            _\
 '-.________________.-'                     \/'-.__.-'\/
  ====================                          ======

 blob.v2.e3  Low, steady               blob.v4.e1  Okay, drained
 round, slight slump, shallow frown    near-circle, eyes 35% open, flat mouth
 slate blue  #3c5593                   grey-teal  #7d8c8e

       .-~~~~~~-.                             .------.
     .'          '.                         .'        '.
    /   o      o   \                       /   -    -   \
   |     .----.     |                     |              |
    \              /                       \    ----    /
     '.__________.'                         '.________.'
      ============                         ==============

 blob.v4.e3  Okay, steady              blob.v4.e5  Okay, wired
 the reference shape                   five clear lobes, tall, eyes wide
 grey-teal  #758f92                    grey-teal, stronger  #6d9195

       .-~~~~-.                              _.-^-._
     .'        '.                         .-'       '-._
    /   o    o   \                       (    O    O    )
   |              |                       )             (
    \    ----    /                       (     -----     )
     '.________.'                         '-._       _.-'
      ==========                              '-.^.-'
                                               =====

 blob.v6.e2  Good, slow                blob.v7.e1  Great, drained
 soft, low lobes, gentle smile         near-circle, eyes 35% open, wide smile
 straw  #bdc080                        pale gold, muted  #ead6a5

       .-~~~~~-.                              .------.
     .'         '.                          .'        '.
    /   o     o   \                        /   -    -   \
   |   '._____.'   |                      |  '.______.'  |
    \             /                        \            /
     '._________.'                          '.________.'
      ===========                          ==============

 blob.v7.e5  Great, wired              blob.none  No entry
 lively lobes, tall, broad smile       dotted outline, no face
 warm gold  #fed25d                    neutral  #d8dfe3 / #23363f

      _.-^-._   _                             . - - - .
    .'       '-' '.                         .           .
   (    O    O     )                       .             .
    )  '.______.'  (                       .             .
   (              _)                        .           .
    '._.-^-.__.-'                             ' - - - '
        =====                               ============
```

Reading rule for reviewers: move along a row of the state grid and only liveliness changes (lobes, height, eyes, saturation, ground width). Move along a column and only pleasantness changes (hue, lightness, mouth, slump, brows at the tense corner).

### 4.6 Static and animated forms

| Form | Canvas | Method | When | Text equivalent |
|---|---|---|---|---|
| Static mood card | 1080 by 1080 PNG, opaque tinted background, level name drawn under the blob | `sendPhoto` by cached `file_id` | Default reply to a check-in | Caption, for example "Mood logged: Good, steady. 14:05." |
| Label-free blob | Vector symbol reused inside other cards; 1080 by 1080 PNG per theme for reuse | n/a | Weekly strip, calendars, day blob | Carried by the host card |
| Mini App blob | Inline SVG built from the same scene | n/a | Mood picker (morphs between states as the picker moves), history | `aria-label` with the level names |
| Animated sticker | 512 by 512 TGS, transparent, 3 s loop | `sendSticker` by cached `file_id`, followed at once by a text message because stickers have no caption (P9) | Opt-in account setting, after spike S5 | The following text message |

Motion mapping for the animated form (limits VERIFIED, P10; playback on clients is ASSUMPTION A8):

| Property | Rule |
|---|---|
| Loop | 3 seconds. Every animated property completes a whole number of cycles. |
| Wobble | Harmonic phases advance by one full turn per loop, as outline keyframes at 8 key times (E1, E2), 10 (E3) or 12 (E4, E5). |
| Breathing | Uniform scale of plus or minus 2 percent (E1) to 5 percent (E5); 1 cycle per loop for E1 and E2, 2 for E3 and E4, 3 for E5. |
| Bounce | Vertical offset of 0, 0, 1, 2, 3 percent of `R` for E1 to E5, in phase with breathing. |
| Blink | One blink per loop, eyes closed for about 100 ms, at 1.9 s. |
| Valence | V1 to V3 use 70 percent of the amplitudes above and a slower ease. |
| Safety | No flashing, no change of fill lightness over time, no motion faster than 3 cycles per loop. |

### 4.7 The day blob: daily overall status

The day blob answers "how is today going" for the daily summary card (IMG-03) and the Mini App Today screen. It is the mood blob surrounded by a halo.

```
                  medication
                 .===========.
             .=='             '==.
           //     .-~~~~-.        \\      Halo: one arc segment per tracked
   sleep  ||    .'        '.       ||     domain, clockwise from 12 o'clock:
          ||   /   o    o   \      ||     medication, water, food, sleep.
           :  |              |     ::     Filled part   = progress today
           :   \   '.__.'   /      ::     Track (grid)  = remainder
          ..    '.________.'       ||     Dotted track  = nothing recorded yet
           ..                     //      Hatched part  = estimate (sleep T1)
             ..             .==''
               ' . . . .==='     water
                 food
```

Geometry on the 1080 by 1350 daily card: blob `R = 190 px`; halo radius 300 px; halo stroke 28 px with round caps; segments have equal angular spans separated by 8 degree gaps; a 56 px domain icon sits outside the start of each segment.

| Segment | Progress `p` | Drawn as |
|---|---|---|
| Medication | Scheduled doses of the local day with status taken (on time or late), divided by all scheduled doses of the day that are not cancelled or unknown. Skipped doses are a separate part. | Filled arc of length `p` in `spruce.base`; then a hollow arc (6 px outline in `ink2`) for the skipped share; then track. |
| Water | `min(1, effective volume / goal)` (section 7.4) | Filled arc in `tide` |
| Food | Meals logged today divided by the user's number of planned meals (default 3), capped at 1 | Filled arc in `rye` |
| Sleep | Last main sleep duration divided by the user's sleep goal, capped at 1. Tier T2 or T3 only. | Filled arc in `dusk`. With only a passive quiet window (T1) the segment is a hatched outline of full length with no fill, because a bound is not a duration. |
| Mood | Not a segment. Mood is the blob. | |

Rules that keep the day blob from shaming:

| ID | Requirement |
|---|---|
| VA-BL-01 | The blob's outline, colour, face and motion are functions of the mood state only. No other domain's data is an input to the blob renderer. Enforced by the type of the renderer's input. |
| VA-BL-02 | With no mood check-in today the centre is `blob.none`. The product never substitutes yesterday's mood, an average or a guess for today's blob. |
| VA-BL-03 | An unfilled halo part is the `grid` track or a dotted track. It has the same appearance at 09:00 and at 23:59. Nothing in the halo turns to `attention` or `critical`, and nothing is drawn for "missed" that differs from "not yet". |
| VA-BL-04 | Every blob that carries a mood state is accompanied by the written valence name (and the energy name when the user gave it), in the image or in the caption. |
| VA-BL-05 | A domain that the user does not track, or has paused for the day, has no segment. The remaining segments share the circle equally. With no tracked domain other than mood there is no halo. |
| VA-BL-06 | The text beside the halo states counts, for example "Medication 2 of 3 recorded as taken, 1 skipped", "Water 1.4 of 2.0 l". No summary word such as "good day" or "bad day" is generated. |
| VA-BL-07 | The fill of a segment never exceeds its span. A value above the goal is stated in the text only. |

### 4.8 Aggregate blobs

A weekly or monthly report may show one blob for the period. Its valence is the period mood average of section 7.6 and its energy is the mean of the daily mean energies, both quantised as in section 4.2. It is labelled "average of N check-in days" and appears only when N is at least 4 in a week or 10 in a month. Rejected alternative: showing the most frequent state, which hides variation and ties arbitrarily.

### 4.9 Reserved and rejected blob features

| Feature | Status | Reason |
|---|---|---|
| Stability shown as a second, offset outline | Reserved | Research brief 04 section 9.3 allows it. It would make the blob a personal asset. Revisit after the weekly report exists. |
| A blob that grows, levels up or unlocks accessories | Rejected | It turns a record into a reward and adds the dark-pattern risk of research brief 03 section 6.2. |
| A blob that looks unwell after missed doses or a low water day | Rejected | VA-BL-01. |
| User-chosen blob colours | Rejected for the first release | Colour is part of the valence encoding. |

### 4.10 Blob acceptance checks

| ID | Check |
|---|---|
| VA-BL-08 | SVG text golden for all 36 states in both themes. |
| VA-BL-09 | Property: for every state the sampled outline stays inside a circle of radius 1.25 `R`, never self-intersects, and the face features lie inside the outline with a margin of 0.05 `R`. |
| VA-BL-10 | Monotonicity: for fixed energy, mouth control offset and fill lightness are strictly increasing from V1 to V7; for fixed valence, amplitude, eye opening and chroma are non-decreasing from E1 to E5. |
| VA-BL-11 | The 36 static cards are global assets that are sent by `file_id` after their first use. Mechanism: lazy upload. The Bot API has no method that uploads a photo without sending it to a chat; the only upload-only method is `uploadStickerFile` (VERIFIED by absence in the method list at https://core.telegram.org/bots/api, checked 2026-11-12 by the review of this document set). A `file_id` therefore exists only after a send to a real chat. The first send of each state, theme and locale is a multipart upload to the chat of the user who triggered it, and the returned `file_id` is cached per bot and reused for every later send. The first use of each asset per bot pays the upload; nothing is uploaded at start-up, and the start-up check only verifies that the rendered bytes exist. Staging and production bots have separate caches. A template, font or renderer revision changes the content hash and starts a new cache row. A rejected `file_id` falls back to upload and replaces the cache row (P4). Rejected alternative: an operator-owned asset-sink chat to which every global asset is sent at start-up; it needs a configured chat that has started the bot, about 100 bulk sends per bot per revision, and it is not needed once reminder images are withdrawn (D13). |
| VA-BL-12 | Animated form: Lottie JSON golden; file size at most 64 KB; only constructs on the allowed list are emitted; the first and last frames are identical. |
| VA-BL-13 | Review: the contact sheet of 35 states is checked by an independent reviewer against the restraint rules of section 4.4 and for resemblance to any existing product's characters. |

## 5. Rules shared by all generated images

### 5.1 Platform facts this section depends on

| ID | Fact | Status | Source, checked 2026-11-12 |
|---|---|---|---|
| P1 | `sendPhoto`: at most 10 MB; width plus height at most 10000; aspect ratio at most 20. | VERIFIED | https://core.telegram.org/bots/api (Bot API 10.4), via research brief 04 section 3.1 |
| P2 | Photo and document captions are 0 to 1024 characters after entity parsing. | VERIFIED | Same |
| P3 | `has_spoiler` covers a photo until tapped. `protect_content` blocks forwarding and saving of the message. Neither prevents a screenshot. | VERIFIED for the parameters; the screenshot statement is ASSUMPTION | Same |
| P4 | A file on Telegram's servers is re-sent by `file_id` without upload; a `file_id` is bound to one bot and cannot change media type. Its lifetime is not documented. | VERIFIED; indefinite validity is ASSUMPTION | https://core.telegram.org/bots/api#sending-files |
| P5 | `editMessageMedia` replaces the media of an existing message and can turn a text message into a media message. | VERIFIED | https://core.telegram.org/bots/api |
| P6 | A bot can delete a message only if it was sent less than 48 hours ago. | VERIFIED | Same |
| P7 | A photo upload is re-encoded as JPEG and scaled so that the longest side is about 1280 px. | ASSUMPTION (A5) | Research brief 04 section 3.4 |
| P8 | `sendDocument` delivers original bytes, up to 50 MB, with a caption of up to 1024 characters. | VERIFIED | https://core.telegram.org/bots/api |
| P9 | `sendSticker` has no caption parameter. | VERIFIED | Same |
| P10 | Animated stickers: 512 by 512, at most 3 s, must loop, at most 64 KB, 60 frames per second, with a list of forbidden Lottie features. | VERIFIED | https://core.telegram.org/stickers |
| P11 | About 30 messages per second per bot in bulk, about 1 per second per chat; excess is answered with HTTP 429 and `retry_after`. | VERIFIED | https://core.telegram.org/bots/faq |
| P12 | `sendMediaGroup` sends 2 to 10 items; photos group with photos and videos. | VERIFIED | https://core.telegram.org/bots/api |
| P13 | Bot chats are cloud chats with server-client encryption; Telegram holds everything the bot sends, including images and captions. | VERIFIED | https://telegram.org/faq and https://telegram.org/privacy, via research brief 06 section 2 |
| P14 | The Bot API and the Mini App API expose no last-seen, online or presence data, and no user time zone. | VERIFIED by absence | https://core.telegram.org/bots/api, https://core.telegram.org/bots/webapps, https://telegram.org/faq |
| P15 | Interface elements of a bot or Mini App must not imitate Telegram, the operating system or the browser. | VERIFIED | https://telegram.org/tos/bot-developers section 5.3 |
| P16 | A Mini App receives `colorScheme`, `themeParams` (also as `--tg-theme-*` CSS variables) and the `themeChanged` event. | VERIFIED | https://core.telegram.org/bots/webapps |
| P17 | `Telegram.WebApp.downloadFile` lets a Mini App hand the user a file from the developer's server. | VERIFIED | Same |
| P18 | `savePreparedInlineMessage` plus `Telegram.WebApp.shareMessage` lets the user send a prepared message to a chat of their choice. Whether this needs inline mode enabled is unknown. | VERIFIED for the methods; ASSUMPTION (A9) for the inline-mode question | https://core.telegram.org/bots/api and /bots/webapps |
| P19 | Message widgets (Bot API 10.4): at most 4096 bytes after zlib compression, private chat with the bot only in production, no accessibility semantics, unsupported clients show the caption only. | VERIFIED | https://core.telegram.org/bots/widgets |
| P20 | `sendChatAction` with `upload_photo` shows a status for 5 seconds or until a message arrives. | VERIFIED | https://core.telegram.org/bots/api |
| P21 | User data may be used only with clear information and "individual, explicit, active and revocable consent"; collection beyond what is essential and any dataset or model building are prohibited. | VERIFIED | https://telegram.org/tos/bot-developers section 4.3 |
| P22 | Message text is 1 to 4096 characters after entity parsing. | VERIFIED | https://core.telegram.org/bots/api |
| P23 | A `date_time` message entity carries a Unix time and a format; that clients render it in the device's zone and locale is not stated. | VERIFIED for the entity; ASSUMPTION (A10) for local rendering | https://core.telegram.org/bots/api/formatting |
| P24 | The Mini App events `activated` and `deactivated` and the method `isVersionAtLeast` exist. | VERIFIED | https://core.telegram.org/bots/webapps |
| P25 | A bot receives no read receipt and no signal that a message was displayed. | VERIFIED by absence in the Bot API reference (research brief 02 section 15); research brief 05 section 3.1 treats it as an ASSUMPTION to confirm in a live chat | https://core.telegram.org/bots/api |

### 5.2 Pipeline

The pipeline of research brief 04 section 7 is adopted without change: snapshot, view model, layout to a scene tree, SVG text in "Dosegram SVG profile 1", rasterisation by a pinned resvg child process, publication through the outbox. This document adds three inputs that every view-model builder takes besides the snapshot:

| Input | Values | Effect |
|---|---|---|
| `privacyLevel` | L0 to L3 (section 5.4) | Which strings and rows may appear |
| `evidence` | Per value: `recorded`, `self_reported`, `estimated`, `imported(<source>)` | How a mark is drawn (solid, hatched) and how it is labelled |
| `canvas` | A canvas kind from section 5.3 | Layout constants |

### 5.3 Canvas kinds

| Kind | Pixels | Aspect | Used for |
|---|---|---|---|
| `portrait` | 1080 by 1350 | 4:5 | Daily, weekly, monthly, strip, sleep, calendars |
| `square` | 1080 by 1080 | 1:1 | Blob card, hydration vessel, insight, milestone, shareable card |
| `wide` | 1280 by 720 | 16:9 | Reminder card, timelines, refill card |
| `a4` | 595 by 842 pt | A4 portrait | PDF pages (Typst) |
| `miniapp` | 360 units wide, height by content | free | Mini App charts (section 9); one unit is one CSS pixel at 360 px width and the SVG scales by `viewBox` |

All chat images are PNG with an opaque background and a long side of at most 1280 px. Expected size is 50 to 300 KB and the limit is 2 MB (research brief 04 section 8.2).

### 5.4 Privacy levels and discreet mode

| Level | Name | May contain | May not contain |
|---|---|---|---|
| L0 | Public-safe | Nothing personal: blob states, milestone numbers without context, onboarding and empty-state art | Any value derived from a user's records |
| L1 | Aggregate | The user's counts, durations, percentages, times of day, mood states, dates | Medication names, user-entered labels, tags, notes, free text, photos |
| L2 | Named | L1 plus medication names (or the user's neutral labels), dose amounts as entered, tag words | Notes, free text, photos, account identifiers |
| L3 | Full record | L2 plus instructions as entered and, when the user ticks them, notes | Account identifiers, Telegram name or username |

| ID | Requirement |
|---|---|
| VA-PV-01 | Every template declares its maximum privacy level. The view-model builder receives the effective level `min(template maximum, account setting, discreet rule)` and physically omits fields above it. A property test with canary strings in names, labels, tags and notes asserts that no canary appears in the SVG, the caption, the alt text or the file name of an L1 render. |
| VA-PV-02 | The account setting `names_in_images` is off until the user turns it on once, on a screen that states that images stay in the Telegram chat and on Telegram's servers (P13) and can be deleted by the bot only for 48 hours (P6). With the setting off, chat images are L1 at most. |
| VA-PV-03 | Discreet mode forces L1 for every chat image, replaces row labels by the user's neutral label or by "Item 1", "Item 2" in schedule order (the discreet-label scheme of BOT_UX section 22.4), makes the caption generic, and sends personal images with `has_spoiler`. It applies to images, captions, alt text and file names alike. |
| VA-PV-04 | Personal chat images are sent with `protect_content` by default; an account setting turns it off. The shareable card (IMG-17) is the exception and is always sent without it. |
| VA-PV-05 | File names are neutral and constant per template, for example `dosegram-week.png` and `records-summary.pdf`. They contain no date of birth, name, medication name or account identifier. |
| VA-PV-06 | The first time a personal image would be sent into the chat, the bot asks for the separate consent "Images in chat" (research brief 06 section 14.3, item 8). Until it is given, every image request is answered with the text equivalent and the Mini App link. Withdrawal stops image delivery and offers deletion of images younger than 48 hours. |
| VA-PV-07 | Rendered bytes are purged within one hour of the terminal delivery state; `file_id` rows for personal assets are deleted after 48 hours (research brief 04 section 7.6). |
| VA-PV-08 | PNG output carries no text chunks and no timestamps (ASSUMPTION A11, asserted by a test on the rasteriser output). |
| VA-PV-09 | Logs record the template identifier, asset key, sizes, timings and outcome. They never record view-model content, SVG text or rasteriser error output. Covered by the canary log-hygiene gate of research brief 06 section 11.1. |

### 5.5 Captions and text equivalents

| ID | Requirement |
|---|---|
| VA-TX-01 | Every image is sent with a text equivalent produced from the same view model. If it fits in 1024 characters (P2) it is the caption. Otherwise the caption is a one-line summary ending with the catalogue string "Details below." and the full text follows as the next message. |
| VA-TX-02 | The text equivalent states the period, each headline value with its unit and denominator, the exclusions, and the evidence type of estimated or imported values. It does not describe the picture ("a bar chart shows"). |
| VA-TX-03 | Every image message carries a "Text view" inline button beside any image controls (inherited rule, BOT_UX_PLAN section 6). Exception: an album sent with `sendMediaGroup` cannot carry an inline keyboard, because that method has no `reply_markup` parameter (VERIFIED, https://core.telegram.org/bots/api#sendmediagroup, checked 2026-11-12 by the review of this document set). For an album the controls are on the follow-up text message (section 6.6). |
| VA-TX-04 | Times in captions are written as plain localised text in the account zone. Use of `date_time` entities is deferred until assumption A10 is confirmed. |
| VA-TX-05 | The alt text stored with the asset is the first 1024 characters of the text equivalent at the same privacy level. |

### 5.6 Localisation

| ID | Requirement |
|---|---|
| VA-L10N-01 | Templates contain no words. Numbers, dates, units, plural forms, 12 or 24 hour time and the first day of the week are resolved in the view model from the account locale and settings. |
| VA-L10N-02 | Layouts accept strings 40 percent longer than English using the fitting order of VA-TY-04. The fixture matrix includes English, Ukrainian and one right-to-left pseudo-locale for every template. |
| VA-L10N-03 | In right-to-left locales the block layout mirrors: start-aligned text, the cut corner of the late glyph, icon positions, the halo's first segment still starts at 12 o'clock and still runs clockwise. The direction of time axes is the catalogue property `chart_time_direction`, which is left-to-right for every locale until a native-speaker review decides otherwise (open question Q9). |
| VA-L10N-04 | Units: water in millilitres and litres or US fluid ounces; energy in kilocalories or kilojoules; weight in kilograms or pounds. The setting is per account, and the caption states the unit. |
| VA-L10N-05 | Percentages are written with the locale's percent pattern. Decimal separators follow the locale. Durations are written with localised hour and minute abbreviations, for example "7 h 30 min". |
| VA-L10N-06 | User-entered text is sanitised before layout: control characters and bidirectional override and isolate controls are removed (research brief 04 section 7.4). |

### 5.7 Failure, fallback and load

| ID | Requirement |
|---|---|
| VA-FB-01 | A render that fails, times out after 5 seconds, or finds the queue full is answered with the text equivalent at once. The failure is counted in a metric with the template identifier as its only label. |
| VA-FB-02 | Rendering starts after the interaction is acknowledged and outside any database transaction. `sendChatAction(upload_photo)` (P20) is sent when a render is expected to exceed 1 second. |
| VA-FB-03 | On-demand images are limited to one concurrent render per account and 20 per account per day (research brief 06 section 12.3); beyond that the text equivalent is sent with the time the limit resets. |
| VA-FB-04 | Outbound priority, budget and jitter follow the single notification policy of PRODUCT_SPEC section 3.2. In its terms: an image sent in answer to a user action is an Outcome, and a scheduled image of section 10 is a Report, which is spread by the deterministic per-account offset of 0 to 600 seconds and never pre-empts an Outcome or a Reminder (platform fact P11). No image is ever part of a Reminder (D13). |
| VA-FB-05 | A switch between two views that were already sent as single photo messages (for example the 7-day and 30-day adherence views) uses `editMessageMedia` with the cached `file_id` (P5) and does not render again within the 48-hour cache window. The monthly report is excluded: it is an album, and an album has no single message to edit (section 6.6). |

## 6. Catalogue of generated images

Conventions for every entry:

- "Template" is the stable identifier used in cache keys, metrics and tests.
- "Trigger" is one or more of: on demand (a command, a button or a Mini App action), scheduled (section 10), event (a state change such as a threshold crossing), milestone.
- "Class" is global (rendered once, cached by `file_id` for all users) or personal.
- "Privacy" gives the maximum level of section 5.4 and what discreet mode changes.
- Layout sketches show block order and the dominant element, not exact proportions. Legends, the fact line and the footer of section 3.5 are present on every personal card even where a sketch abbreviates them.
- Example captions are illustrative English with synthetic values. They are never fallback data.

### 6.1 Overview

| ID | Image | Template | Canvas | Class | Max privacy | Trigger |
|---|---|---|---|---|---|---|
| IMG-01 | Reminder card (withdrawn, section 6.2) | none | none | none | none | never sent |
| IMG-02 | Mood card | `card.mood.v1` | square | global | L0 | on demand (check-in reply) |
| IMG-03 | Daily summary | `card.day.v1` | portrait | personal | L2 | on demand, scheduled |
| IMG-04 | Weekly report | `card.week.v1` | portrait | personal | L1 | scheduled, on demand |
| IMG-05 | Monthly report | `card.month.v1` (1 to 3 pages) | portrait | personal | L1 | scheduled, on demand |
| IMG-06 | Adherence calendar | `card.med.calendar.v1` | portrait | personal | L2 | on demand, inside IMG-05 |
| IMG-07 | Per-medication strip | `card.med.strip.v1` | portrait | personal | L2 | on demand |
| IMG-08 | Hydration vessel | `card.water.v1` | square | personal | L1 | on demand, event (goal reached, opt-in) |
| IMG-09 | Food day plate and macro bar | `card.food.day.v1` | square | personal | L1 | on demand |
| IMG-10 | Mood timeline | `card.mood.timeline.v1` | wide | personal | L1 | on demand |
| IMG-11 | Mood calendar | `card.mood.calendar.v1` | portrait | personal | L1 | on demand, inside IMG-05 |
| IMG-12 | Sleep regularity | `card.sleep.regularity.v1` | portrait | personal | L1 | on demand, inside IMG-04 and IMG-05 as a reduced panel |
| IMG-13 | Insight card | `card.insight.v1` | square | personal | L1 | scheduled inside IMG-04, on demand |
| IMG-14 | Milestone card (Later, opt-in; not sent by default) | `card.milestone.v1` | square | global | L0 | on demand only |
| IMG-15 | Refill card | `card.refill.v1` | wide | personal | L2 | event (threshold) |
| IMG-16 | Measurement trend | `card.body.trend.v1` | wide | personal | L1 | on demand |
| IMG-17 | Shareable card | `card.share.v1` | square | personal | L1 with a whitelist | on demand only |
| IMG-18 | Gate and empty-state art | `card.gate.v1` | square | global | L0 | on demand |
| DOC-01 | Record summary (PDF) | `doc.summary.v1` | a4 | personal | L3 | on demand only |

### 6.2 IMG-01 Reminder card (withdrawn)

IMG-01 is withdrawn. The identifier is kept so that it is not reused. A medication reminder is always a text message and never carries an image (D13; BOT_UX D5 and BUX-REM-05; ARCHITECTURE TG-30; PRODUCT_SPEC section 3.1; ROADMAP conflict X-27 and section 9.2).

The first draft of this document specified two image forms: R1, a generic pre-rendered card sent by `file_id` with the reminder as its caption, and R2, a personal card rendered ahead of the due time. They were withdrawn for four reasons.

| Reason | Detail |
|---|---|
| The reminder must be readable in a notification preview | A photo message shows its caption after a thumbnail, and on a locked screen often only "Photo". |
| The reminder state machine cannot follow a photo message | BOT_UX section 9.5 expresses every transition as `editMessageText`. A photo message can only be changed by `editMessageCaption` and `editMessageMedia`, and a caption holds 1024 characters where the slot card is sized for 4096 (P2). Every row of the table would need a media variant. |
| Global assets cannot exist at start-up | A `file_id` exists only after a send to a real chat (VA-BL-11), so "no upload at send time" could not be met for the first reminder of each asset. |
| Three sibling documents forbid it | BOT_UX D5, ARCHITECTURE TG-30 and PRODUCT_SPEC section 3.1 each state that no image is attached to a reminder. |

Re-entry needs an owner decision and all of: a media variant of every row of BOT_UX section 9.5, a 1024-character slot layout, the asset-upload mechanism of VA-BL-11 extended to reminder assets, and amendments to BOT_UX D5, ARCHITECTURE TG-30 and PRODUCT_SPEC section 3.1.

| ID | Requirement |
|---|---|
| VA-IMG-01a | No reminder send waits on a render, because no reminder contains an image. A test with the render queue stopped shows reminders delivered on time. |
| VA-IMG-01b | Withdrawn with IMG-01. |
| VA-IMG-01c | A reminder is one text message. The text and the keyboard are never split across messages. |

### 6.3 IMG-02 Mood card

| Field | Specification |
|---|---|
| Purpose | Confirm a mood check-in with the blob for the chosen state. |
| Trigger | Reply to a mood check-in, after the entry is stored. Also the reply when the user opens today's mood. |
| Data inputs | Valence level, energy level, theme, locale. |
| Dimensions | square, 1080 by 1080 |
| Privacy | L0 asset. The caption carries the personal part (time, tags at L2). In discreet mode the caption is "Mood logged." |
| Localisation | The level name is drawn into the image, so one asset exists per locale. Level names are short (at most 14 characters recommended to translators). |

```
+------------------------------+
|   (tinted background)        |
|                              |
|          .-~~~~-.            |
|        .'        '.          |
|       /   o    o   \         |
|      |   '.____.'   |        |
|       \            /         |
|        '.________.'          |
|         ==========           |
|                              |
|           Good               |
|          steady              |
+------------------------------+
```

Example caption: "Mood logged at 14:05: Good, steady. Tags: work, outdoors."

### 6.4 IMG-03 Daily summary card

| Field | Specification |
|---|---|
| Purpose | One picture of the local day: the day blob and one line per tracked domain. It is the image form of the "Today" message. |
| Trigger | On demand ("Today picture" button under the Today card). Sent with the evening wrap-up when the user opted in (section 10). The Today text message is edited in place immediately on every log; its picture is refreshed only on demand or by the schedule, never on every log. |
| Data inputs | Local date and zone; per domain the values of section 4.7; the list of today's dose occurrences with status; mood state; evidence tier of the sleep value. |
| Dimensions | portrait, 1080 by 1350 |
| Privacy | L2 lists medication rows by name (at most 6, then "and N more"). L1 and discreet mode show one medication line with counts only. |
| Localisation | Date format; unit of water; plural forms for meals and items. |

```
+------------------------------------+
| Today                Thu 12 Nov    |
|                                    |
|          .==== halo ====.          |
|        //   .-~~~~-.     \\        |
|       ||   /  o  o  \     ||       |
|        \\  \  '__'  /    //        |
|          '====.____.===='          |
|            Good, steady            |
|                                    |
| (cell) Medication  [#][#][/][ ]    |
|        2 taken, 1 skipped, 1 open  |
| (drop) Water       1.4 of 2.0 l    |
| (plate) Food       2 of 3 meals    |
| (moon) Sleep       7 h 10 min      |
|        from your check-ins         |
|                                    |
| Recorded entries only.             |
| Not medical advice.                |
+------------------------------------+
```

Example caption (L1): "Thu 12 Nov. Mood: Good, steady. Medication: 2 of 4 recorded as taken, 1 skipped, 1 still open. Water: 1.4 of 2.0 l. Food: 2 of 3 meals logged. Sleep: 7 h 10 min, from your check-ins."

| ID | Requirement |
|---|---|
| VA-IMG-03a | The daily summary image is not attached to the evening wrap-up on a day with no record in any domain. |
| VA-IMG-03b | An open dose is drawn with the dotted glyph and worded "still open", never "missed", until the domain marks it missed. |

### 6.5 IMG-04 Weekly report

| Field | Specification |
|---|---|
| Purpose | The main recap: seven days in small multiples, one row per tracked domain, each with one headline value and a seven-day strip. |
| Trigger | Scheduled on the user's report weekday (section 10); on demand for any completed week and for the current week to date. |
| Data inputs | Week range in the account zone; per day and domain: medication counts by bucket; water volume and goal; meals logged; daily mood mean state; main sleep duration, goal and tier. Own baseline: the mean of the previous four weeks where each has enough data (section 7.1). At most one insight (section 8). |
| Dimensions | portrait, 1080 by 1350 |
| Privacy | L1. Medication is pooled over all medications. Discreet mode adds the spoiler only. |
| Localisation | Week start day; weekday initials (one or two letters from the locale); units. |

```
+------------------------------------+
| Your week           2 to 8 Nov     |
|            M  T  W  T  F  S  S     |
| (cell) Medication                  |
|  26 of 28 taken   [cells per day,  |
|  93%               stacked by dose]|
| (drop) Water                       |
|  goal on 5 of 7   | | | | | | |    |
|  days recorded    bars, goal line  |
| (plate) Food                       |
|  19 meals logged  . : : . : : .    |
| (blob) Mood                        |
|  6 check-in days  o o o   o o o    |
|                   (mini blobs, gap)|
| (moon) Sleep                       |
|  7 h 05 min avg   = == = == = ==   |
|  bedtime +-35 min (bars, goal line)|
| ---------------------------------- |
| Pattern: see caption               |
| Recorded entries only. Not medical |
| advice.                            |
+------------------------------------+
```

Row details:

| Row | Strip | Headline | Baseline note |
|---|---|---|---|
| Medication | One column per day; each scheduled dose is one cell glyph, stacked, at most 4 per day, more than 4 shown as a proportional column split into the status shares | "N of M taken" and the adherence percentage when M is at least 5 | "Previous four weeks: 90%" when available |
| Water | One bar per day against a dotted goal line; a day without a log has no bar and a dotted slot | Days on which the goal was reached, out of days with a log | Mean daily volume of the previous four weeks |
| Food | Up to three dots per day, one per logged main meal, plus a small dot for snacks | Meals logged | none |
| Mood | One mini blob per day with a check-in; an empty slot otherwise | Number of check-in days and the average blob when N is at least 4 | none |
| Sleep | One horizontal duration bar per night against the goal line; hatched outline for nights with only a quiet window | Mean duration of T2 and T3 nights; bedtime consistency when 5 of 7 nights exist | Previous four weeks' mean |

Example caption: "Week 2 to 8 Nov. Medication: 26 of 28 scheduled doses recorded as taken (93%), 1 skipped, 1 not confirmed. Water: goal reached on 5 of 7 recorded days, 1.9 l a day on average. Food: 19 meals logged. Mood: 6 check-in days, average 3.8 (pleasant). Sleep: 7 h 05 min on average over 6 nights from your check-ins; bedtime varied by about 35 min. One night is an estimate from bot activity and is not included in the average."

| ID | Requirement |
|---|---|
| VA-IMG-04a | A domain with no record in the week is listed as one line "No records this week" without a strip. A domain that is not tracked is absent. |
| VA-IMG-04b | The weekly report is not sent when no domain has a record in the week (section 10). |
| VA-IMG-04c | Each row's strip uses its own scale. No two rows share an axis. |

### 6.6 IMG-05 Monthly report

| Field | Specification |
|---|---|
| Purpose | A reflective recap of a calendar month with a minimum-data gate. |
| Trigger | Scheduled on the first day of the next month when the gate of PRODUCT_SPEC R-IR-11 is met: at least one enabled domain has a record on at least 14 days of the month. A domain's panel appears only when that domain meets the same threshold; otherwise the panel shows the gate. On demand for any past month. |
| Form | An album of 1 to 3 photos through `sendMediaGroup` (P12), followed by the text equivalent as a message. `sendMediaGroup` has no `reply_markup` parameter (VA-TX-03), so the inline keyboard ("Text view", "Stop these", "Open in app") is on that follow-up text message, and "Stop these" confirms by editing the text message. The send cost is the number of pages plus one message, against the per-chat and global send limits; it is one unit of the daily budget (PRODUCT_SPEC section 3.2). Rejected alternative: one tall single-photo page that could carry the keyboard itself; a 1080 by 4000 image is unreadable after the client's downscaling (P7). Page 1 overview; page 2 the adherence calendar (IMG-06) and the mood calendar (IMG-11) as two half-height panels; page 3 the sleep regularity panel and the water and food month bars. Pages 2 and 3 are omitted when their domains have no data. With one page it is a single `sendPhoto`. |
| Data inputs | As IMG-04, aggregated per week of the month and per day for the calendars; previous month's values as the baseline. |
| Dimensions | portrait, 1080 by 1350 per page |
| Privacy | L1 on all pages. Per-medication detail is available only through IMG-07 and DOC-01. |
| Localisation | Month names in the nominative form used for headings; week numbering follows the locale; calendar week start. |

```
 Page 1                                Page 2
+----------------------------------+  +----------------------------------+
| October 2026                     |  | Medication by day                |
|                                  |  |  M  T  W  T  F  S  S             |
|  94%   of scheduled doses        |  | [ ][#][#][#][#][/][#]            |
|        recorded as taken         |  | [#][#][#][#][#][#][#]  ...       |
|        (112 of 119)              |  |  legend: all, most, some, none   |
|                                  |  |----------------------------------|
| Week 1  2  3  4  5               |  | Mood by day                      |
| (cell)  #  #  #  #  #  bars      |  |  o  o  .  o  o  o  o             |
| (drop)  |  |  |  |  |  bars      |  |  o  .  o  o  o  o  .  ...        |
| (blob)  o  o  o  o  o  blobs     |  |  22 check-in days                |
| (moon)  =  =  =  =  =  bars      |  +----------------------------------+
|                                  |
| Compared with September: ...     |
+----------------------------------+
```

The hero number on page 1 is the adherence percentage only when medication is tracked and the denominator is at least 5; otherwise the hero is the number of days with any record ("27 days with records").

Example caption on the first photo: "October 2026. Details below." Text equivalent message: the same content as IMG-04 for the month, one paragraph per domain.

### 6.7 IMG-06 Adherence calendar heatmap

| Field | Specification |
|---|---|
| Purpose | Show which days of a month had all, most, some or none of the scheduled doses recorded as taken. |
| Trigger | On demand, for all medications pooled or for one medication; embedded in IMG-05. |
| Data inputs | For each local day of the month: the day's adherence fraction over its resolved doses (section 7.2), counts of open and unknown doses, whether any dose was scheduled. |
| Dimensions | portrait, 1080 by 1350 |
| Privacy | L1 when pooled. L2 when filtered to one medication, with the name in the title; in discreet mode the title is the neutral label. |
| Localisation | Week start; weekday header; month title. |

```
+------------------------------------+
| Medication by day     October 2026 |
|   Mo   Tu   We   Th   Fr   Sa   Su |
|                  [#]  [#]  [+]  [#]|
|                   1    2    3    4 |
|  [#]  [#]  [-]  [#]  [#]  [#]  [o] |
|   5    6    7    8    9   10   11  |
|  [#]  [/]  [#]  [#]       [#]  [#] |
|  12   13   14   15   16   17   18  |
|  ...                               |
| [#] all taken  [+] 75 to 99%       |
| [-] 50 to 74%  [.] under 50%       |
| [o] none taken [/] no record       |
| blank: nothing scheduled           |
| 112 of 119 resolved doses taken.   |
| 2 doses: no record, not counted.   |
+------------------------------------+
```

| Day class | Condition | Cell |
|---|---|---|
| All taken | Fraction equals 1 | `spruce.heat.4` solid |
| Most | 0.75 up to but not including 1 | `spruce.heat.3` solid |
| Some | 0.50 up to but not including 0.75 | `spruce.heat.2` solid |
| Few | Above 0 and under 0.50 | `spruce.heat.1` solid |
| None taken | Fraction equals 0 with at least one resolved dose | Hollow cell (the skipped glyph) when every resolved dose was skipped; empty cell with the dash when at least one was missed |
| Not known | Only unknown doses | Hatched cell |
| Open | The day is today or later and doses are open | Dotted outline |
| Nothing scheduled | No dose | Blank slot |

Cells are 96 px squares in 128 px slots; the day number sits under the cell in `label` size and `ink3`, so no text lies on a coloured fill (VA-CO-03). Today has a 4 px `ink` outline around the slot.

Example caption: "October 2026, all medications. All scheduled doses recorded as taken on 25 days, most on 3 days, none on 1 day, no record on 1 day. Nothing scheduled on 1 day. 2 doses have no record and are not counted. 112 of 119 resolved doses taken (94%)."

### 6.8 IMG-07 Per-medication adherence strip

| Field | Specification |
|---|---|
| Purpose | Compare medications over the same 7 or 14 days, one row per medication. |
| Trigger | On demand ("By medication"). |
| Data inputs | For each active medication and each local day: the ordered list of that day's occurrences with status. |
| Dimensions | portrait, 1080 by 1350. At most 6 rows per image; more medications paginate (page 1 of N in the title), ordered by the user's registry order. |
| Privacy | L2 with names. L1 and discreet mode: rows are labelled with neutral labels or "Item 1" onward, in registry order, and the caption omits names. |
| Localisation | Names use the condensed width before wrapping to two lines; weekday initials. |

```
+------------------------------------+
| By medication       27 Oct - 9 Nov |
|              T W T F S S M T W ... |
| Metformin    # # # # # # # # / #   |
| 500 mg       # # # # # o # # # #   |
|              27 of 28 taken        |
| Vitamin D    #   #   #   #   #     |
|              7 of 7 taken          |
| Item C (as   ( )   ( )             |
| needed)      3 logs on 3 days      |
| legend ...                         |
+------------------------------------+
```

A day with several doses of one medication stacks up to 4 glyphs vertically in the day column; above 4 the column shows a proportional split. As-needed medications show circles on the days with a log and no adherence figure.

Example caption (L2): "27 Oct to 9 Nov. Metformin 500 mg: 27 of 28 scheduled doses recorded as taken, 1 skipped. Vitamin D: 7 of 7. Ibuprofen (as needed): 3 logs on 3 days."

### 6.9 IMG-08 Hydration vessel

| Field | Specification |
|---|---|
| Purpose | Progress toward today's water goal as one shape and one number. |
| Trigger | On demand (after a quick-add when the user enabled picture replies, or the "Water picture" button). Optional event: once on the day the goal is first reached, if the user opted in. Never sent more than once per 30 minutes; quick-add replies otherwise edit the existing message's caption. |
| Data inputs | Effective volume today, goal, unit, number of entries, time of the last entry, hydration-factor setting. |
| Dimensions | square, 1080 by 1080 |
| Privacy | L1. Nothing changes in discreet mode except the spoiler. |
| Localisation | Unit and decimal separator; "of" phrase order differs by language, so the fraction is one catalogue pattern. |

```
+------------------------------+
| Water              Thu 12 Nov|
|                              |
|        .---------.   - 2.0 l |
|        |         |           |
|        |         |   - 1.5   |
|        |~~~~~~~~~|   <- 1.4 l|
|        |#########|   - 1.0   |
|        |#########|           |
|        |#########|   - 0.5   |
|        '---------'           |
|   1.4 of 2.0 l      70%      |
|   5 entries, last at 15:20   |
+------------------------------+
```

The vessel is a rounded rectangle 360 by 640 px with a 96 px corner radius and a 6 px `ink2` outline. The fill rises from the bottom in `tide`; the level is a straight line; ticks mark each quarter of the goal. Above the goal the vessel is full and the surplus appears as text only ("2.4 of 2.0 l"); nothing overflows, sparkles or celebrates. The percentage is floored and is 100 only when the goal is reached.

Example caption: "Water today: 1.4 of 2.0 l (70%). 5 entries, last at 15:20. The goal is the one you set."

| ID | Requirement |
|---|---|
| VA-IMG-08a | The card never suggests an amount to drink and never states that the user should drink more or less. The goal is always described as the user's own. |
| VA-IMG-08b | With the hydration factor setting on, the caption states "counted with your drink factors". |

### 6.10 IMG-09 Food day plate and macro bar

| Field | Specification |
|---|---|
| Purpose | Show which meals of the day were logged and, when the user entered numbers, the day's energy and macro split. |
| Trigger | On demand. |
| Data inputs | Meal entries of the local day (type, time, whether a photo exists, optional energy and macros); the user's planned meals; unit setting. User photos are never drawn into the image. |
| Dimensions | square, 1080 by 1080 |
| Privacy | L1. Meal descriptions and photos never appear. |
| Localisation | Meal-type names; energy unit; gram abbreviation. |

```
+------------------------------+
| Food               Thu 12 Nov|
|            .-------.         |
|         .-'  B | L  '-.      |   Plate: a circle in four slots.
|        |  08:10|13:05  |     |   B breakfast, L lunch, D dinner,
|        |-------+-------|     |   S snacks. A logged slot is filled
|        |   D   |  S x2 |     |   (rye) with its time beside it;
|         '-.    |    .-'      |   an unlogged slot is a dotted outline.
|            '-------'         |
|  3 of 3 meals, 2 snacks      |
|  [ protein | carbs  | fat  ] |
|     22%      51%      27%    |
|  1,840 kcal from 3 of 5      |
|  entries with numbers        |
+------------------------------+
```

The macro bar is a tall segmented bar (three segments with 4 px surface gaps) in three steps of the Rye ramp: light theme `#dfa367`, `#b56d07`, `#774502`; dark theme `#774502`, `#b56d07`, `#fabc80`. The segment names and shares are written under the bar, because the three steps are one hue and identity is carried by the labels and the fixed order (protein, carbohydrate, fat). The bar is drawn only when at least one entry of the day has numbers, and the coverage ("3 of 5 entries with numbers") is always printed with it. Without numbers the bar is replaced by the line "No numbers entered today".

Example caption: "Food today: breakfast 08:10, lunch 13:05, dinner 19:40, 2 snacks. From the 3 entries with numbers: 1,840 kcal; protein 22%, carbohydrate 51%, fat 27% of that energy. Numbers are your entries or estimates, not measurements."

| ID | Requirement |
|---|---|
| VA-IMG-09a | No colour or wording rates a meal, a day or a number as good or bad. There is no target line unless the user set a personal reference value, and it is then drawn as a dotted line labelled "your reference". |
| VA-IMG-09b | An estimated number (a value the user marked as approximate, or, if the deferred packaged-food lookup is ever adopted, a looked-up value; no model produces nutrition values in any wave, ROADMAP conflict X-10) is labelled "estimate" in the caption and its bar segment is hatched. |

### 6.11 IMG-10 Mood timeline

| Field | Specification |
|---|---|
| Purpose | Show how daily mood moved over 14 or 30 days. |
| Trigger | On demand. |
| Data inputs | Per local day: the daily mean valence, the lowest and highest check-in of the day, the number of check-ins, the daily mean energy when given. |
| Dimensions | wide, 1280 by 720 |
| Privacy | L1. |
| Localisation | The y axis is labelled with three level names (V1, V4, V7) at the start edge; date ticks at week starts. |

```
+----------------------------------------------------------+
| Mood                                    27 Oct - 9 Nov   |
| Great  .................................................. |
|                 o--o        o                             |
|            o--o/    \      / \--o--o                      |
| Okay  ....o..........\o...o.............................. |
|          /                               (gap)   o        |
|      o--o                                                 |
| Rough ................................................... |
|        27  28  29  30  31   1   2   3   4   5   6   7   8 |
| energy  .   :   :   |   :   .   .       :   |   :   :   . |
| 12 check-in days of 14. Lines join consecutive days only. |
+----------------------------------------------------------+
```

The upper panel plots the daily mean as a dot per day in `beet`, joined by a 6 px line between consecutive days only. A day with more than one check-in has a 4 px vertical whisker from its lowest to its highest check-in. The lower panel is a separate strip with its own scale showing daily mean energy as bar heights in five steps. The two panels do not share a y axis (VA-CO-06).

Example caption: "Mood, 27 Oct to 9 Nov. 12 check-in days of 14. Daily mood ranged from Low to Great, most often Good. Day-to-day change averaged 0.8 levels. Two days have no check-in and are shown as gaps."

### 6.12 IMG-11 Mood calendar

| Field | Specification |
|---|---|
| Purpose | A month, or a year in a reduced form, of daily mood at a glance. |
| Trigger | On demand; embedded in IMG-05. |
| Data inputs | Per local day: the quantised daily mean valence and energy. |
| Dimensions | portrait, 1080 by 1350. The year form uses 12 columns of up to 31 dots of 24 px (dots, not blobs, because of the size) with month initials. |
| Privacy | L1. |
| Localisation | Week start; month and weekday names. |

```
+------------------------------------+
| Mood by day           October 2026 |
|   Mo   Tu   We   Th   Fr   Sa   Su |
|                  (o)  (o)  (o)  (o)|
|                   1    2    3    4 |
|  (o)  (o)   .   (o)  (o)  (o)  (o) |
|   5    6    7    8    9   10   11  |
|  ...                               |
| Rough (o)(o)(o)(o)(o)(o)(o) Great  |
|  .  no check-in                    |
| 22 check-in days.                  |
+------------------------------------+
```

Each day with a check-in is a mini blob 96 px wide in its state colour with its mouth curve, on the `panel`. A day without a check-in is a 12 px `grid` dot. The legend shows the seven valence states in order with the two end names.

Example caption: "October 2026: 22 check-in days. Most frequent: Good (9 days). Lowest: Low (2 days). Highest: Great (3 days). 9 days have no check-in."

### 6.13 IMG-12 Sleep regularity chart

| Field | Specification |
|---|---|
| Purpose | Show when the user slept on each of the last 7 or 14 nights and how regular bedtime and wake time were. |
| Trigger | On demand; a reduced panel inside IMG-04 and IMG-05. |
| Data inputs | Per sleep day (the local date on which the main sleep ends): onset and wake instants, their local clock times, tier (T1 passive, T2 explicit or confirmed, T3 sensor), confidence band, interruptions, naps, the `tz_shift` flag, the goal. Period metrics from section 7.7. |
| Dimensions | portrait, 1080 by 1350 |
| Privacy | L1. |
| Localisation | 12 or 24 hour clock labels; weekday names; the tier phrases. |

```
+------------------------------------+
| Sleep               27 Oct - 9 Nov |
|        18  21  00  03  06  09  12  |
| Tue 27      [==========]           |
| Wed 28        [=========]          |
| Thu 29     [===========]           |
| Fri 30          [////////////]  at |
|                  most 8 h 20 min   |
| Sat 31            [=========]      |
| Sun 1               [========]  n  |
| Mon 2       [==========]           |
|  ...        :          :           |
|         median    median           |
|         bedtime   wake             |
| 7 h 05 min average (11 nights)     |
| Bedtime +-35 min, wake +-22 min    |
| [=] your check-ins [/] estimate    |
| from bot activity, not a duration  |
+------------------------------------+
```

| Element | Rule |
|---|---|
| Axis | A clock axis from 18:00 to 18:00, the sleep-day frame of research brief 05 section 7.3. For users with the irregular-schedule flag the axis is 00:00 to 24:00 and all episodes of each 24 hours are drawn. |
| T2 bar | Solid `dusk`, 44 px high, cell-radius ends. |
| T3 bar | Solid `dusk` with a 4 px `ink2` outline, and the source named in the legend exactly as the vendor names it, for example "from your Oura ring". Stages, when imported, are not drawn on this card. |
| T1 quiet window | Hatched outline in `dusk`, no fill, with the words "at most" and the bound next to it. It is never included in averages or regularity (section 7.7). Shown only when its confidence is at least 0.25. |
| Interruption | A 12 px surface-coloured gap in the bar at the time of an awake burst. A reminder acknowledged at night is stated in the caption as a neutral fact. |
| Nap | A separate short bar, 28 px high, same row, marked "n". |
| Medians | Two dotted vertical lines at the circular median bedtime and wake time, over nights with T2 or T3 data. |
| `tz_shift` nights | Drawn, with a small clock icon; excluded from regularity for that night and the next. |
| Daylight saving nights | Clock times are drawn as local times; the caption gives the true duration and notes the transition. |

Example caption: "Sleep, 27 Oct to 9 Nov. 11 nights from your check-ins: 7 h 05 min on average; bedtime varied by about 35 min and wake time by about 22 min. 1 night is an estimate from bot activity: at most 8 h 20 min, not counted in the average. 2 nights have no record. Your goal is 7 h 30 min."

| ID | Requirement |
|---|---|
| VA-IMG-12a | The renderer takes tier and confidence as inputs for every episode, and a T1 episode can never be drawn with the solid bar style. Type-level and golden test. |
| VA-IMG-12b | No sleep score, stage chart, efficiency, latency or awakening count is drawn from T1 or T2 data. Sensor-only values appear only with attribution and never blended with self-reported values. |
| VA-IMG-12c | The caption states the source of every figure ("from your check-ins", "estimate from bot activity", vendor attribution). |

### 6.14 IMG-13 Correlation insight card

| Field | Specification |
|---|---|
| Purpose | Present one descriptive comparison found by the insight engine, with its group sizes. |
| Trigger | Inside the weekly report as its last block (one sentence there, the card on demand through "Show pattern"); on demand from the Mini App Insights tab. Never a standalone scheduled message. |
| Data inputs | The insight record of section 8.4: rule identifier, the two group definitions, the two group values with units, the two group sizes, the period, the template sentence. |
| Dimensions | square, 1080 by 1080 |
| Privacy | L1. Rules that would need a medication name are not eligible for chat images. |
| Localisation | The sentence is a catalogue template with numbered slots and plural rules per language; translators receive the list of forbidden causal words for their language. |

```
+------------------------------+
| A pattern in your records    |
| 13 Oct - 9 Nov               |
|                              |
| After nights at or above     |
| your 7 h 30 min goal         |
|   o-----------------(o) 5.1  |
|   18 days   Fairly good      |
|                              |
| After shorter nights         |
|   o----------(o)        4.0  |
|   9 days    Okay             |
|                              |
| Mood is the daily average on |
| a 1 to 5 scale.              |
| This compares your own days. |
| It does not show a cause.    |
+------------------------------+
```

Form: two horizontal dumbbell rows on one shared scale, one hue (the outcome domain's hue), the two values printed, the group sizes printed under each group label. The fixed footer sentence "This compares your own days. It does not show a cause." is part of the template and cannot be removed by a rule.

Example caption: "A pattern in your records, 13 Oct to 9 Nov. On the 18 days after a night at or above your 7 h 30 min goal, your average mood was 3.7 (pleasant). On the 9 other days it was 3.0 (neutral). This compares your own days. It does not show a cause."

The grouping threshold is always the user's own goal or the user's own median, never a general norm (VA-PR-07).

### 6.15 IMG-14 Milestone card (Later, opt-in)

Status: not part of MVP, V1 or V2. PRODUCT_SPEC R-GAM-4 makes a milestone one line in the weekly report and forbids a message of its own (ROADMAP conflict X-24). This card is kept only as a Later item that a user could request on demand from the Mini App after turning it on; it is never sent proactively, and it has no slice in ROADMAP (listed in ROADMAP section 9.1). The specification below describes that Later form. Where it mentions runs or "days in a row", read the milestone kinds of section 7.3 instead.

| Field | Specification |
|---|---|
| Purpose | Mark a logging milestone in a non-medication domain without turning it into a reward for intake. |
| Trigger | On demand only, for a milestone of section 7.3 that already appeared as a line in a weekly report. Never scheduled, never at a touchpoint. |
| Data inputs | Milestone kind and number only. |
| Dimensions | square, 1080 by 1080 |
| Privacy | L0 global asset per milestone, theme and locale. The caption may add which domains contributed at L1. |
| Localisation | Number and plural form are in the image, so one asset per locale. |

```
+------------------------------+
|                              |
|   # # # # # # # # # #        |
|   # # # # # # # # # #        |   30 cells, one per day,
|   # # # # # # # # # #        |   in ink (neutral), not in
|                              |   a domain colour.
|            30                |
|      days with records       |
|                              |
+------------------------------+
```

The badge is the number of cells itself up to 100 (a 10-column grid); above 100 the grid is a fixed 10 by 10 block with the number. Cells are `ink`, not Spruce, because the milestone is about record keeping across domains and must not look like a medication reward.

Example caption: "50 mood check-ins recorded."

| ID | Requirement |
|---|---|
| VA-IMG-14a | No milestone exists for doses taken, adherence percentages, hours slept, litres drunk, calories or weight. Catalogue lint on milestone kinds. |
| VA-IMG-14b | No message ever states that anything ended, is about to end or was lost. There are no runs (section 7.3). |
| VA-IMG-14c | No milestone message is sent proactively. The milestone line in the weekly report disappears with the gamification switch (R-GAM-5). |

### 6.16 IMG-15 Refill warning card

| Field | Specification |
|---|---|
| Purpose | Tell the user that the count they entered for a medication has reached the threshold they set. |
| Trigger | Event: the remaining count reaches or passes the user's threshold. Repeats follow section 10. |
| Data inputs | Label, remaining count as tracked from the user's entered stock and logged doses, the user's threshold, the projected number of days covered. |
| Projection rule | Days covered is computed by walking the schedule's future occurrences (the materialiser) and consuming the per-dose quantity until the count is exhausted, not by dividing the count by a daily average. Every-other-day, weekday and cycle schedules therefore project correctly. As-needed medications show the count only. |
| Dimensions | wide, 1280 by 720 |
| Privacy | L2 with the name; L1 and discreet mode show the neutral label and the caption "One of your items is running low." |
| Localisation | Plural forms for units and days; the unit word is the user's own entry at L2. |

```
+----------------------------------------------------------+
| (refill)  Running low                                    |
|                                                          |
|  Metformin 500 mg                                        |
|                                                          |
|  # # # # # # | . . . . . . . . . . . . . .       6       |
|  left        ^ your threshold: 10                tablets |
|                                                   left   |
|  Covers your schedule until about Sun 15 Nov.            |
|  Based on the count you entered.                         |
+----------------------------------------------------------+
```

The remaining units are solid cells in `ink`; the threshold marker and the icon use the `attention` token. This is the only image template in which `attention` appears.

Example caption (L2): "Metformin 500 mg: 6 tablets left by your count, below your threshold of 10. At your current schedule of two a day that covers until about Sun 15 Nov. Update the count after a refill."

| ID | Requirement |
|---|---|
| VA-IMG-15a | The card states the source of the count ("by your count") and never instructs the user to obtain, buy or take anything. |
| VA-IMG-15b | Property test: for every schedule rule kind the projected last covered day equals the day found by simulating occurrences. |

### 6.17 IMG-16 Measurement trend

| Field | Specification |
|---|---|
| Purpose | Draw the user's entered measurements of one kind (for example weight) over time. |
| Trigger | On demand. |
| Data inputs | Values with instants and units, the period, optional context labels the user chose (for example "fasting"). |
| Dimensions | wide, 1280 by 720 |
| Privacy | L1. |
| Form | Dots for entries in `ink2`. For weight only, a 6 px line for the 7-day moving mean, drawn only where at least 3 entries fall inside the window. For blood pressure, blood glucose, temperature and pulse the card shows the recorded points in time order and nothing else: no mean, no trend line, no band (PRODUCT_SPEC R-VIT-4, AC-VIT-4). The y axis does not start at zero and says so with an axis break mark. No bands, zones, thresholds, targets or colour changes by value. Two different measurement kinds are two cards. |

Example caption: "Weight, 13 Oct to 9 Nov: 12 entries, from 80.9 to 82.3 kg, latest 81.4 kg on 9 Nov. Values are your entries."

| ID | Requirement |
|---|---|
| VA-IMG-16a | No template, caption or legend classifies a measurement (no "normal", "high", "low", "in range"). Forbidden-phrase lint. |

### 6.18 IMG-17 Shareable anonymised card

| Field | Specification |
|---|---|
| Purpose | Let the user show someone a fact about their routine without exposing health detail. |
| Trigger | On demand only, from the Mini App or a "Share" button, with a preview and an explicit confirmation. |
| Content whitelist | Exactly one of: a weekly consistency count for water, sleep check-ins or mood check-ins ("Water goal on 5 of 7 days"); the week's average mood blob with its name. Medication facts are not on the whitelist. |
| Removed | Medication names and counts, dates (the period is "this week" or "last 30 days"), times of day, measurements, tags, notes, the Telegram name, any identifier, any link that contains an identifier. |
| Dimensions | square, 1080 by 1080 |
| Delivery | Sent to the user's own chat without `protect_content` so the user can forward it. Sharing through `shareMessage` (P18) is a later option that depends on assumption A9. |
| Privacy | L1 restricted to the whitelist. Not available in discreet mode unless the user confirms a second time. |
| Localisation | As IMG-14. |

```
+------------------------------+
|                              |
|   | | | | |   .   .          |
|   M T W T F   S   S          |
|                              |
|      Water goal on           |
|      5 of 7 days             |
|      this week               |
|                              |
|                    Dosegram  |
+------------------------------+
```

| ID | Requirement |
|---|---|
| VA-IMG-17a | The share view-model type has no field for a medication, a date, a time or a free-text string. A canary test confirms that no such value can reach the image or its caption. |
| VA-IMG-17b | The preview shows the exact image and the sentence "Anyone you send this to can keep it." before the user confirms. |

### 6.19 IMG-18 Gate and empty-state art

Global L0 cards that show progress toward a minimum-data gate or explain an empty state, for example "3 more nights of sleep check-ins until your first sleep summary" drawn as 5 cells with 2 filled. The count comes from the gate definition in section 7 or 8, never from an invented unlock. One asset per gate size, filled count, theme and locale.

### 6.20 DOC-01 Record summary (PDF)

| Field | Specification |
|---|---|
| Purpose | A printable record that the user can keep or choose to show to a clinician. It lists what the user recorded. It contains no interpretation. Its only title on every surface is "Record summary" (PRODUCT_SPEC R-IMX-6; catalogue key `doc.record_summary.title`; export kind `record_summary_pdf`). It is not called a clinical or medical report (research brief 06 section 14.9). Wave: V2 in PRODUCT_SPEC; deferred in ROADMAP section 9.1. |
| Trigger | On demand only, from the Mini App Reports screen (S-14) or the "Record summary" button of `/export`, after the step-up confirmation in chat that research brief 06 section 4.5 requires for exports. The user chooses the period (default the last 90 days, maximum 12 months) and ticks the sections to include. |
| Renderer | Typst 0.15.1 through a queued job with a 30 second timeout, fed with a JSON data file and the same SVG charts (research brief 04 section 11). Fallback: PDFBox. |
| Format | A4 portrait, tagged PDF, PDF/UA-1 and PDF/A-2 when spike S8 confirms the combination; real text; every figure has alternative text from the view model. Light theme only, and legible in greyscale print: every status is distinguished by glyph and label, not by colour. |
| Delivery | Default: download in the Mini App through `downloadFile` (P17) with a download ticket valid for 15 minutes (ARCHITECTURE section 9.7 owns the ticket rules). Option: "Send to this chat" through `sendDocument` (P8) after a warning that the file will be stored by Telegram (P13); this needs the `export_to_chat` consent of PRODUCT_SPEC R-IDC-6; the file name is `record-summary.pdf` and the caption is "Your record summary for 13 Aug to 10 Nov. Contains health records." |
| Privacy | L3. The user's name appears only if the user types it into the "name on the report" field at generation time; it is not stored. Notes are included only for sections where the user ticks "include my notes". |
| Localisation | The report language is the account locale, with an option to generate it in English or Ukrainian regardless of the interface language. Dates are written unambiguously ("9 Nov 2026"). Units as set by the account. |

Sections. Sections 1 to 3 are the core of PRODUCT_SPEC R-IMX-6; sections 4 to 7 are included only when the user selects them:

| # | Section | Content |
|---|---|---|
| 1 | Cover and scope | Title, period, generation date and time zone, the list of included sections, the statement "This document lists entries recorded by the user in Dosegram. It is not a medical record and contains no medical advice or interpretation." |
| 2 | Medication list | A table of active and archived medications in the period: name, strength and form as entered, schedule in words, start and end dates, instructions as entered. |
| 3 | Medication records | Per medication: a table by month of scheduled, taken on time, taken late, skipped, not confirmed and no-record counts, with the adherence and on-time figures of section 7.2 and their denominators; the adherence strip for the last 14 days; as-needed logs as a dated list or a monthly count. |
| 4 | Measurements | Per kind: a table of entries (date, time, value, unit, context label) and the trend figure of IMG-16. |
| 5 | Sleep | Monthly means of T2 and T3 nights, bedtime and wake consistency, the regularity chart for the last 14 nights, with sources named. |
| 6 | Mood | Monthly count of check-in days, distribution over the five captured valence steps as a table, and the timeline. No interpretation and no totals that resemble a questionnaire score. |
| 7 | Water and food | Monthly means and counts. |
| 8 | Definitions | How each figure is calculated, taken from section 7, including what is excluded. |

```
+----------------------------------------------+
| Record summary                  page 2 of 6  |
| 13 Aug - 10 Nov 2026                         |
|                                              |
| Medication records                           |
| Metformin 500 mg, 1 tablet, 08:00 and 20:00  |
| +-------+------+------+------+------+------+ |
| | Month | Sched| On t.| Late | Skip.| Miss.| |
| | Aug   |  38  |  33  |   3  |   1  |   1  | |
| | Sep   |  60  |  55  |   4  |   0  |   1  | |
| +-------+------+------+------+------+------+ |
| Taken: 95 of 98 resolved (97%).              |
| [ strip figure, last 14 days ]               |
|                                              |
| Recorded entries only. Not medical advice.   |
+----------------------------------------------+
```

| ID | Requirement |
|---|---|
| VA-DOC-01a | Every number in the PDF equals the value the Mini App shows for the same period and filter. Cross-representation test on a fixture account. |
| VA-DOC-01b | The PDF passes a PDF/UA checker in CI (after spike S8) and two runs with the same inputs and a fixed creation time produce identical bytes. |
| VA-DOC-01c | The PDF contains no account identifier, Telegram identifier or username, and its metadata fields (title, author, producer) carry fixed neutral strings. |
| VA-DOC-01d | The service never sends the PDF to anyone other than the requesting user and offers no "send to my doctor" function. |
| VA-DOC-01e | The data file given to Typst lives on a memory-backed mount and is removed when the job ends; the stored PDF is deleted 15 minutes after it is ready. |

## 7. Metric definitions

Every number in an image, a caption, the PDF and the Mini App is one of the metrics below, computed by one pure function in `core` over a snapshot. A metric that is not defined here is not displayed.

### 7.1 Common definitions

| Term | Definition |
|---|---|
| Local day | The half-open interval from 00:00 to the next 00:00 in the account's IANA time zone in force at that instant. A daylight-saving day has 23 or 25 hours. Each record stores its UTC instant, the zone identifier in force and the derived local date at the time of recording; a later zone change does not move past records to other days. |
| Period | A closed range of local days. A week is 7 local days starting on the account's first day of the week. A month is a calendar month. |
| Tracked day (per domain) | A local day on which the domain is enabled and not paused. A pause is a user-set interval for one domain or all domains, with no reason required. |
| Recorded day (per domain) | A tracked day with at least one record in that domain. |
| Coverage | Recorded days divided by tracked days in the period. It is printed next to every average. |
| Average over days | The mean over recorded days. A tracked day without a record is "no record" and is never counted as zero. |
| Evidence type | `recorded` (a tap or entry at the time), `self_reported` (entered or corrected afterwards), `estimated` (derived, for example a quiet window or a looked-up nutrition value), `imported(<source>)`. |
| Own baseline | The mean of the same metric over the previous four periods of equal length that each meet the display threshold. It is shown only when at least two such periods exist. |

Display thresholds and rounding (PROPOSED):

| Rule | Value |
|---|---|
| A percentage is shown | Only when its denominator is at least 5. Below that, counts only ("2 of 3"). |
| A weekly average is shown | Only with at least 3 recorded days; a monthly average with at least 10. |
| Percentages | Whole numbers, rounded half up, except that 100 is shown only when numerator equals denominator and 0 only when the numerator is 0; otherwise the value is clamped to 1 to 99. Progress toward a goal is floored. |
| Durations | To 5 minutes in images and captions; to the minute in the PDF tables and the Mini App detail view. |
| Volumes | To 0.1 l (or 1 US fluid ounce) in images; to 10 ml in detail views. |
| Mood averages | One decimal on the 1 to 5 scale of the stored valence ("3.5"; PRODUCT_SPEC R-MOO-5, AC-MOO-4), always with the level name of its quantised level (section 4.2, with `v = (m - 3) / 2`). No surface shows a mood value "of 7". |
| Baseline wording | "about the same" when the absolute difference is under 2 percentage points, 10 minutes, 0.2 mood levels or 100 ml; otherwise "higher" or "lower", "longer" or "shorter", "earlier" or "later". The words "better" and "worse" are never used. |

| ID | Requirement |
|---|---|
| VA-ME-01 | Each metric is a pure function with an oracle test suite. SQL read models used by reports and by the Mini App API return the same values as the pure function over the same rows (the rule already set for adherence in BOT_UX_PLAN section 6). |
| VA-ME-02 | Every displayed value carries its denominator or sample count and its evidence type in the view model, and the text equivalent prints both. |
| VA-ME-03 | Property test: no metric function returns a number when its display threshold is not met; it returns an explicit "not enough data" value with the count that is missing. |

### 7.2 Medication

The inherited definitions of `core.domain.Adherence` and ADR-012 stay authoritative. They are restated here and extended by two metrics (recorded rate and day fraction) and by as-needed statistics.

Buckets for a dose occurrence in a period:

| Bucket | Symbol | Condition |
|---|---|---|
| Taken on time | `Ton` | Status taken and `effective_at` not later than `due_window_end` (snooze-shifted windows included) |
| Taken late | `Tlate` | Status taken and `effective_at` later than `due_window_end` |
| Skipped | `S` | Status skipped (the user answered Skip) |
| Missed | `M` | Status missed (the window closed without an answer although a reminder was delivered) |
| Unknown | `U` | Status unknown (no evidence that a reminder was delivered, for example a service outage) |
| Open | `O` | Pending, due or snoozed |
| Excluded | | Cancelled occurrences, and occurrences with origin manual (as-needed logs) |

| Metric | Formula | Undefined when | Display |
|---|---|---|---|
| Adherence | `(Ton + Tlate) / (Ton + Tlate + S + M)` | Denominator is 0: "No data" | "26 of 28 taken (93%)" |
| On-time rate | `Ton / (Ton + Tlate)` | Nothing was taken | "24 of 26 taken doses on time" |
| Recorded rate (new) | `(Ton + Tlate + S) / (Ton + Tlate + S + M)` | Denominator is 0 | "27 of 28 answered" |
| Day fraction (new, for IMG-06) | Adherence computed over the occurrences attributed to one local day | The day has no resolved occurrence | Heat step |
| Delay statistics | Median and nearest-rank 90th percentile of `effective_at - scheduled_for` over taken doses, excluding corrected entries | Fewer than 5 samples | Mini App and PDF only |
| Exclusion counts | `U` and `O` | never | Always printed when non-zero: "2 with no record, not counted" |

Attribution to a day (inherited): a taken dose counts on the local day of `effective_at`; a skipped or missed dose on the local day of its resolution; an open dose on its scheduled local day.

Aggregation: totals over several medications pool the bucket counts. They are never the mean of per-medication percentages.

Edge cases:

| Case | Rule |
|---|---|
| Taken before the scheduled time | On time; the delay sample is negative. |
| Answered after `due_window_end` but inside the late-log window | Taken late. If the occurrence had already become missed, the late log moves it from `M` to `Tlate` and its day attribution moves to the day of `effective_at`. |
| The user corrects the "taken at" time | Classification uses the corrected `effective_at`. The entry is excluded from delay statistics. |
| Snooze | The due window shifts; lateness is judged against the shifted end. |
| Quiet-hours deferral | The window shifts; it never produces unknown. |
| Skipped | Lowers adherence and counts as an answer in the recorded rate. It is drawn hollow and never in a warning colour. |
| Unknown | Excluded from every rate; the count is displayed. |
| Schedule edited, paused or medication archived | Cancelled occurrences are excluded and not displayed. Resolved occurrences before the change stay in the period; an archived medication is marked "(archived)" at L2. |
| A dose scheduled late in the evening and taken after midnight | It counts on the next local day. A day can therefore show more resolved doses than it had scheduled; the heatmap uses the fraction, which stays within 0 to 1. |
| Daylight-saving gap or fold | The occurrence exists once, as ADR-011 materialises it. |
| Several units in one dose | One occurrence is one dose regardless of quantity. |
| Chain or interval schedules not started on a day | Counted as missed with the domain's reason, as the inherited design specifies. |
| No scheduled dose in the period | "No data". No percentage, no heat step. |

As-needed (PRN) medications never enter adherence, on-time rate or recorded rate, and have no counter of any kind. Their statistics are descriptive counts:

| Metric | Definition | Where shown |
|---|---|---|
| Logs in period | Number of manual logs | Strip, PDF |
| Days with a log | Number of local days with at least one log | Strip, PDF |
| Logs by day | The dated list, or a monthly count when there are more than 40 | PDF |

No maximum, interval, limit or comparison is computed for as-needed logs, because any of those would be read as dosing guidance.

| ID | Requirement |
|---|---|
| VA-ME-10 | Adherence, on-time rate, delay statistics and day attribution equal the existing pure `Adherence` oracle for every fixture, including the 30-day simulation data. |
| VA-ME-11 | Recorded rate and day fraction are added to the same pure module with table tests for every edge case above. |
| VA-ME-12 | No output of the medication metrics contains a recommendation, a target percentage or a classification of the percentage. |

### 7.3 Weekly consistency and milestones

PRODUCT_SPEC section 8.14 owns this behaviour (R-GAM-1 to R-GAM-7), and this section defines the figures it needs. The first draft of this document defined consecutive-day runs with a grace rule, including a "medication record keeping" run in which Skip counted as an answer and a "days with records" run in which an answered dose counted. Runs are withdrawn (D9; ROADMAP conflict X-24): a run can end, which R-GAM-1 forbids, and a run or milestone that counts dose answers rewards tapping an answer, which R-GAM-2 forbids.

| Term | Definition |
|---|---|
| Hit day (water, food, mood) | A tracked day with at least one record in the domain. |
| Hit day (sleep) | A tracked sleep day with a T2 or T3 episode. Days flagged `tz_shift` and paused days are not tracked days. |
| Weekly consistency | Hit days among the last 7 local days, out of the tracked days among them: "5 of 7 days". It is computed from records every time it is shown; no counter is stored. It is a count of successes in a window, so nothing resets and nothing can be lost. |
| Milestone | A count of acts of logging in one non-medication domain reaching 10, 50 or 100 (for example the 50th mood check-in), and one year since the first record. A milestone is one line in the weekly report (R-GAM-4). It never produces a message of its own and is never an image in this wave (IMG-14 is a Later, opt-in item). |
| Medication | No consistency counter, run or milestone exists for medication intake, adherence, dose answers or any measured value (sleep duration, water volume, mood value, weight). Medication shows its statistics of section 7.2 only. |
| Backfill | A record added later for a past day changes that day's class and therefore the weekly count. |
| Switch | One account setting turns counters, gates and milestones off on every surface (R-GAM-5). |
| Prohibited | Any run of consecutive days; warnings that something is at risk; messages that something ended; counters shown as a loss; any payment or task to restore anything; the words of R-GAM-6. |

The inherited function `Adherence.streak` counts days on which all doses were taken and ends on any skipped or missed day. It is not surfaced to users under this specification (open question Q5).

| ID | Requirement |
|---|---|
| VA-ME-20 | Table tests cover the weekly count: paused days inside the window; today still open; a backfilled day; a week with no tracked day (shown as a gate, not as "0 of 0"). |
| VA-ME-21 | The copy catalogue contains no string that announces a lost or endangered count and none of the words of R-GAM-6. Catalogue lint. |
| VA-ME-22 | Registry test: the list of counters and milestone kinds contains no kind that reads a medication table or a measured value. |

### 7.4 Hydration

| Term | Definition |
|---|---|
| Entry | Volume `V` in millilitres (stored in millilitres whatever the display unit; 1 to 5000 ml per entry, PRODUCT_SPEC R-HYD-1), a drink type, and a time the user can edit. |
| Hydration factor | `f` between 0 and 1 per drink type. All factors are 1 unless the user turns the optional setting on. |
| Effective volume of a day | `E = sum of V * f` over the entries attributed to the local day of their time. |
| Goal of a day | `G`, the user's goal in force at the end of that local day. Goal changes apply from the day of the change; past days keep their goal. |
| Completion | `min(1, E / G)`; displayed as `floor(100 * E / G)` percent, capped at 100 in the vessel while the text states the real amount. |
| Goal day | A recorded day with `E >= G`. |
| Weekly figures | Goal days out of recorded days; mean `E` over recorded days; coverage. |

Edge cases: a tracked day with no entry is "no record", not zero. Deleting or editing an entry recomputes the day. Switching display units changes no stored value (1 US fluid ounce is 29.5735 ml). An entry at 00:10 belongs to the new local day. A goal change during the day applies to that whole day.

| ID | Requirement |
|---|---|
| VA-ME-30 | No hydration output states a recommended amount, a warning about too little or too much, or any comparison other than with the user's own goal and own baseline. |
| VA-ME-31 | If the product offers a starting goal, it is labelled as a rough starting point that the user should change freely, and the stored goal is marked as user-set once edited (PRODUCT_SPEC.md owns the onboarding copy). |

### 7.5 Food

| Term | Definition |
|---|---|
| Entry | Meal type (breakfast, lunch, dinner, snack), a time, optional photo, optional text, optional energy and macronutrient numbers with an evidence type. |
| Planned meals | The set of main meal types the user tracks (default breakfast, lunch, dinner; 1 to 6 allowed). |
| Meals logged | Distinct planned meal types with at least one entry on the day, out of the planned meals. Snacks are counted separately. |
| Day energy | The sum of energy over entries that have a number. Always shown with "from N of M entries with numbers". |
| Macro shares | Shares of energy: `4P / (4P + 4C + 9F)`, `4C / (...)`, `9F / (...)`, with protein `P`, carbohydrate `C` and fat `F` in grams, using the general Atwater factors of 4, 4 and 9 kcal per gram (ASSUMPTION A12: a nutrition convention not re-read in a primary source in this run). Shown only when at least one entry has all three numbers. |
| Unit | 1 kcal is 4.184 kJ. |

Edge cases: entries without numbers count as meals and not toward energy. An entry with energy but no macros counts toward energy only, and the macro bar's coverage line says so. A value from a lookup or a model is `estimated`. No calorie budget, deficit, remaining amount or weight-linked figure is computed.

| ID | Requirement |
|---|---|
| VA-ME-40 | The food metrics contain no target unless the user entered a personal reference value, and then only the plain difference from it is shown. |
| VA-ME-41 | Food outputs never use moral or evaluative words ("clean", "cheat", "bad", "over", "too much"). Forbidden-phrase lint. |

### 7.6 Mood

| Term | Definition |
|---|---|
| Level value | `m`, the stored valence from 1 to 5 of a check-in (equivalently `m = 3 + 2 * v`). Energy value `g`, the stored energy from 1 to 5. |
| Daily mean | `Md`: the mean of `m` over the check-ins attributed to the local day. A check-in belongs to the local day of its time unless the user marks it "for yesterday". |
| Period average | The mean of `Md` over check-in days, not over check-ins, so a day with many check-ins does not dominate. |
| Distribution | The number of days at each quantised level of `Md`. |
| Range | The lowest and highest `Md` in the period. |
| Variability | The sample standard deviation of `Md` (at least 5 check-in days), and the day-to-day change: the mean of `abs(Md - Md-1)` over pairs of consecutive calendar days that both have a check-in (at least 4 pairs). |
| Most frequent tags | Counts of tag words in the period; L2 only. |

Variability is reported as numbers ("day-to-day change averaged 0.8 levels"). It is never labelled stable, unstable, volatile or any similar word, and no threshold is applied to it. A day without a check-in is a gap in every chart and is excluded from every figure.

| ID | Requirement |
|---|---|
| VA-ME-50 | Mood outputs never sum levels into a total, never compare with other people, and never name a condition. No template resembles a scored questionnaire. |
| VA-ME-51 | Table tests: one check-in a day; several a day; gaps; a "for yesterday" check-in after midnight; edits and deletions. |

### 7.7 Sleep

The tier model and the algorithm of research brief 05 are adopted. Tiers: T1 passive quiet window from the user's own bot and Mini App interactions (opt-in), T2 explicit check-ins or a confirmed or corrected estimate, T3 imported sensor data.

| Metric | Definition | Tiers | Minimum data | Where shown |
|---|---|---|---|---|
| Sleep day | The local date on which the main sleep ends, in the zone in force at the wake instant. | all | | all |
| Duration | Wake instant minus onset instant minus recorded awake intervals. A difference of instants, so daylight-saving nights give the true duration. | T2, T3 | one night | images, Mini App, PDF, labelled "self-reported" or with the device source |
| Quiet window | The interval between the last user-originated event before a long gap and the first after it. An upper bound: "at most". | T1 | confidence at least 0.25 | drawn hatched; never in any average |
| Total per 24 hours | Main sleep plus explicit naps. | T2, T3 | | Mini App, PDF |
| Difference from goal | The sum of `duration - goal` over nights with data, signed. Worded "40 min under your goal in total over 6 nights". | T2, T3 | 3 nights | weekly report, Mini App |
| Bedtime consistency, wake consistency | Circular standard deviation of onset (or wake) clock times. With `t` in minutes after local midnight and `a = 2 * pi * t / 1440`: `C = mean(cos a)`, `S = mean(sin a)`, `R = sqrt(C^2 + S^2)`, `sd = sqrt(-2 * ln R) * 1440 / (2 * pi)` minutes. | T2, T3 | 5 of 7, 10 of 14 or 20 of 28 nights | images as "bedtime varied by about 35 min" |
| Typical bedtime and wake time | The circular median of the clock times. | T2, T3 | as above | regularity chart markers |
| Midpoint | The instant halfway between onset and wake, as a local clock time. | T2, T3 | | Mini App |
| Sleep Regularity Index | The percentage probability of being in the same state (asleep or awake) at two instants 24 hours apart, rescaled so 100 is perfectly regular. Computed on 5-minute epochs from binary episodes over consecutive day pairs that both have data. The epoch formula of research brief 05 section 8 must be checked against Phillips et al. 2017 before implementation (ASSUMPTION A13). | T2, T3 | 5 of 7 consecutive days | Mini App and PDF only, with its published name and a "how this is calculated" link |
| Social jet lag | Midpoint on free days minus midpoint on work days, stored signed, shown as an absolute duration with the direction in words. Work-day labels come from the user's calendar; without them the metric is not computed. | T2, T3 | 3 work days and 2 free days | Mini App and PDF only |
| Quality rating | The user's 1 to 5 rating; mean over rated nights. | T2 | 3 rated nights | "your rating" |
| Stages, efficiency, latency, awakenings, device scores | Imported values only, attributed verbatim to the device, never recomputed or blended. | T3 | | Mini App detail and PDF only |

Decision: the Sleep Regularity Index and social jet lag are shown in the Mini App and the PDF but not in chat images. Rationale: a bare 0 to 100 number in a chat picture reads as a score, and both need a definition beside them. Rejected alternative: making the index the hero number of the sleep card.

Edge cases:

| Case | Rule |
|---|---|
| Sensor and self-reported data for the same sleep day | The sensor episode is primary for metrics; the self-report keeps the quality rating. |
| `tz_shift` (the zone offset at wake differs from the previous day's, including daylight saving) | The night is drawn and its duration counts; it and the next night are excluded from consistency, the index and social jet lag. |
| Irregular-schedule flag | No night prior; main sleep is the longest episode per rolling 24 hours; a second episode of 3 hours or more is a second main sleep; consistency and social jet lag are not presented as better or worse. |
| Naps | Only explicit or imported; excluded from bedtime, wake time, midpoint and regularity. |
| A night with only a quiet window | Shown as a bound if confidence allows. It is "no record" for every average, and for the weekly sleep consistency count it is not a hit day unless the user confirms or corrects it. |
| A confirmed estimate | Becomes T2 with evidence type `self_reported`; a corrected one records both the estimate and the correction. |
| Service outage inside the candidate window | No passive estimate for that night. |

| ID | Requirement |
|---|---|
| VA-ME-60 | No function computes a duration, an average or a regularity figure from T1 data. Type-level separation of `QuietWindow` and `SleepEpisode`. |
| VA-ME-61 | No Dosegram sleep score exists. Vendor scores are displayed only with the vendor's name and are never used in any Dosegram metric or insight. |
| VA-ME-62 | Table tests for the circular statistics include times that straddle midnight (23:40, 00:10, 00:30) and a daylight-saving night. |
| VA-ME-63 | The sleep goal is always the user's own. No general reference duration is shown (research brief 05, open question Q4). |

### 7.8 Body measurements

Latest value; lowest and highest in the period; number of entries. For weight only: the 7-day moving mean where at least 3 entries fall in the window, and the plain difference between the first and the last entry of the period ("0.6 kg lower than on 13 Oct"). For blood pressure, blood glucose, temperature and pulse no mean, moving mean or difference is computed or shown (PRODUCT_SPEC R-VIT-4). Measurement kinds, units and bounds are those of PRODUCT_SPEC R-VIT-1 to R-VIT-3; symptom entries have a user-given name and a severity from 1 to 5 (R-VIT-6). No rate of change, projection, target, range, classification or alert (VA-IMG-16a).

### 7.9 Day progress for the halo

The four progress values of section 4.7 are the only cross-domain figures the product shows together. They are never added, averaged or weighted into one number (VA-PR-05).

## 8. Insight engine

### 8.1 Purpose and boundary

The insight engine finds descriptive differences between groups of the user's own days and states them with their counts. It is deterministic, rule-based and runs per account. It uses no machine-learning model, no data from other accounts and no third-party service (P21). It does not explain, predict, advise or warn.

### 8.2 Consent and control

| ID | Requirement |
|---|---|
| VA-IN-01 | Cross-domain comparison is a separate purpose. No rule that combines two domains runs for an account until the user turns on "Compare my records across areas" (consent purpose `cross_domain_patterns`, PRODUCT_SPEC R-IDC-6), which is off by default and revocable in one tap. Single-domain statements (IR-07, IR-11) do not need it. |
| VA-IN-02 | The user can switch off each rule, dismiss a single insight ("Not useful"), and switch off all insights. A dismissed rule is not shown again for 90 days. |
| VA-IN-03 | Every insight offers "How was this worked out?", which lists the period, the two group definitions, the group sizes, the minimum-data rules and the sentence "This is a count over your own records. It cannot show a cause." |
| VA-IN-04 | Insight records are health data of the account (class B in research brief 06) and are deleted with the domains they were computed from. |

### 8.3 Variables that may and may not be used

| Role | Allowed | Never allowed |
|---|---|---|
| Grouping factor | Sleep duration against the user's own goal; bedtime against the user's own median; water goal reached; all planned meals logged; work day or free day; day of week class; a drink type; a user-chosen evening tag; a user-chosen mood context tag | Any medication measure: taken, skipped or missed, dose timing, punctuality of an answer, as-needed logs. Also any body measurement; any symptom entry; food energy or macros; any imported device score |
| Outcome | Daily mood mean; daily energy mean; sleep duration and quality rating (T2, T3); water volume | Any medication measure, including the share of scheduled doses answered on time, late or not at all. Also any body measurement; as-needed logs; any symptom entry; any imported device score |

Decision: no medication measure appears on either side of a cross-domain rule (PRODUCT_SPEC R-INS-8, R-INS-9 and AC-INS-5; ROADMAP conflict X-23). Rationale: "on days you took everything, your mood was higher" is read as evidence that a treatment works or does not, which is information for a therapeutic decision (research brief 03 section 8.1) and is not supported by self-report; the reverse form, "after shorter nights your morning doses were answered late", links a health state to medication behaviour in the same way and invites the same reading. Rejected alternatives: a "medication and mood" card, which several competing applications offer; and medication on the outcome side only, as punctuality of answering, which the first draft of this document allowed in rules IR-03, IR-06 and IR-10. If the owner wants the weaker rule, it is put to counsel first (PRODUCT_SPEC Q-27, ROADMAP G11.17). See open question Q6.

The only medication statement produced by this engine is the single-domain timing statement of PRODUCT_SPEC R-INS-7 (rule IR-07): it compares dose time slots with each other and with nothing else, needs no cross-domain consent, and never suggests changing a schedule.

As-needed logs, symptoms and measurements are excluded from both sides, because each is a proxy for a clinical state.

### 8.4 Rule catalogue

Each rule compares two groups of local days and one outcome. "Pairing" says which records share a day. Rule identifiers carry the prefix `IR-` (insight rule). The first draft used `INS-`, which collides with the computation rules `INS-nn` of ARCHITECTURE section 11; a withdrawn rule keeps its number.

| Rule | Groups | Outcome | Pairing | Minimum display difference |
|---|---|---|---|---|
| IR-01 | Nights at or above the user's sleep goal; shorter nights | Daily mood mean | Sleep day D with mood of local day D | 0.3 levels |
| IR-02 | Bedtime within 30 minutes of the user's own median; outside | Daily mood mean | As IR-01 | 0.3 levels |
| IR-03 | Withdrawn (medication outcome; X-23) | none | none | none |
| IR-04 | Water goal reached; not reached (recorded days only) | Daily mood mean | Same local day | 0.3 levels |
| IR-05 | All planned meals logged; fewer | Daily mood mean; daily energy mean | Same local day | 0.3 levels |
| IR-06 | Work days; free days (the user's calendar, else Monday to Friday against Saturday and Sunday, stated as such) | One of: sleep duration; water volume; daily mood mean | Same local day; sleep day for sleep | 30 minutes; 300 ml; 0.3 levels |
| IR-07 | Single-domain medication timing statement (PRODUCT_SPEC R-INS-7), not a cross-domain rule: dose time slots (morning, midday, evening, night) compared with each other | Share of doses in the slot answered late or not at all, stated as counts ("Evening doses were recorded late on 5 of 7 days") | Each slot's doses in the period | 15 percentage points between the highest and lowest slot, each with at least 10 doses |
| IR-08 | Evenings with a given tag (for example "coffee after 15:00"); without | That night's sleep duration; quality rating | Tag on day D-1 after 15:00 with sleep day D | 30 minutes; 0.7 rating points |
| IR-09 | Days with a given mood context tag; without | Daily mood mean | Same local day | 0.3 levels |
| IR-10 | Withdrawn (medication outcome; X-23) | none | none | none |
| IR-11 | This period; the own baseline (section 7.1) | Any single-domain headline metric | Period against periods | The "about the same" limits of section 7.1 |

Mood differences are in levels of the stored 1 to 5 scale (section 7.6).

### 8.5 Procedure

| Step | Rule |
|---|---|
| Window | The last 28 local days by default; 56 and 84 days selectable in the Mini App. Computed when the weekly report is prepared and on demand. |
| Evidence filter | Sleep from T2 and T3 only. Estimated nutrition values and quiet windows are never inputs. Days flagged `tz_shift` are dropped for rules that use clock times. |
| Pairing | A day enters a rule only if both the grouping value and the outcome exist for it. |
| Minimum data | At least 14 paired days in total and at least 5 in each group (10 doses per slot for IR-07). |
| Difference | The difference of group means, or of proportions for share outcomes. |
| Minimum difference | The value in the rule table. Smaller differences are not shown. |
| Stability check 1 | Split the paired days into an earlier and a later half. The difference must have the same sign in both halves, and each half must have at least 2 days in each group. |
| Stability check 2 | Removing any single day must not change the sign of the difference or reduce its size below half the minimum display difference. |
| Ranking | Among rules that pass, order by the standardised size: the absolute difference divided by the pooled standard deviation of the outcome (for shares, divided by `sqrt(p * (1 - p))` of the pooled share). Ties are broken by rule number. |
| Selection for the weekly report | The first rule in the ranking that was not shown in the last 28 days (unless its direction has reversed) and is not dismissed. At most one. If none qualifies, the report has no pattern block; no filler sentence is generated. |
| Insights tab | Up to 5 currently passing rules, in rank order, plus a list of rules that are close to their minimum data with the count still needed. |
| Record | Rule identifier, window, group definitions, the two values, the two sizes, unit, computed-at time, snapshot revision. |

No probability, significance statement or confidence wording is shown to the user. The safeguards are the counts, the minimum sizes and the two stability checks, which the "How was this worked out?" view lists in plain words.

### 8.6 Wording rules

Every insight sentence is built from a catalogue template with numbered slots. No sentence is composed freely, and no language model writes one.

Sentence shape: "On the {n1} days {group one}, {outcome} was {value one}. On the {n2} other days it was {value two}." followed, in the image and the caption, by the fixed sentence "This compares your own days. It does not show a cause."

| Rule | Allowed | Forbidden |
|---|---|---|
| Verbs | was, were, averaged, logged, recorded, answered | causes, leads to, makes, helps, harms, improves, worsens, boosts, affects, triggers, results in |
| Connectives | on days when, on the other days, after nights with | because, due to, thanks to, so, therefore, as a result |
| Comparatives | higher, lower, longer, shorter, earlier, later, more, fewer, about the same | better, worse, good, bad, healthy, unhealthy, normal, abnormal, enough, too little, too much |
| Mood | The level names of section 4.2 | depressed, anxious, manic, stable, unstable, any condition name |
| Address | Statements about records ("your average mood was") | should, try, consider, need to, must, we recommend, aim for, avoid |
| Statistics words | pattern, days, average, share | significant, proven, correlation, predicts, risk, likely, effect, impact |
| Clinical words | none | diagnose, treat, symptom, dose advice, deficiency, disorder, insomnia, dehydration, any disease name |

Allowed sentences:

1. "On the 18 days after a night at or above your 7 h 30 min goal, your average mood was 3.7 (pleasant). On the 9 other days it was 3.0 (neutral)."
2. "Evening items were answered late or not at all on 5 of 14 days. Morning items on 1 of 14."
3. "On your 8 free days you logged 1.6 l of water on average. On your 20 work days, 2.1 l."
4. "On the 6 evenings tagged 'coffee after 15:00', you logged 6 h 20 min of sleep that night on average. On the 15 other evenings, 7 h 10 min."
5. "This week you logged 7 h 05 min of sleep a night on average, 40 min longer than your previous four weeks."

Forbidden sentences, each with the rule it breaks:

| Sentence | Why it is forbidden |
|---|---|
| "Sleeping more improves your mood." | Causal verb; general claim. |
| "You feel worse because you slept less than 7 hours." | Causal connective; evaluative word; a general threshold instead of the user's goal. |
| "Try to go to bed earlier to take your evening items on time." | Advice. |
| "Your mood was higher on days you took all your medication." | Medication intake as the grouping factor. |
| "Your adherence is poor in the evenings." | Evaluative word about a health behaviour. |
| "Your mood has been unstable this month. Consider talking to a professional." | Clinical label; advice; a prompt toward clinical action based on a value. |
| "Coffee after 15:00 significantly reduces your sleep." | Statistical claim; causal verb. |
| "You are not drinking enough water." | Judgement against a norm. |
| "You sleep less than most people your age." | Population comparison. |

| ID | Requirement |
|---|---|
| VA-IN-10 | A forbidden-phrase lint runs over every catalogue string in the image, caption, report, insight and Mini App dashboard namespaces, in every shipped language, with the per-language lists maintained beside the catalogue. It extends the existing `ForbiddenPhrases` test of `core.domain.copy`. A match fails the build. |
| VA-IN-11 | The fixed non-causal sentence is part of the insight template and of its caption; a template without it does not compile into the catalogue. |
| VA-IN-12 | Property test: for generated datasets below any minimum, the engine returns no insight; for datasets where one day flips the sign, it returns none. |
| VA-IN-13 | The rule registry is a closed list in code. A test asserts that no cross-domain rule has a medication variable (taken, skipped, missed, timing, punctuality, as-needed) on either side, as grouping factor or as outcome; that no rule has a measurement or nutrition-number variable as its grouping factor; and that no rule has a measurement or symptom variable as its outcome. The single-domain rule IR-07 is the only rule that reads a medication table, and the test asserts that it reads no other domain. |
| VA-IN-14 | No insight is delivered as its own scheduled message. Push delivery is only as the last block of the weekly report (section 10). |

## 9. Mini App dashboard

MINI_APP.md owns navigation, screens outside the dashboard, authentication and the API contract. This section specifies the dashboard content so that it mirrors the images exactly: the same metrics, the same glyphs, the same colours, the same words.

### 9.1 Principles

| ID | Requirement |
|---|---|
| VA-MA-01 | Every dashboard figure is a metric of section 7 served from the same pure function as the images. For a fixture account, the value in the Mini App, the caption of the matching image and the PDF are equal. |
| VA-MA-02 | Charts are drawn from the same scene tree as the images, serialised as JSON for the `miniapp` canvas and painted into SVG DOM nodes by a small scene painter in the front end. No HTML or SVG string is inserted into the page, which keeps the Trusted Types policy of research brief 06 section 12.4. No chart library is used. This is the single chart mechanism of the Mini App (ROADMAP conflict X-30): the API returns scenes, there is no inline-SVG route and no client-side chart component that lays out its own marks from a data series. A scene has at most 400 nodes (the client budget of MINI_APP MA-INS-5). Rejected alternatives: an inline SVG string from the server, which Trusted Types forbids inserting; client-side SVG chart components fed by data series, which would be a second layout engine; and a JavaScript chart library, which would add a second layout engine, a second visual language and a large dependency to a page with a strict content security policy. |
| VA-MA-03 | Charts sit on `panel` cards; chrome follows the Telegram theme (section 3.7). |
| VA-MA-04 | The dashboard shows no score, ranking, badge wall or leaderboard. |
| VA-MA-05 | Everything the dashboard shows is reachable as text: each chart has an `aria-label` equal to its text equivalent and a "Table" toggle that replaces the plot with a table of the same view model. |

### 9.2 Screens

The dashboard has five views, presented as tabs or as sections of one scrolling page according to MINI_APP.md.

**Today**

```
+--------------------------------------+
| Thu 12 Nov                 (eye)     |   (eye) hides names on this screen
|          .== halo ==.                |
|        //  .-~~-.    \\              |   Tap the blob: mood check-in sheet
|        \\  \_''_/    //              |   Tap a halo segment: that domain
|          '==.__.=='                  |
|          Good, steady                |
| +----------------+ +---------------+ |
| | (cell) Medic.  | | (drop) Water  | |   Stat tiles: icon and name,
| | 2 of 4 taken   | | 1.4 of 2.0 l  | |   value with denominator,
| | 1 open         | | 70%           | |   7-day mini strip,
| | [#][#][/][ ]   | | | | | | | . . | |   own-baseline line when available
| +----------------+ +---------------+ |
| +----------------+ +---------------+ |
| | (plate) Food   | | (moon) Sleep  | |
| | 2 of 3 meals   | | 7 h 10 min    | |
| | . : : . : : .  | | your check-ins| |
| +----------------+ +---------------+ |
|  [ Log something ]   (MainButton)    |
+--------------------------------------+
```

| Element | Content | Interaction |
|---|---|---|
| Day blob | Section 4.7, as inline SVG | Tap the blob to open the mood check-in; while the picker moves the blob morphs between states (no morph with reduced motion). Tap a segment to open the domain. |
| Stat tile | Domain icon and name; today's value with its denominator; a 7-day mini strip in the domain's glyphs; "previous four weeks: ..." when a baseline exists | Tap to open the domain detail |
| Open items | Today's open doses listed under the medication tile with the same glyphs | Tap to answer; the tile and the halo update from the server response |
| Eye toggle | Replaces medication names by neutral labels on screen for the session | Tap; the state is not stored on the device |

A tile for a domain with no record today says "Nothing logged yet today" and offers the quick-add control. It shows no zero and no empty percentage.

**Trends**

```
+--------------------------------------+
| [ 7 d ][ 30 d ][ 90 d ][ Custom ]    |   One filter row for the screen
| Medication            93%  26 of 28  |
| [ bars per day or week, Spruce ]     |   One chart per domain, each on its
| Water        goal on 5 of 7 days     |   own panel with its own axis:
| [ bars against goal line, Tide ]     |   small multiples, never two
| Mood          average Fairly good    |   domains on one axis
| [ timeline as IMG-10, Beet ]         |
| Sleep         7 h 05 min average     |
| [ bars against goal line, Dusk ]     |
| Food          19 meals logged        |
| [ dots per day, Rye ]                |
+--------------------------------------+
```

| Period | Bucket | Notes |
|---|---|---|
| 7 days | Day | Default |
| 30 days | Day | |
| 90 days | Week | A week bucket shows the pooled value and its coverage |
| Custom | Day up to 45 days, week above | At most 12 months |

**Calendar**

A month grid with a domain selector (Medication, Mood, Sleep, Water, Food). Medication uses the cells of IMG-06, mood the mini blobs of IMG-11, sleep a cell with a fill height proportional to duration against the goal (hatched for quiet-window nights), water a cell filled to the completion level, food up to three dots. Tapping a day opens a sheet listing that day's records with their evidence types and edit actions. Swiping the month is replaced by previous and next buttons, because vertical and horizontal swipes belong to the Telegram client.

**Patterns**

The list of section 8.5: each passing insight as a card with its dumbbell figure, its sentence, the fixed non-causal sentence, "How was this worked out?", "Not useful" and "Show as picture in chat". Below it, rules that are close to their minimum data ("5 more days with both sleep and mood records"). When `cross_domain_patterns` is off, the view shows only the consent explanation and the switch.

**Reports**

The list of weekly and monthly periods that have data, each with "Open" (the report as a page built from the same view model) and "Send picture to chat"; the record summary generator (DOC-01) with its period and section choices; the shareable card builder (IMG-17) with its preview; the schedule and switches of section 10; and the link to the full export (owned by MINI_APP.md and ARCHITECTURE.md).

### 9.3 Correspondence between images and dashboard components

| Dashboard component | Image | Metric section | Added interaction |
|---|---|---|---|
| Day blob | IMG-03 | 4.7, 7.9 | Tap targets, morph |
| Medication tile strip, by-medication view | IMG-07 | 7.2 | Tap a cell: the occurrence sheet (scheduled time, answer, effective time, edit) |
| Medication calendar | IMG-06 | 7.2 | Tap a day |
| Water chart and vessel | IMG-08 | 7.4 | Tap the vessel to add; tap a bar for the day's entries |
| Food day and month dots | IMG-09 | 7.5 | Tap a meal slot to add or open |
| Mood timeline and calendar | IMG-10, IMG-11 | 7.6 | Tap a dot or blob for the day's check-ins |
| Sleep regularity | IMG-12 | 7.7 | Tap a bar to confirm, correct or delete; the index and social jet lag appear here with their definitions |
| Measurement trend | IMG-16 | 7.8 | Tap a dot for the entry |
| Pattern card | IMG-13 | 8 | Explanation, dismiss |

### 9.4 Interaction rules

| ID | Requirement |
|---|---|
| VA-MA-10 | Every data mark that can be selected has a hit target of at least 44 by 44 CSS pixels, larger than the mark where necessary. |
| VA-MA-11 | Selecting a mark highlights it with a 2 px `ink` outline and opens a bottom sheet with the exact values, the evidence type and the available actions. On pointer devices hovering shows the same values in a tooltip. Nothing is revealed only on hover. |
| VA-MA-12 | Filters are in one row above the charts. Changing the period changes every chart on the screen. Hiding a domain never changes another domain's colour. |
| VA-MA-13 | A period change animates marks for at most 200 ms. With `prefers-reduced-motion`, or on Android devices that report the LOW performance class, there is no animation. |
| VA-MA-14 | On desktop clients the dashboard is usable by keyboard: Tab reaches each chart, arrow keys move between marks, Enter opens the sheet, Escape closes it. Focus is always visible. |
| VA-MA-15 | Destructive actions on records (delete an entry, delete a day) ask for confirmation through the native popup and are not placed on chart marks. |

### 9.5 States and data loading

| State | Behaviour |
|---|---|
| Loading | Each tile and chart shows a skeleton of its final size on `panel`; the layout does not shift when data arrives. |
| Empty | One sentence that says what is missing and a control to add it (VA-WR-04). No sample or placeholder data. |
| Not enough data | The metric's "not enough data" value is shown as words with the missing count ("2 more recorded days for an average"). |
| Error | "Could not load this. Try again." with a retry control on the affected tile only. Other tiles stay usable. |
| Stale | Data older than 60 seconds is refetched when the Mini App receives `activated` (P24) and after every write. |

Requests for a screen run in parallel, one per tile group. Responses are held in memory only, because API responses carry `Cache-Control: no-store` and no health data is written to device or cloud storage (research brief 06 section 3.2). There is no polling and no push channel; the dashboard is refreshed by user action and by `activated`.

Budgets (PROPOSED, to be measured): scene JSON at most 30 KB per chart; at most 600 SVG nodes per chart; server time at most 300 ms at the 95th percentile for a screen's requests; the Today view is complete within 1.5 seconds of session creation on a mid-range phone.

## 10. Schedule of proactive reports

BOT_UX.md owns reminders, prompts and touchpoints. This section governs the reports and cards of section 6 that the bot sends without a request.

### 10.1 Schedule

| Item | Default | Offered when | Send time (account zone) | Sent only if | Cap |
|---|---|---|---|---|---|
| Daily summary image with the evening wrap-up (IMG-03) | Off | After 3 days with records | The evening wrap-up time (PRODUCT_SPEC R-DAY-7, default 21:00). When enabled, the wrap-up is sent as this image with the wrap-up text as its caption and the wrap-up keyboard: one message, one budget unit, never a second message | At least one record that local day, or something is open for the wrap-up | 1 per day |
| Weekly report (IMG-04, with at most one pattern sentence) | Off | At the end of the first week with 3 or more recorded days | The default of PRODUCT_SPEC R-INS-4: the first day of the locale's week, 09:00 | At least one record in the reported week | 1 per week |
| Monthly report (IMG-05) | Off | When a month first meets the gate | First day of the month, 10:00 | The gate of PRODUCT_SPEC R-INS-11: at least one enabled domain with a record on at least 14 days of the month | 1 per month |
| Milestone card (IMG-14) | Never sent proactively (section 6.15). A milestone is one line inside the weekly report | n/a | n/a | n/a | n/a |
| Refill notice (text; the IMG-15 card only on request from the notice) | On for each medication with a threshold | When the user sets a threshold | When the count reaches the threshold; outside quiet hours. The proactive notice is a text message (BOT_UX section 10.5); its "Show card" button sends IMG-15 as an Outcome | The count is at or below the threshold and the user has not acknowledged | Repeats every 72 hours, at most 3 times; stops on "Got it" or a count update |
| Water goal picture (IMG-08) | Off | In water settings | When the goal is first reached that day | Goal reached | 1 per day |
| Pattern card (IMG-13) | Never scheduled on its own | n/a | Inside the weekly report only | Section 8.5 | 1 per week |

### 10.2 Rules

| ID | Requirement |
|---|---|
| VA-PS-01 | Each scheduled report is switched on by the user individually. Every scheduled message carries a one-tap "Stop these" control that turns that report type off and confirms by editing the same message. |
| VA-PS-02 | Budget: the single daily budget of PRODUCT_SPEC section 3.2 and R-SCH-22 (default 4, range 0 to 12) covers the nudges of BOT_UX.md and the reports of this section together; this document sets no budget of its own. Inside it, at most 1 unprompted personal image per account per local day. Dose reminders, their follow-ups and the catch-up digest are outside the budget. A refill notice counts as one unit. A report that consists of several messages (the monthly album and its text, section 6.6) is one unit of the budget and (pages + 1) messages against the platform send limits. |
| VA-PS-03 | Precedence on one local day: monthly report, then weekly report, then the daily summary image. When the monthly report is sent, the weekly report moves to the next day. On a day with a weekly or monthly report the evening wrap-up is sent as text, without the daily summary image. Items that lose to the budget are skipped, not queued, except the weekly report, which may move by one day once. |
| VA-PS-04 | Nothing in this section is sent during the user's quiet hours. An item that falls due in quiet hours is sent when they end if that is within 12 hours, otherwise it is skipped. |
| VA-PS-05 | No report is sent for a period without records. The first time a weekly report is skipped for that reason the bot sends one short neutral line ("No records last week, so there is no report. Reports resume when there are records."). After that it stays silent until there are records. |
| VA-PS-06 | Adaptive pause: when three consecutive reports of one type are each followed by no interaction with the bot or the Mini App within 24 hours, that report type is paused and one text line says so with a "Resume" control. Interaction is the only available signal, because a bot receives no read receipts (P25). The pause never applies to dose reminders. |
| VA-PS-07 | Scheduled images require the "Images in chat" consent (VA-PV-06). Without it the text equivalent is sent with a button that opens the same report in the Mini App. |
| VA-PS-08 | Scheduled images are rendered in the 30 minutes before their send time in the report class and sent with the deterministic per-account offset of 0 to 600 seconds (PRODUCT_SPEC section 3.2, VA-FB-04). A report that is not rendered by its send time is sent as text. |
| VA-PS-09 | A report has the idempotency key (report type, period, account). A time-zone change, a restart or a retry never produces a second report for the same period. |
| VA-PS-10 | When the user blocks the bot, all scheduling of this section stops; it resumes automatically on unblock (PRODUCT_SPEC R-IDC-18) without sending the reports that were skipped. |
| VA-PS-11 | The delivery state of every scheduled item (sent, skipped: no records, skipped: budget, skipped: quiet hours, skipped: not rendered, paused) is recorded without content and kept for 90 days. |
| VA-PS-12 | No scheduled message contains urgency, countdowns, a reference to what the user failed to do, or a comparison with other people. Forbidden-phrase lint. |
| VA-PS-13 | Minimum-data gates are mentioned only inside a message the user asked for or inside a report, as a count ("3 more nights of sleep check-ins until the first sleep summary"). They are never sent as their own message. |

## 11. Acceptance: test layers and hand-offs

### 11.1 Test layers

The seven layers of research brief 04 section 12 apply to every template. The table adds the layers this document needs and maps requirement groups to them.

| Layer | What it checks | Requirements |
|---|---|---|
| SVG text golden | Exact scene output per fixture: light and dark; English, Ukrainian and a right-to-left pseudo-locale; empty, typical and extreme data; privacy levels L1 and L2; discreet on and off | VA-ID, VA-TY-04, VA-LA-02, VA-BL-08, all VA-IMG |
| Scene lints | Contrast, minimum text, stroke and cell sizes, text inside slots, palette whitelist per template (for example no `attention` outside IMG-15, no `critical` anywhere), a non-colour channel on every status mark | VA-PR-06, VA-PR-10, VA-ID-06, VA-CO-02 to VA-CO-05, VA-TY-01, VA-LA-01 |
| Palette validation | The `dataviz` validator on the domain bases (all-pairs, both themes) and the heat steps (ordinal) | VA-CO-01 |
| Property tests | Determinism, hostile strings, overflow, canaries for privacy levels, metric thresholds, insight minimums | VA-PR-11, VA-PV-01, VA-IMG-17a, VA-LA-03, VA-ME-03, VA-IN-12 |
| Metric oracles | Table and property tests for every metric of section 7; equality between pure functions and SQL read models | VA-ME-01, VA-ME-10 to VA-ME-63 |
| Cross-representation equality | For a fixture account, the same numbers in the image view model, the caption, the Mini App API response and the PDF data file | VA-PR-01, VA-MA-01, VA-DOC-01a |
| Text-equivalent golden | Caption and text view per fixture | VA-PR-09, VA-TX |
| Catalogue lints | Forbidden phrases in every namespace and language; sentence case; milestone kinds; the fixed non-causal sentence | VA-PR-02, VA-PR-08, VA-WR-01, VA-TY-03, VA-IN-10, VA-IN-11, VA-ME-21, VA-PS-12 |
| Registry assertions | Closed list of insight rules and their variable roles; closed list of milestone kinds; each template's maximum privacy level | VA-IN-13, VA-IMG-14a, VA-PV-01 |
| Pixel golden | Decoded-pixel hashes from the pinned rasteriser and font pack, in the production image | Drift of renderer and fonts |
| Schedule simulation | A simulated clock over 60 days with several personas: budgets, precedence, quiet hours, gates, adaptive pause, idempotency, zone change | VA-PS-01 to VA-PS-13 |
| Fault injection | Render queue stopped, rasteriser timeout, rejected `file_id`, global asset without a cached `file_id` (first use uploads, VA-BL-11) | VA-PR-12, VA-FB, VA-IMG-01a, VA-IMG-01b, VA-BL-11 |
| Mini App component tests | Scene painter output, hit targets, keyboard path, table toggle, states, extreme Telegram themes | VA-TG-02, VA-MA |
| Contact-sheet review | All fixtures at 360 px display width, light and dark, colour and greyscale, judged by a reviewer who did not write the template | VA-ID-05, VA-BL-13, legibility and taste |
| Device spike | Research brief 04 spikes S4 (photo processing), S5 (stickers) and S9 (`file_id`), on Android, iOS, Desktop and Web | P7, A2, A3, A8 |

### 11.2 What this specification needs from the sibling documents

| Need | Owner |
|---|---|
| Snapshot types per domain with range, zone, as-of time and source revision; the scene, layout and emitter in `report-renderer`; rasteriser and Typst adapters in `infra`; the asset store and the `file_id` cache | ARCHITECTURE.md |
| A report preparation queue that maps to the notification classes of PRODUCT_SPEC section 3.2, the per-account render limits and the delivery log of VA-PS-11 | ARCHITECTURE.md |
| Stored history for goals (water, sleep, planned meals), pause intervals, the work-day calendar, tier and confidence on sleep episodes, evidence types on all entries, the zone identifier on every record | ARCHITECTURE.md, PRODUCT_SPEC.md |
| Account settings: `image_theme`, `names_in_images`, discreet mode, `protect_images`, units, first day of the week, report schedule and switches, neutral labels per medication | PRODUCT_SPEC.md, MINI_APP.md |
| Consent purposes `images_in_chat` and `cross_domain_patterns` in the consent ledger | ARCHITECTURE.md (security section), BOT_UX.md (flows) |
| The mood check-in flow with a mandatory valence tap and an optional energy tap; the "Text view", "Stop these", "Show pattern" and "Today picture" controls | BOT_UX.md |
| The dashboard views of section 9 inside the Mini App navigation; the scene painter; the API endpoints that return view models and scenes | MINI_APP.md |
| The copy catalogue namespaces for images, captions, reports, insights and the dashboard, and the per-language forbidden-phrase lists | BOT_UX.md, PRODUCT_SPEC.md |

## 12. Assumptions and spikes

Each assumption below is something a requirement relies on. Until confirmed, the fallback in the last column applies.

| ID | Assumption | How to confirm | Fallback |
|---|---|---|---|
| A1 | A bot cannot learn the user's chat theme for ordinary messages. | Read new Bot API releases; no test possible for an absence. | The `image_theme` setting. |
| A2 | Transparency is lost on the photo path. | Research brief 04 spike S4. | All cards are opaque already. |
| A3 | Clients round the corners of photo bubbles. | Spike S4, visual check on four clients. | The 48 px corner safe zone stays. |
| A4 | A typical Telegram dark chat background is near `#0e1621`. | Sample the default dark themes of the four clients. | The double sticker outline works on any background. |
| A5 | Photo uploads are recompressed to JPEG with a long side of about 1280 px. | Spike S4: record returned `PhotoSize` values and judge legibility. | Canvases are at most 1280 px and text is at least 40 px already; raise the minimum if the spike shows blur. |
| A6 | resvg honours tabular numerals through SVG attributes. | Spike S6. | Bake the feature into the static font instances. |
| A7 | IBM Plex Sans and Geologica cover every letter of the Ukrainian alphabet and every other shipped locale. | The font-pack coverage test (VA-TY-05). | Inter plus Noto Sans. |
| A8 | Generated TGS stickers play and loop on current clients. | Spike S5. | Static images only. |
| A9 | `savePreparedInlineMessage` works with inline mode disabled. | Research brief 02 spike 13. | The shareable card is sent to the user's own chat for manual forwarding. |
| A10 | Clients render `date_time` entities in the device zone and locale. | Research brief 02 spike 2. | Plain localised text times (VA-TX-04). |
| A11 | The rasteriser's PNG output carries no text metadata. | Spike S10 and a permanent test. | Strip chunks in the adapter. |
| A12 | The general Atwater factors (4, 4 and 9 kcal per gram) are the accepted convention for macro energy shares. | Read a primary nutrition reference before implementation. | Show grams only, without shares. |
| A13 | The epoch formula of the Sleep Regularity Index as written in research brief 05. | Read Phillips et al., Scientific Reports 7, 3216 (2017). | Do not ship the index; consistency figures remain. |
| A14 | An album's caption is shown from its first item and `editMessageMedia` accepts a cached `file_id`. | Spike S9, extended to albums and edits. | Send the monthly report as separate photos; send a new message instead of editing media. |

The ten spikes S1 to S10 of research brief 04 section 16 gate this specification as they gate that brief. A no-go on S1 to S3 switches the rasteriser to the Java2D fallback without changing any requirement here except colour emoji in user text.

## 13. Open questions

Each entry needs the owner's decision. The recommendation is what the plan assumes until then.

| # | Question | Recommendation |
|---|---|---|
| Q1 | Mood input: a five-step tap with an optional energy tap, or the inherited 1 to 10 level? | Five-step tap first, optional energy second. The blob supports both through the mappings of section 4.2. PRODUCT_SPEC.md records the choice. |
| Q2 | Accept that the blob shows mood only and that daily status is the halo around it? | Yes. It is the rule that keeps the picture truthful and free of shaming. |
| Q3 | Typefaces: IBM Plex Sans with Geologica (this document), or Inter with Noto (research brief 04)? | Plex Sans with Geologica, subject to assumptions A6 and A7. Inter with Noto remains the tested fallback. |
| Q4 | Accept the palette: Spruce, Tide, Rye, Beet, Dusk on a porcelain or deep blue-green surface, with no red or green judgement colours? | Yes. The values are validated; the names and exact hues can still be tuned if the validator is re-run. |
| Q5 | Consistency: weekly counts only, no consecutive-day run of any kind, nothing on medication, and stop surfacing the inherited "all doses taken" streak? (Changed by the review: the first draft proposed runs with a grace day; PRODUCT_SPEC Q-28.) | Yes. Remove `Adherence.streak` from every surface in ROADMAP slice G9.4. |
| Q6 | Insights: keep every medication measure out of both sides of every cross-domain comparison? (Changed by the review: the first draft kept it out of the explanatory side only; PRODUCT_SPEC Q-27.) | Yes. Revisit only with legal advice on the medical-device boundary. |
| Q7 | Require a separate opt-in before any cross-domain comparison? | Yes, off by default, one switch. |
| Q8 | Show the Sleep Regularity Index and social jet lag only in the Mini App and the PDF? | Yes. |
| Q9 | Direction of time axes in right-to-left locales, and the first shipped locales. | English and Ukrainian first; time axes left to right everywhere until a native-speaker review. |
| Q10 | Hydration as a vessel instead of a ring? | Vessel. |
| Q11 | Reminder images: withdrawn by the review (section 6.2; ROADMAP conflict X-27). Does the owner want them back as an opt-in enhancement? | No. A reminder stays a text message. Re-entry conditions are listed in section 6.2. |
| Q12 | Record summary delivery: Mini App download by default, chat on request? | Yes, as research brief 06 recommends for exports. |
| Q13 | Shareable card: may it carry the word "Dosegram", and should medication facts stay off its whitelist? | Wordmark yes, no link or handle; medication facts stay off. |
| Q14 | Milestone cards: withdrawn as a proactive message by the review (section 6.15; PRODUCT_SPEC R-GAM-4). Keep IMG-14 as a Later, on-demand item? | Yes, Later and on demand only. |
| Q15 | The body domain has no colour and is limited to neutral trend lines. Which measurement kinds ship first? | Weight only in the first release that includes the domain; others after the device-scope review named in research brief 06 Q11. |
| Q16 | When to try message widgets for the vessel and the mood picker? | After the image pipeline is green and the client-coverage spike of research brief 02 has run. The scene emitter already stays inside the widget SVG subset. |
| Q17 | Default image theme, names in images and `protect_content` (research brief 04 Q4 to Q6). | Dark; names off until enabled once; `protect_content` on with a setting. |

## 14. Sources

All checked on 2026-11-12.

| Source | URL or path | Used for |
|---|---|---|
| Research brief 01, repository inventory | `docs/research/01-repo-inventory.md` | Existing adherence code, module names, inherited rules |
| Research brief 02, Telegram platform | `docs/research/02-telegram-platform.md` | P11, P14 to P18, P22 to P24, Mini App theme and events, rate limits |
| Research brief 03, health application teardown | `docs/research/03-health-app-teardown.md` | Feature patterns, dark patterns to refuse, regulatory lines |
| Research brief 04, image rendering | `docs/research/04-image-rendering.md` | Pipeline, canvases, blob parameters, fonts, spikes, P1 to P10, P19, P20 |
| Research brief 05, sleep and presence | `docs/research/05-sleep-and-presence.md` | Tiers, quiet window, sleep metrics |
| Research brief 06, privacy, security and compliance | `docs/research/06-privacy-security-compliance.md` | P13, P21, consent purposes, data classes, discreet mode, export delivery |
| Existing design | `docs/BOT_UX_PLAN.md` section 6, `docs/DESIGN.md` section 7.3, `core/src/dosecord/core/domain/Adherence.scala` | Adherence definitions and infographic rules that are inherited |
| Telegram Bot API 10.4 | https://core.telegram.org/bots/api | P1 to P6, P8, P9, P12, P20, P22 (through the research briefs) |
| Telegram stickers | https://core.telegram.org/stickers | P10 |
| Telegram Bots FAQ | https://core.telegram.org/bots/faq | P11 |
| Telegram Mini Apps | https://core.telegram.org/bots/webapps | P14, P16, P17, P24 |
| Telegram widgets | https://core.telegram.org/bots/widgets | P19 |
| Telegram formatting options | https://core.telegram.org/bots/api/formatting | P23 |
| Telegram Bot Platform Developer Terms | https://telegram.org/tos/bot-developers | P15, P21 |
| Telegram FAQ and Privacy Policy | https://telegram.org/faq , https://telegram.org/privacy | P13, P14 |
| Google Fonts metadata, IBM Plex Sans | https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexsans/METADATA.pb | Licence OFL; subsets Latin, Latin Extended, Cyrillic, Cyrillic Extended, Greek, Vietnamese; axes width and weight (read directly in this run) |
| Google Fonts metadata, IBM Plex Sans Arabic and Hebrew | https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexsansarabic/METADATA.pb , https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexsanshebrew/METADATA.pb | Licence OFL; Arabic and Hebrew subsets (read directly in this run) |
| Google Fonts metadata, Geologica | https://raw.githubusercontent.com/google/fonts/main/ofl/geologica/METADATA.pb | Licence OFL; subsets Latin, Cyrillic, Greek, Vietnamese; weight axis (read directly in this run) |
| Google Fonts metadata, Inter and Noto Sans | https://raw.githubusercontent.com/google/fonts/main/ofl/inter/METADATA.pb , https://raw.githubusercontent.com/google/fonts/main/ofl/notosans/METADATA.pb | Fallback families: licence OFL, Cyrillic subsets (read directly in this run) |
| Palette validator | The `validate_palette.js` script of the `dataviz` skill, run in this session | Every COMPUTED colour figure |

### 14.1 Not checked in this run

1. Nothing was rendered. No SVG, PNG, sticker or PDF was produced, so every layout sketch is a design intent, and legibility after Telegram processing is unproven (assumptions A2, A3 and A5).
2. The palette was validated numerically, not viewed on devices. The hues may need tuning after the first contact sheet; any change must re-run the validator.
3. Font metadata confirms licences and declared subsets only. Glyph-level coverage, tabular numerals and rendering quality through resvg are untested (A6, A7).
4. The mood blob geometry was not prototyped beyond the scratch experiment of research brief 04. The phase seeds, tension values and face proportions are starting values for the first golden review.
5. The sibling documents were written in parallel and were not read. Terms that may differ: the mood input scale, the names of settings and consent purposes, the Mini App navigation, the names of domain keys.
7. After the cross-document review of 2026-11-12 this document was amended in place (REVIEW.md lists every change): reminder images (IMG-01) and consecutive-day runs were withdrawn, the insight rules lost every medication outcome and were renamed from `INS-nn` to `IR-nn`, the mood labels and the 1 to 5 display scale follow PRODUCT_SPEC, and budget, priority and jitter refer to PRODUCT_SPEC section 3.2. The amendments were targeted edits; sketches and example captions elsewhere in the document may still show a superseded word or number, and ROADMAP slice G0.1 is the control.
6. The legal reading behind the wording rules (sections 7 and 8) follows research briefs 03 and 06, which are information and not legal advice. A legal review of the insight wording and of the records summary is needed before a public release.
