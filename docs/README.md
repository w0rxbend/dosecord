# Dosegram: plan and specification

Status: in force since 2026-11-13. This file was prepared on 2026-11-12 as the entry point of the proposal for the owner's review; the owner approved the plan and treats every recommendation in it as adopted, and roadmap slice G0.2 promoted the document set from `docs/dosegram/` to `docs/` ([ADR-014](adr/ADR-014.md)). [`AGENTS.md`](../AGENTS.md) and [`ROADMAP.md`](ROADMAP.md) are the sources of truth for the repository; the Dosecord roadmap and design are under [`archive/`](archive/). The text below is kept as written for the review: where it says "proposal", "nothing is in force" or "no code was written", read it as the state before approval. The current state of `main` is in `AGENTS.md` and in ROADMAP Appendix B.

This file is the entry point. It summarises the change of direction, says in which order to read the documents, lists the decisions that matter most, states plainly where the owner's request could not be met as asked, collects every open question of the set with a recommended answer, and lists what the owner must do before work can start.

Evidence labels in this file. A statement about the Telegram platform carries VERIFIED with its source and the date it was checked, or ASSUMPTION. "VERIFIED (brief 02)" means that research brief 02 checked the source on 2026-11-12. "VERIFIED (review)" means that the cross-document review checked the source on 2026-11-12. Neither was fetched again when this file was written.

## 1. Executive summary

**What changes.** Dosecord, a Scala rewrite of a Discord medication-reminder bot, becomes Dosegram, a Telegram-native personal health record keeper. Discord is dropped entirely, together with the unused Zulip and Matrix profiles. Telegram is the only chat platform. The product has two surfaces that share one backend: a private-chat bot for the daily loop, and a Telegram Mini App for configuration and exploration.

**What stays.** The core idea (record medication intake, remind, show statistics) and the tested engine behind it: the Scala 3 process on PostgreSQL, the pure occurrence state machine, the daylight-saving-safe materialiser, the reminder loop, the outbox and the console adapter that carries the simulation. About 85 percent of the existing main code has no Discord symbol (research brief 01).

**What is added.**

| Area | Content | Wave |
|---|---|---|
| Medication on Telegram | Onboarding with consent, registry and schedules, reminders answered in one tap on the message, Undo and Correct, Today card, quiet hours, discreet mode, text statistics, export and deletion | MVP |
| Mini App | Medication registry and schedule builder with a preview, Today and day detail, settings and consent view, export download | MVP |
| Generated images | Server-rendered cards with a text equivalent each: adherence calendar, medication strip, hydration vessel, mood blob, daily summary | MVP |
| Water and mood | One-tap capture, own goal, optional nudges inside a daily budget, the mood blob | MVP |
| Sleep and food | Sleep check-ins with a timeline card; a meal journal | V1 |
| Insights | Weekly report, comparison with the user's own previous weeks, weekly consistency counts | V1 |
| Import, measurements, caregivers, monthly report | Apple Health import for sleep and water, a measurements and symptom diary, caregiver alerts | V2 and later |

**What the product is not.** It records what the user did and reminds the user of what the user configured. It never prescribes, advises a dose, interprets a value, names a condition or computes a health score. No third-party model processes user content in any planned wave.

**How it is delivered.** Thirteen milestones, G0 to G12, cut into 132 slices of one or a few sittings each, with 18 owner gates. The order is: reconcile the documents; retire Discord and rename; a walking skeleton that proves a Telegram webhook, one reminder, one answer, one rendered image and one Mini App screen end to end on a staging bot with synthetic data; the data-protection baseline (row-level security, encryption of every content store, consent ledger, hard delete); the medication MVP in chat, released as `0.1.0` after the owner has used it for fourteen days; then the Mini App, images, water and mood. The plan can stop after `0.1.0`, after the MVP wave or after V1 and leave a whole product each time.

**What the review changed.** The six documents were written in parallel. A cross-document review raised 64 findings (5 blockers). All are dispositioned in [`REVIEW.md`](REVIEW.md); the design documents and the roadmap were amended in place. Five changes matter most to the owner: nobody but the owner's allowlisted Telegram ids can create an account before the invite gate exists; every store that holds message content is encrypted before real data enters; no insight may involve a medication measure and nothing on medication is counted or rewarded; the sleep estimate is built only if a replay on the owner's own data shows that it would appear often enough; and the plan was cut where it carried work that one owner and 50 invited accounts do not need.

**What the owner decides first.** Eight questions block the first slice (section 5.1). The most consequential one is whether sleep without "last seen" is acceptable (section 4).

## 2. Reading order

| Order | Document | What it is for |
|---|---|---|
| 1 | This file | Orientation, decisions, constraints, questions, owner actions |
| 2 | [`PRODUCT_SPEC.md`](PRODUCT_SPEC.md) | What the product does, module by module, with requirement ids, waves, non-goals and acceptance criteria. Read sections 1 to 7 first; section 8 is reference |
| 3 | [`ROADMAP.md`](ROADMAP.md) | Delivery order, slices, gates, the migration from Dosecord, the conflict list (section 1.9), owner actions (1.10) and questions (10) |
| 4 | [`BOT_UX.md`](BOT_UX.md) | Every chat flow, the command registry, message operations, copy keys and the status vocabulary |
| 5 | [`MINI_APP.md`](MINI_APP.md) | Mini App screens, launch and session, design system, frontend stack and client budgets |
| 6 | [`ARCHITECTURE.md`](ARCHITECTURE.md) | Modules, the Telegram adapter, the reminder engine, the API resource list, the data model, security and deployment |
| 7 | [`VISUALS_AND_ANALYTICS.md`](VISUALS_AND_ANALYTICS.md) | Visual identity, the mood blob, the image catalogue, metric definitions and the insight rules |
| 8 | [`REVIEW.md`](REVIEW.md) | The 64 review findings with their dispositions, and what is known to be left for slice G0.1 |
| Reference | [`research/`](research/) | Six briefs that the documents cite: 01 repository inventory, 02 Telegram platform, 03 health-application teardown, 04 image rendering, 05 sleep and presence, 06 privacy, security and compliance |

Precedence when two documents disagree (ROADMAP section 0.1): ROADMAP for order and status; PRODUCT_SPEC for behaviour, defaults and waves; ARCHITECTURE for structure, data model and API conventions; BOT_UX for chat flows and copy; MINI_APP for screens and client budgets; VISUALS_AND_ANALYTICS for visuals, metrics and insight rules. Three tables have one owner and are only referenced elsewhere: the notification policy (PRODUCT_SPEC 3.2), the consent purposes (PRODUCT_SPEC R-IDC-6) and the API resource list (ARCHITECTURE 9.5).

## 3. The ten most consequential decisions

| # | Decision | Rationale | Rejected alternative | Where |
|---|---|---|---|---|
| 1 | Keep the Scala engine and the adapter boundary; replace Discord by an in-repository thin Bot API client; keep the console adapter as the test transport | The engine is tested and has no vendor symbol; a narrow own client gives control of logging, which matters because the bot token is part of every request URL (VERIFIED (brief 02), https://core.telegram.org/bots/api) | A rewrite in another language; a third-party Telegram library | ARCHITECTURE D1, D2, D4 |
| 2 | The chat is for the daily loop, the Mini App for anything with a form, a calendar or a chart; both call the same use cases; every safety and data-rights function also works in chat | One write path and one set of invariants; the product never depends on a WebView working | A chat-only product; a Mini-App-first product | PRODUCT_SPEC 3.1; BOT_UX D1; ARCHITECTURE D7 |
| 3 | Sleep never uses Telegram presence. Check-ins are the sleep feature. The opt-in quiet-window estimate is built only after a coverage replay on the owner's own data | A bot cannot read last seen or online status (section 4). As specified, the estimate appears only on nights with several interactions before sleep and after waking | A user-authorised Telegram session held by the server: a full-account credential for two timestamps a day | PRODUCT_SPEC 8.11; ROADMAP G7.4b, CP7-r |
| 4 | The non-clinical boundary is enforced by closed registries: no advice, thresholds or scores; no insight with a medication measure on either side; no counter, run, milestone or reward on medication | The repository rule; a link between intake and mood or sleep reads as evidence about a treatment; a reward for tapping an answer pays for false records | Dose punctuality as an insight outcome; runs with a grace day (both were in a first draft) | PRODUCT_SPEC N2 to N11, R-INS-9, 8.14; ROADMAP X-23, X-24 |
| 5 | The data-protection baseline lands before real data: row-level security, per-account encryption of every store that holds content, a consent ledger keyed by 13 purposes, hard delete with a purge list; an owner allowlist until the invite gate | Telegram's developer terms require encryption at rest with separately stored keys (VERIFIED (brief 02), https://telegram.org/tos/bot-developers); the schema can be reshaped in place only while no real data exists; a stranger can find a bot's username | Encrypting medication columns only; a rule of "synthetic accounts only" | ARCHITECTURE D8, section 15; ROADMAP G1.6, G2.2 to G2.4b, G2.8, G2.17 |
| 6 | Medication first. A reminder is a text message. One sender owns all outbound calls with priorities. Every optional message shares a daily budget of 4, is silent and respects quiet hours | About 30 messages per second per bot and 1 per second per chat (VERIFIED (brief 02), https://core.telegram.org/bots/faq); a muted bot also silences medication reminders; a reminder must be readable in a notification preview | Images on reminders; one schedule of prompts per domain without a common budget | PRODUCT_SPEC 3.2; BOT_UX D5, D9; ARCHITECTURE D5 |
| 7 | Images are rendered on the server as SVG and rasterised by a native child process; every image has a text equivalent from the same data; personal images need the `images_in_chat` consent, omit medication names by default and are sent as protected content | Images are the one surface every Telegram client shows the same way; Telegram stores every sent image, and a bot can delete its own message only within 48 hours (VERIFIED (brief 02), https://core.telegram.org/bots/api) | Headless browser rendering; client-drawn widgets first; names in images by default | ARCHITECTURE D11; VISUALS D2; ROADMAP G1.9, G4.6 |
| 8 | The Mini App authenticates once with Telegram's Ed25519 signature over `initData`, then uses an opaque server session; no health data in any client store; charts are scenes laid out by the server and painted by one client painter; Svelte 5 with TypeScript, confirmed or reversed by a measured bundle budget | A stolen bot token cannot forge a login; deletion and encryption stay under the server's control; one layout engine for chat images and app charts | HMAC with the bot token as the primary path; client-side chart components; Scala.js | ARCHITECTURE D6, 9.5.1; MINI_APP 3, 5.6, 12; ROADMAP X-30 |
| 9 | Food is a journal; mood is five steps with an optional energy tap; the mood blob shows mood only and daily status is a halo around it; no model processes user text, voice or photos | Photo calorie estimates were about 33 percent low in the only controlled test found (research brief 03, secondary source); a blob whose face reacts to missed doses would shame | A calorie counter; a 1 to 10 mood picker; an optional model-based estimate | PRODUCT_SPEC 8.9, 8.12, Q-21; VISUALS D3, D4; ROADMAP X-01, X-10 |
| 10 | Delivery is a walking skeleton first, then strict gates: milestone-entry rule, schema freeze before real data, a fourteen-day owner-use gate that measures the system and not the person, a traceability matrix from every acceptance id to one slice, and scope cut to what one owner and 50 invited accounts need | The riskiest unknowns (native rasteriser, signed launch data, undocumented Bot API behaviour) can invalidate module boundaries; three bullets per slice cannot stand for about 870 criteria | Reshaping the schema before the first live reminder; a release gate that fails on one forgotten dose | ROADMAP 0.5, 3.1, 4.2, G2.23, RQ-23 |

## 4. Where the owner's request was changed because of a constraint

Each row says what was asked, why it cannot be done as asked, and what the plan does instead.

| Asked for | Constraint | What the plan does instead |
|---|---|---|
| A sleep tracker derived from the user's "last seen" | Not buildable. A bot cannot read last seen, online status or read receipts: no such field exists in the Bot API or the Mini App API. VERIFIED by absence (brief 02), https://core.telegram.org/bots/api and https://core.telegram.org/bots/webapps, 2026-11-12; Telegram's FAQ states it positively ("Bots cannot see your last seen time", https://telegram.org/faq, as quoted in PRODUCT_SPEC PC-01). The only technical route is a user-account session, which needs the user's full-account credential (research brief 05 section 4) | Sleep is recorded with two taps, "Going to sleep" and "I'm up", plus typed retrospective entry. As an opt-in, Dosegram can notice the quiet window between the user's own interactions with Dosegram and ask "at most N hours of quiet. Was that sleep?". This is not a replacement for last seen: by its own formula the question appears only on nights with about three interactions in the hour before sleep and several after waking, and a user who only answers reminders never sees it. The plan therefore measures first: slice G7.4b replays the owner's own interaction times and gate CP7-r decides whether the estimate is built at all. PRODUCT_SPEC section 8.11 and Q-01; ROADMAP G7 |
| "The bot should do everything" | A chat product has no sensors. Dose advice, diagnostic thresholds, risk statements and fertility status are treated as medical-device functions in EU and US guidance (research briefs 03 and 06, secondary reading; ASSUMPTION until counsel confirms, ROADMAP G11.6c and G11.17) | Everything that can be recorded, reminded, counted and drawn is in scope, staged over four waves. PRODUCT_SPEC section 5 lists 21 refused items with reasons |
| The feature set of Samsung Health and similar applications | Steps, heart rate, sleep stages, snoring, blood oxygen and energy or readiness scores depend on wearable sensors, and a bot or Mini App has no bridge to on-device health stores (research brief 03; VERIFIED by absence (brief 02), https://core.telegram.org/bots/webapps) | The manual and reminder loops are rebuilt from the same ideas, with no asset or branding copied. PRODUCT_SPEC section 4.1 gives the disposition of every feature group: adopted with its wave, later, or refused. Sensor data may be imported from a file later and is shown with its source. No score is imitated |
| Reliable reminders | A reminder is an ordinary message. There is no alarm-grade delivery and no delivery or read signal, and the bot cannot see that a chat is muted. VERIFIED by absence (brief 02), https://core.telegram.org/bots/api | Best-effort delivery, said at onboarding; one follow-up; a truthful "No record" state when delivery cannot be shown; a test reminder that asks the user whether the phone alerted |
| No tap is lost in an outage | Telegram keeps undelivered updates for at most 24 hours, and for webhooks it gives up "after a reasonable amount of attempts". VERIFIED (review), https://core.telegram.org/bots/api/methods/setWebhook.md, 2026-11-12. The retry horizon is undocumented: ASSUMPTION that it may be much shorter | The guarantee is not promised. After every recorded outage the digest says that taps may not have arrived, until spike G1.1 has measured the horizon |
| Images as the status language, including on reminders | A reminder's state machine edits text; a photo caption is limited to 1,024 characters against 4,096 for text. VERIFIED (brief 02), https://core.telegram.org/bots/api. A photo cannot be uploaded without sending it to a chat, so image assets cannot be prepared at start-up. VERIFIED (review), same source, 2026-11-12 | Reminders are always text. Images are sent on demand and as scheduled reports, each with a text equivalent. Shared assets are uploaded on first use and reused by `file_id` |
| A monthly report as one rich message | An album cannot carry buttons: `sendMediaGroup` has no `reply_markup`. VERIFIED (review), https://core.telegram.org/bots/api/methods/sendMediaGroup.md, 2026-11-12 | The buttons live on the text message that follows the album |
| Food intake with calories | The only controlled test found measured about 33 percent underestimation for photo-based calorie estimates (research brief 03, a conference abstract; a secondary source, not a platform fact) | A meal journal. Numbers are optional and typed by the user, later. No model estimates anything |
| "Enterprise grade" | A judgement, not a platform fact: 99.9 percent availability and a one-hour recovery time are not credible with one operator and one host (research brief 06 section 15) | Measurable targets that one operator can meet, with the controls that matter (isolation, encryption, consent, deletion, audit, backups, restore drill) before the first external user. General-availability targets presume managed PostgreSQL and a second person and are deferred |
| A caregiver chosen from the Telegram contact list | The user picker returns an identifier the bot may be unable to use, and a bot cannot message a person who has not started it. VERIFIED (review), https://core.telegram.org/bots/api/types/SharedUser.md, 2026-11-12 | The owner always sends an invite link; the picker can only bind the invitation to one person |
| Knowing the user's time zone | Telegram does not give a bot the user's time zone. VERIFIED by absence (brief 02), https://core.telegram.org/bots/api | The product asks: one tap in a Mini App probe, or a city search in chat |

## 5. Open questions, consolidated

Every open question of the six documents is listed once. Identifiers: `RQ-` ROADMAP section 10; `Q-nn` PRODUCT_SPEC section 14; `AQ` ARCHITECTURE section 23 (written there as Q1 to Q18); `OQ-` BOT_UX section 25; `MA-Q-` MINI_APP section 18; `VQ` VISUALS_AND_ANALYTICS section 13 (written there as Q1 to Q17). The research briefs have open questions of their own; those that affect the plan are carried by the questions below. The plan is written on the recommended answers, and each is provisional until the slice named in the last column starts (ROADMAP section 10.3). The groups are ordered by how much work they block.

### 5.1 Blocking: needed before the first slice (G0.1) can close

| # | Question | Recommended answer | Affects |
|---|---|---|---|
| 1 | Is sleep without last seen accepted: check-ins, plus an opt-in estimate that is built only if the coverage replay justifies it? (RQ-3, Q-01, AQ3) | Yes. The platform offers no alternative | PRODUCT_SPEC 8.11; ARCHITECTURE 14; ROADMAP G7 |
| 2 | Is the order accepted: rename, walking skeleton, medication in chat, Mini App, images, then water and mood as the end of the MVP wave? (RQ-1, Q-04) | Yes | ROADMAP 2.2, 3 |
| 3 | Must any existing database or deployment be preserved? (RQ-2, AQ2) | No. The baseline is reshaped in place until the freeze at CP2-f | ARCHITECTURE 10; ROADMAP G2.2 |
| 4 | Several accounts from the start, by invitation, capped at 50; or the owner only? (RQ-4, Q-03) | Several. Isolation, consent and deletion are then proven before growth, and the encryption work is needed either way | PRODUCT_SPEC 8.2, 8.3; ROADMAP G2, G11.1 |
| 5 | Remove `handle`, the password, challenge and recovery tables, and the unused habit tables? (RQ-8, AQ12, AQ13) | Remove all. The Telegram identity is the account | ARCHITECTURE 10.2; ROADMAP G2.2 |
| 6 | Mini App stack: Svelte 5 with TypeScript in `miniapp/` with its own toolchain? (RQ-5, AQ11, MA-Q-1) | Yes, confirmed or reversed by the measured budget in G3.2b | MINI_APP 12; ROADMAP G3.2 |
| 7 | Are native binaries (resvg) accepted in a service that is JVM-only today? (RQ-6, AQ4) | Yes, subject to spike G1.2; the fallback is a Java2D painter over the same scene tree | ARCHITECTURE 12; ROADMAP G1.2, G1.9 |
| 8 | When can the bot usernames, the dedicated owner account, the domain and the EU host exist? (RQ-7, AQ16) | Staging bot and any HTTPS origin as soon as G0.2 merges; the production bot only at G2.23 | ROADMAP 1.10 |

### 5.2 Needed during G0 and G1 (foundation and walking skeleton)

| # | Question | Recommended answer | Affects |
|---|---|---|---|
| 9 | Are the scope cuts of the review accepted (plain audit table and nightly dump before owner use, 412 and refetch instead of merge and change feed, load tests and the PDF deferred, public-beta work unscheduled)? (RQ-23) | Yes. Each cut has a re-entry condition | ROADMAP G2.16, G2.19, G3.5, G3.7, 9.1, G11 part B |
| 10 | Is the checkpoint model accepted: 18 gates that block and 29 reviews read once per milestone? (RQ-12) | Yes | ROADMAP 4.1 |
| 11 | Is the walking skeleton accepted before the data-protection baseline, behind the owner allowlist and on synthetic data? (RQ-11) | Yes | ROADMAP 4.2, G1.6 |
| 12 | Confirm that no third-party model processes user text, voice or photos in any planned wave; voice notes are not an input (RQ-15, Q-21, AQ8, OQ-5) | Confirm | PRODUCT_SPEC N10; ARCHITECTURE 13 |
| 13 | Repository name `w0rxbend/dosegram`, renamed after G0.4 merges? (RQ-16) | Yes | ROADMAP 1.3 |
| 14 | Keep the callback wire prefix `dc:` after the rename? (AQ1, OQ-12) | Keep. No user sees it and changing it touches 13 source files and 39 golden files | ROADMAP G0.4 |
| 15 | Which Telegram clients and which low-end Android phone can the owner test on? (RQ-17, MA-Q-8) | Name them before G1.1; untested client families are recorded as blocked, not passed | ROADMAP G1.1, G1.3, G3.2b |
| 16 | Hosting region (AQ10, first part) | One host in an EU region | ROADMAP G1.8 |
| 17 | Mini App launch data: Ed25519 signature only, and reject clients older than Bot API 8.0? (Q-15, AQ5, MA-Q-2) | Yes; measure refused launches during the invite-only phase before considering the HMAC fallback | ARCHITECTURE 9.2; ROADMAP G1.3, G1.12 |
| 18 | Menu button: the Mini App button per chat after onboarding, or one default-level button for every chat? The review received both recommendations (RQ-21, AQ18, OQ-16) | Per chat, so that a new user is not sent to a gate screen. Cost: one call per account when the label or URL changes | BOT_UX 6.3; ARCHITECTURE 7.9; ROADMAP G1.13 |

### 5.3 Needed during G2 (medication MVP in chat)

| # | Question | Recommended answer | Affects |
|---|---|---|---|
| 19 | Schema baseline freeze at CP2-f with tag `0.1.0-rc.1`, before real data enters? (RQ-13) | Yes | ROADMAP 3.4 |
| 20 | Where does the key-encryption key live, and who holds the offline copies? (AQ7) | A file secret outside the data volume; two sealed offline copies in separate places | ARCHITECTURE 15.2; ROADMAP G2.4 |
| 21 | Deletion and backups: accept "unreadable in live storage at once, gone from backups within 35 days", or a separate key store? (RQ-22, Q-25, AQ17) | Accept the 35-day statement for the invite-only phase and say it to the user; revisit before a public beta | PRODUCT_SPEC R-IDC-19; ARCHITECTURE SEC-06; ROADMAP G2.4b, G2.17 |
| 22 | Form of the consent step: the statement as the label of the first button (PRODUCT_SPEC), or the statement in the message with "I agree" and a confirming second tap (BOT_UX)? The two documents still disagree (RQ-25, OQ-9) | The BOT_UX form: a button label is short and may be cut by a client (ASSUMPTION); both forms keep two taps. Counsel confirms | PRODUCT_SPEC R-IDC-7; BOT_UX 5.5; ROADMAP G2.8 |
| 23 | Discreet mode as a forced choice at onboarding? (Q-05) | Forced choice; if a default is required, discreet | BOT_UX 5.6; ROADMAP G2.8 |
| 24 | Reminder defaults: one follow-up after 30 minutes, two reminders in total, instead of three reminders 10 minutes apart? BOT_UX still shows a third set of values (RQ-9, Q-07, OQ-7) | Change to one follow-up after 30 minutes | PRODUCT_SPEC 8.5; BOT_UX 9.1; ROADMAP G2.10 |
| 25 | Other defaults: nudge budget 4 (range 0 to 12), quiet hours 22:00 to 07:00, wrap-up 21:00, weekly report on the first day of the week at 09:00 (RQ-9, Q-23, OQ-7) | Accept as starting values; all are settings | PRODUCT_SPEC 3.2; ROADMAP G2.14, G5.2, G6.6, G9.2 |
| 26 | Is the division of labour accepted: interval, cycle and taper schedules and per-schedule policy are edited only in the Mini App? (OQ-1) | Yes; all safety and data-rights functions stay in chat | BOT_UX 3.2; ROADMAP G2.12 |
| 27 | Grouped reminder for doses due in the same minute: V1, with "Taken all N" and a two-tap Skip? (Q-22, OQ-4) | V1; the MVP sends one card per dose | BOT_UX 9.3; ROADMAP G2.10, G9.7 |
| 28 | Today card not pinned; no persistent quick-keys keyboard; word labels only on buttons? (OQ-10, OQ-2, OQ-13) | Yes to all three for the MVP | BOT_UX 7, 8; ROADMAP G2.13 |
| 29 | After an unblock, do reminders resume automatically with one digest? (Q-29, OQ-17) | Yes, with a "Pause reminders" button; a paused state the user did not choose loses reminders silently | BOT_UX 15.4; ROADMAP G2.15 |
| 30 | Which language does the owner-use gate need? (RQ-10) | English; Ukrainian before the first external user | ROADMAP G2.22, G2.23 |
| 31 | Keep `docs/MEDICATION_REMINDER_UX.md` as a living document? (RQ-18) | Keep it, subordinate to PRODUCT_SPEC, until G2 is green; then archive what PRODUCT_SPEC covers | ROADMAP G2.23 |

### 5.4 Needed during G3 to G6 (Mini App, images, water, mood)

| # | Question | Recommended answer | Affects |
|---|---|---|---|
| 32 | No health data in any client-side store, at the cost of no offline reading? (MA-Q-3) | Accept | MINI_APP 11; ROADMAP G3.2 |
| 33 | Session lifetime: 15 minutes idle and 12 hours absolute, or 12 hours for both if the relaunch is not seamless? (MA-Q-4) | Decide by the rule of MA-SESSION-4 after spike G1.3 | MINI_APP 3.6; ARCHITECTURE API-06; ROADMAP G3.9 |
| 34 | Medication names in images off by default, personal images as protected content, dark default image theme? (Q-06, VQ17) | Yes to all three | VISUALS 5; ROADMAP G4.6 |
| 35 | Visual identity: the five-hue palette without red or green judgement colours; IBM Plex Sans with Geologica; one shared design-token file; time axes left to right; English and Ukrainian first; no mascot (VQ3, VQ4, VQ9, MA-Q-9, Q-24) | Accept; the owner judges the identity on real cards at gate CP4-b | VISUALS 3; ROADMAP G4.1, G4.2 |
| 36 | Hydration drawn as a vessel, not a ring, on every surface? (VQ10) | Vessel. Settled by the review (X-12) unless the owner objects | VISUALS D7; ROADMAP G5.6 |
| 37 | Mood: five valence steps with an optional five-step energy tap, replacing the 1 to 10 level? (Q-08, OQ-3, MA-Q-5, VQ1) | Yes. Settled by the review (X-01) unless the owner objects | PRODUCT_SPEC 8.9; ROADMAP G6.1 |
| 38 | The blob shows mood only; daily status is the halo around it? (VQ2) | Yes | VISUALS 4; ROADMAP G6.2, G6.7 |
| 39 | Mood safety: a static, counsel-reviewed sentence and no analysis of notes for crisis language? (Q-17, OQ-6) | Yes, subject to counsel | PRODUCT_SPEC 8.9; ROADMAP G6.1, G11.6c |
| 40 | When does the first external user join: after the MVP wave or after V1? (RQ-14) | After the MVP wave if the owner wants feedback early; the Gate E1 work depends only on G6 | ROADMAP G11 part A |

### 5.5 Needed during V1 (sleep, food, insights)

| # | Question | Recommended answer | Affects |
|---|---|---|---|
| 41 | Which share of nights with a displayable estimate justifies building the quiet-window estimate? (RQ-26) | At least one night in three on the owner's own data; below that, defer it | ROADMAP G7.4b, G7.5, G7.6 |
| 42 | If built: the estimate off by default, raw instants kept 48 hours and an hourly profile 14 days? (Q-09, AQ6, OQ-15, MA-Q-7) | Yes. Settled by the review (X-08) unless the owner objects | PRODUCT_SPEC R-SLP-16; ROADMAP G7.5 |
| 43 | May the product show a general reference for adult sleep duration? (Q-10) | No. The user's own goal only | ROADMAP G7.3 |
| 44 | Food as a journal, text and one tap first, photos in V2? (Q-11, OQ-11) | Yes | PRODUCT_SPEC 8.12; ROADMAP G8.1, G12.6 |
| 45 | Weekly report: asked once at the first eligible week, nothing sent before the answer? (OQ-14) | Yes | BOT_UX 12.4; ROADMAP G9.2 |
| 46 | May any insight have a medication measure on either side of a comparison? (RQ-19, Q-27, VQ6) | No. A weaker rule goes to counsel first | VISUALS 8.3, 8.4; ROADMAP G9.3 |
| 47 | May a consecutive-day run, a counter on medication or a milestone message of its own exist? (RQ-20, Q-28, VQ5, VQ14) | No. Weekly counts only; milestones as one line of the weekly report | PRODUCT_SPEC 8.14; ROADMAP G9.4 |

### 5.6 Needed before the first external user and a public beta

| # | Question | Recommended answer | Affects |
|---|---|---|---|
| 48 | Who is the controller, where are they established, which regions launch first, and when is the legal review? (Q-02, AQ10, OQ-8) | State the controller before the first external user; invite-only, adults, EU or EEA and Ukraine, each region enabled only when cleared; counsel reviews the consent wording and the notice before Gate E1 | ROADMAP OA-11, G11.6c, G11.7 |
| 49 | After a material change of the notice, do medication reminders stop for an account that has not re-confirmed? (RQ-24) | Announce the change 14 days ahead with the re-confirmation button; after the effective date stop and say so plainly. Counsel confirms | PRODUCT_SPEC R-IDC-11; ROADMAP G11.2 |
| 50 | What happens when a user loses the Telegram account, or Telegram suspends the bot? (Q-13) | No second credential; promote regular export; state the limit in the notice | ROADMAP G11.2 |
| 51 | Beta service objectives for one host, with general-availability targets deferred? (AQ15) | Accept the beta targets | ARCHITECTURE 18.4; ROADMAP G11.10 |

### 5.7 Needed for V2 and later

| # | Question | Recommended answer | Affects |
|---|---|---|---|
| 52 | Which phone and wearable does the owner use? (Q-12) | Answer before V2; it decides the first importer | ROADMAP OA-12, G10.6 |
| 53 | Measurements diary in V2, weight first, values encrypted, no averages or trend lines on clinical measures? (Q-18, VQ15, AQ14) | Yes; its chat flow and screens are specified at CP9-d | PRODUCT_SPEC 8.15; ROADMAP G12.1 |
| 54 | Caregiver alerts on missed doses only, names off by default, in V2? (Q-19) | Yes | ROADMAP G12.3 |
| 55 | A separate opt-in before any cross-domain comparison? (VQ7) | Yes, off by default | ROADMAP G12.4 |
| 56 | Sleep Regularity Index and social jet lag only in the Mini App and the document? (VQ8) | Yes | ROADMAP G12.9 |
| 57 | Shareable card: the word "Dosegram" allowed, medication facts never? (VQ13) | Yes | ROADMAP G12.11 |
| 58 | Record summary delivered as a Mini App download by default? (VQ12) | Yes, when the document is built; it is deferred | ROADMAP 9.1 |

### 5.8 No scheduled slice depends on these

| # | Question | Recommended answer |
|---|---|---|
| 59 | Profiles for dependants under one account? (Q-14) | No |
| 60 | A period log? (Q-16) | Defer; if ever added, a calendar record only, never a prediction |
| 61 | Payments? (Q-20) | None through V2; the safety core stays free |
| 62 | A manual exercise or activity log? (Q-26) | Later, after V1, on the domain template |
| 63 | Reminder images back as an opt-in enhancement? (VQ11) | No |
| 64 | Message widgets for the vessel and the mood picker? (VQ16) | After the image pipeline is green and client coverage is known |
| 65 | App lock on a device without biometrics? (MA-Q-6) | A confirmation in chat per session; the app lock itself is deferred |
| 66 | Open Food Facts licence review and a contact mailbox? (AQ9) | Before the deferred barcode lookup, not earlier |

## 6. Owner actions before implementation can start

No agent may perform these (ROADMAP section 1.10). The first four are needed before any code changes.

| Order | Action | Needed by |
|---|---|---|
| 1 | Read this file, PRODUCT_SPEC sections 1 to 7 and ROADMAP sections 0 to 4; approve the plan or return it with changes | Everything |
| 2 | Answer the eight blocking questions of section 5.1 in an issue (OA-1). Say for the questions of sections 5.2 and 5.3 whether the recommendation stands or which ones to hold | G0.1 |
| 3 | Decide what happens to the planning branch `docs/dosegram-revamp-plan`: merge it, or use it as the base of G0.1 | G0.1 |
| 4 | Revoke the Discord bot token and delete Discord entries from every local `.env` and secret store (OA-2) | G0.3 |
| 5 | Create a staging bot in BotFather, with group joins, inline mode and bot-to-bot off; store its token as a secret file, never in chat or in an issue (OA-4). Name the Telegram clients and the low-end phone available for tests (OA-10). Provide the numeric Telegram user ids for the allowlist (OA-13) | G1.1, G1.6 |
| 6 | Provide any public HTTPS origin for the Mini App spike, then a domain, an IPv4 address and one EU host with off-host backup storage (OA-6, OA-7) | G1.3, G1.8 |
| 7 | Rename the GitHub repository after the rename slice merges (OA-3) | After G0.4 |
| 8 | Decide key custody: where the key-encryption key lives and who holds the two offline copies (OA-9) | G2.4 |
| 9 | Before the production bot: a dedicated Telegram owner account with two-step verification that is not the owner's daily account (OA-5) | G2.23 |

Later owner actions (registering the Mini App, controller and counsel, the owner's export file, the first activation of automatic account deletion) are OA-8, OA-11, OA-12 and OA-14 in ROADMAP section 1.10.

## 7. What was deliberately left out

**Refused, with no plan to build** (PRODUCT_SPEC section 5; ROADMAP section 9.2):

- Reading Telegram last seen or online status by any route.
- Dose calculation or advice, interaction checks, a drug database, thresholds or alerts on measured values, scored questionnaires, any statement about a condition or a risk, fertility features.
- A composite health, sleep or readiness score; sleep stages; step counting.
- Calorie estimation from photos; any third-party model on user content; an assistant that gives advice.
- Rewards, runs or counters on medication; guilt or urgency notifications; a mascot.
- Groups, channels, inline mode, leaderboards, challenges between users.
- Passwords, recovery codes or a second identity provider; a native companion application; profiles for dependants.
- Third-party analytics, advertising or fonts in the Mini App.
- Images on medication reminders.
- Discord, Zulip and Matrix adapters; a local Bot API server.

**Deferred, each with a re-entry condition** (ROADMAP section 9.1):

- The record summary as a PDF, load tests, the three-way merge of concurrent edits, the change feed and the offline action queue: cut by the review as not needed at the scale of one owner and 50 invited accounts.
- The quiet-window sleep estimate, if the coverage replay says no.
- Measurements and symptom diaries, caregiver sharing, cross-domain comparison cards, the monthly report and the mood calendar, meal photos, typed nutrition numbers, chain and taper schedules, the Sleep Regularity Index, an animated mood blob, a shareable card (milestone G12, re-cut at checkpoint CP9-d).
- Barcode lookup of packaged food; wearable connections and other health-platform exports; a manual exercise log; in-message widgets; app lock; a period log.
- Payments and Telegram Stars; readiness for the United States and the United Kingdom; managed PostgreSQL with failover and general-availability objectives; external certification.
- All work for a public beta (ROADMAP G11 part B) until the owner decides to attempt one.

**Not done in this run:**

- No product code, no rename, no change to any file outside `docs/dosegram/`, no Git operation.
- No build, test or container was run; figures about the existing code come from research brief 01.
- The surfaces of the measurements module (the chat flow for `/measure`, Mini App screens S-17 and S-18) are not specified; they are written at checkpoint CP9-d.
- The traceability matrix from every acceptance id to one slice does not exist yet; slice G0.1 produces it.
- The documents were amended by targeted edits after the review and were not re-read from start to end; [`REVIEW.md`](REVIEW.md) section 2 lists the known residue, and slice G0.1 is the control.
- Legal statements are engineering constraints taken from research, not legal advice.
