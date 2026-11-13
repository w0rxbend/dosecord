# Dosegram product specification and capability map

Status: In force since 2026-11-13: the owner approved the plan and roadmap slice G0.2 promoted this document from `docs/dosegram/` to `docs/` ([ADR-014](adr/ADR-014.md)). Where the text below says "proposal" or "proposed", read it as the adopted specification; an open question keeps the status its own section gives it. Dosecord documents named as inputs (`docs/DESIGN.md`, the old `docs/ROADMAP.md`, `docs/BOT_UX_PLAN.md`) are now under `docs/archive/`.
Date: 2026-11-12.
Scope: what Dosegram does for its users, module by module, with testable acceptance criteria.
Out of scope for this document: architecture and technology choices (ARCHITECTURE.md), the exact chat flows and copy (BOT_UX.md), Mini App screens (MINI_APP.md), card designs and metric formulas (VISUALS_AND_ANALYTICS.md), and delivery order (ROADMAP.md in this directory). Where this document names a release wave, it is a recommendation that the roadmap may refine without changing the requirement.

This document writes no product code and renames nothing. The repository is still named `dosecord` at the time of writing.

## 0. How to read this document

### 0.1 Evidence base

The specification is built on six research briefs in `docs/research/`. They are cited by number.

| Ref | File | Subject |
|---|---|---|
| R01 | `research/01-repo-inventory.md` | What the existing repository contains and what is reusable |
| R02 | `research/02-telegram-platform.md` | Telegram Bot API 10.4, Mini Apps, limits, terms |
| R03 | `research/03-health-app-teardown.md` | Feature landscape of health applications; regulatory lines |
| R04 | `research/04-image-rendering.md` | Server-side image, sticker and PDF rendering |
| R05 | `research/05-sleep-and-presence.md` | Telegram presence and the honest sleep design |
| R06 | `research/06-privacy-security-compliance.md` | Threat model, privacy, legal baseline |

The existing documents `docs/MEDICATION_REMINDER_UX.md`, `docs/adr/ADR-011.md` (time and daylight saving time) and `docs/adr/ADR-012.md` (dose lifecycle) remain the precise definition of the medication lifecycle. This specification restates their rules only where the Telegram direction changes or extends them, and says so.

### 0.2 Labels

| Label | Meaning |
|---|---|
| VERIFIED | A statement about an external platform that a research brief read in a primary source on 2026-11-12. The platform constraint table in section 6 gives the source URL. |
| ASSUMPTION | A statement about an external platform, a law or user behaviour that was not confirmed in a primary source. Every assumption that a requirement depends on is listed in section 13 with a way to confirm it. |
| PROPOSED | A value chosen by this specification (a default, a limit, a threshold). The owner may change it without changing the structure of the requirement. |

A requirement in this document never rests on an unlabelled claim about Telegram. Where a requirement cites `PC-nn`, the claim and its label are in section 6.

### 0.3 Identifiers

| Kind | Form | Example |
|---|---|---|
| Module | stable kebab-case id, chosen once, never renamed | `intake-log` |
| User story | `US-<MOD>-n` | `US-HYD-2` |
| Domain rule | `R-<MOD>-n` | `R-SCH-7` |
| Acceptance criterion | `AC-<MOD>-n` | `AC-SLP-4` |
| Platform constraint | `PC-nn` | `PC-01` |
| Cross-cutting requirement | `X-<AREA>-n` | `X-LAT-1` |
| Assumption | `A-nn` | `A-07` |
| Open question | `Q-nn` | `Q-03` |

Module prefixes: IDC `identity-and-consent`, LOC `localisation`, OPS `admin-and-ops`, REG `medication-registry`, SCH `schedule-and-reminders`, INT `intake-log`, DAY `daily-overview`, VIS `visual-reports`, HYD `hydration`, MOO `mood`, SLP `sleep`, NUT `nutrition`, VIT `vitals-and-symptoms`, INS `insights-and-correlations`, GAM `gamification`, SHR `sharing-and-caregivers`, IMX `import-export`, BIL `billing`.

### 0.4 Tiers and waves

MoSCoW tier: Must, Should, Could, Will not. A tier states importance inside the wave named next to it.

| Wave | Meaning | Audience |
|---|---|---|
| MVP | The smallest product the owner can use every day in place of the current reminder tool | The owner, then an invite-only group of at most 50 adult accounts (PROPOSED, R06 section 14.12) |
| V1 | All five tracking domains usable; weekly report; the product is complete enough for a public beta decision | Invite-only until the public-beta controls of R06 section 16.2 are closed |
| V2 | Sharing, imports, correlations, the diary of measurements | Public beta, subject to legal review |
| Later | Adopted in principle, not scheduled | n/a |

### 0.5 How an acceptance criterion is verified

Each criterion ends with one or more verification tags. A criterion is met only when the named kind of evidence exists.

| Tag | Evidence |
|---|---|
| [U] | Unit, table or property test on pure domain code |
| [T] | Flow transcript on the console adapter or the Telegram fake wire (the repository already has both harnesses, R01 sections 3.4 and 3.7) |
| [P] | Test against PostgreSQL in a container |
| [G] | Golden file: SVG text, pixel hash, text equivalent or transcript |
| [S] | The multi-day simulation (R01 section 3.7) |
| [M] | Mini App component or end-to-end test against the API |
| [L] | Live check on real Telegram clients, recorded as a smoke transcript |
| [O] | Operational or continuous-integration gate (lint, canary scan, metric, alert) |

## 1. Objective

### 1.1 Problem statement

How might a person who already lives in Telegram record and be reminded of the things that keep them well (medication first, then water, food, mood and sleep) in one tap, without installing another application, and see an honest picture of what they recorded?

### 1.2 What Dosegram is

Dosegram is a Telegram-native personal health record keeper and reminder service. It consists of:

1. A private-chat bot that sends reminders the user configured, records the user's answers in place, and replies with short text and generated images.
2. A Telegram Mini App that manages the medication registry and schedules, shows history and charts, and holds settings, consent, export and deletion.
3. A backend that owns all state, schedules every reminder, computes descriptive statistics, and renders images (cards, charts, the mood blob) and documents.

Discord is dropped entirely. Telegram is the only chat platform. The console adapter remains as a test transport only (R01 section 6.1).

### 1.3 What success looks like

1. The owner uses Dosegram for their own medication for 14 consecutive days without falling back to another reminder tool.
2. A due dose is answered with one tap on the reminder message, and the message itself shows the final state.
3. Water, mood, sleep and food can each be recorded with one tap from the chat.
4. Every number the product shows is something the user recorded or a plain count, duration, percentage or regularity measure derived from it. Nothing is presented as measured when it was estimated.
5. The user can export everything and delete everything by themselves.

Measurable targets are in section 10.

### 1.4 Constraints that shape the product

Five verified platform facts define the product more than any design preference. They are restated in section 6 with sources.

1. A bot cannot read a user's "last seen" or online status (PC-01). The owner's sleep idea, taken literally, cannot be built on the Bot API. Section 8.11 specifies the replacement.
2. A bot cannot learn the user's time zone from Telegram (PC-02). The product must ask for it.
3. A reminder is an ordinary chat message. There is no alarm-grade delivery, and the bot cannot see whether the chat is muted (PC-03, PC-04). The product must say that reminders are best-effort.
4. Telegram can read everything that passes through the bot, including images (PC-12). The product must minimise what it sends and must offer a discreet mode.
5. About 30 messages per second per bot and 1 per second per chat (PC-05). Medication reminders have priority over every other message.

## 2. Target users and jobs to be done

### 2.1 Personas

| Id | Persona | Description | First served in |
|---|---|---|---|
| P1 | Regimen keeper | An adult with one or more scheduled medications or supplements who wants a reliable prompt and a truthful history. The owner is the first P1. | MVP |
| P2 | As-needed user | An adult who takes something only when needed and wants a record of when and how often. | MVP |
| P3 | Self-tracker | An adult who wants to log water, mood, sleep or food, with or without medication. | MVP (water, mood), V1 (sleep, food) |
| P4 | Irregular-schedule user | A shift worker or frequent traveller. Not a separate product, but every time rule must hold for this person. | MVP (time zones), V1 (sleep) |
| P5 | Caregiver | Another adult Telegram user whom a P1 invites to be told when a scheduled dose was not confirmed. | V2 |
| P6 | Operator | The person who runs the service. Has no routine access to user data. | MVP |

Dosegram is for adults (18 or older, self-attested). It is not designed for children and offers no dependent profiles (section 5, Q-14).

### 2.2 Jobs to be done

| Id | When ... | I want to ... | So that ... | Module |
|---|---|---|---|---|
| J1 | a dose is due | be prompted where I already am and answer in one tap | I do not forget, and the record is true | `schedule-and-reminders`, `intake-log` |
| J2 | I take something as needed | log it in two taps | I know when I last took it and how often | `intake-log` |
| J3 | I set up or change a regimen | use a proper form with review before saving | I do not mistype a schedule in a chat | `medication-registry`, `schedule-and-reminders` |
| J4 | I am running low | be told in time | I do not run out | `medication-registry` |
| J5 | I drink, eat, feel or sleep | record it with one tap | logging costs less than skipping it | `hydration`, `nutrition`, `mood`, `sleep` |
| J6 | I open the chat | see what is due and what is done today | I do not need a dashboard | `daily-overview` |
| J7 | a week ends | receive one honest picture of it | I notice patterns without reading tables | `insights-and-correlations`, `visual-reports` |
| J8 | I travel or the clocks change | have my schedule follow me correctly | I am not reminded at the wrong hour | `schedule-and-reminders` |
| J9 | I see a clinician | hand over a plain record | the appointment starts from facts | `import-export` |
| J10 | I want support from someone I trust | let them know when I did not confirm a dose | they can check on me | `sharing-and-caregivers` |
| J11 | I stop trusting or needing the product | take my data and erase the rest | I stay in control | `identity-and-consent`, `import-export` |
| J12 | I own a wearable | bring its sleep data in | I see measured sleep next to my own entries | `import-export`, `sleep` |

## 3. Product principles

Each principle is followed by the rule that makes it testable.

1. **Chat-first capture in one tap.** Every routine record (dose taken, dose skipped, water added, mood, going to sleep, waking up, meal eaten) is completed by one tap on a message the user can already see. Section 9.1 gives the tap budget per action; the budget is an acceptance criterion.
2. **The Mini App is for configuration and exploration.** Anything that needs more than one field, a review step, a calendar or a chart with interaction lives in the Mini App. The chat keeps a text fallback for every safety-relevant action (add a medication, pause, export, delete), so the product never depends on a WebView working.
3. **Images are the status language.** State that benefits from a glance (progress to a goal, a week, a mood) is sent as a server-rendered image. Every image has a text equivalent generated from the same data, and a failed render sends the text instead.
4. **Honest data.** The product stores what the user did and when, keeps the scheduled time and the effective time separate, never converts "unknown" into "missed", never shows an estimate without calling it one, defines no composite health score, and shows gaps as gaps.
5. **Non-clinical.** The product records and reminds. It never prescribes, advises a dose or a time the user did not enter, interprets a value, names a condition or assesses a risk. Section 5 lists what is refused and why.
6. **Medication first.** No other feature may delay, crowd out or mute a medication reminder. Optional prompts have a daily budget; medication reminders are exempt from it and are governed only by the user's own settings.
7. **Private by default.** Health content is shown only to the account owner. Nothing is shared, forwarded to a third party, or processed by an external model unless the user asks for that specific thing. Medication names are hidden in generated images until the user enables them.
8. **The safety core is free.** Reminders, logging in every domain, export, deletion, consent controls and security features are never placed behind payment (section 8.18).
9. **No guilt.** Copy reports what happened. It never shames, never manufactures urgency and never makes a medication record something that can be "lost".

### 3.1 Surface allocation

| Kind of interaction | Bot chat | Mini App | Image |
|---|---|---|---|
| Answer a reminder (taken, skip, snooze, undo) | Primary | View and correct | No |
| Quick log (water, mood, sleep check-in, meal type) | Primary | Also possible | Reply card where useful |
| See today | Primary: one message edited in place | Dashboard | Daily summary on request |
| Add or edit a medication and its schedule | Fallback wizard (existing three-step flow) | Primary | No |
| Edit history, backdate, correct | Limited (today and yesterday; Now or At scheduled time) | Primary | No |
| Explore history and charts | Short text summaries | Primary | Weekly and monthly cards |
| Settings, consent, export, delete | Commands with confirmation | Primary | No |
| Weekly report | Delivered here | Detail and drill-down | Primary |
| Import a file | Not used (PC-06) | Primary | No |

The Mini App is launched only from the Main Mini App entry, the menu button and inline `web_app` buttons, because only those launches carry signed launch data (PC-08).

### 3.2 Notification classes

One term per class. The classes are used throughout section 8. This table is the single notification policy of the document set. BOT_UX section 14, ARCHITECTURE TG-27 and VISUALS_AND_ANALYTICS section 10 map their own terms to these classes and do not restate the values (ROADMAP conflict X-06).

| Class | Definition | Send priority (1 is highest) | Counts toward the daily budget | Inside quiet hours | Sound |
|---|---|---|---|---|---|
| Outcome | The reply to something the user just did, including the answer to a callback and the edit of the message the user acted on | 1 | No. It is a reply | Sent; the user is present | Silent |
| Reminder | A message for one or more medication occurrences that are due, its follow-up, and the catch-up digest that replaces reminders after an outage, a safe shutdown or an unblock (R-SCH-20) | 2 | No. Governed by the schedule's reminder policy | Per the schedule's quiet-hours mode: deliver, silent or defer (R-SCH-14) | Per reminder policy and quiet-hours mode |
| Caregiver alert | A message to a caregiver about an unconfirmed dose (V2) | 3 | No. Rate limited per grantee (R-SHR-8) | Sent | Normal |
| Nudge | An optional prompt for a non-medication domain (drink, check in, log a meal, bedtime), and each fixed touchpoint (morning, midday, evening wrap-up) | 4 | Yes, one unit each (R-SCH-22) | Not sent and not moved to a later time: the prompt ends as `suppressed` with the reason `quiet_hours` (R-SCH-23) | Silent |
| Report | A scheduled summary (weekly, monthly) | 5 | Yes, one unit on the day it is sent, however many messages it consists of. A report due on a day reserves its unit before any nudge. Also at most one per period | Held and sent at the end of quiet hours (R-SCH-23) | Silent |
| Service notice | Operator message about the service (incident, material change, consent re-prompt) | 6 | No. Rare; rate limited by the operator (R-OPS-6) | Held until the end of quiet hours unless security-critical | Silent unless security-critical |

Further values of the policy (all PROPOSED):

- Default daily budget: 4; range 0 to 12 (R-SCH-22).
- Minimum spacing: two budgeted messages (nudges or reports) to one chat are at least 20 minutes apart. Outcomes and reminders are not spaced by this rule.
- Jitter: a report is sent at its chosen time plus a deterministic per-account offset of 0 to 600 seconds, so that accounts with the same chosen time do not share one second. Reminders and nudges carry no product-level jitter.
- Unprompted images: at most one unprompted personal image per account per local day, inside the budget, not in addition to it.
- A lower class never delays a higher one (R-SCH-26, PC-05).

## 4. Where the owner's wishes meet verified constraints

This section states each conflict plainly.

| Owner's wish | Constraint | What Dosegram does instead |
|---|---|---|
| Track sleep "based on the last seen of the user" | Not available. Bots cannot see last seen or online status (PC-01). Telegram's own FAQ says so. The only technical routes use a user-account session, which R05 section 4 classifies as risky or forbidden. | Sleep is recorded with one-tap check-ins. As an opt-in, the product notices the quiet window between the user's own interactions with Dosegram and asks the user to confirm it. This estimate appears only for users who interact with Dosegram several times in the hour before sleep and again in the morning; it is not a general replacement for last seen, and the check-ins are the sleep feature. Section 8.11. |
| "This bot should do everything" | A chat product has no sensors, and several health features are regulated medical-device functions (R03 section 8). | Everything that can be recorded, reminded, counted and drawn is in scope and staged across four waves. Section 5 lists what is refused. |
| Feature set of Samsung Health and similar | Steps, heart rate, sleep stages, snoring and energy or readiness scores depend on wearable sensors (R03 section 2). A bot and a Mini App have no bridge to on-device health stores (PC-18). | The manual and reminder loops are rebuilt from the same ideas. Sensor data may be imported from files later and is shown with its source. No score is imitated. |
| Reminders | No alarm-grade delivery, no read receipts, mute is invisible (PC-03, PC-04). | Best-effort delivery, stated at onboarding; a follow-up reminder; a truthful "unknown" state when delivery cannot be shown; later, an optional caregiver alert. |
| Images in chat for status | Telegram stores every sent image, and a bot can delete a message only within 48 hours (PC-06, PC-12). | Images omit medication names by default, are sent as protected content by default, and the erasure confirmation says what cannot be recalled. |
| Food intake | Photo-based calorie estimates were about 33 percent low in the only controlled test found (R03 section 3.3, secondary source). | A meal journal first. Numbers are optional and typed by the user. No model estimates calories in the first three waves. |
| "Enterprise grade" | 99.9 percent availability and a one-hour recovery time are not credible with one operator and one host (R06 section 15). | Measurable targets per wave in section 9, with beta targets that one operator can meet and general-availability targets that presume a second person and managed infrastructure. |

### 4.1 Coverage of the reconstructed health-application feature set

The owner asked for the feature set of applications such as Samsung Health to be rebuilt from the same ideas. The feature-by-feature matrix is in R03 section 5. The table below gives the disposition of each of its feature groups, so that every reconstructed feature is visibly adopted, deferred or refused. "Later" means adopted in principle and not specified in this document; "Refused" points to a non-goal of section 5.

| Feature group (R03 section 5) | Disposition | Module and wave, or non-goal |
|---|---|---|
| Medication registry, schedules, reminder with Taken, Skip and Snooze, follow-up, corrected time, as-needed log, adherence statistics and calendar | Adopted | `medication-registry`, `schedule-and-reminders`, `intake-log`, `visual-reports`; MVP, further schedule patterns in V1 |
| Refill tracking; time-zone-change handling | Adopted | `medication-registry` V1 (R-REG-11); `schedule-and-reminders` MVP (R-SCH-15) |
| Caregiver notification of an unconfirmed dose | Adopted | `sharing-and-caregivers`, V2 |
| Medication list and records as a document for an appointment | Adopted | `import-export`, V2, as the "Record summary" (R-IMX-6) |
| Natural-language or voice schedule entry; label or package photo to pre-fill a medication | Refused | N10 (no model processes user content) |
| Drug database; drug-interaction checking | Refused | N3 |
| Water: presets, own goal, progress image, interval nudges that stop at the goal | Adopted | `hydration`, MVP |
| Drink types; optional hydration factor; caffeine and alcohol | Adopted | `hydration` V1 (R-HYD-11) and V2 (factor). Caffeine and alcohol are drink types, not separate tallies (R-NUT-9) |
| Hydration challenges against oneself; fasting timer; breathing exercise; monthly sleep-pattern label | Later | `gamification`, `nutrition`, `mood`, `sleep`; "Could, Later" |
| Meal journal by type with optional text; regular-meal view | Adopted | `nutrition`, V1 |
| Meal photos; typed calorie and macro numbers; recent and favourite meals | Adopted | `nutrition`, V2 |
| Packaged-food lookup by barcode | Later | Needs a licence review and a scanner spike |
| Calorie estimate from a photo; full nutrient database; weight-loss programme | Refused | N9, N10 |
| One-tap mood check-in; optional energy; mood blob image | Adopted | `mood`, `visual-reports`; MVP |
| Mood tags and notes; mood calendar | Adopted | `mood` V1 (tags, notes); V2 (calendar) |
| Regulation suggestions matched to an emotion; scored questionnaires; virtual pet | Refused | N4, N11, R-MOO-6, Q-24 |
| Voice journal note | Later | Needs speech processing; only together with a decision on model-based processing (Q-21) |
| Good-night and good-morning log; morning quality rating; bedtime nudge; weekly regularity and duration card | Adopted | `sleep`, V1 |
| Activity-bounded sleep estimate confirmed by the user | Adopted with limited coverage | `sleep`, V1, opt-in (R-SLP-8 to R-SLP-16; coverage stated in R-SLP-13) |
| Evening tags (caffeine, alcohol, late meal) | Not a separate feature | Covered by drink types (R-HYD-11) and mood tags (R-MOO-3) |
| Import of measured sleep | Adopted in part | `import-export` V2: Apple Health export for sleep, weight and water. Other sources are Later |
| Sleep stages, snoring, blood oxygen, smart alarm; sleep from Telegram last seen | Refused | N8, N1 |
| Weight log; blood pressure, glucose, temperature and pulse diary; symptom diary | Adopted | `vitals-and-symptoms`, V2, values only (R-VIT-4) |
| Body composition beyond weight (body fat, muscle mass) | Later | Would be further measurement kinds of `vitals-and-symptoms`; needs a device reading the user types in |
| Stress | Not a separate tracker | Sensor-derived stress is refused (N7, N8). A self-rated state is the mood check-in with energy; "stress" can be a user tag (R-MOO-3) |
| Manual exercise or activity log | Later | Not specified in this document. It can be built as one more small domain on the hydration template; owner question Q-26 |
| Steps, workouts, heart rate and blood oxygen counted by Dosegram | Refused | N8 (no sensor access) |
| Imported steps and heart rate as context | Will not through V2 | N21. The import of R-IMX is limited to sleep, weight and water |
| Custom trackers | Refused for now | N20 |
| Reading a value from a photo of a device screen | Refused | N10 |
| Period log | Deferred | Q-16; any prediction is refused (N6) |
| Threshold alerts on measured values | Refused | N5 |
| Today view; Mini App dashboard; full export; account deletion | Adopted | `daily-overview`, `visual-reports`, `import-export`, `identity-and-consent`; MVP |
| Weekly report card; forgiving consistency counter | Adopted | `insights-and-correlations`, `gamification`; V1 |
| Monthly report; comparison cards with counts | Adopted | V2; comparisons only between non-medication measures (R-INS-8, R-INS-9) |
| Share a card to another chat | Adopted | `sharing-and-caregivers`, V2 (Could) |
| Import from other applications' CSV files | Later | The Dosegram CSV template is V2 (R-IMX) |
| Personal webhook for phone automations | Will not through V2 | N21 |
| Group challenges and leaderboards; an assistant that gives advice | Refused | N12, N10 |

## 5. Explicit non-goals

Each item is refused for the stated reason. A later change of mind needs a change to this section first.

| # | Not built | Reason |
|---|---|---|
| N1 | Reading Telegram last seen, online status or read receipts by any route (Bot API, user-account session, observer account, Business connection) | Not exposed to bots (PC-01); the remaining routes need a full-account credential or resemble surveillance (R05 section 4) |
| N2 | Dose calculation, dose suggestions, advice after a missed or late dose, computed limits on as-needed use | Information used for therapeutic decisions is medical-device software (R03 section 8; R06 section 14.9) and conflicts with the repository rule in `AGENTS.md` |
| N3 | Drug-interaction, duplication, contraindication or allergy checks; a drug database with dosing, indication or warning text | Same as N2; also needs a licensed, maintained data set |
| N4 | Scored questionnaires (for example PHQ-9, GAD-7), any statement about depression, anxiety or risk, crisis triage, sentiment analysis of notes | The European guidance lists mood-questionnaire assessment software as a medical device (R03 constraint C7); a bot cannot meet the duty of care |
| N5 | Thresholds, classifications, colours of judgement or alerts on any measured value | Diagnostic thresholds and clinical-action prompts remove wellness status (R03 constraint C8) |
| N6 | Fertile-window, ovulation or contraception features | Medical-device software; the most litigated category of consumer health data (R03 section 3.6) |
| N7 | A composite health, energy, readiness or sleep score computed by Dosegram | The originals rely on sensors; a self-report imitation is false precision (R03 section 10.2; R05 section 8) |
| N8 | Sleep stages, snoring, blood oxygen, smart alarm, automatic sleep detection, step counting | No sensor access (PC-18) |
| N9 | Calorie estimation from photos by a model; weight-loss programmes; calorie-deficit coaching; moral colours for food | Accuracy evidence (R03 section 3.3); risk for users with disordered eating |
| N10 | An AI health coach, or any third-party model processing of user content | Regulatory and safety exposure; R06 open question Q9; Telegram's developer terms restrict use of bot data for AI products (PC-11). Deterministic parsing covers quick logs. |
| N11 | Losable streaks, rewards or points for medication intake; guilt or urgency notifications; a mascot that suffers | They pay for false records and harm users in distress (R03 section 6.2) |
| N12 | Group chats, channels, inline mode, public leaderboards, challenges between users | Health data is personal; inline results can be cached across users (R02 sections 10 and 18) |
| N13 | Passwords, recovery codes, email login, a second identity provider | Telegram is the only identity; the repository forbids collecting passwords in chat; a credential store adds attack surface (R06 section 4.7) |
| N14 | A native Android or iOS companion application | The premise is that Telegram is the only client (R03 section 7.1) |
| N15 | Dependent profiles (tracking for a child or another adult under one account) | The data subject would be a third person who has not consented (R06 open question Q7) |
| N16 | Third-party analytics, advertising, tag managers or fonts in the Mini App | Health data must not reach third parties (R03 constraint C10; R06 section 12.4) |
| N17 | Payments in MVP, V1 or V2 | R06 decision 10; section 8.18 |
| N18 | Targeting residents of the United States, and the United Kingdom, in the first releases | R06 sections 14.6 and 14.7 list unresolved obligations |
| N19 | Telegram checklists, topics in private chats, the attachment menu, Telegram Serverless hosting | Checklists need a Business connection; topics carry a 15 percent fee on Stars revenue; the attachment menu is restricted; Serverless places records outside the operator's control (R02 sections 4.5, 6.5, 6.11, 11.1) |
| N21 | A personal webhook for phone automations; imported steps, workouts or heart rate shown as context (through V2) | A webhook is a second write path into health records that is authenticated only by a secret in a URL. Imported activity data needs a real export file per source and adds a domain the owner did not list; the import of R-IMX is limited to sleep, weight and water. Re-entry: an owner decision after V2 |
| N20 | Habits and user-defined custom trackers as a separate module | The existing `habits` tables are unused (R01 section 7). Hydration is its own module. A generic tracker is a possible later module and is not in the capability map. |

## 6. Platform constraints used by this specification

All sources were checked on 2026-11-12 by the research briefs named in the last column. "Bot API" means https://core.telegram.org/bots/api at version 10.4 (released 2026-11-09). A constraint marked "VERIFIED by absence" means the reference was read in full and the field or method does not exist; that is weaker than a positive statement.

| Id | Constraint | Label | Source | Brief |
|---|---|---|---|---|
| PC-01 | The Bot API and the Mini App API expose no last-seen time, online status or presence for a user, and no update reports it. Telegram's FAQ states: "Bots cannot see your last seen time". | VERIFIED (by absence in the reference; positively in the FAQ) | Bot API; https://core.telegram.org/bots/webapps; https://telegram.org/faq | R05 section 3; R02 section 19.2 |
| PC-02 | No Telegram object gives a bot or a Mini App the user's time zone. | VERIFIED by absence | Bot API; https://core.telegram.org/bots/webapps | R02 section 19.2 |
| PC-03 | A bot cannot start a conversation. The user must press Start, send a message, or grant write access first. | VERIFIED | https://core.telegram.org/bots | R02 section 15 |
| PC-04 | A bot message is an ordinary chat message. No alarm, critical-alert or call-style delivery exists for bots, and no update tells the bot that a message was read. | VERIFIED by absence | Bot API | R02 section 15 |
| PC-04a | The user's mute setting and the device's focus or do-not-disturb mode decide whether a message alerts, and the bot cannot read or override them. | ASSUMPTION | n/a | R02 section 15 |
| PC-05 | A bot should send at most about 1 message per second in one chat and about 30 messages per second overall. Exceeding the limits returns error 429 with a `retry_after` value. | VERIFIED | https://core.telegram.org/bots/faq; Bot API | R02 section 5 |
| PC-06 | Text is 1 to 4096 characters; a caption is 0 to 1024 characters; `callback_data` is 1 to 64 bytes; a photo upload is at most 10 MB; a bot can delete a message only within 48 hours of sending; a bot can download a user-sent file of at most 20 MB. | VERIFIED | Bot API | R02 sections 6, 7, 16, 17 |
| PC-07 | Telegram checklists can be sent only on behalf of a connected Business account. | VERIFIED | Bot API (`sendChecklist`) | R02 section 6.5 |
| PC-08 | Signed launch data (`initData`) is present when a Mini App is opened as the Main Mini App, from the menu button, from an inline `web_app` button or from a direct link. It is empty for reply-keyboard and inline-mode launches. | VERIFIED | https://core.telegram.org/bots/webapps | R02 section 11.1 |
| PC-09 | Since 2026-07-20 Mini App methods are blocked from origins other than the registered Mini App domain unless the developer opts out. | VERIFIED | https://core.telegram.org/bots/api-changelog | R02 section 11.2 |
| PC-10 | Digital goods and services sold inside Telegram must be sold in Telegram Stars (`XTR`). Subscriptions have a fixed period of 30 days. | VERIFIED | https://core.telegram.org/bots/payments-stars; https://telegram.org/tos/bot-developers | R02 section 13 |
| PC-11 | The Bot Platform Developer Terms require: data limited to what is essential; no dataset building or model training on user data; use of voluntarily submitted data only with "individual, explicit, active and revocable consent"; encryption at rest with the key stored separately; deletion on request without undue delay; an accessible privacy policy; no requests for passwords or one-time codes; no spam. | VERIFIED | https://telegram.org/tos/bot-developers (sections 4, 4.2, 4.3, 4.4, 5.2) | R02 section 20; R05 section 4.1 |
| PC-12 | Chats with a bot are cloud chats with server-client encryption. They are not end-to-end encrypted. Telegram can read messages, captions and images sent through a bot. | VERIFIED | https://telegram.org/faq; https://telegram.org/privacy | R06 section 2 (F1, F2) |
| PC-13 | When a user blocks or unblocks the bot in a private chat, the bot receives a `my_chat_member` update. | VERIFIED | Bot API | R02 section 4.1 |
| PC-13a | Sending to a user who blocked the bot fails with HTTP 403. | ASSUMPTION | n/a | R02 section 15 |
| PC-14 | A file already on Telegram's servers can be sent again by its `file_id` without upload. A `file_id` belongs to one bot and cannot change media type. | VERIFIED | Bot API (sending files) | R04 section 3.3 |
| PC-15 | Message widgets (Bot API 10.4) work in production only in the private chat with the bot, are limited to 4096 bytes after compression, have no accessibility semantics, and show as unsupported media with a caption on clients without support. | VERIFIED | https://core.telegram.org/bots/widgets | R02 section 6.9; R04 section 3.5 |
| PC-16 | `language_code` on a user is optional and can be stale. | VERIFIED | Bot API; https://core.telegram.org/bots/features | R02 section 19.1 |
| PC-17 | Command lists, the bot name and the bot descriptions can be set per language, and command lists per chat. | VERIFIED | Bot API (`setMyCommands`, `setMyName`, `setMyDescription`) | R02 section 8 |
| PC-18 | A Mini App runs in a WebView with only the documented bridges. None reaches Health Connect, Samsung Health or HealthKit. Motion sensors work only while the Mini App is open. | VERIFIED by absence | https://core.telegram.org/bots/webapps | R02 sections 11.7, 19.2 |
| PC-19 | Telegram re-encodes an uploaded photo as JPEG and scales its long side to about 1280 pixels; transparency is lost. | ASSUMPTION | n/a | R04 section 3.4 |
| PC-20 | A `date_time` message entity exists and carries a Unix time and a format. | VERIFIED | https://core.telegram.org/bots/api/formatting | R02 section 6.2 |
| PC-20a | Clients render a `date_time` entity in the device's own time zone and locale. | ASSUMPTION | n/a | R02 section 6.2 |
| PC-21 | A bot can edit the text, caption, media and inline keyboard of its own message. `editMessageMedia` can turn a text message into a media message. Only messages with no markup or with an inline keyboard are editable. | VERIFIED | Bot API | R02 sections 6.3, 16 |
| PC-21a | A bot's own message stays editable after 48 hours, and an edit produces no notification. | ASSUMPTION | n/a | R02 sections 15, 16 |
| PC-22 | A reply-keyboard button can ask the user to pick other users (`request_users`); the result arrives as a service message. | VERIFIED | Bot API | R02 section 7.2 |
| PC-23 | A Mini App can ask for permission to message the user (`requestWriteAccess`), and the launch data says whether the user already allows it (`allows_write_to_pm`). | VERIFIED | https://core.telegram.org/bots/webapps | R02 section 11.7 |
| PC-24 | An animated sticker (TGS) is 512 by 512 pixels, at most 3 seconds, at most 64 KB, must loop, and has no caption. | VERIFIED | https://core.telegram.org/stickers; Bot API | R04 section 3.2 |
| PC-25 | The Mini App has a QR scanner popup that returns text. | VERIFIED | https://core.telegram.org/bots/webapps | R02 section 11.5 |
| PC-25a | That scanner reads one-dimensional retail barcodes. | ASSUMPTION | n/a | R02 section 11.5 |
| PC-26 | Inside a Telegram WebView, `Intl.DateTimeFormat().resolvedOptions().timeZone` returns the device's IANA time zone. | ASSUMPTION | n/a | R02 section 19.3 |
| PC-27 | The Mini App receives `activated` and `deactivated` events and has no "closed" event. | VERIFIED | https://core.telegram.org/bots/webapps | R05 section 3.2 |
| PC-28 | `protect_content` blocks forwarding and saving of a message; `has_spoiler` covers a photo until tapped; `disable_notification` delivers without sound. | VERIFIED | Bot API | R02 sections 6.1, 6.3 |
| PC-29 | Telegram keeps undelivered updates for at most 24 hours. | VERIFIED | Bot API | R02 section 4.1 |
| PC-30 | Every callback query must be answered with `answerCallbackQuery`; the client shows a progress indicator until then. The deadline is not documented. | VERIFIED (duty); ASSUMPTION (deadline) | Bot API | R02 section 7.1 |
| PC-31 | A Mini App can hand the user a file to save (`downloadFile`). | VERIFIED | https://core.telegram.org/bots/webapps | R02 section 11.7 |
| PC-32 | A bot can pin a message in a private chat, and pinning there never notifies. | VERIFIED | Bot API | R02 section 6.12 |

## 7. Capability map

### 7.1 Modules

The map follows the Phase 0 form of the spec-driven-development skill: stable ids, one-way dependencies, no cycles. A module's interface is specified in the provider's section of this document and designed in ARCHITECTURE.md.

| Module id | Responsibility | Depends on | First wave | Adopted |
|---|---|---|---|---|
| `localisation` | Message catalogue, locale resolution, plural rules, date, number and unit formatting, forbidden-phrase lint | none | MVP | Yes |
| `identity-and-consent` | Account bound to a Telegram identity, onboarding, age and region attestation, per-domain consent ledger, account settings (time zone, quiet hours, discreet mode, units, theme), Mini App session, data-rights entry points, hard delete | `localisation` | MVP | Yes |
| `admin-and-ops` | Invite gate, operator tooling without content access, service notices, health and readiness, service-level objectives, retention jobs, incident runbooks | `identity-and-consent` | MVP | Yes |
| `medication-registry` | The user's list of medications: name, form, strength, dose, instructions, status, inventory and refill | `identity-and-consent`, `localisation` | MVP | Yes |
| `schedule-and-reminders` | Schedules and their revisions, occurrence materialisation, reminder policy, quiet hours, time-zone and daylight-saving handling, pauses, the generic nudge scheduler and the daily nudge budget | `medication-registry`, `identity-and-consent` | MVP | Yes |
| `intake-log` | The dose lifecycle and its append-only action log: taken, skipped, snoozed, missed, unknown, undo, correction, as-needed intake, adherence figures | `schedule-and-reminders`, `medication-registry` | MVP | Yes |
| `visual-reports` | The rendering pipeline and the card templates: view model in, image plus text equivalent out; themes; discreet rendering; delivery rules for images | `localisation`, `identity-and-consent` | MVP | Yes |
| `hydration` | Drink entries, daily goal, quick-add presets, hydration nudges | `identity-and-consent`, `schedule-and-reminders`, `visual-reports` | MVP | Yes |
| `mood` | Mood check-ins (valence, optional energy, tags, note), check-in prompts, the mood blob states | `identity-and-consent`, `schedule-and-reminders`, `visual-reports` | MVP | Yes |
| `daily-overview` | The Today card, the daily touchpoints (morning, midday, evening wrap-up), the daily summary card | `intake-log`, `hydration`, `mood`, `visual-reports`; from V1 also `sleep`, `nutrition` | MVP | Yes |
| `import-export` | Full account export, the record summary document, file imports and, later, wearable connections | `identity-and-consent` and every domain module whose data it carries | MVP (export), V2 (import) | Yes |
| `sleep` | Sleep episodes from check-ins, the opt-in quiet-window estimate with confirmation, naps, sleep goal, regularity metrics | `identity-and-consent`, `schedule-and-reminders`, `visual-reports` | V1 | Yes |
| `nutrition` | Meal journal entries, regular-meal check, optional user-typed numbers | `identity-and-consent`, `schedule-and-reminders`, `visual-reports` | V1 | Yes |
| `insights-and-correlations` | Statistics snapshots per domain, weekly and monthly reports, descriptive comparisons across domains with counts | `intake-log`, `hydration`, `mood`, `sleep`, `nutrition`, `vitals-and-symptoms` (each when present), `visual-reports` | MVP (adherence text), V1 (weekly report), V2 (correlations) | Yes |
| `gamification` | Forgiving consistency counters, minimum-data gates with visible progress, neutral milestones for non-medication logging | `insights-and-correlations`, `visual-reports` | V1 | Yes, in restricted form (section 8.14) |
| `vitals-and-symptoms` | A diary of user-entered measurements (weight, blood pressure, glucose, temperature, pulse) and symptom severity; no interpretation | `identity-and-consent`, `visual-reports`, `localisation` | V2 | Yes, as a diary only |
| `sharing-and-caregivers` | Grants to another Telegram user, caregiver alerts for unconfirmed doses, user-initiated sharing of one card | `identity-and-consent`, `intake-log`, `schedule-and-reminders` | V2 | Yes |
| `billing` | Entitlements; if a paid tier is ever added, Telegram Stars payments | `identity-and-consent` | Later | Not adopted for MVP to V2 |

`daily-overview` is an addition to the list the planning brief required. It exists because the Today card and the batching of prompts are consumed by every domain and owned by none.

### 7.2 Build order

```
1. localisation -> identity-and-consent -> admin-and-ops
2. medication-registry -> schedule-and-reminders -> intake-log
3. visual-reports (pipeline and first cards)
4. hydration, mood (parallel) -> daily-overview
5. import-export (export part) and insights-and-correlations (adherence text)      [MVP ends]
6. sleep (check-ins, then quiet-window estimate), nutrition (parallel)
7. insights-and-correlations (weekly report), gamification                          [V1 ends]
8. vitals-and-symptoms, sharing-and-caregivers, import-export (imports),
   insights-and-correlations (correlations)                                         [V2 ends]
9. billing, wearable connections, enhancements                                      [Later]
```

Rationale for the order: steps 1 and 2 reuse the engine that already exists and is tested (R01 section 1); step 3 must precede the first new domain because images are the status language; hydration and mood are the two cheapest new domains (R03 section 10.1) and produce the daily habit of opening the chat; export and deletion are launch-blocking controls (R06 section 16.1, LB-13) and therefore belong to MVP even though import does not. Rejected alternative: building all five domains before any image work, which would postpone the owner's central idea and leave the new domains without their feedback.

Before step 1, two engineering preconditions from R01 apply and are owned by ROADMAP.md, not by this specification: the removal of the Discord adapter followed by the mechanical rename, and the start of the reminder loop from the executable (the existing slice M2.2b), without which no reminder is delivered on any platform.

### 7.3 Subdomain classification and language boundaries

Classification follows the strategic-ddd skill. "Exists" means a module, package or schema in the repository today (R01 sections 3 and 7). Everything else is a planned split and is labelled as not built.

| Bounded context | Modules | Class | Exists today | One language inside it |
|---|---|---|---|---|
| Medication adherence | `medication-registry`, `schedule-and-reminders`, `intake-log` | Core | Yes: `core.domain`, `core.scheduling`, medication tables | medication, schedule, revision, occurrence, dose action, reminder |
| Conversation | the chat mediator, wizard engine and callback codec (specified in BOT_UX.md and ARCHITECTURE.md) | Supporting | Yes: `core.chat` | principal, session, action, card, outcome |
| Identity and consent | `identity-and-consent` | Generic, with product-specific consent rules | Partly: `users`, `platform_identities`; the consent ledger is not built | account, Telegram identity, consent, grant |
| Daily tracking | `hydration`, `mood`, `sleep`, `nutrition`, `vitals-and-symptoms` | Supporting; each is its own small model | Only `mood_checkins` (insert only). The rest is not built. | entry, check-in, episode, measurement, goal |
| Insight | `insights-and-correlations`, `gamification`, `daily-overview` | Supporting | Adherence functions exist; the rest is not built | snapshot, period, statistic, comparison |
| Presentation of status | `visual-reports` | Core to the product idea, supporting to the domain | Not built (design on paper only) | card, view model, text equivalent, theme |
| Sharing | `sharing-and-caregivers` | Supporting | Not built | grant, scope, caregiver alert |
| Data portability | `import-export` | Generic | Not built | export, import batch, source record |
| Operations | `admin-and-ops`, `billing` | Generic | Partly (health endpoint, settings) | operator, support code, entitlement |

Relationships between contexts, named before anything is wired:

| Upstream | Downstream | Relationship | What crosses the boundary |
|---|---|---|---|
| Telegram platform | Conversation | Conformist behind an anti-corruption layer (the Telegram adapter). The platform's vocabulary (update, callback query, `initData`, `file_id`) stops at the adapter. | Normalised inbound events and outbound messages |
| Identity and consent | every other context | Customer and supplier. It supplies the account id, settings and the answer to "is this purpose consented?". | Account id, locale, time zone, consent decision |
| Medication adherence | Insight, Sharing, Data portability | Published language: domain events and read-only snapshots. Downstream never imports occurrence or decision types. | Events such as "occurrence resolved", adherence snapshots |
| Daily tracking | Insight, Data portability | Published language: per-domain day summaries and snapshots | Day summary, period snapshot |
| Insight and every tracking context | Presentation of status | Customer and supplier. The supplier accepts only display-ready view models. | View model |
| Conversation | every context | Open host: one action registry through which the bot and the Mini App reach the same use cases | Commands and their outcomes |

Terms that mean different things in two contexts and are therefore translated, not shared:

| Word | In Medication adherence | Elsewhere |
|---|---|---|
| "Reminder" | A message for a due occurrence | Other contexts use "nudge" (section 3.2). The word "reminder" is not used for water or mood prompts. |
| "Schedule" | A medication schedule with revisions | A nudge has a "nudge plan", never a schedule |
| "Entry" | Not used; the record is a "dose action" on an "occurrence" | A hydration or meal record |
| "Goal" | Not used. A medication has no goal, target or streak. | A user-set daily target in hydration, sleep or nutrition |
| "Skipped" | A deliberate decision recorded on an occurrence | A dismissed nudge is "dismissed", never "skipped" |

What is deliberately not shared: no tracking context reads another's tables; the sleep context receives interaction evidence as instants and kinds only, never the content of the interaction (R-SLP-9).

## 8. Functional specification per module

### 8.1 `localisation`

Tier and wave: Must, MVP. Starting assumption: English (`en`) and Ukrainian (`uk`).

**User stories**

- US-LOC-1. As a user I see the bot and the Mini App in my language without configuring anything.
- US-LOC-2. As a user I can change the language, the units, the 12 or 24 hour clock and the first day of the week.
- US-LOC-3. As a user I read numbers, dates and plurals written the way my language writes them.

**Domain rules and edge cases**

- R-LOC-1. Locale resolution order: the account's explicit setting; otherwise the Telegram `language_code` if it maps to a shipped locale (`uk` and its regional variants map to `uk`); otherwise `en` (PC-16).
- R-LOC-2. Every user-visible string comes from the message catalogue. Templates, cards and screens contain no words. A missing key in a shipped locale fails the build.
- R-LOC-3. Plural selection follows the CLDR categories of the locale. Ukrainian uses one, few, many and other. A string that contains a count has all categories that its locale requires.
- R-LOC-4. Number input accepts both a decimal comma and a decimal point in every locale. Output uses the locale's separator.
- R-LOC-5. Defaults: 24 hour clock and Monday as first day of the week for `uk`; for `en` the same defaults (PROPOSED), each changeable in settings.
- R-LOC-6. Unit settings are per account and independent of the locale: volume (ml or US fl oz), weight (kg or lb), temperature (Celsius or Fahrenheit), glucose (mmol/L or mg/dL), energy (kcal or kJ). Defaults are metric, mmol/L and kcal (PROPOSED).
- R-LOC-7. User-entered text (medication names, notes, tags the user created) is never translated, transliterated or changed in case.
- R-LOC-8. The forbidden-phrase list (clinical wording and shaming wording, R01 section 11) exists per locale and is checked against the whole catalogue of that locale.
- R-LOC-9. Layouts tolerate strings 40 percent longer than English (R04 section 8.5). One right-to-left locale is kept in the test fixtures although none ships.
- R-LOC-10. The bot's command list, name and descriptions are registered per language (PC-17).
- R-LOC-11. Legal texts (privacy notice, consent statements) exist in every shipped locale. A consent is recorded with the locale and the hash of the text that was shown (R-IDC-8).
- R-LOC-12. Time zone names shown to the user are localised; stored values are IANA identifiers.

**Data captured**

| Field | Values | Class (R06 section 6) |
|---|---|---|
| `locale` | `en`, `uk` | C |
| Clock format, week start | 12 or 24; Monday, Sunday or Saturday | C |
| Unit settings | as in R-LOC-6 | C |

**Capture surfaces.** Bot: `/language` and `/settings` open a short keyboard. Mini App: Settings screen. Image: every card is rendered in the account locale.

**Acceptance criteria**

- AC-LOC-1. For each shipped locale, a build step proves that every catalogue key has a value and every counted string has all required plural categories. [O]
- AC-LOC-2. A new account whose Telegram language is `uk` receives the first message in Ukrainian; one whose language is `de` or absent receives English. [T]
- AC-LOC-3. Entering `0,5` and `0.5` as an amount gives the same stored value in both locales. [U]
- AC-LOC-4. The Ukrainian strings for 1, 2, 5, 21 and 22 "days" use the forms required by the one, few and many categories. [U]
- AC-LOC-5. No catalogue string in any shipped locale contains a phrase from that locale's forbidden list. [O]
- AC-LOC-6. Changing the volume unit changes the display of existing hydration entries and does not change any stored canonical value. [U]
- AC-LOC-7. Every card template passes its layout lint with the long-string fixture locale and with the right-to-left fixture locale. [G]

### 8.2 `identity-and-consent`

Tier and wave: Must, MVP, except where a row says otherwise.

| Capability | Tier | Wave |
|---|---|---|
| Account creation from `/start` in a private chat; invite gate | Must | MVP |
| Adult attestation, region step, layered notice | Must | MVP |
| Per-domain two-step consent, consent receipt, withdrawal | Must | MVP |
| Time zone capture and change | Must | MVP |
| Discreet mode as a forced choice at onboarding | Must | MVP |
| Mini App session from validated launch data | Must | MVP |
| Account settings (quiet hours, units, theme, clock) | Must | MVP |
| Hard delete of the account and per-domain delete | Must | MVP |
| Confirmation in the private chat for sensitive actions (X-PRV-9) | Must | MVP for deletion and export; with the feature for grants; V1 for consent-set changes made in the Mini App |
| Day rollover time setting | Should | V1 |
| User-visible access history | Should | V1 |
| Automatic deletion of inactive and unreachable accounts | Should | V1 |
| Optional app lock with device biometrics | Could | Later |

**User stories**

- US-IDC-1. As a new user I learn who runs the service, what it stores and what Telegram can see, before anything about me is stored.
- US-IDC-2. As a user I agree separately to each kind of tracking, and I can withdraw each one as easily as I gave it.
- US-IDC-3. As a user I choose whether reminders show medication names.
- US-IDC-4. As a user I open the Mini App from the bot and am signed in without a password.
- US-IDC-5. As a user I delete one domain's data or my whole account by myself and receive a confirmation.
- US-IDC-6. As a user who opened the Mini App from a link before ever pressing Start, I am asked to allow the bot to message me.

**Domain rules and edge cases**

- R-IDC-1. An account is identified by exactly one Telegram user id. There is no password, email or second identity provider. Losing the Telegram account means losing access; the mitigation is easy export (Q-13).
- R-IDC-2. The bot works only in a private chat. In any other chat type it sends one short redirect that contains no health data, and leaves a group if it was added (R06 threat WH-8).
- R-IDC-3. Nothing about the person is stored before the consent step completes, except the minimum needed to continue onboarding (Telegram user id, onboarding step, locale). An onboarding that is abandoned is deleted after 24 hours (PROPOSED).
- R-IDC-4. Onboarding order: service identity and best-effort statement; "I am 18 or older"; region of residence from a short list; layered privacy notice; consent for each domain the user enables; discreet-mode choice; time zone; "Account ready". The time zone is asked after the consent step so that nothing beyond the onboarding record of R-IDC-3 is stored before consent (BOT_UX section 5.3). The region list is a setting that is empty by default. Its intended content for the first releases is EU or EEA countries and Ukraine, and a region is enabled only when the owner has recorded a cleared legal position for it (ROADMAP G11.7, owner action OA-11). Any choice that is not enabled ends onboarding with a plain "not available in your region yet" message and stores nothing (N18, Q-02).
- R-IDC-5. During the invite-only phase `/start` without a valid single-use invite code replies that the service is invite-only and stores nothing.
- R-IDC-6. Consent purposes are separate and individually revocable. The table below is the canonical list for the whole document set (ROADMAP conflict X-26): the consent ledger, the API, the copy keys and every other document use exactly these identifiers. A purpose is asked when its domain is enabled or its feature is first used.

  | Purpose id | What it covers | Kind | Wave |
  |---|---|---|---|
  | `medication` | Medication registry, schedules, reminders and the intake log | Domain | MVP |
  | `hydration` | Drink entries, the goal and hydration nudges | Domain | MVP |
  | `mood` | Mood check-ins, tags and notes | Domain | MVP |
  | `sleep` | Sleep check-ins, typed episodes and sleep metrics | Domain | V1 |
  | `nutrition` | The meal journal | Domain | V1 |
  | `vitals` | Measurements and symptom entries (one purpose for both diaries) | Domain | V2 |
  | `images_in_chat` | Generated images that contain health data, sent into the Telegram chat | Feature | MVP |
  | `sleep_passive_estimate` | The quiet-window estimate from the times of the user's own interactions with Dosegram | Feature | V1 |
  | `export_to_chat` | Delivery of an export file into the Telegram chat instead of a Mini App download (asked per export) | Feature | V1 |
  | `photo_storage` | Storing meal photos | Feature | V2 |
  | `cross_domain_patterns` | Comparisons between two non-medication domains (R-INS-8) | Feature | V2 |
  | `caregiver_sharing` | Disclosure to one caregiver (asked per grant) | Feature | V2 |
  | `import` | Import from one external source (asked per source) | Feature | V2 |

  Acceptance of the terms and the adult attestation are not consent purposes. They are recorded in the same ledger as `attestation` events and cannot be withdrawn separately; leaving the service is account deletion. There is no purpose for model-based processing, because no model processes user content in any wave (Q-21, N10).
- R-IDC-7. Consent is explicit and two-step: the first button's label is the statement itself, the second message repeats the statement and asks for "Confirm". The consent is recorded on the second tap only. No option is preselected and "accept all" is never the only path.
- R-IDC-8. Each consent is stored in an append-only ledger with: purpose, granted and withdrawn instants, notice version, hash of the notice text, locale, region, channel, and the identifier of the confirming update. The bot then sends a consent receipt message.
- R-IDC-9. A feature of a domain without an active consent is unavailable: its commands answer with the consent prompt, its nudges are not scheduled and its data is not shown.
- R-IDC-10. Withdrawing a consent stops that domain's processing at once, cancels its queued nudges, and offers "Delete this domain's data now" and "Keep my data, just stop". Withdrawing `medication` cancels all pending occurrences.
- R-IDC-11. A material change to the notice or a new purpose triggers a re-prompt. Silence is never treated as consent.
- R-IDC-12. Time zone: an IANA zone is mandatory. In the Mini App the device zone is proposed (PC-26, ASSUMPTION) and the user confirms it. In chat the user picks a region and then a city from a keyboard, or types a city name. The zone is never derived from location sharing, a phone number or an IP address.
- R-IDC-13. On every Mini App launch the device zone is compared with the account zone. If they differ the user is asked "You seem to be in <zone>. Switch?" with "Switch", "Keep <current>" and "Do not ask for 7 days". The zone never changes without a tap. The consequences for schedules are in R-SCH-9.
- R-IDC-14. Discreet mode is a forced choice with no preselection. On: reminder text shows the time or a neutral label and no medication name; the name appears only after the user taps "Show" inside the chat. Off: names are shown. If the owner insists on a default, the default is discreet (Q-05). Discreet mode applies to text, captions, alt text, file names and images alike.
- R-IDC-15. Quiet hours are an account setting: one local-time interval that may cross midnight; default 22:00 to 07:00 for nudges and reports (PROPOSED). Their effect on medication reminders is governed by each schedule's quiet-hours mode (R-SCH-14).
- R-IDC-16. Mini App sign-in: the launch data is validated on the server, must be fresh (PROPOSED: at most 5 minutes old), and is exchanged for a short-lived server session. The Mini App never creates an account; an unknown Telegram user is sent to the bot. The validation method and session limits are specified in ARCHITECTURE.md (Q-15).
- R-IDC-17. If the user's launch data shows that the bot may not message them (PC-23), the Mini App asks for write access before any feature that depends on reminders.
- R-IDC-18. Blocked bot: on the `my_chat_member` update (PC-13) the account becomes `unreachable`, all sends stop, and open occurrences resolve as `unknown` with reason `undelivered`, never `missed`. On unblock the account becomes `active`, schedules resume from that instant, and one catch-up digest is sent instead of the missed prompts. The digest carries one informational line that reminders have resumed and a "Pause reminders" button; resumption does not wait for a tap, because a paused state that the user did not choose would lose reminders silently (ROADMAP conflict X-29).
- R-IDC-19. Account deletion: requested in the bot (`/privacy`) or the Mini App; confirmed by a tap on a confirmation message in the private chat that names what will be deleted; reminders stop at once; the user's encryption key is destroyed so that names, notes and stored images become unreadable in live storage at once; rows and objects are deleted; a final confirmation is sent; then the bot is silent. Backups taken before the deletion still contain the wrapped key and the encrypted rows until they expire, at most 35 days later (section 9.6); a restore applies the purge list before it serves traffic, so a deleted account is never brought back (ARCHITECTURE SEC-06, open question Q-25). The confirmation states that messages and images already delivered remain in the user's Telegram chat until the user deletes them (PC-06, PC-12), and that encrypted backups expire within 35 days. An optional 7-day cancellable delay is offered at request time; the default is no delay.
- R-IDC-20. Per-domain deletion removes that domain's records and withdraws its consent.
- R-IDC-21. Inactive accounts receive a notice after 23 months without any interaction and are deleted after 24 months. Unreachable accounts are deleted 90 days after the first permanent delivery failure. (PROPOSED, R06 section 9.1.)
- R-IDC-22. Not collected at all: names, username, profile photo, phone number, contacts, location, date of birth, email, diagnosis, prescriber, pharmacy. The Telegram first name may be used transiently in a greeting and is not stored.
- R-IDC-23. If the operator learns that an account holder is under 18, the account is closed and its data deleted.
- R-IDC-24. The day rollover time (V1) is one account setting between 00:00 and 05:00 that decides to which local day a late-night hydration, mood or meal record belongs. Default 00:00. It does not affect medication occurrences, whose local date comes from the schedule, or sleep, whose day is defined in R-SLP-5.

**Data captured**

| Field | Limits | Class |
|---|---|---|
| Account id (internal), Telegram user id, chat id | opaque | C |
| Status | `onboarding`, `active`, `unreachable`, `deleting` | C |
| Adult attestation | boolean with instant; no birth date | C |
| Region | self-declared code from the offered list | C |
| Time zone and its history | IANA id, effective instant | C |
| Settings | discreet mode, quiet hours, theme (auto, light, dark), units, clock, day rollover, nudge budget | C |
| Consent ledger | as in R-IDC-8 | B |
| Sessions | hashed token, created, last used, expiry | C |

**Capture surfaces.** Bot: `/start`, `/privacy`, `/settings`, `/timezone`, `/language`, `/help`. Mini App: Settings, Privacy and data, Consent. Image: none.

**Acceptance criteria**

- AC-IDC-1. Before the second consent tap, the database holds no row for the user other than the onboarding record of R-IDC-3; after 24 hours of abandonment that record is gone. [P]
- AC-IDC-2. With one domain enabled, onboarding from `/start` to "Account ready" takes at most 9 taps and no typed text when the time zone is picked from the keyboard. [T]
- AC-IDC-3. A consent row exists if and only if both taps occurred; a first tap with no second tap within 30 minutes leaves no consent. [T][P]
- AC-IDC-4. Each consent receipt names the purpose, the date, the notice version and the way to withdraw. [G]
- AC-IDC-5. A command of a domain without consent returns the consent prompt and records nothing. [T]
- AC-IDC-6. After withdrawal of a domain's consent no nudge of that domain is sent and its data is absent from Today, reports and the Mini App. [T][M]
- AC-IDC-7. In a group chat the bot sends exactly one redirect without health data and leaves. [T]
- AC-IDC-8. With discreet mode on, no reminder, caption, alt text, file name or image produced for the account contains a medication name; a canary name inserted in the registry does not appear in any outbound payload except the reply to "Show". [T][O]
- AC-IDC-9. Launch data older than the freshness window, with a bad signature, or for a Telegram user without an account is rejected, and no session is created. [M]
- AC-IDC-10. After the block update, no message is sent to the account; occurrences that pass their miss deadline while blocked resolve as `unknown(undelivered)`. After unblock, exactly one catch-up digest is sent. [T][P]
- AC-IDC-11. After a confirmed account deletion: the account's key is destroyed in the same operation; within 24 hours no account-scoped row or object remains in live stores; restoring a backup taken before the deletion and applying the purge list leaves the account absent. [P][O]
- AC-IDC-12. The deletion confirmation text states the limit about messages already in the chat. [G]
- AC-IDC-13. A Mini App launch from a device whose zone differs from the account zone shows the switch prompt and changes nothing until a tap. [M]
- AC-IDC-14. `/start` without a valid invite during the invite-only phase stores nothing and replies with the invite-only text. [T][P]
- AC-IDC-15. A region outside the offered list ends onboarding and stores nothing. [T][P]

### 8.3 `admin-and-ops`

Tier and wave: Must, MVP for the launch-blocking controls of R06 section 16.1; Should, V1 for the public-beta controls of section 16.2. The controls themselves are specified in ARCHITECTURE.md; this section states what the product requires of them.

**User stories**

- US-OPS-1. As the operator I can see whether reminders are being delivered on time without seeing anyone's data.
- US-OPS-2. As the operator I can help a user who gives me a support code, without searching by name.
- US-OPS-3. As a user I am told about an incident or a material change through the bot and on a page outside the bot.
- US-OPS-4. As the operator I can stop all sending in one action and keep all data.

**Domain rules and edge cases**

- R-OPS-1. The operator has no routine access to health content. Dashboards and logs contain operational counters and internal account ids only.
- R-OPS-2. A user can generate a support code in the bot. It maps to their account for 24 hours. Operator tooling accepts support codes and internal ids, never Telegram names.
- R-OPS-3. Decrypting one account's content is a time-boxed break-glass action with a reason code. It is written to the audit log and, from V1, shown to the user in their access history.
- R-OPS-4. Invites: the operator issues single-use invite codes that fit a deep-link start parameter (at most 64 characters of the allowed alphabet). A code carries no meaning by itself, expires after 14 days (PROPOSED) and is consumed at consent time, not at `/start`.
- R-OPS-5. The account cap of the invite-only phase is a configuration value (50, PROPOSED). At the cap, a valid invite still receives a polite "full" reply and stores nothing.
- R-OPS-6. Service notices reach all active accounts through the outbound queue at the lowest priority, never inside a user's quiet hours unless security-critical, and never delay a reminder.
- R-OPS-7. Safe shutdown stops every outbound send and every materialisation while keeping inbound updates durable. On resume, occurrences whose window passed during the stop resolve as `unknown(outage)` and one catch-up digest is sent per account (existing lifecycle rule).
- R-OPS-8. Every retention period of section 9.6 is enforced by a job with a test.
- R-OPS-9. The service publishes a status and notice page on the Mini App origin, reachable without the bot, for use when the bot channel cannot be trusted (R06 section 13.3).
- R-OPS-10. A scheduled check compares the Bot API changelog with the version this specification was checked against (10.4) and raises a task when it changes.
- R-OPS-11. Feature availability is controlled per account by a flag set (wave flags), so that a domain can be enabled for the owner before the invite group.

**Data captured.** Operational records only (class D): invite codes (hashed), support codes (hashed, 24 hours), audit events without health content, delivery states without content, counters.

**Capture surfaces.** Bot: `/support` (issues a support code), `/status` (service state, link to the notice page). Mini App: About and status. Operator: a command-line tool; no web console in MVP.

**Acceptance criteria**

- AC-OPS-1. A canary string placed in every user-supplied field (medication name, note, tag, meal text) does not appear in any log line, metric label, trace attribute or audit row after the full transcript suite has run. [O]
- AC-OPS-2. The reminder-timeliness measure of X-REM-1 is exported as a metric and has an alert. [O]
- AC-OPS-3. A support code resolves to its account for 24 hours and to nothing afterwards. [P]
- AC-OPS-4. After safe shutdown no outbound request is made; after resume no reminder older than its window is sent and each affected account receives one digest. [S]
- AC-OPS-5. Each retention job deletes a fixture older than its period and keeps one that is younger. [P]
- AC-OPS-6. An expired or reused invite code is refused and stores nothing. [T][P]
- AC-OPS-7. A service notice queued while reminders are due is sent after them. [S]

### 8.4 `medication-registry`

| Capability | Tier | Wave |
|---|---|---|
| Add, edit, pause, resume, archive a medication; name, dose amount and unit, instructions | Must | MVP |
| Mini App registry as the primary surface; chat wizard as fallback | Must | MVP |
| Form, strength, appearance (colour and shape tokens), neutral label for discreet mode | Should | V1 |
| Inventory and refill nudge | Should | V1 |
| Permanent deletion of a medication with its history | Should | V1 |
| Medication list inside the record summary document | Should | V2 (with `import-export`) |
| Pre-fill from a label photo or a barcode | Could | Later |
| Drug database, descriptions, side effects, interaction checks | Will not | n/a (N3) |

**User stories**

- US-REG-1. As a user I add a medication with its dose and instructions in a form, review it, and save it.
- US-REG-2. As a user I add a medication from the chat when the Mini App is not available.
- US-REG-3. As a user I pause a medication for a while and resume it without re-entering anything.
- US-REG-4. As a user I archive a medication I no longer take and keep its history.
- US-REG-5. As a user I enter how many units I have and am told before I run out.
- US-REG-6. As a user I give a medication a neutral label that is shown instead of its name in discreet mode.

**Domain rules and edge cases**

- R-REG-1. A medication has a name of 1 to 80 characters after trimming. Names are unique per account among non-archived medications, compared case-insensitively after trimming. A duplicate is refused with the existing item offered for opening.
- R-REG-2. Names are free text. No catalogue is offered. Autocomplete, where present, searches only the user's own registry, including archived names.
- R-REG-3. Dose amount is an optional positive decimal with at most 3 fraction digits. Dose unit is optional and chosen from: tablet, capsule, ml, mg, mcg, g, IU, drop, puff, unit, application, sachet, or a custom unit of at most 16 characters. The product never converts between dose units.
- R-REG-4. Instructions are optional user text of at most 200 characters, shown verbatim on the reminder. The product never adds instructions of its own.
- R-REG-5. Status is `active`, `paused` or `archived`. Pausing cancels all pending occurrences of the medication's schedules and stops materialisation. Resuming materialises from the resume instant; nothing is backfilled. A pause may carry a resume date, at which the medication resumes automatically at local midnight of that date in the account zone and the user is told with one silent message.
- R-REG-6. Archiving ends all schedules, keeps all history and removes the medication from pickers. An archived medication can be restored; its schedules are restored as paused and must be resumed explicitly.
- R-REG-7. Permanent deletion (V1) removes the medication, its schedules, occurrences and dose actions. The confirmation names the medication and the number of records. It is not undoable.
- R-REG-8. Changing dose amount, unit or instructions never rewrites history. Each occurrence keeps the dose snapshot of the schedule revision it was created under (existing `dose_snapshot`).
- R-REG-9. Quotas: at most 200 medications and 400 schedules per account (PROPOSED, R06 section 12.3). The limit is stated when reached.
- R-REG-10. A medication without a schedule is an as-needed medication. It never produces an occurrence or a reminder and is logged manually (R-INT-9).
- R-REG-11. Inventory (V1) is optional per medication: units on hand (decimal, at least 0), units per intake (defaults to the dose amount when the dose unit is countable, otherwise must be entered), and a refill threshold expressed either in days of supply (default 7, PROPOSED) or in units.
- R-REG-12. Inventory decreases by the units-per-intake value of the occurrence's snapshot when a dose action `taken` is recorded, for scheduled and as-needed intake alike. `skipped`, `missed` and `unknown` do not change it. Undo and a correction away from `taken` restore the amount; a correction to `taken` deducts it.
- R-REG-13. Days of supply is computed by evaluating the medication's active schedules forward from now. It is the number of whole local days from today to the date of the first scheduled intake that the units on hand cannot cover. It is not units divided by an average. Every-other-day, cycle and taper schedules therefore give a correct figure. For an as-needed medication no days figure is shown and only a unit threshold applies.
- R-REG-14. The refill nudge is sent once when the threshold is crossed, at the next daily touchpoint, and repeats every 3 days (PROPOSED) until the user records a refill or taps "Stop reminding for this pack". It counts against the nudge budget.
- R-REG-15. "Refilled" adds a quantity. The last added quantity is offered as the default. The quantity can be corrected afterwards.
- R-REG-16. Inventory never goes below zero. A deduction that would do so sets it to zero and shows "The count may be off. Update it?" once. The product never refuses or questions a "taken" record because of inventory.
- R-REG-17. Appearance (V1) is a pair of tokens from a fixed set of colours and shapes, used in the Mini App and, when names are enabled for images, in cards. It is decoration and never identifies a product.

**Data captured**

| Field | Limits | Class |
|---|---|---|
| Name | 1 to 80 characters | A |
| Form | enumerated, optional | B |
| Strength text | up to 40 characters, optional | A |
| Dose amount, dose unit | decimal; enumerated or custom | B (custom unit text: A) |
| Instructions | up to 200 characters | A |
| Neutral label | up to 30 characters | A |
| Appearance tokens | enumerated | B |
| Status, pause and resume instants | enumerated, instants | B |
| Inventory: on hand, per intake, threshold, refill events | decimals, instants | B |

**Capture surfaces.** Mini App: Registry list, Medication form with review, Inventory panel. Bot: `/add` (three-step wizard: text form, cadence, review), `/meds` (picker, detail, pause, resume, archive), "Refilled" button on the refill nudge. Image: none; pill appearance tokens may appear in cards when names are enabled.

**Acceptance criteria**

- AC-REG-1. Saving a medication whose name equals an existing non-archived name after trimming and case folding is refused and offers the existing item; the same name as an archived item is accepted. [U][T]
- AC-REG-2. The review step shows exactly the fields that are persisted; after saving, reading the medication back returns the reviewed values. [T][M]
- AC-REG-3. Pausing a medication with pending occurrences cancels all of them, and no reminder for it is sent until resume. [P][S]
- AC-REG-4. Resuming at 14:00 a medication scheduled at 08:00 and 20:00 produces the 20:00 occurrence of that day and no 08:00 occurrence. [U]
- AC-REG-5. A pause with a resume date resumes at local midnight of that date and sends one silent notice. [S]
- AC-REG-6. Editing the dose from 1 to 2 tablets leaves every earlier occurrence showing 1 tablet. [U]
- AC-REG-7. For a medication taken every other day with 5 units on hand and 1 unit per intake, days of supply is 10 when today's intake is still open and 11 when the next intake is tomorrow. It is never 5. [U]
- AC-REG-8. Taken, then Undo, leaves the inventory unchanged; Taken on an as-needed medication reduces it by the units-per-intake value. [U]
- AC-REG-9. Crossing the refill threshold produces exactly one nudge at the next touchpoint and none again within 3 days. [S]
- AC-REG-10. A deduction below zero stores zero and shows the "count may be off" notice once. [U][T]
- AC-REG-11. An as-needed medication never has an occurrence row. [P]
- AC-REG-12. No outbound text produced by this module contains wording about what a medication is for, how much to take or when to take it, other than values the user entered. [O]

### 8.5 `schedule-and-reminders`

The scheduling engine already exists and is tested: schedule rules, the evaluator, daylight-saving resolution, materialised occurrences, the reminder loop, the outbox and catch-up (R01 sections 1 and 3.2). This section states the product behaviour the engine must show through Telegram and the extensions the new direction needs.

| Capability | Tier | Wave |
|---|---|---|
| Fixed times every day; several times per day; selected weekdays; start and end date; as-needed (no schedule) | Must | MVP |
| Reminder with follow-up, snooze, miss deadline; per-schedule reminder policy | Must | MVP |
| Account time zone, daylight-saving rules, time-zone change handling | Must | MVP |
| Quiet-hours mode per schedule (deliver, silent, defer) | Must | MVP |
| Unreachable and outage handling with one catch-up digest | Must | MVP |
| Generic nudge plans and the daily nudge budget (needed by `hydration` and `mood`) | Must | MVP |
| Best-effort statement at onboarding | Must | MVP |
| Reminder window expressed as clock times in the Mini App | Should | V1 |
| Different times on different days (for example weekend times); every N days; every N weeks; cycle (days on, days off); every N hours from a fixed start | Should | V1 |
| One grouped reminder message for doses due in the same minute | Should | V1 |
| "Pause everything until" for the whole account | Should | V1 |
| Adaptive quieting of ignored nudges | Should | V1 |
| Interval after each dose (chain); taper (dose phases over date ranges) | Should | V2 |
| User-defined free-text reminders that are not medication | Could | Later |
| Natural-language schedule entry | Could | Later |
| Alarm-grade or guaranteed delivery | Will not | n/a (PC-04) |

**User stories**

- US-SCH-1. As a user I schedule a medication at fixed times on the days I choose, review the schedule in words, and save it.
- US-SCH-2. As a user I get a reminder at the time I set and one follow-up if I do not answer.
- US-SCH-3. As a user I postpone a reminder by a duration I pick from valid options.
- US-SCH-4. As a traveller I tell Dosegram my new time zone and see exactly when my next doses will be reminded.
- US-SCH-5. As a user I decide what happens to reminders during my quiet hours.
- US-SCH-6. As a user I pause everything while I am in hospital or on holiday and resume later.
- US-SCH-7. As a user who was offline or had blocked the bot, I get one summary instead of a burst of old reminders.
- US-SCH-8. As a user on a taper I enter each phase and its dose myself, and each reminder shows the dose of its phase.

**Schedule kinds**

| Kind | Example | Existing rule case (R01, `core.domain`) | Wave |
|---|---|---|---|
| Fixed times | every day at 08:00 and 20:00; Monday, Wednesday, Friday at 10:00 | `FixedTimes` | MVP |
| As needed | no reminder, manual log | no schedule | MVP |
| Fixed times in groups | weekdays 07:00, weekends 09:00 | `FixedTimes` with several slot groups | V1 |
| Every N days | every 2 days at 09:00 | `EveryNDays` | V1 |
| Every N weeks | every 2 weeks on Monday at 09:00 | `EveryNWeeks` | V1 |
| Cycle | 21 days on, 7 days off | `Cycle` | V1 |
| Interval from a fixed start | every 6 hours from 06:00, at most 4 per day | `IntervalFixedStart` | V1 |
| Interval after each dose | 8 hours after the previous dose was taken | `Chain` | V2 |
| Taper | week 1: 2 tablets, week 2: 1 tablet | `Taper` | V2 |

**Domain rules and edge cases**

Schedules and revisions

- R-SCH-1. A schedule belongs to one medication. A medication may have several schedules. Editing a schedule creates a new revision that applies from the moment of saving. Past occurrences are never rewritten. Pending occurrences of the old revision are cancelled and re-created under the new one.
- R-SCH-2. Slot times are local wall-clock times `HH:MM` in the schedule's zone. A schedule has at most 12 slots per day (PROPOSED). Duplicate slots are refused. The product applies no minimum spacing between slots and gives no opinion on a schedule.
- R-SCH-3. There is at most one live occurrence per schedule, local date and slot.
- R-SCH-4. A schedule has a start date and an optional end date, both local dates in its zone. The last occurrence is the last slot on the end date. After it resolves, the bot sends one silent "course finished" message with "Archive" and "Extend". It gives no advice about continuing.
- R-SCH-5. The schedule review step states the schedule in words in the user's language, including the zone, the first three resulting occurrences and the reminder policy. What is reviewed equals what is saved.

Reminder policy

- R-SCH-6. Each schedule has a reminder policy. The defaults below are PROPOSED. Two of them differ from the defaults in the existing code (`repeatEveryMinutes` 10 and `maxReminders` 3), following the 30-minute follow-up pattern in R03 section 4.2 and the notification-inflation risk in R03 section 6.2. Changing them is Q-07.

| Policy field | Meaning | Default | Range |
|---|---|---|---|
| Initial offset | Minutes before or after the slot at which the first reminder is sent | 0 | -1440 to 1440 |
| On-time grace | Minutes after the slot during which a taken dose counts as on time | 60 | 1 to 10080 |
| Repeat every | Minutes between reminders while the occurrence is due | 30 | 1 to 1440 |
| Maximum reminders | Total reminders including the first | 2 | 1 to 10 |
| Miss after | Minutes after the slot at which an unanswered occurrence is resolved | 120 | 1 to 10080 |
| Snooze options | Durations offered | 10, 30, 60 minutes | each 1 to 1440 |
| Maximum snoozes | Snoozes allowed per occurrence | 3 | existing |
| Miss after snooze | Minutes after a snooze ends before resolution | 30 | 1 to 1440 |
| Maximum late | Latest minutes after the slot to which a snooze may extend | 360 | existing |
| Late-log window | Minutes after the slot during which a direct "Taken" tap is accepted | 1440 | 1 to 43200 |
| Undo window | Minutes during which Undo is offered | 15 | existing |
| Quiet-hours mode | deliver, silent or defer | deliver | enumerated |
| Discreet | Overrides the account's discreet setting for this schedule | account setting | boolean |

- R-SCH-7. A reminder is never sent before its scheduled instant. Outbound pacing may delay it. The punctuality target is X-REM-1.
- R-SCH-8. The reminder window (V1) is the same policy expressed as three clock times in the Mini App: "remind from", "on time until", "mark as not confirmed at". Saving converts them to the fields above; a window whose times are not in ascending order is refused.
- R-SCH-9. Snooze follows ADR-012: the snooze ends at the later of now and the window start plus the chosen duration; it is bounded by the next occurrence of the same schedule minus one minute and by the maximum-late value; options that would pass a bound are not offered; an explicit snooze is honoured inside quiet hours and is delivered silently there.
- R-SCH-10. When the miss deadline passes: if at least one reminder for the occurrence was accepted by Telegram, the occurrence becomes `missed`; if none was, it becomes `unknown` with reason `undelivered` or `outage`. `unknown` is excluded from adherence and is never shown in a failure colour.
- R-SCH-11. The message that reports a missed occurrence is neutral and carries the standing sentence that the user should follow the instructions of their prescriber or the package leaflet when unsure about a late or missed dose. It contains no advice of its own.

Time zones, daylight saving time and travel

- R-SCH-12. Every schedule revision stores an IANA zone and a flag saying whether it follows the account zone (default: follows). All instants are stored in UTC; each occurrence also stores its local date, local time, zone and a daylight-saving marker (ADR-011).
- R-SCH-13. Daylight saving time, per ADR-011. A slot that falls in a spring-forward gap is moved forward by the length of the gap (a 03:30 slot on the Kyiv spring night is reminded at 04:30 local time). A slot that falls in an autumn overlap uses the first of the two instants. In both cases exactly one occurrence exists for that local date and slot. "Every N hours from a fixed start" re-anchors on each local day; "interval after each dose" counts elapsed real time.
- R-SCH-14. Quiet-hours mode per schedule, per ADR-012. `deliver`: sent normally. `silent`: sent without sound (PC-28) and counted. `defer`: the window start moves to the end of quiet hours and the miss deadline is extended; if the end of quiet hours would pass the next occurrence, the reminder is sent silently instead. Quiet hours never produce `unknown`. The default is `deliver`, because the user chose the time. Rejected alternative: default `defer`, which would silently move night-time doses.
- R-SCH-15. Changing the account zone, for every schedule that follows it:
  1. A new revision takes effect at the instant of the change, with the same wall-clock times in the new zone.
  2. Occurrences that are `due`, `snoozed` or resolved are untouched. `pending` occurrences are cancelled and re-created.
  3. A slot for the current local date in the new zone is not created again if an occurrence for that date and slot is already live or resolved (R-SCH-3). A traveller flying west is therefore not reminded twice for the same wall-clock slot.
  4. A slot of the current local date whose new instant is already past: if it is past by no more than the miss-after value, the occurrence is created as `due` now and reminded once; otherwise it is created as `cancelled` with reason `time_zone_change`, shown with a neutral "not scheduled (time zone changed)" marker and excluded from adherence.
  5. The bot sends one review message listing the doses of the next 24 hours with their new local times, with "Looks right" and "Keep home time".
  6. The product does not compute or comment on the real-time interval between doses across the change (N2). The review message carries the standing sentence about following the prescriber or the leaflet.
- R-SCH-16. "Keep home time" sets a schedule to a fixed zone. Its reminders then arrive at the same absolute instants as before, and the reminder shows both the home time and the local time.
- R-SCH-17. The bot cannot detect travel (PC-02). The only triggers for a zone change are the Mini App's device-zone prompt (R-IDC-13) and `/timezone`. A user who travels without changing the zone keeps receiving reminders on home time; this is stated in the help text.
- R-SCH-18. Displayed times use the schedule's zone and say so when it differs from the account zone. Where the client supports it, instants are also sent as date-time entities so the client can show them locally (PC-20; local rendering is PC-20a, ASSUMPTION), with the plain text as the fallback.

Pauses, outages and catch-up

- R-SCH-19. "Pause everything until <date>" (V1) cancels every pending occurrence and every nudge until local midnight of that date, then resumes without backfill. While paused, the Today card says so and offers "Resume now". Pausing gives no opinion and asks no reason.
- R-SCH-20. After a service outage, a safe shutdown, or a period in which the account was unreachable, occurrences whose window has passed are not reminded. One catch-up digest per account lists them with their records' controls. Stale reminders are never sent in a burst.

Nudges

- R-SCH-21. A nudge plan belongs to one non-medication domain and has: a kind (interval inside a daily window, fixed touchpoint, or event-driven), a daily cap, and a stop condition. Nudge plans use the same scheduling engine and outbox as reminders but a different message class (section 3.2).
- R-SCH-22. The daily nudge budget is an account setting: the maximum number of nudges per local day across all domains. Default 4 (PROPOSED; ROADMAP conflict X-06, owner question RQ-9); range 0 to 12. Fixed touchpoints (morning, midday, evening) count as one nudge each however many domains they carry. When the budget is spent, further nudges of that day are dropped, not queued. A report counts as one unit on the day it is sent (section 3.2). Reminders, the catch-up digest, outcomes, caregiver alerts and service notices never count.
- R-SCH-23. No nudge and no report is sent inside quiet hours. A nudge that falls there is dropped: its prompt ends as `suppressed` with the reason `quiet_hours` and is not moved to the end of quiet hours. A report is sent at the end of quiet hours.
- R-SCH-24. Adaptive quieting (V1): after three consecutive nudges of one kind receive no response, that kind pauses for 7 days and the bot says so once, with "Turn back on". It never applies to reminders.
- R-SCH-25. At most one unanswered nudge message per domain exists in the chat. When a new nudge of the same domain is due, the previous unanswered one is deleted if it is younger than 48 hours (PC-06), otherwise edited to a one-line expired state.
- R-SCH-25a. Owner decision of 2026-11-13 (ADR-023): the daily budget of R-SCH-22 is a default. A nudge plan the user chose from a preset that carries its own cap (the water interval presets, including "Work hours, every 30 min") is capped by its own slot count and does not draw on the shared budget. Quiet hours (R-SCH-23), the 20-minute spacing between kinds, silent delivery, one open nudge per kind (R-SCH-25), the skip after an entry or a reached goal (R-HYD-10), and a one-tap way to pause or turn the plan off still apply to it. For such a plan, adaptive quieting is per day: after three unanswered nudges only every second slot is sent, after six the kind pauses until the next day, each said once.

Priority, grouping and disclosure

- R-SCH-26. Outbound priority, highest first: outcomes and callback answers; reminders; caregiver alerts; nudges; reports; service notices. A lower class never delays a higher one (PC-05).
- R-SCH-27. Grouped reminders (V1): occurrences of one account whose first reminder falls in the same minute are delivered as one message that lists each item with its own controls and offers "All taken". Each occurrence keeps its own state and history. In MVP each occurrence has its own message, paced at one message per second in the chat.
- R-SCH-28. Onboarding and the help text state, in plain words: reminders are Telegram messages; whether they alert depends on the user's Telegram and device settings, which Dosegram cannot see; Dosegram is not an alarm and must not be the only safeguard for a time-critical medication.
- R-SCH-29. With discreet mode on, a reminder names the time or the neutral label only; the medication name is shown after "Show" (R-IDC-14).

Taper and chain (V2)

- R-SCH-30. A taper is an ordered list of phases, each with a date range, a dose amount and a schedule rule, all entered by the user. Phases must not overlap; a gap between phases is allowed and produces no occurrences. The product never proposes, completes or extrapolates a phase. Each occurrence carries the dose of its phase. The bot sends one silent message on the first day of each phase stating the dose the user entered for it.
- R-SCH-31. A chain creates the next occurrence when the previous one reaches a terminal state, at the previous effective time plus the interval, within the day window the user set. If the day's first dose is not started by the cutoff, the remaining rows of that day are recorded as not started and excluded from adherence (ADR-012).

**Data captured**

| Record | Fields | Class |
|---|---|---|
| Schedule | medication, zone, follows-account flag, start date, end date, status, current revision | B |
| Schedule revision | rule, reminder policy, dose snapshot, effective instant | B (dose snapshot may contain a custom unit: A) |
| Occurrence | scheduled instant, local date, local time, zone, daylight-saving marker, window, status, reminder count, snooze state | B |
| Nudge plan | domain, kind, window, cap, stop condition | B |
| Delivery state | per message: class, accepted instant, outcome; no content | D |

A schedule's timing alone is health data (class B), because regular reminder times reveal a treatment pattern even without a name (R06 section 6).

**Capture surfaces.** Mini App: Schedule editor with review, Reminder settings, Time zone, Quiet hours, Pause. Bot: the cadence and review steps of `/add`; reminder messages with Taken, Skip, Snooze; `/timezone`; `/pause`; the zone review message; the catch-up digest. Image: none.

**Acceptance criteria**

- AC-SCH-1. A schedule "every day at 08:00 and 20:00" produces exactly two occurrences per local date for 30 simulated days, including across a daylight-saving change in each direction. [U][S]
- AC-SCH-2. For Europe/Kyiv, a 03:30 slot on the spring-forward date yields one occurrence at 04:30 local time, and a 03:30 slot on the autumn date yields one occurrence at the first 03:30. The generated golden rows for the zones named in ADR-011 pass unchanged. [G]
- AC-SCH-3. With default policy and no answer, exactly two reminders are sent, at the slot and 30 minutes later, and the occurrence resolves at 120 minutes. [S]
- AC-SCH-4. No reminder is ever accepted by the fake wire at an instant earlier than its scheduled instant, across the simulation. [S]
- AC-SCH-5. Snooze options that would end after the next occurrence minus one minute, or after the maximum-late bound, are absent from the keyboard. [U][T]
- AC-SCH-6. An occurrence whose reminders all failed to send resolves as `unknown(undelivered)`; one with at least one accepted reminder resolves as `missed`. [P]
- AC-SCH-7. In `defer` mode a 02:00 slot inside quiet hours 22:00 to 07:00 is reminded at 07:00, is not marked missed before 07:00 plus the miss-after value, and has a `reminder_deferred` action. In `silent` mode it is sent at 02:00 with the silent flag. [U][T]
- AC-SCH-8. Changing the account zone from Europe/Kyiv to America/New_York at 12:00 Kyiv time with a daily 08:00 and 20:00 schedule: the 08:00 Kyiv dose of that date, already resolved, is not created again for the same local date in New York; the next occurrences are 20:00 and 08:00 New York time; the review message lists them. [U][T]
- AC-SCH-9. Changing the zone eastward so that today's slot is 3 hours past (more than the miss-after value) yields a `cancelled` occurrence with reason `time_zone_change`, no reminder, and no effect on adherence. [U]
- AC-SCH-10. A schedule set to "Keep home time" is reminded at the same UTC instants before and after an account zone change. [U]
- AC-SCH-11. After a simulated 6-hour outage that covers two slots, no reminder for those slots is sent, both resolve as `unknown(outage)`, and exactly one digest is sent. [S]
- AC-SCH-12. With a nudge budget of 6, a day that would generate 9 nudges sends 6 and drops 3; the count of reminders that day is unaffected. [S]
- AC-SCH-13. No nudge or report is accepted by the fake wire inside the account's quiet hours across the simulation. [S]
- AC-SCH-14. With reminders and reports queued together under a constrained rate, every reminder is sent before any report. [S]
- AC-SCH-15. The schedule review text equals the persisted schedule for every supported kind: rendering the persisted revision reproduces the reviewed text. [G]
- AC-SCH-16. The onboarding transcript contains the best-effort statement of R-SCH-28. [G]
- AC-SCH-17. (V1) Three occurrences due in the same minute produce one message; "All taken" resolves all three with one tap, and each has its own dose action. [T][P]
- AC-SCH-18. (V2) A taper with a gap between phases produces no occurrence in the gap, and each occurrence shows its phase's dose. An overlapping phase is refused at validation. [U]
- AC-SCH-19. (V1) After three unanswered hydration nudges the fourth is not sent, one notice is sent, and nudges resume after 7 days or on "Turn back on". [S]

### 8.6 `intake-log`

The dose state machine exists and is tested cell by cell (R01 section 3.2). ADR-012 is the precise definition. This section states the behaviour as the user sees it on Telegram and the additions.

| Capability | Tier | Wave |
|---|---|---|
| Taken, Skip, Snooze on the reminder message; the message is edited to its final state | Must | MVP |
| Undo within the undo window; Correct to "now" or "at scheduled time" | Must | MVP |
| As-needed intake log; extra intake outside a schedule | Must | MVP |
| Today and 7-day history in chat; adherence text for today, 7 and 30 days | Must | MVP |
| Corrected "taken at" with a custom time; notes; skip reasons | Should | V1 |
| Full history with paging, filter and correction in the Mini App calendar | Should | V1 |
| Adjustable amount on an as-needed intake | Should | V1 |
| Rewards, streaks or points for intake | Will not | n/a (N11) |
| Any comment on timing, amount or frequency | Will not | n/a (N2) |

**User stories**

- US-INT-1. As a user I tap "Taken" on the reminder and see the message change to "Recorded at 08:03".
- US-INT-2. As a user who tapped by mistake I undo within a few minutes.
- US-INT-3. As a user who took the dose earlier and tapped late I set the real time.
- US-INT-4. As a user I log an as-needed medication in two taps and see when I last logged it.
- US-INT-5. As a user I see what I took today and this week as plain counts.
- US-INT-6. As a user I add a short note or a reason when I skip.

**Domain rules and edge cases**

- R-INT-1. Occurrence statuses are `pending`, `due`, `snoozed`, `taken`, `skipped`, `missed`, `unknown`, `cancelled`. Every change is an entry in an append-only action log. A terminal status ends automatic reminders; it does not make history immutable, because corrections are new entries.
- R-INT-2. Three times are kept apart for every intake: the scheduled instant, the effective instant (when the user says it was taken) and the recorded instant (when the tap arrived). Adherence uses the effective instant.
- R-INT-3. "Taken" inside the late-log window records the effective instant as now. If the tap arrives after the on-time grace, the outcome message offers "Taken earlier?" with "At scheduled time" (MVP) and "Other time" (V1). This answers the failure reported for other applications, where a late tap was stored as the intake time (R03 section 3.1).
- R-INT-4. "Taken" on a reminder older than the late-log window changes nothing by itself and opens the correction choice ("Now", "At scheduled time"). A tap on a `skipped` record opens the same choice. A tap on a `cancelled` record is refused with a short explanation. A repeated tap on an already recorded outcome replays that outcome and creates no second action.
- R-INT-5. "Skip" records a deliberate decision. Skipped is its own category, shown neutrally, never as a failure. From V1 the outcome offers an optional reason from a closed list (out of stock; not available right now; advised by my clinician; did not want to; other) and an optional note.
- R-INT-6. Undo is offered for the undo window. It returns the occurrence to `due`, clears the outcome, extends the miss deadline so the user is not marked missed seconds later, and restores inventory (ADR-012).
- R-INT-7. A correction sets the status and the effective instant explicitly, is recorded as its own action, and is excluded from delay statistics. The effective instant must not be in the future and must not be earlier than 24 hours before the scheduled instant (PROPOSED).
- R-INT-8. Each displayed manual-log control commits at most once, even on rapid repeated taps. "Log another" issues a new control. This rule also governs hydration and meal quick-add buttons (R-HYD-6).
- R-INT-9. As-needed intake: from the Today card or `/take` (alias `/log`), the user picks the medication and the intake is recorded with the effective instant as now. It takes at most two taps. The outcome states two facts and nothing else: the time since the previous logged intake of that medication, and the number logged today. The product computes no limit, shows no warning and never refuses a log (N2).
- R-INT-10. Logging a scheduled medication manually: if it has an open occurrence, the picker offers that occurrence first. Otherwise the user may log an extra intake, which is recorded with origin `manual` and excluded from adherence.
- R-INT-11. Adherence over a period is taken divided by the sum of taken, skipped and missed, over resolved scheduled occurrences. `unknown`, `cancelled`, manual and open rows are excluded and their counts are shown beside the figure. A denominator of zero is displayed as "No data", never as 0 percent. A denominator from 1 to 4 is displayed as counts only ("3 of 4 taken"), without a percentage; a percentage is shown from a denominator of 5 (the minimum-sample rule of VISUALS_AND_ANALYTICS section 7.1). On-time rate, and the median and 90th percentile of delay, are reported separately and exclude corrections.
- R-INT-12. Notes (V1) are user text of at most 500 characters, class A, attached to an action. They are never parsed.
- R-INT-13. If the result of a tap cannot be confirmed (the commit is uncertain), the bot shows "Checking whether this was recorded" and resolves it from durable state before saying either "Recorded" or "Nothing was recorded" (existing recovery protocol, R01 section 5).
- R-INT-14. Read-only views (Today, history, statistics) create no action.
- R-INT-15. A control that belongs to another account, an expired session or a superseded message reveals no names and changes nothing.

**Data captured**

| Record | Fields | Class |
|---|---|---|
| Dose action | occurrence, action kind, actor (user or system), effective instant, recorded instant, idempotency key, source surface | B |
| Note, skip reason "other" text | up to 500 characters | A |
| As-needed intake | medication, amount snapshot, effective instant, origin | B |

**Capture surfaces.** Bot: reminder message controls; outcome message with Undo and Correct; `/take`; `/today`; `/history` (7 days); `/stats`. Mini App: Day view, Calendar, Intake detail with correction and notes. Image: adherence heatmap and weekly card (through `visual-reports`).

**Acceptance criteria**

- AC-INT-1. Taken, Skip, each offered Snooze, and Undo each take one tap on the visible reminder or outcome message. [T]
- AC-INT-2. After "Taken" the reminder message no longer has Taken, Skip or Snooze controls and shows the recorded local time. [T][G]
- AC-INT-3. Two identical taps delivered concurrently produce one dose action and two identical outcomes. [P]
- AC-INT-4. A "Taken" tap 3 hours after the slot records now as the effective instant, classifies the intake as late, and offers "Taken earlier?"; choosing "At scheduled time" changes the effective instant to the slot, classifies it as on time and excludes it from delay statistics. [U][T]
- AC-INT-5. A "Taken" tap on a three-day-old reminder records nothing until the user chooses "Now" or "At scheduled time". [T]
- AC-INT-6. Undo at 14 minutes restores `due` and the next reminder time; Undo is not offered at 16 minutes. [U][T]
- AC-INT-7. An as-needed log from the Today card takes two taps and its outcome shows the time since the previous log and today's count, and contains none of the words in the forbidden-phrase list. [T][O]
- AC-INT-8. With 8 taken, 1 skipped, 1 missed, 2 unknown and 1 cancelled in a period, adherence is displayed as 80 percent with "2 unknown, 1 cancelled not counted". With only unknown rows it is "No data". [U]
- AC-INT-9. The statistics query result equals the existing pure adherence function for every generated fixture. [U][P]
- AC-INT-10. A callback built for account A and replayed by account B returns a generic refusal with no name and writes nothing. [T][P]
- AC-INT-11. A manual log control tapped five times in one second creates one intake. [P]
- AC-INT-12. No message produced by this module tells the user to take, not take, delay or double a dose. The safety-copy fixtures of `docs/MEDICATION_REMINDER_UX.md` pass in every shipped locale. [O][G]

### 8.7 `visual-reports`

The pipeline, card catalogue, canvas rules and test strategy are specified in VISUALS_AND_ANALYTICS.md from R04. This section fixes what the product requires of them.

| Capability | Tier | Wave |
|---|---|---|
| Rendering pipeline with text equivalent and text fallback | Must | MVP |
| Hydration vessel card; static mood blob; daily summary card | Must | MVP |
| Light and dark themes; discreet rendering; protected content by default | Must | MVP |
| Weekly summary card; adherence heatmap; trend charts; sleep timeline | Must | V1 |
| Mood calendar (month and year); correlation cards; record summary document (PDF) | Should | V2 |
| Animated mood blob as a sticker | Could | V2, only after its spike passes (R04 spike S5) |
| In-message widgets as an enhancement with an image fallback | Could | Later (PC-15) |
| Photographic or model-generated imagery | Will not | n/a |

**User stories**

- US-VIS-1. As a user I get a picture that tells me my state at a glance and reads correctly in my chat theme.
- US-VIS-2. As a user of a screen reader, or on a slow connection, I get the same information as text.
- US-VIS-3. As a user I decide whether images may show medication names and whether they can be forwarded.

**Domain rules and edge cases**

- R-VIS-1. An image, its caption and its text view are produced from one immutable snapshot. A number that appears in the image appears in the text.
- R-VIS-2. Every image has a text equivalent that states content, not appearance ("Water: 1.8 of 2.5 litres, 72 percent"). It is the caption when it fits the caption limit and otherwise a text message sent immediately after (PC-06).
- R-VIS-3. Rendering starts after the interaction is acknowledged and never inside the transaction that records the user's action. A failed, late or refused render sends the text equivalent. It never changes a record, never blocks a reminder and never marks the account unreachable.
- R-VIS-4. Medication names are absent from images until the user enables "Show names in images" once, after reading that images stay in the chat. In discreet mode they are absent regardless.
- R-VIS-5. Images that contain personal data are sent as protected content by default (PC-28). The setting can be turned off. Global assets that contain no personal data (the mood blob states) are not protected.
- R-VIS-6. Sending generated images that contain health data into the chat requires the `images_in_chat` consent, asked the first time an image would be sent. Without it the user receives text.
- R-VIS-7. Theme: an account setting of auto, light or dark. In auto the last colour scheme reported by the Mini App is used; with none reported the default is dark (R04 section 8.3).
- R-VIS-8. Status is never carried by colour alone. Unknown is neutral. Skipped is not drawn in a failure colour. A gap in data is drawn as a gap. A denominator of zero is "No data".
- R-VIS-9. No card contains a composite score, a causal statement, advice, a diagnosis or a judgement colour on a measured value. Multi-page reports carry the line "Recorded entries only. Not medical advice."
- R-VIS-10. Every sleep card states the data tier in words and draws estimated intervals as visibly uncertain (R-SLP-14).
- R-VIS-11. File names are neutral and carry no account identifier or medication name. Images carry no metadata.
- R-VIS-12. Animation is opt-in. The default mood reply is a static image. An animated sticker has no caption (PC-24) and is therefore always followed by a text message.
- R-VIS-13. A full-quality copy of a card is sent as a document only on explicit request.

**Data captured.** No user data of its own. Rendered bytes are kept only until delivery and are purged within one hour of the terminal state; the reusable Telegram file identifier of a personal image is kept for at most 48 hours (R04 section 7.6). Settings: theme, names in images, protected content, animation.

**Capture surfaces.** Bot: every card has a "Text view" control. Mini App: the same charts as inline graphics with interaction. Image: this module is the image surface.

**Acceptance criteria**

- AC-VIS-1. For every card fixture, each numeric value in the view model appears in both the image source and the text equivalent. [G]
- AC-VIS-2. When the renderer is disabled or exceeds its timeout, the user receives the text equivalent and the triggering record is unchanged. [T][S]
- AC-VIS-3. With "Show names in images" off, a canary medication name appears in no image source, caption, alt text or file name. [G][O]
- AC-VIS-4. Personal cards are sent with the protected-content flag unless the setting is off; mood blob states are not. [T]
- AC-VIS-5. Without the `images_in_chat` consent no personal image is sent and the text equivalent is. [T]
- AC-VIS-6. Every card template passes the contrast, minimum text size and overflow lints in both themes. [G]
- AC-VIS-7. Tap to image visible is at most 2.5 seconds at the 95th percentile in the production container (X-LAT-4). [O]
- AC-VIS-8. While the render queue is saturated, reminder timeliness (X-REM-1) is unchanged. [S]
- AC-VIS-9. Every card fixture with all-unknown, empty and single-point data renders "No data" or a gap, never a zero. [G]

### 8.8 `hydration`

| Capability | Tier | Wave |
|---|---|---|
| Quick-add presets; daily goal set by the user; progress in the Today card; vessel card | Must | MVP |
| Undo; edit and delete an entry; add for yesterday | Must | MVP |
| Text quick-log ("300 ml") | Should | MVP |
| Interval nudges with a cap and automatic silence at the goal | Should | MVP |
| Drink types; fluid ounces; backdating any day in the Mini App | Should | V1 |
| Optional per-drink hydration factor; separate caffeine and alcohol tallies | Could | V2 |
| Self-challenges | Could | Later |
| A goal computed from body weight, activity or weather; any statement that the user drinks too little or too much | Will not | n/a (N2, N5) |

**User stories**

- US-HYD-1. As a user I tap "+250 ml" and see my progress change.
- US-HYD-2. As a user I set my own daily goal and my own preset volumes.
- US-HYD-3. As a user I type "300 ml" and it is recorded.
- US-HYD-4. As a user I am nudged a few times a day if I want that, and never after I reach my goal.
- US-HYD-5. As a user I fix a wrong entry.

**Domain rules and edge cases**

- R-HYD-1. An entry has an amount, a drink type (default water), an effective instant and a source. Amounts are stored canonically in millilitres as an integer from 1 to 5000, together with the value and unit the user entered, so that an entry made as "12 fl oz" is always shown as 12 fl oz and never as a rounded conversion. One US fluid ounce is 29.5735 ml.
- R-HYD-2. The daily goal is set by the user. Setup offers 1500, 2000, 2500 ml and "Other" (PROPOSED; "Other" accepts 100 to 10,000 ml), with the sentence that this is the user's own target and not medical advice. The product never computes or recommends a goal. A user may have no goal; progress is then shown as a total only.
- R-HYD-3. Up to four quick-add presets per account. Defaults: 200, 300 and 500 ml, or 8, 12 and 16 fl oz (PROPOSED).
- R-HYD-4. An entry belongs to the local day given by its effective instant, the account zone in force at that instant and the day rollover time. The local date and zone are stored on the entry. Changing the zone later does not move past entries to another day.
- R-HYD-5. Progress above 100 percent is shown as the true number; the vessel is drawn full. No message comments on a high or a low total.
- R-HYD-6. Each displayed quick-add control commits at most once (R-INT-8). Because quick-add buttons on the Today card are pressed repeatedly by design, each press is answered with a new control state: the message is edited after every entry, and every edit issues fresh controls.
- R-HYD-7. A quick-add is acknowledged with a short toast and an edit of the Today card. It does not send a new message or an image. The vessel card is sent on `/water` and, if the user enabled it, once on the day the goal is first reached.
- R-HYD-8. Text quick-log: an unambiguous amount with a unit ("300 ml", "0,5 l", "12 oz", "water 300") is recorded at once and echoed with "Undo" and "Edit". A bare number or an amount outside 1 to 5000 ml is not recorded; the bot asks. Parsing is deterministic; no model is used.
- R-HYD-9. "Undo" removes the last entry within 15 minutes. Entries can be edited or deleted in the Mini App at any age. Edits change the day totals; no history of edits is shown to the user, but the change is an audit event without content.
- R-HYD-10. Nudges are off until the user chooses a plan at setup (forced choice: off, every 2 hours, every 3 hours, custom) inside a daily window (default 09:00 to 21:00, PROPOSED). A nudge is sent only if no entry was recorded since the previous nudge time and the goal is not reached. Nudges stop for the day when the goal is reached. They respect quiet hours, the daily budget and adaptive quieting (R-SCH-22 to R-SCH-25). Each nudge carries the quick-add presets.
- R-HYD-10a. The water plans are the presets of ADR-023: "Work hours, every 30 min" (from the start of work to half an hour before its end, work days only), "Every hour, 08:00 to 21:00", "Gentle: 4 a day at meal times", "Only when I am behind my goal" (the goal spread evenly over the window), a custom interval of 30 to 720 minutes inside a window, and off. The choice stays forced with nothing preselected; the interval presets carry their own cap (R-SCH-25a).
- R-HYD-11. Drink types (V1): water, tea, coffee, juice, milk, soft drink, other. By default every type counts fully toward the goal. The optional hydration factor (V2) is off by default and, when on, uses factors the user sets per type; the product ships no factors of its own.
- R-HYD-12. Quota: at most 60 entries per local day (PROPOSED). The limit is stated when reached.

**Data captured**

| Record | Fields | Class |
|---|---|---|
| Entry | canonical ml, entered value and unit, drink type, effective instant, local date, zone, source | B |
| Goal and presets | ml values, effective from | B |
| Nudge plan | window, interval, cap | B |

**Capture surfaces.** Bot: quick-add row on the Today card and on nudges; `/water`; typed amounts. Mini App: Hydration day view, entry editor, goal and presets. Image: vessel card; hydration bars in the weekly card.

**Acceptance criteria**

- AC-HYD-1. One tap on a preset creates one entry, answers the callback, and edits the Today card to the new total; no new message is sent. [T]
- AC-HYD-2. The same control delivered twice creates one entry; two presses of the refreshed control create two. [P]
- AC-HYD-3. An entry of 12 fl oz is stored as 355 ml and displayed as 12 fl oz; after switching the account to millilitres it is displayed as 355 ml. [U]
- AC-HYD-4. "0,5 l" and "0.5 l" each record 500 ml; "500" alone records nothing and asks; "9 l" records nothing and asks. [U][T]
- AC-HYD-5. An entry at 23:50 local time followed by a zone change stays on its original local date. [U]
- AC-HYD-6. With a goal of 2000 ml and entries totalling 2300 ml the text shows 2300 of 2000 ml and 115 percent, the vessel is full, and no further nudge is sent that day. [U][S]
- AC-HYD-7. With nudges every 2 hours from 09:00 to 21:00 and an entry at 10:30, the 11:00 nudge is not sent and the 13:00 nudge is sent if no further entry exists. [S]
- AC-HYD-8. No hydration nudge is sent inside quiet hours or after the daily budget is spent. [S]
- AC-HYD-9. Undo within 15 minutes removes the last entry and restores the previous total; after 15 minutes the Undo control is absent. [T]
- AC-HYD-10. No string of this module contains a recommendation of an amount other than the labels of the preset choices. [O]

### 8.9 `mood`

| Capability | Tier | Wave |
|---|---|---|
| One-tap check-in on a five-step scale; static mood blob reply | Must | MVP |
| Optional second step: energy, tags, note | Should | MVP (energy), V1 (tags and note in chat; all three in the Mini App from MVP) |
| Scheduled check-in prompt at a touchpoint | Should | MVP |
| Month and year mood calendar; trend chart | Should | V1 (trend), V2 (calendar) |
| Breathing exercise in the Mini App | Could | Later |
| Animated blob | Could | V2 (R-VIS-12) |
| Scored questionnaires; statements about the user's mental state; reactions that differ by value | Will not | n/a (N4) |

**User stories**

- US-MOO-1. As a user I tap how I feel and get a small picture back.
- US-MOO-2. As a user I may add how energetic I feel, what it relates to, and a private note.
- US-MOO-3. As a user I am asked once in the evening if I have not checked in.
- US-MOO-4. As a user I see my month as a calendar.

**Domain rules and edge cases**

- R-MOO-1. A check-in has a valence from 1 to 5, labelled in the user's language as very unpleasant, unpleasant, neutral, pleasant, very pleasant (catalogue keys `mood.valence.1` to `mood.valence.5`). These five labels are the only names of a captured valence step: the chat buttons, the label inside the image, the caption, the text equivalent and the Mini App all use the same keys. A caption reads as the valence label followed, when energy was given, by the energy label ("Pleasant. Energy: high."); it does not use a number ("4 of 5") or a second vocabulary. The descriptive state names of VISUALS_AND_ANALYTICS section 4 are used only for averaged states that fall between captured steps. Valence is the only mandatory field and is given with one tap on a row of five buttons. This replaces the 1 to 10 level in the existing `/mood` command (Q-08).
- R-MOO-2. Energy is optional, from 1 to 5, labelled very low, low, medium, high, very high (catalogue keys `mood.energy.1` to `mood.energy.5`), with the same one-vocabulary rule as R-MOO-1. Without it the blob is drawn at middle energy and statistics treat energy as absent, not as 3.
- R-MOO-3. Tags are optional, at most 5 per check-in in total, from one list the user can edit; there is no separate fixed catalogue of feeling words and context words. The default list is neutral and names no condition: sleep, work, study, family, friends, partner, health, exercise, food, weather, money, rest (PROPOSED). User-created tag names are class A.
- R-MOO-4. A note is optional user text of at most 500 characters, class A. Notes are never parsed, classified or searched by the service, and never appear in images, reports or caregiver views.
- R-MOO-5. Several check-ins per day are allowed. The day value used in calendars is the arithmetic mean of that day's valences, shown with one decimal on the 1 to 5 scale in text ("3.5") and rounded half up for the calendar cell. Every surface that shows a day value or a period average of mood uses this mean on the 1 to 5 scale; a "latest check-in" display, where one exists, is labelled as such. A day without a check-in is a gap.
- R-MOO-6. The product's response to a check-in is the same for every value: the outcome, the blob and the optional second step. It never adds advice, concern, praise or a suggestion that depends on the value (N4).
- R-MOO-7. A static notice is always reachable from `/help` and from the mood settings: Dosegram is not a medical or crisis service, and a person in danger should contact local emergency services. The notice is not triggered by any value or text. Its regional wording needs review (Q-17).
- R-MOO-8. The mood blob is a generated abstract shape. Each pair of valence and energy maps to exactly one blob state; the same pair always gives the same image. Blob states contain no personal data and are shared assets (R04 section 9). The blob shows nothing that mocks or dramatises a low value.
- R-MOO-9. The check-in prompt is a nudge at the evening touchpoint by default, sent only if no check-in exists for the local day. The user may choose up to three prompt times or none. "Not now" dismisses it with one tap.
- R-MOO-10. A check-in can be edited or deleted in the Mini App. In chat, "Undo" is offered for 15 minutes.
- R-MOO-11. The reply to a check-in replaces the prompt message with the blob image and its text equivalent in place (PC-21), so a check-in adds no extra message.

**Data captured**

| Record | Fields | Class |
|---|---|---|
| Check-in | valence 1 to 5, optional energy 1 to 5, tag ids, effective instant, local date, zone, source | B |
| Note | up to 500 characters | A |
| Tag list | names up to 24 characters | A for user-created names |
| Prompt plan | times | B |

**Capture surfaces.** Bot: `/mood`; the five-button row on the Today card and on the evening wrap-up; the optional second step. Mini App: check-in with slider, tags and note; calendar; trend. Image: static blob; mood strip in the weekly card; calendar (V2).

**Acceptance criteria**

- AC-MOO-1. One tap on a valence button records a check-in and replaces the prompt with the blob and its text equivalent; no additional message is sent. [T]
- AC-MOO-2. The outcome text and the set of follow-up controls are identical for all five valence values apart from the value label. [G]
- AC-MOO-3. The same valence and energy pair yields the same image identifier on every send; all states have a golden image. [G]
- AC-MOO-4. A day with valences 2 and 5 shows 3.5 in text and the cell for 4 in the calendar; a day without check-ins is rendered as a gap. [U][G]
- AC-MOO-5. A canary note text appears in no image, report, export preview shown to a caregiver, log or metric. [O]
- AC-MOO-6. The evening prompt is not sent on a day that already has a check-in. [S]
- AC-MOO-7. The static notice of R-MOO-7 is present in `/help` and in the mood settings in every shipped locale. [G]
- AC-MOO-8. A check-in without energy is excluded from energy statistics and drawn at middle energy. [U][G]
- AC-MOO-9. No catalogue string of this module names a condition or uses a word from the clinical forbidden list. [O]

### 8.10 `daily-overview`

| Capability | Tier | Wave |
|---|---|---|
| The Today card, edited in place through the day | Must | MVP |
| Evening wrap-up as one message | Should | MVP |
| Daily summary card on request | Should | MVP |
| Morning touchpoint with the sleep confirmation; midday touchpoint | Should | V1 |
| Optional pinning of the Today card | Could | V1 |

**User stories**

- US-DAY-1. As a user I open the chat and see in one message what is due, what is done and what I logged today.
- US-DAY-2. As a user I log water or mood from that same message.
- US-DAY-3. As a user I get one evening message instead of one per domain.

**Domain rules and edge cases**

- R-DAY-1. There is at most one Today card per account and local day. It is created by `/today`, by the first touchpoint of the day, or by the first record of the day, and is edited in place after every record. An edit follows each record at once when no edit was sent to that chat in the preceding second; otherwise the changes are coalesced into one edit one second later (PC-05). Every edit issues fresh quick-action controls (R-HYD-6). An edit that changes nothing is treated as success.
- R-DAY-2. It shows, for each enabled domain only: scheduled doses with their state and time; water total and goal; the latest mood; from V1, last night's sleep and the meals logged. Each line is a fact. It contains no score.
- R-DAY-3. It carries quick actions for the enabled domains (water presets, mood row, "Log dose", "Open app"). It never carries the Taken, Skip or Snooze controls of a due occurrence in MVP; those stay on the reminder message, and the Today card links to it.
- R-DAY-4. A reminder is always its own message. The Today card never replaces, delays or absorbs a reminder.
- R-DAY-5. At the start of the next local day the previous Today card is edited to a compact final summary without controls. If it is younger than 48 hours it may instead be deleted when the user chose "keep the chat tidy" (PC-06).
- R-DAY-6. With discreet mode on, doses are listed by time or neutral label.
- R-DAY-7. The evening wrap-up is one silent message at the evening touchpoint (default 21:00, PROPOSED). It contains only what is open: a mood row if no check-in exists, the count of doses not confirmed today with a "Review" control, and, from V1, "Going to sleep". If nothing is open it is not sent. "Not today" dismisses it with one tap.
- R-DAY-8. Touchpoints are optional and their times are account settings. Each counts as one nudge (R-SCH-22).
- R-DAY-9. If the account zone changes during the day and the local date in the new zone is the same, the same Today card continues. If the date differs, a new one is created.
- R-DAY-10. An account with no enabled domain and no record receives no Today card and no touchpoint.

**Data captured.** No user data of its own: the identifier of the current Today card per account and day (class D), and the touchpoint times (class C).

**Capture surfaces.** Bot: `/today`, the Today card, the wrap-up. Mini App: the dashboard shows the same facts. Image: the daily summary card.

**Acceptance criteria**

- AC-DAY-1. Ten records in one day leave exactly one Today card for that day in the transcript. [T][S]
- AC-DAY-2. A domain without consent contributes no line and no control to the Today card. [T]
- AC-DAY-3. The Today card text equals the day summaries published by each domain for the same instant. [U]
- AC-DAY-4. On a day with a check-in, no open dose and no sleep feature, the wrap-up is not sent. [S]
- AC-DAY-5. The wrap-up counts as one nudge and is not sent inside quiet hours. [S]
- AC-DAY-6. At day change the previous Today card has no controls. [T]
- AC-DAY-7. Three records made within one second through three different controls (a typed amount, a Mini App entry and a mood tap) produce three records and at most two edit requests, and the final text reflects all three. Five presses of one displayed quick-add control within one second produce one entry. [T][P]

### 8.11 `sleep`

**Decision on the owner's idea.** The owner asked for a sleep tracker "based on the last seen of the user". That signal is not available to bots. The Bot API and the Mini App API expose no last-seen time and no online status, and Telegram's FAQ says "Bots cannot see your last seen time" (PC-01, VERIFIED). The only technical routes are a service-owned user account that watches users, or a server-side session logged in as the user. R05 section 4 classifies the first as risky and unreliable (it fails silently for every user with a private last seen) and the second as risky in its consensual form and forbidden in any form that alters status or acts without consent; both would require Dosegram to hold or operate full Telegram user accounts, and the second conflicts with the repository rule against collecting credentials. Dosegram therefore does not use Telegram presence in any form (N1).

What is built instead keeps the intent of the idea (the product notices a likely sleep window without effort) only in part, and is honest about what it knows. The estimate is offered on a night only when the user interacted with Dosegram several times shortly before sleep and again after waking (R-SLP-13); for most users on most nights it will not appear, and the one-tap check-ins are the sleep feature. ROADMAP measures the real coverage on the owner's own interaction times before the estimator is built (gate before G7.5). It follows the recommendation of R05 section 11. The two places where this specification is stricter than R05 are stated in R-SLP-12 and R-SLP-16.

1. Tier 2, the primary input: one-tap check-ins "Going to sleep" and "I'm up", a one-tap quality rating, and retrospective entry. This is sleep-diary grade.
2. Tier 1, an opt-in assistant: the quiet window between the user's own interactions with Dosegram. It is an upper bound on sleep opportunity, not a measurement. It is shown as "at most N hours", proposed to the user, and becomes a sleep record only when the user confirms it.
3. Tier 3, optional: sensor data imported from a file or, later, a wearable connection. Only this tier may show stages or a device's own score.

Rejected alternative: passive-only "automatic sleep tracking" from interaction gaps. A bot sees a few interactions per day; even a study with every touchscreen event found gap-derived duration unreliable (R05 section 6). It would be invented precision.

| Capability | Tier | Wave |
|---|---|---|
| Check-ins (going to sleep, up), quality rating, retrospective entry and editing | Must | V1 |
| Duration against the user's own goal; bedtime and wake consistency; sleep timeline card with tier labels | Must | V1 |
| Opt-in quiet-window estimate with the confirmation flow | Should | V1 |
| Bedtime mode; bedtime nudge; naps; irregular-schedule mode | Should | V1 |
| Sleep Regularity Index; social jet lag with a work-day calendar; evening tags | Could | V2 |
| Mini App foreground signal as additional evidence | Could | V2 |
| Imported sensor episodes shown with their source (through `import-export`) | Should | V2 |
| Wearable connections | Could | Later |
| A Dosegram sleep score; stages, efficiency, latency or awakenings without sensor data | Will not | n/a (N7, N8) |
| Telegram last seen or online status | Will not | n/a (N1) |

**User stories**

- US-SLP-1. As a user I tap "Going to sleep" at night and "I'm up" in the morning and see how long I slept.
- US-SLP-2. As a user who forgot to check in, I enter last night's times the next day.
- US-SLP-3. As a user who opted in, I am asked in the morning "Quiet from 00:40 to 07:10, at most 6 h 30 min. Is that about when you slept?" and answer with one tap.
- US-SLP-4. As a shift worker I tell Dosegram my schedule is irregular and am not judged against a night pattern.
- US-SLP-5. As a user I see how regular my bedtime and wake time were this week.
- US-SLP-6. As a user I can see how any estimate was made and switch the estimate off without losing my check-ins.

**Domain rules and edge cases**

Episodes and check-ins

- R-SLP-1. A sleep episode has an onset instant, a wake instant, a kind (main or nap), a source for each edge (check-in, retrospective, passive, sensor), a tier, a confidence, an optional quality rating from 1 to 5, and optional awake intervals. Durations are always differences of instants.
- R-SLP-2. Validation: wake is after onset; a main episode lasts from 1 to 18 hours and a nap from 5 minutes to 6 hours (PROPOSED); episodes of one account do not overlap; neither edge is in the future.
- R-SLP-3. "Going to sleep" opens an episode with the onset as now. "I'm up" closes the open episode with the wake as now and offers the quality rating. Either time can be adjusted in 15-minute steps in chat or freely in the Mini App.
- R-SLP-4. Open-episode cases. A second "Going to sleep" replaces the open onset. "I'm up" with no open episode asks for the bedtime with time chips and creates a retrospective edge. When the quiet-window estimate is enabled, an interaction more than 30 minutes after "Going to sleep" is kept as "active after bedtime", moves the onset bound and lowers the confidence (R-SLP-12); the stated bedtime itself changes only if the user confirms. An open episode whose onset is more than 18 hours old is closed as incomplete, is not counted, and is offered for manual completion.
- R-SLP-5. The sleep day of an episode is the local date of its wake instant in the zone in force at that instant. This is independent of the day rollover setting.
- R-SLP-6. Retrospective entry: any past night can be entered or edited in the Mini App. Each change is a new revision; the previous revision is kept so the record's history is truthful.
- R-SLP-7. Naps are recorded only from an explicit "Nap" check-in, a retrospective entry or sensor data. A daytime gap in bot use is never classified as a nap. A nap counts toward total sleep per 24 hours and is excluded from bedtime, wake time, midpoint and regularity.

The quiet-window estimate (Tier 1)

- R-SLP-8. The estimate is off by default and needs the separate consent `sleep_passive_estimate`. The consent text states which signal is used ("the times you tap or message this bot") and which is not ("your Telegram last seen, your other chats"), and that the result is only an upper bound.
- R-SLP-9. Evidence is the stream of user-originated events with their instants and kinds: a message to the bot, a button press, a reminder answer, a Mini App launch and, from V2, a Mini App foreground heartbeat that follows user input. Message content is never part of the evidence. Messages the bot sent are not evidence of wakefulness. No other store of interaction times may be used for this purpose.
- R-SLP-10. The estimate follows the algorithm of R05 section 7.3 with these configuration values (all PROPOSED, tunable): search frame from 18:00 local of the previous day to 18:00 local of the sleep day; minimum gap 3 hours; a burst of at most 3 events within 10 minutes that is followed by at least 60 minutes of quiet is bridged and kept as an awake interval; the candidate must overlap the expected rest phase by at least 60 minutes; the longest qualifying candidate is selected; a window longer than 14 hours gives no estimate. The expected rest phase comes, in order of preference, from the circular mean of the last 14 confirmed episodes (at least 5), the user's declared typical times, the least-active 6 hours of the last 14 days, or 23:00 to 07:00 when fewer than 7 days of data exist.
- R-SLP-11. A gap is a bound. If `a` is the last event before the gap and `b` the first after it, the only statements made are: sleep began no earlier than `a`, ended no later than `b`, and lasted at most `b - a` minus the awake intervals. The Tier 1 result is called a "quiet window", is worded "at most", and is never displayed as a sleep duration, bedtime or wake time.
- R-SLP-12. Confidence follows R05 section 7.4: sensor episode 1.0; both edges explicit 0.9, or 0.7 when an event contradicts an edge by more than 30 minutes; a passive estimate confirmed without change 0.8; one explicit and one passive edge `0.5 + 0.3 * s * p_dur`; both edges passive `0.5 * mean(s_on, s_wake) * p_dur * (0.5 + 0.5 * p_prior)`. Presentation bands: high from 0.8, medium from 0.5 to below 0.8, low from 0.25 to below 0.5; below 0.25 nothing is shown and the day is offered for manual entry. A purely passive estimate is capped at 0.49, so it can never reach the medium band. (R05 states both "never above 0.5" and a medium band that begins at 0.5; this specification resolves the boundary in favour of the stricter reading.)
- R-SLP-13. Confirmation. The estimate is evaluated only when a wake edge `b` exists, and no earlier than 60 minutes after `b` or at the next user interaction after those 60 minutes, whichever comes first, so that the wake-side evidence of R-SLP-12 can accumulate; evaluating at the very first interaction would fix the wake-side strength at its minimum. The prompt is sent at that evaluation instant. The morning touchpoint carries the question only if `b` already exists and the 60 minutes have passed; a morning touchpoint that arrives before any interaction carries no sleep question, because no window exists yet. The prompt is never sent inside quiet hours and at most once per sleep day. Expected coverage, stated plainly: with the formula of R-SLP-12 a displayable estimate (confidence at least 0.25) needs several interactions in the hour before sleep and several in the hour after waking. A user who only answers one evening and one morning reminder never sees an estimate (AC-SLP-5). The estimate is a convenience for frequent users; the check-ins of R-SLP-1 to R-SLP-4 are the sleep feature. Its controls are "Yes", "Adjust", "Not sleep" and "Stop asking". "Yes" creates a Tier 2 episode with confidence 0.8. "Adjust" opens the two times for editing and stores the result as a corrected self-report, keeping the original estimate so the algorithm's error can be measured. "Not sleep" discards the estimate. "Stop asking" disables prompts; disabling the estimate itself is a separate switch. After three consecutive unanswered prompts, prompting pauses for 7 days and the weekly report offers a bulk review.
- R-SLP-14. A day in which the service was unavailable for more than 30 minutes inside the candidate window has no passive estimate.
- R-SLP-15. Every estimate offers "How was this estimated?", which lists the kinds of evidence, the tier and the confidence band.
- R-SLP-16. Retention for this purpose: evidence instants are deleted after 48 hours; a per-day hourly activity profile (24 counts per local day) is kept for 14 days to compute the least-active hours; an unconfirmed estimate is deleted after 14 days (all PROPOSED). Rejected alternative: keeping raw instants for 35 days as R05 proposes; the hourly profile serves the same purpose with less data, and R06 section 7.2 asks for 48 hours.

What is shown at which tier

- R-SLP-17. Display rules, from R05 section 8:

| Metric | Tier 1, passive only | Tier 2, check-in or confirmed | Tier 3, sensor |
|---|---|---|---|
| Quiet window, "at most N hours" | Shown, labelled as an upper bound | Not needed | Not needed |
| Sleep duration, bedtime, wake time | Not shown | Shown, labelled "self-reported" | Shown as the device's values |
| Duration against the user's own goal | No | Yes | Yes |
| Bedtime and wake consistency | Only as "consistency of first and last activity", low confidence | Yes, with at least 5 of 7 days | Yes |
| Sleep Regularity Index (V2) | No | Yes, with at least 5 of 7 days | Yes |
| Social jet lag (V2) | No | Yes, with at least 3 work days and 2 free days labelled by the user | Yes |
| Naps and total sleep per 24 hours | No | Yes, explicit naps only | Yes |
| Self-rated quality | n/a | Shown as the user's own rating | Shown beside device data |
| Stages, efficiency, latency, awakenings | Never | Never | As imported, attributed to the device |
| A sleep score | Never | Never | The vendor's score verbatim with its name; never recomputed or blended |
| Wording such as "insomnia", "sleep disorder", "you should sleep more" | Never | Never | Never |

- R-SLP-18. Every sleep card and chart states its tier in words ("estimated from activity", "your check-ins", "from your <device>") and draws a low-confidence interval as visibly uncertain. When sensor and self-reported data exist for the same sleep day, the sensor episode is used for metrics and the self-report is kept for the quality rating.
- R-SLP-19. The duration goal is set by the user. No general reference duration is shown in the first version (Q-10). The difference from the goal is stated as a number, never as a judgement.
- R-SLP-20. Consistency is the circular standard deviation of onset and of wake clock times over 7, 14 or 28 sleep days, requiring 5 of 7, 10 of 14 or 20 of 28 days.

Time, travel and irregular schedules

- R-SLP-21. On a daylight-saving night the card shows clock times and the true duration and adds a note. A sleep day is flagged when the zone offset at its wake differs from that at the previous day's wake, including daylight-saving changes; flagged days are shown but excluded from regularity and social jet lag for that day and the next. After a zone change the rest-phase expectation is re-anchored to the new local clock and its weight is halved for 3 days.
- R-SLP-22. The profile flag "My schedule is irregular or I work shifts" removes the night expectation, the 18:00 frame and the 23:00 to 07:00 default. The main sleep is then the longest qualifying episode in a rolling 24 hours; a second episode of at least 3 hours is a second main sleep, not a nap. Consistency and social jet lag are not shown as better or worse for these users.

Interaction with the rest of the product

- R-SLP-23. Bedtime mode begins at "Going to sleep" and ends at "I'm up": nudges and reports are held; reminders are not affected in any way.
- R-SLP-24. If a reminder was answered inside the sleep window, the card states it as a neutral fact ("a reminder was answered at 03:10"). It gives no advice about dose times.
- R-SLP-25. The bedtime nudge is optional, at a user-set time, and counts against the nudge budget.
- R-SLP-26. No reward, milestone or counter is tied to a sleep value. Only the act of logging may be counted (section 8.14).

**Data captured**

| Record | Fields | Class |
|---|---|---|
| Episode and its revisions | edges, edge sources, kind, tier, confidence, awake intervals, quality, sleep day, zones, flags | B |
| Estimate (unconfirmed) | window, confidence, evidence kinds | B, 14 days |
| Evidence | instant, kind | B, 48 hours |
| Hourly activity profile | 24 counts per local day | B, 14 days |
| Goal, typical times, irregular flag, work-day calendar (V2) | user-set | B |

**Capture surfaces.** Bot: "Going to sleep" on the evening wrap-up; "I'm up" and the quality row in the morning; `/sleep`; the confirmation prompt. Mini App: night editor, calendar, timeline, sleep settings, "How was this estimated?". Image: sleep timeline card; sleep bars in the weekly card.

**Acceptance criteria**

- AC-SLP-1. "Going to sleep" at 23:40 and "I'm up" at 07:10 create one Tier 2 episode of 7 h 30 min with confidence 0.9, on the sleep day of the wake date. [U][T]
- AC-SLP-2. On the spring-forward night, an episode from 23:00 to 07:00 local time has a duration of 7 hours, and on the autumn night 9 hours; the card shows the clock times, the true duration and the note. [U][G]
- AC-SLP-3. Without the `sleep_passive_estimate` consent no evidence row and no estimate exists. [P]
- AC-SLP-4. For fixtures with both edges passive, the confidence never exceeds 0.49, and the output contains the words for "at most" and no bedtime, wake time or duration field. [U][G]
- AC-SLP-5. With only one reminder answer in the evening and one in the morning, 11 hours apart, the confidence is below 0.25, nothing is shown, and the day is offered for manual entry. [U]
- AC-SLP-6. A burst of 2 events at 03:10 inside an otherwise quiet 00:40 to 07:10 window yields one window with an awake interval and the interrupted flag, not two windows. [U]
- AC-SLP-7. A candidate window of 15 hours yields no estimate. [U]
- AC-SLP-8. At most one confirmation prompt is sent per sleep day and none inside quiet hours; after three unanswered prompts none is sent for 7 days. [S]
- AC-SLP-9. "Yes" creates a Tier 2 episode with confidence 0.8; "Adjust" stores the corrected episode and retains the original estimate; "Not sleep" leaves no episode. [T][P]
- AC-SLP-10. A daytime gap of 4 hours creates no nap. [U]
- AC-SLP-11. Evidence older than 48 hours, hourly profiles older than 14 days and unconfirmed estimates older than 14 days are absent after the retention job. [P]
- AC-SLP-12. Each sleep card fixture contains its tier label; Tier 1 fixtures draw the interval in the uncertain style. [G]
- AC-SLP-13. With the irregular-schedule flag, a main sleep from 09:00 to 16:00 is found and no consistency judgement text is produced. [U][G]
- AC-SLP-14. During bedtime mode a due reminder is sent on time and a hydration nudge is not sent. [S]
- AC-SLP-15. No string of this module contains a diagnostic word from the forbidden list or a recommendation about sleep duration. [O]
- AC-SLP-16. The repository contains no Telegram user-client library and no code path that reads presence; the dependency and symbol gate fails if one is added. [O]

### 8.12 `nutrition`

**Decision.** The food domain is a meal journal, not a calorie counter. Rationale: a full counter needs a food database of millions of items against entrenched products, and the only controlled test found of photo-based calorie estimation showed a mean underestimate of about a third (R03 section 3.3, a secondary report of a conference abstract). A journal delivers the lowest-friction record and avoids presenting a wrong number as measured. Rejected alternative: database-backed calorie counting with barcode scanning in the first waves.

| Capability | Tier | Wave |
|---|---|---|
| One-tap meal log by meal type; optional short text; regular-meal view | Must | V1 |
| Text quick-log ("lunch: soup") | Should | V1 |
| Optional meal-time nudges | Could | V1 |
| Meal photo attached to an entry | Should | V2 |
| Optional numbers typed by the user (energy, protein, carbohydrate, fat); recent and favourite meals | Could | V2 |
| Packaged-food lookup by barcode | Could | Later, after a licence review and the scanner spike (PC-25a) |
| Fasting timer | Could | Later |
| Calorie estimate from a photo; calorie budget coaching; nutrient targets; food ratings | Will not | n/a (N9) |

**User stories**

- US-NUT-1. As a user I tap "Lunch" and my meal is logged.
- US-NUT-2. As a user I add a few words about what I ate.
- US-NUT-3. As a user I see which meals I logged this week.
- US-NUT-4. As a user I send a photo of my plate and keep it in my diary (V2).

**Domain rules and edge cases**

- R-NUT-1. A meal entry has a meal type (breakfast, lunch, dinner, snack), an effective instant, and optional text of at most 300 characters (class A). The meal type is given with one tap; the effective instant is now unless changed.
- R-NUT-2. An entry belongs to a local day by the same rule as a hydration entry (R-HYD-4).
- R-NUT-3. The regular-meal view states, per day, which of breakfast, lunch and dinner have an entry. An absent meal is "not logged", never "skipped" or "missed". No message comments on a meal that was not logged.
- R-NUT-4. Text quick-log: a message that begins with a meal-type word of a shipped locale followed by a colon is recorded as that meal with the rest as text, and echoed with "Undo" and "Edit". Anything else is not interpreted as a meal.
- R-NUT-5. The product never rates a food or a day, never uses moral words for food, and never computes or estimates energy or nutrients.
- R-NUT-6. Meal nudges are off by default, set per meal type at user-chosen times, and follow the nudge rules of section 8.5.
- R-NUT-7. Photos (V2). A photo sent to the bot is neither downloaded nor stored until the user taps a meal type on the reply "Save as a meal?"; without that tap within 10 minutes the prompt expires and nothing was ever fetched. Storing photos needs the `photo_storage` consent. The service downloads the photo itself; a Telegram file address is never given to the Mini App or written to a log, because it contains the bot's credential (R02 section 17). Only images sent as photos are accepted, not documents. Limits: one photo per entry (an album is answered with a request to send one photo), 5 MB per photo, 6 photo entries per day, 100 MB of photos per account (PROPOSED). Photos are shown only to the owner in the Mini App, are never placed in generated cards, reports or caregiver views, and are never sent to any third party or model. Deleting the entry deletes the photo.
- R-NUT-8. Typed numbers (V2) are optional per entry: energy in kcal or kJ, and protein, carbohydrate and fat in grams. Day totals are sums of what was typed and are labelled "from your entries". An optional user-set daily energy figure may be shown beside the total as a plain number without colour, countdown or reminder.
- R-NUT-9. Caffeine and alcohol are not nutrition features. They are drink types in `hydration` (R-HYD-11).
- R-NUT-10. Entries can be edited and deleted at any age and added for past days in the Mini App.

**Data captured**

| Record | Fields | Class |
|---|---|---|
| Meal entry | meal type, effective instant, local date, zone, source | B |
| Text | up to 300 characters | A |
| Photo (V2) | image bytes, size, hash | A |
| Typed numbers (V2) | energy, macronutrients | B |

**Capture surfaces.** Bot: meal-type row on the Today card and the wrap-up; `/food` (alias `/meal`); typed quick-log; from V2 a photo. Mini App: food diary, entry editor, week view. Image: meals row in the weekly card.

**Acceptance criteria**

- AC-NUT-1. One tap on a meal type creates one entry with now as its instant and updates the Today card. [T]
- AC-NUT-2. "lunch: soup and bread" and its Ukrainian equivalent each create a lunch entry with the text; "soup and bread" alone creates nothing. [U][T]
- AC-NUT-3. A day with breakfast and dinner logged shows lunch as "not logged", and the catalogue of this module contains none of the words "skipped", "missed", "good", "bad" or "cheat" in any shipped locale applied to food. [G][O]
- AC-NUT-4. (V2) A photo sent without a following meal-type tap causes no file download and leaves no stored object; after 10 minutes the prompt is edited to its expired state. [T][P]
- AC-NUT-5. (V2) No API response and no log line contains a Telegram file address. [O]
- AC-NUT-6. (V2) A canary photo's hash appears in no generated card, report or caregiver view. [O]
- AC-NUT-7. No code path of this module calls an external service. [O]

### 8.13 `insights-and-correlations`

| Capability | Tier | Wave |
|---|---|---|
| Adherence text for today, 7 and 30 days, per medication and overall | Must | MVP |
| Period text summaries for hydration and mood | Should | MVP |
| Weekly report (text and card) | Must | V1 |
| Comparison with the user's own previous four weeks | Should | V1 |
| Descriptive timing statements for medication | Should | V1 |
| Monthly report with a minimum-data gate | Should | V2 |
| Cross-domain comparison cards with counts | Should | V2 |
| Composite scores; causal or predictive statements; advice; any comparison that involves whether a medication was taken | Will not | n/a (N2, N7) |

**User stories**

- US-INS-1. As a user I ask for my week and get counts I can check against my own memory.
- US-INS-2. As a user I receive one weekly picture across everything I track.
- US-INS-3. As a user I see how this week differs from my own recent weeks.
- US-INS-4. As a user I see, stated carefully, how two things I track moved together.

**Domain rules and edge cases**

- R-INS-1. Every statistic is computed from one immutable snapshot that fixes the account, the range, the zone, the as-of instant and the source revision. Text, alternative text and image use that snapshot (R-VIS-1). Text is available for every statistic before and without any image.
- R-INS-2. Periods are: today; the last 7 and 30 days; the calendar week according to the account's first day of the week; the calendar month. Period boundaries are local days in the account zone in force on each day.
- R-INS-3. Each figure shows its coverage ("5 of 7 days have data"). A figure with no data is "No data". Rounding: percentages to whole numbers, half up; durations to minutes; means to one decimal.
- R-INS-4. The weekly report covers the previous complete week and is sent at a time the user chooses (default: the first day of the week in the user's locale at 09:00, PROPOSED; this is the single default for every document), only if the week has at least one record. Rejected alternative: Sunday 19:00, which reports a week whose last evening has not happened yet. After a week without records the bot sends one short neutral message and then stays silent until a record exists.
- R-INS-5. The weekly report contains, for enabled domains only: adherence per schedule group (by medication name only if names are enabled for that surface); days on which the hydration goal was reached; the mood strip; sleep bars labelled by tier; meals logged. It contains no score.
- R-INS-6. Personal baseline: a figure may be compared with the mean of the same figure over the previous four calendar weeks when at least two of them have data. The wording is "this week" and "your previous weeks". No population norm is used anywhere.
- R-INS-7. Timing statements for medication are descriptive counts ("Evening doses were recorded late on 5 of 7 days"). They never suggest changing a schedule.
- R-INS-8. Cross-domain comparisons (V2) are produced only between non-medication measures (sleep, mood, hydration, meals logged, drink types). They require at least 14 paired days in the period and at least 5 days in each group, always show both counts, and use fixed sentence templates. Allowed forms: "On days after N or more hours of logged sleep, your average mood was X; on other days Y (A and B days)". Forbidden words in any shipped locale: because, causes, improves, worsens, leads to, due to, helps, should, significant, risk.
- R-INS-9. No comparison is produced in which one side is whether, when or how much of a medication was taken. Such a statement would read as evidence about a treatment's effect and could inform a therapeutic decision (N2). Rejected alternative: "mood on days with and without a dose", which R03 does not recommend and the regulatory reading in R03 section 8 does not support.
- R-INS-10. Statistics are computed per account from that account's records. No statistic combines accounts, and no model is trained on user data (PC-11).
- R-INS-11. The monthly report (V2) is produced only when at least one enabled domain has a record on at least 14 days of the month. Each domain's section appears only when that domain meets the same threshold; otherwise the section shows the gate and its progress (section 8.14). A month in which no domain meets the threshold produces no report.

**Data captured.** Snapshots are derived and short-lived. Report settings (weekday, time, domains included) are class C.

**Capture surfaces.** Bot: `/stats`, `/week`, the weekly report message. Mini App: Trends with period switch and drill-down. Image: weekly and monthly cards, heatmap, trend charts, comparison cards.

**Acceptance criteria**

- AC-INS-1. For every generated fixture the adherence text equals the output of the existing pure adherence function, and the weekly card's values equal the text. [U][G]
- AC-INS-2. A week with no record produces no card and exactly one neutral message; the following empty week produces nothing. [S]
- AC-INS-3. A comparison with 13 paired days, or with 4 days in one group, is not produced. [U]
- AC-INS-4. Every produced comparison sentence contains both group counts and none of the forbidden words, in every shipped locale. [G][O]
- AC-INS-5. The set of comparison templates contains no template that takes a medication measure as an input. [O]
- AC-INS-6. A baseline comparison is absent when fewer than two of the previous four weeks have data. [U]
- AC-INS-7. No query of this module reads rows of more than one account. [P][O]
- AC-INS-8. Every figure in every report fixture is accompanied by its coverage. [G]

### 8.14 `gamification`

**Decision.** Adopted in a restricted form: counting and gating, without rewards that can be lost. Rationale: every studied product uses recaps and short-term goals to keep people logging, but streak mechanics work through loss aversion and, applied to medication, pay for false "taken" records (R03 sections 3.8 and 6.2). Rejected alternative: streaks, points and a mascot, as used by habit applications.

| Capability | Tier | Wave |
|---|---|---|
| Forgiving consistency counter per non-medication domain ("5 of 7 days") | Should | V1 |
| Minimum-data gates with visible progress | Should | V1 |
| Neutral logging milestones inside the weekly report | Could | V2 |
| Monthly pattern label with original illustration | Could | Later |
| Streaks that reset; points; levels; rewards for medication intake; a suffering mascot; leaderboards; paid repairs | Will not | n/a (N11, N12) |

**User stories**

- US-GAM-1. As a user I see on how many days of this week I logged, without being told what I failed to do.
- US-GAM-2. As a user I see how much more data a figure needs before it can be shown.
- US-GAM-3. As a user I can switch all of this off.

**Domain rules and edge cases**

- R-GAM-1. A consistency counter states how many days of the current week have at least one record in a domain. It counts successes in a window. Nothing resets, and no text describes anything as lost.
- R-GAM-2. No counter, milestone, badge or celebratory message exists for medication intake, adherence, or the value of any measurement (sleep duration, water volume, mood value, weight). Medication shows its statistics only.
- R-GAM-3. A minimum-data gate exists only where a statistic truly needs the data, and its threshold is the threshold of that statistic ("3 more nights until your first consistency figure"). No gate is invented to create a goal.
- R-GAM-4. Milestones count acts of logging in non-medication domains (for example the 10th, 50th and 100th mood check-in). They appear as one line in the weekly report. They never produce a separate notification and are never withdrawn.
- R-GAM-5. One account setting turns the whole module off. When off, counters, gates and milestones are absent from every surface.
- R-GAM-6. The words "streak", "lose", "broken", "don't miss" and their equivalents in shipped locales are not used.
- R-GAM-7. Nothing in this module is designed to appeal to children (R06 section 14.10).

**Data captured.** None of its own; all values are derived. The on/off setting is class C.

**Capture surfaces.** Bot: lines in the weekly report and the Today card. Mini App: Trends. Image: a line or small badge in the weekly card.

**Acceptance criteria**

- AC-GAM-1. A week with records on Monday, Tuesday, Thursday, Friday and Sunday shows "5 of 7 days"; no text mentions Wednesday or Saturday. [G]
- AC-GAM-2. No template of this module takes a medication measure or a measured value as input. [O]
- AC-GAM-3. With the module off, no fixture of any surface contains a counter, gate or milestone. [G]
- AC-GAM-4. A milestone never causes a message of its own. [S]
- AC-GAM-5. The catalogue contains none of the words of R-GAM-6 in any shipped locale. [O]

### 8.15 `vitals-and-symptoms`

**Decision.** Adopted as a diary only, in V2, after the legal review of Q-02 confirms the position. The product stores and draws what the user typed. It never classifies, compares with a range, smooths into a trend for clinical measures, or alerts. Rationale: recording and displaying manually entered values is outside device scope, while trend analysis, thresholds and alerts on physiological values are listed as medical-device functions (R03 section 8). Rejected alternative: thresholds and "out of range" highlights, which users may expect from other applications.

| Capability | Tier | Wave |
|---|---|---|
| Weight log with a chart | Should | V2 |
| Blood pressure, blood glucose, temperature and pulse diary with a chart of recorded values | Should | V2 |
| Symptom diary with user-defined symptoms and a severity rating | Should | V2 |
| Values read from a photo of a device screen | Could | Later |
| Period log without any prediction | Could | Later, after a separate decision (Q-16) |
| Thresholds, ranges, classifications, alerts, averages or trend lines on blood pressure, glucose, temperature or pulse | Will not | n/a (N5) |
| Fertility or contraception output | Will not | n/a (N6) |

**User stories**

- US-VIT-1. As a user I write down a reading I took with my own device and find it again later.
- US-VIT-2. As a user I rate a symptom I named myself and see my entries over time.
- US-VIT-3. As a user I include selected readings in the record summary I bring to an appointment.

**Domain rules and edge cases**

- R-VIT-1. Measurement kinds and their fields: weight (value); blood pressure (systolic, diastolic, optional pulse); blood glucose (value, relation to a meal: fasting, before a meal, after a meal, bedtime, unspecified); body temperature (value); pulse (value). Each has an effective instant and an optional note of at most 200 characters (class A).
- R-VIT-2. Values are stored canonically (kg, mmHg, mmol/L, degrees Celsius, beats per minute) together with the value and unit the user entered, and are always displayed as entered unless the account unit changes. Conversions: 1 lb is 0.45359237 kg; glucose in mg/dL is mmol/L multiplied by 18.016 (PROPOSED constant; to be confirmed before implementation); Fahrenheit is Celsius times 9/5 plus 32.
- R-VIT-3. Plausibility bounds exist only to catch typing errors: weight 2 to 500 kg; systolic 40 to 300 and diastolic 20 to 200 mmHg; glucose 0.5 to 60 mmol/L; temperature 25 to 45 degrees Celsius; pulse 20 to 300 (PROPOSED). A value outside them is not refused; the bot asks "That looks like a typing error. Save it anyway?" The words normal, abnormal, high, low, elevated and their equivalents are never used.
- R-VIT-4. Charts of blood pressure, glucose, temperature and pulse show the recorded values in time order and nothing else: no average, no trend line, no band, no highlighted extreme. Their text summary gives the number of entries and the date range. For weight only, the difference between the first and the last value of the period may be stated as a plain number.
- R-VIT-5. No colour, icon or wording expresses a judgement of a value. All values of one kind use one colour.
- R-VIT-6. A symptom is a name of at most 40 characters that the user creates (class A); at most 20 per account. An entry has a severity from 1 to 5 as rated by the user and an optional note. The product offers no symptom catalogue and never links a symptom to a medication, a measurement or a cause.
- R-VIT-7. Measurements are excluded from cross-domain comparisons (R-INS-8) and from gamification (R-GAM-2).
- R-VIT-8. Entries can be backdated, edited and deleted at any age.

**Data captured.** Measurements and symptom entries: class B, with field-level protection for the values where practical (R06 section 6). Symptom names and notes: class A.

**Capture surfaces (outline only).** The chat flow and the Mini App screens of this module are not specified in BOT_UX or MINI_APP in this document set. They are written at the re-plan checkpoint CP9-d of ROADMAP, before slice G12.1 is cut, as a BOT_UX section and two MINI_APP screen specifications (S-17 Measurements, S-18 Symptoms). The intended surfaces are: Bot: `/measure` with a short guided entry; typed quick-log for weight ("weight 81.4"). Mini App: Measurements and Symptoms screens with entry forms and charts. Image: chart cards on request; a list in the record summary document.

**Acceptance criteria**

- AC-VIT-1. A blood pressure entry of 128 over 82 with pulse 64 is stored, displayed and exported with exactly those values. [U][M]
- AC-VIT-2. A glucose value entered as 99 mg/dL is displayed as 99 mg/dL, and as 5.5 mmol/L after the account unit is switched. [U]
- AC-VIT-3. A weight of 1200 kg triggers the typing-error question and is saved only after confirmation. [T]
- AC-VIT-4. The chart sources for blood pressure, glucose, temperature and pulse contain only the recorded points and axes; a lint fails on any additional series. [G][O]
- AC-VIT-5. The catalogue of this module contains none of the judgement words of R-VIT-3 in any shipped locale. [O]
- AC-VIT-6. No comparison or gamification template accepts a measurement as input. [O]

### 8.16 `sharing-and-caregivers`

**Decision.** Adopted for V2 in its smallest form: another adult Telegram user can be told that a scheduled dose was not confirmed. Rationale: it is the differentiator that no Telegram health bot found in R03 section 3.9 offers, and both people are already on the platform. Its authorisation shape (grants with scopes) is designed from the start so that later roles do not change the model (R06 section 5.2). Rejected alternative: family or caregiver group chats, which invite disclosure by mistake (R02 section 18).

| Capability | Tier | Wave |
|---|---|---|
| Grant to one named recipient with two-sided consent; alert when a dose was not confirmed; revoke in one tap | Should | V2 |
| "Send me a copy I can forward" for one card | Could | V2 |
| Read-only caregiver view in the Mini App | Could | Later |
| Caregiver write access; operator-created grants; emergency override; group sharing | Will not | n/a |

**User stories**

- US-SHR-1. As a user I invite a person I trust, choose what they are told, and can end it at any moment.
- US-SHR-2. As a caregiver I accept an invitation in my own chat with the bot and receive a short message when a dose was not confirmed.
- US-SHR-3. As a user I see every alert that was sent about me.

**Domain rules and edge cases**

- R-SHR-1. Only the account owner creates a grant. The recipient is always reached by an invite deep link that the owner sends to them; its code is single-use, random, expires after 24 hours and carries no meaning. A bot cannot message a user who has not started it (PC-03), and the identifier returned by Telegram's user picker may be unusable "unless the user is already known to the bot by some other means" (VERIFIED, https://core.telegram.org/bots/api#shareduser, checked 2026-11-12 by the review of this document set), so the picker alone can never deliver an invitation. The picker (PC-22) is therefore not used in V2. If binding is wanted later, the picked user id may be stored hashed with the invitation so that only that account can redeem the link; no name, username or photo is requested (R-SHR-7).
- R-SHR-2. The recipient must accept in their own private chat with the bot and must have, or complete, an account (adult attestation, terms). Until acceptance nothing is shared.
- R-SHR-3. Creating a grant is a separate explicit consent to disclose health data to that recipient, recorded in the consent ledger. The consent text says what will be sent, that it travels through Telegram to a second account, and that it can be revoked at any time. The consent text and the caregiver's acceptance text both state: an alert means that no answer was recorded; Dosegram cannot know whether the reminder reached the phone or was seen.
- R-SHR-4. A grant has: a scope (all scheduled medications or selected ones), a level (`notified` in V2), a names option (default off), an optional extra delay of 0 to 120 minutes, and an expiry of at most 12 months, after which the owner must renew.
- R-SHR-5. An alert is sent only when an occurrence in scope becomes `missed`, which requires that a reminder was accepted by Telegram (R-SCH-10); a bot receives no delivery or read signal (PC-04). Alert copy says "was not confirmed"; the words "missed" and "ignored" are forbidden in caregiver copy and checked by the copy lint. No alert is sent for `unknown`, `skipped` or `cancelled`. If the owner records or corrects the dose before the alert is sent, the alert is cancelled. If the dose is recorded within 24 hours after an alert, the caregiver receives one silent follow-up saying that it was recorded later.
- R-SHR-6. Alert text: the caregiver's own label for the person, the scheduled local time, and the sentence that a scheduled dose was not confirmed. It contains the medication name only if the owner enabled names for that medication, never a note, and never advice.
- R-SHR-7. Dosegram stores no Telegram names. The owner gives the grant a label; the caregiver gives the person a label at acceptance. Labels are at most 30 characters, class A.
- R-SHR-8. At most 6 alerts per owner and caregiver pair per local day; further ones are combined into one digest. At most 5 active grants and 5 invitations per day per owner (PROPOSED).
- R-SHR-9. The owner sees every grant, its scope and every alert sent under it, and revokes with one tap. Revocation takes effect at once; the caregiver receives one neutral message that the sharing ended. The caregiver can leave at any time.
- R-SHR-10. If the owner's account becomes unreachable or paused, alerts stop and the caregiver receives one message that alerts are paused. The consent text of R-SHR-3 says that this message can be sent.
- R-SHR-11. A caregiver cannot record, correct or view anything in the owner's account in V2.
- R-SHR-12. "Send me a copy I can forward" sends one unprotected copy of a card to the owner's own chat after a confirmation that explains that the copy can be forwarded and saved. Names follow the image setting.

**Data captured**

| Record | Fields | Class |
|---|---|---|
| Grant | owner, grantee, scope, level, options, created, accepted, expiry, revoked | B |
| Labels | up to 30 characters each | A |
| Alert record | grant, occurrence, sent instant, outcome; no content | B |

**Capture surfaces.** Bot: `/share`, the invitation, the acceptance, alerts, revocation. Mini App: Sharing screen with grants and alert history. Image: none.

**Acceptance criteria**

- AC-SHR-1. No alert is sent before the recipient's acceptance, after revocation, or after expiry. [T][P]
- AC-SHR-2. An occurrence that resolves as `unknown` produces no alert; one that resolves as `missed` produces exactly one. [S]
- AC-SHR-3. With names off, a canary medication name appears in no alert; a canary note appears in no alert under any setting. [O]
- AC-SHR-4. A dose recorded during the extra delay cancels the alert. [S]
- AC-SHR-5. A seventh alert in one day is not sent as its own message. [S]
- AC-SHR-6. Every alert sent is visible in the owner's alert history. [M]
- AC-SHR-7. An invitation code is refused when reused or older than 24 hours. [T]
- AC-SHR-8. A caregiver's request for any owner record through the Mini App API returns "not found". [M]

### 8.17 `import-export`

| Capability | Tier | Wave |
|---|---|---|
| Full account export; "export before delete" | Must | MVP |
| Export sent as a file into the chat, after a warning | Could | V1 |
| Record summary document (PDF) | Should | V2 |
| Import: Dosegram CSV template | Should | V2 |
| Import: Apple Health export, limited to sleep, body weight and water, parsed in the Mini App | Should | V2 |
| Import: Health Connect, Samsung Health, Google Health exports; other applications' CSV files | Could | Later, each after inspecting a real file |
| Wearable connections (the first chosen by what the owner uses, Q-12) | Could | Later |
| Writing back to Apple Health or Health Connect | Will not | n/a (PC-18) |
| Sending a record to a clinician on the user's behalf | Will not | n/a (R06 section 14.9) |

**User stories**

- US-IMX-1. As a user I download everything Dosegram holds about me in a form I can read and a form a program can read.
- US-IMX-2. As a user I produce a plain document of my medications and my record for an appointment.
- US-IMX-3. As a user with a watch I bring my measured sleep in and can remove it again.

**Domain rules and edge cases**

- R-IMX-1. The export contains every record of every domain, the consent history and the access history: one JSON file with a published, versioned schema; one CSV file per domain; and a short readable summary. Instants are ISO 8601 with offset, and the zone id is given where the product stored one. Names and notes are included, because the export is for the owner.
- R-IMX-2. An export is requested in the bot or the Mini App, confirmed by a tap in the private chat, produced in the background, and downloaded through the Mini App (PC-31). It is available for 15 minutes and for one download, then deleted. At most 3 exports per 24 hours (PROPOSED, R06 section 9.2).
- R-IMX-3. The export is not sent into the chat by default, because that would copy the complete history to Telegram (PC-12). From V1 the user may choose "Send as a file here" after reading that warning.
- R-IMX-4. The account deletion flow offers "Export first".
- R-IMX-5. The export does not depend on any third-party service and can be produced while reminders are paused.
- R-IMX-6. The record summary (V2) is titled "Record summary". It lists active medications with dose and schedule in words, an adherence table for a period the user chooses, the measurements and symptom entries the user selects and, when the user selects them, per-domain summaries of sleep, mood, hydration and meals drawn from the same period snapshots as the weekly report. Its only name on every surface is "Record summary" (catalogue key `doc.record_summary.title`, export kind `record_summary_pdf`). It contains no interpretation and carries the line "Recorded entries only. Not medical advice." It is never called a clinical or medical report.
- R-IMX-7. Imports (V2) never pass through the bot chat, because a bot can download at most 20 MB (PC-06) and health exports are often far larger (R03 section 7.1). The Mini App reads the file on the device and uploads only the normalised records of the types the user selected. Whether every Telegram WebView can read a file of several hundred megabytes is an ASSUMPTION (A-12) and needs a spike.
- R-IMX-8. Each import source needs its own `import` consent. Before anything is stored the user sees a preview: record counts per type, the date range and the number of records ignored.
- R-IMX-9. An import is one batch. Importing the same file again creates no duplicate: a record is identified by its source, type, start, end and value. A batch can be removed as a unit at any time.
- R-IMX-10. Imported records keep their source and device names, are marked as imported on every surface, and are never edited in place. A user correction is a separate record that refers to the imported one.
- R-IMX-11. Imports create no medication, schedule, occurrence or dose action in V2.
- R-IMX-12. A record whose file gives no offset is assigned the account zone at import time and is flagged as such.
- R-IMX-13. The Dosegram CSV template is the inverse of the export: a file exported by Dosegram and imported unchanged into an empty account reproduces the same records for the supported domains.

**Data captured.** Export jobs and import batches (class D metadata: ids, counts, instants). Export files are class A and exist for at most 15 minutes. Imported records belong to their domain.

**Capture surfaces.** Bot: `/privacy` (export, delete), `/export`. Mini App: Privacy and data; Import wizard. Image: none; the record summary is a document.

**Acceptance criteria**

- AC-IMX-1. For a fixture account, every record of every enabled domain appears in the JSON and in the CSV, and the JSON validates against the published schema. [P][G]
- AC-IMX-2. An export is ready within 10 minutes, can be downloaded once, and is absent from storage 15 minutes after becoming ready. [P][O]
- AC-IMX-3. A fourth export request within 24 hours is refused with the time of the next allowed request. [T]
- AC-IMX-4. Without the confirming tap in the chat no export is produced. [T]
- AC-IMX-5. (V2) Importing the same file twice leaves the record count unchanged after the second run. [P]
- AC-IMX-6. (V2) Removing a batch removes exactly its records. [P]
- AC-IMX-7. (V2) Export followed by import into an empty account yields equal records for the supported domains. [P]
- AC-IMX-8. (V2) The record summary fixtures contain no forbidden word and contain the "Recorded entries only" line. [G][O]
- AC-IMX-9. (V2) During an Apple Health import, no record of a type the user did not select reaches the server. [M]

### 8.18 `billing`

**Decision.** Not adopted for MVP, V1 or V2. Rationale: payments add obligations (mandatory support and terms commands, disputes, refunds, taxes) and, on Telegram, digital services must be sold in Stars with 30-day subscriptions only (PC-10); none of that helps the first users. Rejected alternative: a paid tier at public beta.

The free boundary is fixed now, so that a later paid tier cannot erode trust (R03 section 6.2):

- R-BIL-1. Free under every future option: all reminders; logging in every domain; the Today card; the weekly report; caregiver alerts; full export; deletion; consent controls; discreet mode; every security feature.
- R-BIL-2. Possible paid items, if a tier is ever added: cosmetic themes for cards, extended report history, conveniences that have a per-use cost to the operator.
- R-BIL-3. All feature checks pass through one entitlement decision that today always answers "granted". No feature checks a plan in any other way.
- R-BIL-4. If payments are added: Stars only; the commands for payment support and terms exist before the first invoice; refunds are possible; only the payment charge identifier is stored.

**User stories.** US-BIL-1. As a user I can rely on reminders, logging, export and deletion staying free whatever is added later.

**Data captured.** None in MVP, V1 or V2. **Capture surfaces.** None.

**Acceptance criteria**

- AC-BIL-1. Every gated feature obtains its decision from the single entitlement function; a lint fails on any other plan check. [O]
- AC-BIL-2. No code path creates an invoice in MVP, V1 or V2. [O]

## 9. Cross-cutting requirements

### 9.1 Tap budget

The budget counts taps on controls the user can already see, starting from the message or screen named. It is an acceptance criterion for BOT_UX.md and MINI_APP.md.

| Id | Action | Starting point | Taps | Typing |
|---|---|---|---|---|
| X-TAP-1 | Taken, Skip, or an offered Snooze | Reminder message | 1 | none |
| X-TAP-2 | Undo | Outcome message | 1 | none |
| X-TAP-3 | Correct to "at scheduled time" | Outcome or old reminder | 2 | none |
| X-TAP-4 | Log an as-needed dose | Today card | 2 | none |
| X-TAP-5 | Add a preset amount of water | Today card or nudge | 1 | none |
| X-TAP-6 | Mood check-in | Today card, wrap-up or prompt | 1 (2 with energy) | none |
| X-TAP-7 | "Going to sleep", "I'm up" | Wrap-up, morning message | 1 each | none |
| X-TAP-8 | Confirm a quiet-window estimate | Confirmation prompt | 1 | none |
| X-TAP-9 | Log a meal by type | Today card or wrap-up | 1 | none |
| X-TAP-10 | Open the Mini App on the item a message is about | Any card with "Open" | 1 | none |
| X-TAP-11 | Add a medication with one daily time | Mini App registry | at most 8 | name, optionally dose |
| X-TAP-12 | Request an export | `/privacy` or Mini App | 3, including the confirmation | none |
| X-TAP-13 | Delete the account | `/privacy` or Mini App | 3, including the confirmation | none |
| X-TAP-14 | Withdraw one consent | `/privacy` or Mini App | 3 | none |
| X-TAP-15 | Revoke a caregiver grant | Sharing screen or `/share` | 2 | none |

### 9.2 Latency

Measured in the production container. "Receipt" is the arrival of the update at the service.

| Id | Requirement | Target |
|---|---|---|
| X-LAT-1 | A button press is acknowledged to Telegram | 95th percentile under 1.5 s from receipt (existing gate; the platform's own deadline is undocumented, PC-30) |
| X-LAT-2 | The outcome of a button press is visible (the edit or reply is accepted by Telegram) | 95th percentile under 2 s; 99th under 5 s |
| X-LAT-3 | A text command receives its reply | 95th percentile under 2 s |
| X-LAT-4 | A requested image is visible | 95th percentile at most 2.5 s; after 5 s the text equivalent is sent instead |
| X-LAT-5 | A Mini App screen is usable after launch | 75th percentile under 2 s on a mid-range phone on a 4G connection (PROPOSED) |
| X-LAT-6 | Mini App data requests, server side | Reads: 95th percentile under 300 ms. Writes: under 500 ms (PROPOSED) |
| X-LAT-7 | Export ready; record summary ready | 10 minutes; 30 seconds |

Acknowledgement happens before database work, and rendering happens after acknowledgement and outside the recording transaction (existing rules).

### 9.3 Reminder punctuality and delivery

| Id | Requirement | Invite-only target | Public target |
|---|---|---|---|
| X-REM-1 | Share of due reminders accepted by Telegram within 60 seconds of the scheduled instant, over 30 days, excluding unreachable accounts and confirmed Telegram outages | 99.5 percent | 99.9 percent |
| X-REM-2 | Share accepted within 5 minutes | 99.9 percent | 99.99 percent |
| X-REM-3 | Share of due occurrences that reach a terminal, explained state | 100 percent | 100 percent |
| X-REM-4 | Reminders accepted before their scheduled instant | 0 | 0 |
| X-REM-5 | Duplicate reminders | At most one per process crash, flagged | Same |
| X-REM-6 | Health content sent to the wrong chat | 0; any occurrence is the highest-severity incident | 0 |
| X-REM-7 | Fairness under load | Within one minute's batch the send order is randomised so that no account is systematically last | Same |

Honest limit: the platform figure is about 30 messages per second (PC-05) and the sender is configured for 25 message-changing calls per second, sends and edits together. One answered reminder costs about three calls (the reminder, the edit of the reminder message, the edit of the Today card), so about 500 reminders that share the same minute can be accepted within 60 seconds (ASSUMPTION until ROADMAP spike G1.1 has shown whether edits count against the limit; ARCHITECTURE section 7.6 is the single place where this figure is derived). Beyond that, X-REM-1 cannot hold for that minute whatever the implementation. This does not affect the invite-only phase and must be revisited before growth (R02 section 5).

### 9.4 Accessibility

| Id | Requirement |
|---|---|
| X-ACC-1 | Every image has a text equivalent from the same data (R-VIS-2). An animated sticker is always followed by text. |
| X-ACC-2 | The Mini App meets WCAG 2.2 level AA: text contrast at least 4.5:1 and meaningful graphics 3:1 in both themes; every input and image labelled; visible focus; complete keyboard operation on desktop clients; content usable at 200 percent text size; motion reduced when the system asks for it. |
| X-ACC-3 | Touch targets in the Mini App are at least 44 by 44 CSS pixels (PROPOSED). |
| X-ACC-4 | Status is never carried by colour alone, in chat, in images or in the Mini App. Buttons carry words; a symbol is never the only carrier of meaning. |
| X-ACC-5 | The Mini App is styled from the Telegram theme parameters, follows theme changes while open, and respects the safe areas the client reports. |
| X-ACC-6 | Routine prompts fit 160 characters and outcomes 80 characters (existing rule). User data is never silently truncated; a "Details" control shows the rest. |
| X-ACC-7 | No interaction other than those bounded by Telegram itself requires an answer in less than 10 minutes. |
| X-ACC-8 | Animation is opt-in and contains no flashing. |

### 9.5 Localisation

The requirements are those of section 8.1. In addition: every acceptance criterion tagged [G] that concerns text is run for each shipped locale, and Ukrainian copy is reviewed by a native speaker before release.

### 9.6 Privacy, security and retention

The controls are specified in ARCHITECTURE.md from R06. The product-level requirements are:

| Id | Requirement |
|---|---|
| X-PRV-1 | Every tracking record in every domain, including water, food and sleep, is treated as health data under one set of controls (R06 section 6). |
| X-PRV-2 | No health content appears in logs, metrics, traces, the audit log, error reports or operator tools. A canary test enforces this in continuous integration (AC-OPS-1). |
| X-PRV-3 | Data is encrypted at rest with the key stored separately (PC-11). Names, notes, labels, photos and export files are additionally protected so that destroying one account's key makes them unreadable everywhere. |
| X-PRV-4 | One account can never read or change another's data. A cross-account test over every Mini App route and every data-access method is a release gate. |
| X-PRV-5 | No user data goes to a third party: no analytics, advertising, external model, font service or content network. The only external party is Telegram as the transport. |
| X-PRV-6 | Button payloads, deep-link parameters and file names carry opaque tokens only. |
| X-PRV-7 | The privacy notice is published, registered with the bot, answered by `/privacy`, and says in plain words that Telegram can read bot messages and images (PC-12) and that delivered messages cannot be recalled by Dosegram after 48 hours (PC-06). |
| X-PRV-8 | The service is hosted in one EU or EEA region (PROPOSED, R06 section 14.5). |
| X-PRV-9 | Sensitive actions (export, account deletion, creating or widening a grant, changing the consent set) are confirmed by a tap in the private chat. |
| X-PRV-10 | The operator has no routine access to content (section 8.3). |

Retention (PROPOSED; each row has a job and a test, R-OPS-8):

| Data | Kept for |
|---|---|
| Health records | While the account is active, or until the user deletes them or withdraws the domain's consent |
| Abandoned onboarding record | 24 hours |
| Sleep evidence instants; hourly activity profile; unconfirmed estimates | 48 hours; 14 days; 14 days |
| Rendered image bytes; reusable file identifier of a personal image | Until delivery and at most 1 hour after the terminal state; 48 hours |
| Export file | 15 minutes after ready, one download |
| Unsaved meal photo (V2) | Never stored: the photo is not downloaded before the meal-type tap (R-NUT-7) |
| Raw inbound update bodies | Until processed, at most 24 hours |
| Delivery states without content | 90 days |
| Application logs without health content | 30 days |
| Audit log without health content | 13 months |
| Consent ledger | Life of the account plus 3 years (period to be confirmed by counsel) |
| Backups | 35 days |
| Inactive account | Notice at 23 months, deletion at 24 months |
| Unreachable account | Deletion 90 days after the first permanent delivery failure |

### 9.7 Reliability and integrity

| Id | Requirement |
|---|---|
| X-REL-1 | Every user action takes effect at most once. A repeated or replayed action returns the original outcome. |
| X-REL-2 | "Recorded" or "Saved" is shown only after the record is durable. When that cannot be established the user sees "Checking whether this was recorded" and then the truth. |
| X-REL-3 | The chat history is never the system of record. Losing every message in the chat loses no data (Telegram may delete chats and files, R02 section 20.1). |
| X-REL-4 | Telegram keeps undelivered updates for at most 24 hours (PC-29, an upper bound). With webhook delivery the retry horizon is undocumented and may be shorter: the `setWebhook` reference says Telegram gives up "after a reasonable amount of attempts" (VERIFIED, https://core.telegram.org/bots/api#setwebhook, checked 2026-11-12 by the review of this document set). That an outage shorter than 24 hours loses no user action is therefore an ASSUMPTION until ROADMAP spike G1.1 has measured the horizon. Until then the product tells the user after any recorded outage that taps made during it may not have arrived. |
| X-REL-5 | Availability of the bot and the Mini App: 99.5 percent in the invite-only phase and 99.9 percent publicly. Maximum data loss after a failure: 15 minutes, then 5 minutes. Time to restore: 4 hours, then 1 hour. The public figures presume a second person on call and managed database failover (R06 section 15); they are not promised with one operator and one host. |
| X-REL-6 | Under overload, work is shed in this order: reports, nudges, images (replaced by text). Reminders and outcomes are never shed. |
| X-REL-7 | Without the Mini App the user can still answer reminders, log in every domain, add a medication, pause, request an export and delete the account. |
| X-REL-8 | History is append-only where the domain says so (dose actions, sleep revisions, the consent ledger). A correction is a new entry. |

### 9.8 Client compatibility

| Id | Requirement |
|---|---|
| X-CMP-1 | Supported clients are the current Telegram applications for Android, iOS, desktop and web. |
| X-CMP-2 | The baseline uses only text, inline keyboards, photos and documents. Newer features (widgets, rich messages, date-time entities, button styles, stickers) are enhancements that ship only with a baseline fallback and a recorded live check on each client. |
| X-CMP-3 | Every Mini App feature that depends on a client capability checks for it and has a fallback. |
| X-CMP-4 | The bot operates in private chats only and has inline mode disabled. |
| X-CMP-5 | The Mini App is a single-origin application and embeds no third-party page (PC-09). |

### 9.9 Copy rules

| Id | Requirement |
|---|---|
| X-COPY-1 | Product-authored text describes functions with: record, remind, show, count, compare with your own past, export. It does not use: diagnose, treat, prevent, detect, monitor in a clinical sense, risk, normal, abnormal, should take (R03 section 8.3). |
| X-COPY-2 | Product-authored text never names a disease or condition. User-entered names and notes are the user's own content. |
| X-COPY-3 | The only statement about a late or missed dose is the standing sentence: when unsure, follow the instructions of your prescriber or the package leaflet. |
| X-COPY-4 | No text shames, creates urgency or describes anything as lost. |
| X-COPY-5 | The bot's name, descriptions and any public page count as claims. They say "reminds" and "records", never "improves adherence" or "helps manage" anything. |
| X-COPY-6 | The forbidden-phrase lists are checked against the whole catalogue of every shipped locale in continuous integration. |

### 9.10 Limits and quotas

All PROPOSED. A limit is stated to the user when reached; nothing is silently dropped.

| Item | Limit |
|---|---|
| Medications; schedules; slots per schedule per day | 200; 400; 12 |
| Medication name; instructions; notes | 80; 200; 500 characters |
| Hydration entry; entries per day; presets | 1 to 5000 ml; 60; 4 |
| Mood tags per check-in; tag list; note | 5; 30 tags; 500 characters |
| Meal text; photo entries per day; photo storage (V2) | 300 characters; 6; 100 MB |
| Symptoms per account | 20 |
| Nudge budget per day | default 6, range 0 to 12 |
| Exports per 24 hours | 3 |
| Active grants; invitations per day; alerts per pair per day | 5; 5; 6 |
| Entries per day across all domains | 500 |
| Accounts in the invite-only phase | 50 |

## 10. Success metrics

Metrics are computed from operational counters and from each account's own records. No per-user behavioural event stream is collected beyond what features need, and no third-party analytics is used (X-PRV-5, PC-11). In the invite-only phase the numbers are small; they are read as counts, not as statistics.

| Id | Metric | Target | Measured by |
|---|---|---|---|
| M-01 | Owner-use gate | The owner uses Dosegram for their own regimen for 14 consecutive days without another reminder tool | Owner's statement, recorded in the roadmap evidence |
| M-02 | Activation | At least 80 percent of accounts that complete consent create a medication or log an entry within 24 hours | Counter per account |
| M-03 | Answer in place | At least 90 percent of intake records for scheduled doses are made with one tap on the reminder message | Source surface on the dose action |
| M-04 | Answered in the window | At least 70 percent of delivered reminders are answered before the miss deadline | Occurrence outcomes |
| M-05 | Reminder timeliness | X-REM-1 and X-REM-2 | Delivery states |
| M-06 | System-caused unknown | Under 1 percent of occurrences resolve as `unknown` with reason `outage` | Occurrence outcomes |
| M-07 | Week-4 retention | At least 50 percent of activated accounts have a record on 4 or more days of their fourth week | Records per day |
| M-08 | Nudge health | At least 40 percent of nudges receive a response; the monthly rate at which accounts block the bot is under 5 percent | Nudge outcomes; block updates |
| M-09 | Image health | At least 99 percent of requested images are delivered as images within X-LAT-4; 100 percent of the remainder receive the text equivalent | Render outcomes |
| M-10 | Honesty gates | 0 forbidden phrases; 0 Tier 1 sleep estimates displayed as durations; 100 percent of images with a text equivalent | Continuous integration |
| M-11 | Privacy gates | 0 canary leaks; export ready within 10 minutes; names and notes unreadable immediately after deletion | Continuous integration and jobs |
| M-12 | Sleep estimate usefulness (V1) | Of prompts answered, at least 60 percent are "Yes" or "Adjust" with a change under 30 minutes per edge | Kept originals of R-SLP-13 |
| M-13 | Setup effort | Median time from "Account ready" to the first saved medication under 2 minutes | Timestamps of the two events |

If M-08 fails, the nudge defaults are lowered before anything else is changed. If M-12 fails, the estimate stays off by default and is reconsidered.

## 11. Specification frame

The spec-driven-development template asks for six areas. This document owns Objective, Boundaries, Success Criteria and Open Questions. The others are owned elsewhere and are summarised so that this file can be read alone.

### 11.1 Technology, commands and structure

| Area | Statement | Owner |
|---|---|---|
| Technology | The existing code base is Scala 3.8.4 on JDK 25 with Mill 1.1.9, Ox 1.0.6 and PostgreSQL 18, and about 85 percent of its main code has no Discord symbol (R01 section 1). The product specification assumes that this engine is kept. The Telegram client, the Mini App technology and the rendering stack are decided in ARCHITECTURE.md. | ARCHITECTURE.md |
| Commands | The build, test and gate commands that exist are listed in R01 section 8 (for example `./mill __.compile`, `./mill __.test`, `./mill __.checkFormat`, `./mill __.fix --check`, `scripts/check-vendor-symbols.sh`, `scripts/doc-lint.sh`). None was executed while writing this specification. New gates required here: the canary log-hygiene scan, the forbidden-phrase lint per locale, the cross-account suite, the comparison-template lint and the chart-series lint. | ARCHITECTURE.md, ROADMAP.md |
| Structure | Module ids in section 7 are product modules. Their mapping to build modules and packages is in ARCHITECTURE.md. Module specifications that grow beyond this file are saved beside it as `SPEC-<module-id>.md`. | ARCHITECTURE.md |
| Code style | Unchanged from the repository (`.scalafmt.conf`, `.scalafix.conf`, `-Werror`). | Repository |

### 11.2 Testing strategy at product level

1. Every acceptance criterion names its evidence (section 0.5). A slice is not complete until each criterion it claims has that evidence recorded.
2. Flow behaviour is proved on the console adapter and the Telegram fake wire before any live check. Live checks on real clients are required only for criteria tagged [L] and for every enhancement in X-CMP-2.
3. Time behaviour (daylight saving, zone changes, quiet hours, catch-up) is proved with an injected clock and generated golden rows, never with hand-written expected values (ADR-011).
4. Text and images are proved with golden files per shipped locale and theme.
5. Privacy and non-clinical rules are proved by gates that run on every change, not by review alone.

### 11.3 Boundaries

Always:

- Keep the scheduled time, the effective time and the recorded time separate.
- Give every image a text equivalent from the same snapshot.
- Ask for consent for a domain before storing its first record.
- Label an estimate as an estimate and state its source.
- Answer every button press and every private message.

Ask the owner first:

- Adding a capture field that stores a new kind of personal data.
- Adding a notification, a default-on nudge or a new message class.
- Changing a default in section 8 that is marked PROPOSED after it has shipped.
- Adding a region, a locale, a payment, or any third-party service.
- Using a Telegram feature newer than the baseline of X-CMP-2 as a primary surface.

Never:

- Read or infer Telegram presence, or operate a Telegram user account.
- Output a dose, a time the user did not enter, advice after a missed dose, an interpretation of a value, or the name of a condition.
- Log credentials, message bodies, medication names or notes; collect passwords or codes in chat.
- Reward, count as an achievement, or make losable any medication record or measured value.
- Send user content to a model or any third party.
- Present a number as measured when it was estimated or typed.

## 12. Glossary

One term per concept. A word in this table is used with this meaning in every Dosegram document.

| Term | Meaning |
|---|---|
| Account | The record of one person's use of Dosegram, bound to exactly one Telegram user id. The unit of isolation. |
| User | The person. Used when speaking of what the person does or sees. |
| Owner (of an account) | The user to whom an account's data belongs, as opposed to a caregiver. "The owner" without qualification, in planning documents, also means the person who owns the project. |
| Operator | The person who runs the service. |
| Medication | One entry in the user's registry: something the user takes, with a name the user typed. Includes supplements. |
| As-needed medication | A medication without a schedule. Logged manually. |
| Schedule | The rule that says when a medication is due. Only medications have schedules. |
| Revision | One immutable version of a schedule. |
| Slot | One local time of day in a schedule. |
| Occurrence | One instance of a scheduled dose: a medication, a scheduled instant and a lifecycle status. |
| Dose | The amount and unit the user entered for one intake. |
| Dose action | One append-only entry in the history of an occurrence or an as-needed intake (taken, skipped, snoozed, corrected and so on). |
| Intake | A dose action of kind taken, with its effective instant. |
| Scheduled instant | When an occurrence is due. |
| Effective instant | When the user says something happened. |
| Recorded instant | When Dosegram received the record. |
| Taken, skipped, missed, unknown, cancelled | Terminal outcomes of an occurrence. Skipped is deliberate. Missed means reminded and unanswered. Unknown means the system cannot show the user was reminded. Cancelled means the occurrence was withdrawn before it was due. |
| Adherence | Taken divided by taken plus skipped plus missed, over resolved scheduled occurrences in a period. |
| Reminder | A message about one or more due occurrences. Only medication has reminders. |
| Follow-up | A repeated reminder for the same occurrence. |
| Snooze | The user's postponement of a reminder by a chosen duration. |
| Reminder policy | The per-schedule settings that govern reminders (section 8.5). |
| Nudge | An optional prompt for a non-medication domain. |
| Nudge plan | The rule that says when a nudge may be sent. |
| Nudge budget | The maximum number of nudges per local day for an account. |
| Touchpoint | One of up to three daily times (morning, midday, evening) at which nudges are combined into one message. |
| Wrap-up | The evening touchpoint message. |
| Today card | The one message per local day that shows the day's state and is edited in place. Earlier drafts of this document called it the Today message. |
| Outcome | The reply to something the user just did. |
| Quiet hours | The account's daily interval in which nudges and reports are not sent. |
| Quiet-hours mode | Per schedule: what a reminder does inside quiet hours (deliver, silent, defer). |
| Discreet mode | The setting under which medication names are not shown until the user asks. |
| Neutral label | A user-chosen replacement for a medication name in discreet mode. |
| Entry | One record in hydration or nutrition. |
| Check-in | One record of mood, or one of the sleep taps ("Going to sleep", "I'm up"). |
| Valence | The pleasantness value of a mood check-in, 1 to 5. |
| Energy | The optional activation value of a mood check-in, 1 to 5. |
| Mood blob | The generated abstract image for one pair of valence and energy. |
| Goal | A daily target the user set (water volume, sleep duration, optionally energy intake). Medications have no goals. |
| Sleep episode | One period of sleep with an onset, a wake, a tier and a confidence. |
| Sleep day | The local date on which a sleep episode ends. |
| Quiet window | The interval between two of the user's own interactions with Dosegram, used as an upper bound on sleep opportunity. Not to be confused with quiet hours. |
| Estimate | A proposed sleep episode derived from a quiet window, not yet confirmed. |
| Evidence | The instants and kinds of the user's own interactions, used only for the quiet-window estimate. |
| Tier | The quality class of sleep data: 1 passive, 2 check-in or confirmed, 3 sensor. |
| Confidence | A number from 0 to 1 that says how close an episode's edges are likely to be to the truth. Not a medical probability. |
| Measurement | One user-entered value of weight, blood pressure, glucose, temperature or pulse. |
| Symptom entry | One user rating of a symptom the user named. |
| Local day | A calendar day in the account zone in force, adjusted by the day rollover time where the module says so. |
| Day rollover | The local time at which a new local day begins for hydration, mood and meals. |
| Account zone | The IANA time zone of the account. |
| Home time | A schedule fixed to a zone that no longer follows the account zone. |
| Snapshot | The immutable input of a statistic or a card. |
| Card | A generated image with its text equivalent. |
| Text equivalent | The text that carries the same content as a card. |
| Report | A scheduled summary for a week or a month. |
| Comparison | A descriptive statement that sets two non-medication measures side by side with counts. |
| Consistency counter | The number of days in a week with a record in a domain. |
| Gate | A minimum amount of data that a statistic needs before it is shown. |
| Consent | A recorded, explicit, revocable agreement to one purpose. |
| Consent receipt | The message that confirms a consent to the user. |
| Grant | The owner's permission for one other account to receive defined information. |
| Caregiver | The account that holds a grant. |
| Caregiver alert | A message to a caregiver that a scheduled dose was not confirmed. |
| Export | The complete, downloadable copy of an account's data. |
| Record summary | The generated document of selected records for the user to keep or show. |
| Import batch | The set of records brought in by one import, removable as a unit. |
| Wave | A release stage: MVP, V1, V2, Later. |
| Enhancement | A newer Telegram feature used on top of the baseline with a fallback. |

## 13. Assumptions

Each assumption is something this specification relies on that was not confirmed. The roadmap should schedule the check before the first slice that depends on it.

| Id | Assumption | Affects | How to confirm |
|---|---|---|---|
| A-01 | The bot cannot see or override mute and device focus modes (PC-04a). | R-SCH-28 | Review of the Bot API at each release; live observation |
| A-02 | A send to a user who blocked the bot fails with a recognisable error (PC-13a). | R-IDC-18 | Block a test bot and send |
| A-03 | Photos are recompressed and scaled by Telegram (PC-19). | Card legibility, R04 | Send test cards on each client (R04 spike S4) |
| A-04 | Date-time entities render in the device's zone (PC-20a). | R-SCH-18 | Send one entity to devices in two zones |
| A-05 | A bot's own message stays editable after 48 hours and edits do not notify (PC-21a). | R-DAY-5, R-INT-4, R-SCH-25 | Edit a message older than two days; observe notifications |
| A-06 | The Mini App scanner reads retail barcodes (PC-25a). | Barcode lookup (Later) | Scan a package |
| A-07 | The WebView reports the device time zone (PC-26). | R-IDC-12, R-IDC-13 | Open a test Mini App on each client |
| A-08 | Answering a callback within 1.5 seconds is early enough (PC-30). | X-LAT-1 | Delay the answer in steps on each client |
| A-09 | A row of five labelled buttons is usable on a narrow phone; keyboard size limits are undocumented. | R-MOO-1 | Send the keyboard to the smallest supported device in both locales |
| A-10 | No production data exists that must be preserved, so the stored mood scale and the baseline schema can change in place (R01 section 7). | R-MOO-1 | Owner confirms |
| A-11 | The Mini App's activation events fire reliably on each client. | R-SLP-9 (V2 part) | Device test matrix |
| A-12 | A Telegram WebView can read a file of several hundred megabytes on the device. | R-IMX-7 | Spike with a real Apple Health export |
| A-13 | User-configured reminders, a truthful log and descriptive statistics keep the product outside medical-device scope in the EU and the US. This is a legal conclusion drawn from R03 section 8 and R06 section 14.9. | Sections 5, 8.6, 8.13, 8.15 | Counsel, before public beta |
| A-14 | All tracking records are special-category health data and explicit consent is the fitting legal basis (R06 section 14.2). | Section 8.2 | Counsel |
| A-15 | Answering inside the chat is lower friction than a native notification that opens an application (R03 section 1.1, unverified). | Principle 1, M-03 | Owner-use gate |
| A-16 | A default nudge budget of 4 does not lead users to mute the chat. | R-SCH-22, M-08 | Nudge response and block rates in the invite-only phase |
| A-17 | The glucose conversion constant and the Sleep Regularity Index formula are as stated in this document and in R05 section 8. | R-VIT-2, V2 sleep metrics | Read the primary references before implementation |
| A-18 | Apple Health exports carry sleep, body mass and water under the identifiers that third-party descriptions give (R03 section 7.1). | R-IMX-7 | Inspect a real export |
| A-19 | A reusable file identifier stays valid; a rejection is treated as a miss and the image is uploaded again. | R-MOO-8 | R04 spike S9 |
| A-20 | The rendering times measured on a desktop hold in the production container within the stated allowance. | X-LAT-4 | R04 spike S3 |
| A-21 | Reactions on bot messages are not delivered as updates in a private chat; no requirement depends on them. | none | Not needed unless a later feature wants reactions |
| A-22 | The Telegram first name may be used transiently for a greeting without being stored. | R-IDC-22 | Counsel confirms this is not "storing" for the notice |

## 14. Open questions

Each question needs the owner's decision. The specification is written on the recommendation.

| Id | Question | Recommendation | Rejected alternative |
|---|---|---|---|
| Q-01 | Do you accept that sleep is not derived from Telegram last seen, and is built from check-ins plus an opt-in, confirmed quiet-window estimate? The estimate will appear only on nights with several interactions shortly before sleep and after waking (R-SLP-13); a user who only answers reminders will not see it. | Accept. The signal is not available to bots (PC-01). Build the check-ins first and decide on the estimator after the coverage replay on the owner's own data (ROADMAP G7.4b). | A user-authorised Telegram session held by the server (R05 section 4.3): a full-account credential for two timestamps a day. |
| Q-02 | Who is the controller, where are they established, and which regions launch first? When is the legal review? | State the controller before the first external user. Launch invite-only for adults in EU or EEA countries and Ukraine. Obtain a legal review before public beta and before `vitals-and-symptoms` ships. | Open access worldwide from the start. |
| Q-03 | Is Dosegram for the owner only, or for other people too? | Build for several accounts from the start, capped at 50 by invitation. Isolation, consent and deletion are then proven before growth. | Owner-only build with multi-user added later, which would retrofit isolation. |
| Q-04 | Is the MVP content right: medication, water, mood, Today card, first images, export and deletion, with sleep and food in V1? | Yes. Water and mood are the cheapest new domains and exercise the image pipeline. | Sleep check-ins in MVP. They are cheap, but they add a fourth domain to consent, export and the Today card before the first release. |
| Q-05 | Discreet mode: forced choice at onboarding, or a default? | Forced choice. If a default is required, discreet. | Names shown by default. |
| Q-06 | Medication names in images off by default, and personal images sent as protected content by default? | Yes to both. The record summary is the intended way to hand data to someone else. | Names on and forwarding allowed by default. |
| Q-07 | Change the reminder defaults to one follow-up after 30 minutes (two reminders in total), from the current three reminders 10 minutes apart? | Change. It matches the pattern users know from other products and halves reminder messages. It requires regenerating the affected golden files. | Keep the current defaults. |
| Q-08 | Mood scale: five steps of valence with optional five-step energy, replacing the existing 1 to 10 level? | Yes. Five labelled buttons fit one row and one tap. The blob then has 25 states. If VISUALS_AND_ANALYTICS.md keeps a 7 by 5 grid, the five capture values map to grid columns 1, 2, 4, 6 and 7. | Keep 1 to 10 (ten buttons do not fit one row); seven steps (two rows or unreadable labels). |
| Q-09 | Quiet-window estimate off by default, with evidence kept 48 hours and an hourly profile kept 14 days? | Yes. | On by default; 35 days of raw instants. |
| Q-10 | May the product show a general reference for adult sleep duration? | No. The user's own goal only, in the first version. | A population reference line, which moves toward advice. |
| Q-11 | Food as a journal, with photos in V2? | Yes. If the owner wants photos sooner, they can move to V1 once encrypted object storage exists; nothing else in the module depends on them. | A calorie counter with a food database. |
| Q-12 | Which phone and wearable does the owner use? | Answer before V2. It decides the first importer after the Dosegram CSV template. With a Samsung watch the only route compatible with a bot is the Samsung Health export file. | Building an importer for a device nobody on the project owns. |
| Q-13 | What happens when a user loses their Telegram account, or Telegram suspends the bot? | No second credential. Promote regular export. State the limit in the notice. | A recovery code or password (N13). |
| Q-14 | Profiles for dependants (a child, a parent) under one account? | No. | Proxy tracking of a third person's health data. |
| Q-15 | Which method validates Mini App launch data? R02 recommends the bot-token method; R06 recommends Telegram's public-key signature so that a stolen bot token cannot forge a login. | The public-key signature first, with the bot-token method only as a fallback inside the component that already holds the token. ARCHITECTURE.md decides; the product requirement (R-IDC-16) is the same either way. | Bot-token validation in the Mini App back end. |
| Q-16 | Is a period log in scope? | Defer. If ever added: a calendar record only, never a prediction. | Cycle prediction (N6). |
| Q-17 | What is the wording of the static safety notice in the mood feature, per region? | A short counsel-reviewed text that names no condition and points to local emergency services. | Value-triggered messages (N4). |
| Q-18 | Is the diary of measurements wanted at all, given that it may show no averages or trend lines for clinical measures? | Yes, in V2, as specified. It completes the record summary. | Dropping the module; or adding ranges and alerts. |
| Q-19 | Caregiver alerts: on missed only, names off by default, in V2? | Yes. | Alerts on unknown (false alarms during outages); names on by default. |
| Q-20 | No payments through V2, and the free boundary of R-BIL-1? | Yes. | A paid tier at public beta. |
| Q-21 | May a third-party model ever process user text, voice or photos? | No in every wave of this specification. Deterministic parsing covers quick logs. | Opt-in model features (R06 open question Q9 lists the conditions). |
| Q-22 | Grouped reminders for doses due in the same minute: V1 or MVP? | V1. The existing engine sends one tested message per occurrence; grouping changes the dispatch payload. | MVP, at the cost of a later first release. |
| Q-23 | Defaults: nudge budget 4, quiet hours 22:00 to 07:00, wrap-up 21:00, weekly report on the first day of the locale's week at 09:00. | Accept as starting values and tune on M-08. | n/a |
| Q-24 | A mascot or character? | No. The mood blob and the report cards carry the visual identity. | An illustrated companion (cost and dark-pattern risk, R03 section 6.2). |
| Q-25 | Deletion and backups: the wrapped account key is stored in the same database as the data, so backups of the last 35 days can still decrypt a deleted account for anyone who also holds the key-encryption key. Accept "unreadable in live storage at once, gone from backups within 35 days" (R-IDC-19), or move wrapped keys to a separate key store with a short retention? | Accept the 35-day statement for the invite-only phase and say it in the deletion confirmation and the notice. Revisit before public beta in ADR-021. | A separate key store now: it makes deletion immediate everywhere, at the cost of a second store whose loss makes every account unreadable. |
| Q-26 | Is a manual exercise or activity log wanted (section 4.1)? | Later, after V1, as one more small domain built on the hydration template. It is not specified in this document set. | Adding it to the MVP, which widens the first release. |
| Q-27 | May any insight compare a non-medication measure with whether or when a medication was taken (R-INS-9)? VISUALS_AND_ANALYTICS originally allowed dose punctuality as an outcome ("after shorter nights, morning doses were answered late"). | No. R-INS-9 stands: no medication measure appears on either side of a comparison. The single-domain timing statement of R-INS-7 stays. If the owner wants the weaker rule, it is put to counsel first (ROADMAP G11.17). | Dose punctuality as an outcome variable. |
| Q-28 | May a consecutive-day run or a standalone milestone message exist (R-GAM-1, R-GAM-2, R-GAM-4)? VISUALS_AND_ANALYTICS originally defined runs with a grace day, including one for medication record keeping, and a milestone card sent on its own. | No. Section 8.14 stands: weekly consistency counts only, no run that can end, nothing on medication, milestones as one line in the weekly report. | Runs with a grace rule and a default-on milestone card. |
| Q-29 | After the user unblocks the bot, do reminders resume automatically with one digest (R-IDC-18), or only after a "Resume reminders" tap? | Automatic resume with one digest and a "Pause reminders" button. A paused state the user did not choose loses reminders silently. | Resume only after a tap. |

## 15. Not checked, and risks

1. This specification verified no Telegram behaviour itself. Every platform statement comes from the research briefs, with the labels they assigned. Briefs R02 and R06 disagree on one design point (Q-15), and R05 and R06 disagree on one retention period, which R-SLP-16 resolves.
2. The sibling documents (BOT_UX.md, MINI_APP.md, ARCHITECTURE.md, VISUALS_AND_ANALYTICS.md) were written in parallel and were not read. Terms and module ids here follow the planning brief. Two places need alignment when the set is reviewed together: the mood scale and blob grid (Q-08), and the reminder defaults (Q-07).
3. No build or test was run. Statements about the existing engine come from R01 and from reading `core/src/dosecord/core/domain/Schedule.scala` (reminder policy defaults and schedule rule cases), `docs/MEDICATION_REMINDER_UX.md`, ADR-011 and ADR-012.
4. The legal positions (A-13, A-14) are research, not advice. The largest product risk is that a reviewer draws the medical-device line more narrowly, which would remove or reshape `vitals-and-symptoms` and the timing statements in `insights-and-correlations`.
5. Several thresholds are first values without data behind them (all marked PROPOSED). They are configuration, and section 10 names the metric that should move each of the important ones.
6. The scope is large. If the owner wants a smaller first step, the order of section 7.2 allows stopping after step 2 (medication only on Telegram) without waste.
7. After the cross-document review of 2026-11-12 this document was amended in place (REVIEW.md lists every change). It now owns three tables that the sibling documents reference instead of restating: the notification policy (section 3.2), the consent purposes (R-IDC-6) and the mood labels (R-MOO-1, R-MOO-2). The amendments were made by targeted edits; the document was not re-read from start to end afterwards, so a sentence elsewhere may still use a superseded value. ROADMAP slice G0.1 is the control.

Next action for the owner: answer Q-01 to Q-04. They decide the sleep feature, the launch scope and the size of the first release; every other question can follow.
