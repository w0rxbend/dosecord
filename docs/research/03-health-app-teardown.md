# Research brief 03: Health application teardown

Status: research input for the Dosegram plan and specification. No product decision in this file is final until the owner accepts it.
Date of research: 2026-11-12. Every source in section 12 was checked on that date unless a different date is given.

## 0. Scope, method and evidence labels

This brief reconstructs the feature landscape of consumer health-tracking applications by functional idea. It does not copy assets, names, characters, copy text or branding. It then judges what fits a Telegram bot plus a Telegram Mini App that has no device sensors and no wearable by default.

Evidence labels used throughout:

| Label | Meaning |
|---|---|
| `[V: Sn]` | VERIFIED. The claim was read on 2026-11-12 in source `Sn` from the list in section 12. Where the source is a vendor page, a law firm or a secondary review, the source list says so. |
| `[V*: Sn]` | VERIFIED IN A SECONDARY OR SINGLE SOURCE ONLY. The claim was read in source `Sn`, but the source is not the primary owner of the fact, or sources disagree. Treat as indicative. |
| `[A]` | ASSUMPTION. The claim comes from general knowledge or inference and was not confirmed against a source during this research. It must be verified before it carries a design decision. |

Prices are listed because they show which features competitors charge for. Application-store prices vary by region and change often; every price here is `[V*]` at best.

Build-cost scale used in the feature matrix (all `[A]`, engineering estimates for one experienced developer on the existing Scala backend):

| Code | Meaning |
|---|---|
| S | Up to 3 working days |
| M | 1 to 2 weeks |
| L | 3 to 6 weeks |
| XL | More than 6 weeks, or depends on an external model, licence or data set |

## 1. Summary of findings

### 1.1 Key findings

1. The owner's sleep idea, as stated, is not buildable through the Bot API. The `User` object has no online-status or last-seen field and no update type reports presence `[V: S54]`. Presence exists only in the client (MTProto) API as `userStatusOnline` and `userStatusOffline.was_online` `[V: S55]`, and it is subject to the target user's privacy setting, which can degrade it to approximate labels such as "recently" `[V*: S59]`. Section 9 gives the replacement design: an explicit good-night and good-morning log, with a fallback estimate bounded by the user's own interactions with the bot.
2. The product can stay outside medical-device regulation in both the EU and the US if it records, reminds, trends and exports, and never interprets. The lines are concrete: no dose or timing recommendations, no interaction checking, no fertility or contraception status, no depression or anxiety assessment, no clinical thresholds or alerts on vitals, and no disease claims in any copy (section 8).
3. The strongest fit for a chat product is the reminder-plus-one-tap-log loop. Apple, Samsung, Medisafe, MyTherapy and Round Health all converge on the same loop: a scheduled prompt, a two- or three-state answer (taken, skipped, snooze), a follow-up prompt, and a history view `[V: S10, S25]`. Telegram inline buttons reproduce this loop with less friction than a native notification that opens an application `[A]`.
4. Every sensor-driven score in the market (Samsung Energy Score, Oura Readiness, Fitbit and Apple sleep scores) depends on heart rate, movement or temperature data `[V: S4, S19, S41]`. Dosegram must not imitate these scores from self-report. It can honestly report counts, durations, regularity and percentages.
5. AI photo calorie estimation is not accurate enough to present as a number of record. A 2026 NIH controlled-feeding test of four applications on 102 weighed meals found a mean underestimate of about 33 percent of meal energy (250 to 345 kcal per meal) `[V*: S33]`; the result was a conference abstract, not yet peer reviewed. Photo logging is still valuable as a low-friction journal.
6. The market has opened a price gap. Medisafe moved core functions behind a subscription in several markets `[V*: S23]`, MyFitnessPal moved barcode scanning behind a paid tier `[V*: S29]`, and Fitbit Premium became Google Health Premium at about 9.99 USD per month `[V*: S21]`. A product whose reminders, logging and export are free has a clear position.
7. File import is the realistic substitute for sensors, but it is constrained by Telegram: a bot on the hosted Bot API can download files of at most 20 MB through `getFile` `[V: S54]`, while a multi-year Apple Health export is commonly several hundred megabytes unzipped (the ZIP is roughly 30 to 40 percent of that) `[V*: S13]`. Large imports therefore have to go through the Mini App to the Dosegram backend `[A]`, or through a self-hosted Bot API server, which downloads files without a size limit `[V: S54]` (section 7).

### 1.2 Hard constraints discovered

| # | Constraint | Evidence |
|---|---|---|
| C1 | A bot cannot read a user's online status or last-seen time. | `[V: S54]` |
| C2 | A bot can download a user-sent file only up to 20 MB on the hosted Bot API. A self-hosted (local) Bot API server can "Download files without a size limit" and "Upload files up to 2000 MB". The size of file a user's Telegram client can send to a bot was not verified here `[A]`. | `[V: S54]` |
| C3 | Bulk notifications are limited to about 30 messages per second unless paid broadcasts are enabled (up to 1000 per second). Reminder fan-out at round clock times must be paced. | `[V: S56]` |
| C4 | Apple publishes no schema for `export.xml`; the structure is inferred and has changed between iOS versions. | `[V*: S13]` |
| C5 | Health Connect export requires Android 14 or later, writes a ZIP to a cloud-storage application, and its internal SQLite layout is undocumented. Medical records are excluded. | `[V: S15]`, `[V*: S17]` |
| C6 | Health Connect has no medication data type and no mood data type in its published data-type table. Medication and mood data cannot be imported from it. | `[V: S16]` |
| C7 | In the EU, software intended to assess, monitor and manage depression through mood questionnaires is given as an example of medical device software; software that calculates fertility status is medical device software (class I), and software for contraception is class IIb. | `[V: S50]` |
| C8 | In the US, a product stops being a general wellness product if its labelling, interface or functionality references specific diseases or diagnostic thresholds, issues alerts that recommend clinical action, or gives treatment guidance. | `[V: S45]` |
| C9 | Health data is a special category under GDPR Article 9; processing requires explicit consent or another Article 9(2) ground. | `[V: S51]` |
| C10 | The US FTC Health Breach Notification Rule, as amended with effect from 29 July 2024, covers health applications outside HIPAA and treats unauthorised disclosure (for example to advertising or analytics vendors) as a breach. | `[V: S44]` |

## 2. Platform teardowns

Each teardown uses the same nine headings: core loop, data captured, input-friction tricks, reminder design, visual and status language, insight and correlation, sharing and caregivers, export, monetisation.

### 2.1 Samsung Health

| Aspect | Findings |
|---|---|
| Core loop | A home dashboard of tiles organised, since the June 2026 redesign, around five areas: Sleep, Activity, Nutrition, Mindfulness and Vitals `[V*: S4]`. The user glances at scores in the morning, logs food and water during the day, and follows coaching programmes. |
| Data captured | Steps and exercise (sensor), sleep stages Awake, REM, Light and Deep (watch or ring), blood oxygen during sleep, snoring audio (phone microphone, optional recording kept 7, 31 or 100 days) `[V: S1]`; manual water intake against a goal, meals, snacks and caffeine, menstrual cycle, stress `[V: S7]`; medications with reminders and refill alerts `[V*: S6]`. |
| Input-friction tricks | Passive capture by wearable; pill-bottle scanning for medications (added October 2024) `[V*: S6]`; barcode scanning for food `[V*: S7]`; "Scan Meal" photo logging in beta in the US from October 2026, positioned as nutrition help "without requiring users to log every meal" `[V*: S3]`. |
| Reminder design | Medication alerts for doses and for refills, with ranking of medications by importance `[V*: S6]`. Bedtime guidance through sleep coaching `[V: S1]`. |
| Visual and status language | Sleep score; eight "sleep animals" assigned after 7 days and nights of wear: Unconcerned Lion, Sensitive Hedgehog, Nervous Penguin, Sun Averse Mole, Cautious Deer, Easygoing Walrus, Alligator on the Hunt, Exhausted Shark `[V: S1]`; a pentagon "Sleep Balance" graph with five indicators (sleep time, sleep regularity, night-time wakefulness, sleep satisfaction, Energy Score) added October 2026 `[V*: S2, S3]`; Energy Score combining sleep, activity and heart rate `[V*: S4]`; Diet Score with four tiers from "Needs Attention" to "Excellent" `[V*: S3]`; sleep consistency shown as the number of days in the current week on which the user met their bed and wake goals `[V: S1]`. |
| Insight and correlation | Sleep coaching programme of four to five weeks with missions, checklists, articles and reports `[V: S1]`; from October 2026 coaching starts after three days of data and adjusts weekly `[V*: S2]`. Health Assistant (AI) beta for eligible US users from 21 July 2026 `[V*: S5]`. |
| Sharing and caregivers | "Together" challenges with friends `[V*: S7]`. No caregiver escalation for medications was found `[A]`. |
| Export | Settings, then "Download personal data"; files land in My Files, Downloads, Samsung Health `[V: S8]`. Third-party descriptions: one CSV per data category, file names prefixed `com.samsung.shealth.` `[V*: S9]`. |
| Monetisation | Free; it supports the hardware business `[A]`. Samsung describes the features as wellness tools, not tools for diagnosis or treatment `[V*: S4]`. |

Transferable ideas: weekly consistency counted in days instead of an unbroken chain; a monthly archetype derived from regularity and duration (original characters, not Samsung's animals); a balance polygon that shows several self-reported dimensions at once; a three-day minimum before any coaching statement.

### 2.2 Apple Health (including Medications, State of Mind and Sleep Score)

| Aspect | Findings |
|---|---|
| Core loop | Health is an aggregation store with a Summary of pinned metrics. Medications and State of Mind are the two manual loops: a notification arrives, the user answers on the lock screen or watch, and the entry appears in history. |
| Data captured | Medications: name, type, strength, optional shape and colour, schedule, start and end date; each dose logged as Taken or Skipped; as-needed medications logged on demand `[V: S10]`. State of Mind: a "feeling kind" (momentary emotion or daily mood), a position on a scale from Very Unpleasant through Neutral to Very Pleasant, descriptive words, and associations (what had the biggest impact) `[V*: S12]`. The scale has seven positions `[A]`. |
| Input-friction tricks | Search of a medication database; camera scan of a label in the US only; "Add Custom" for unlisted items `[V: S10]`. Logging from the notification itself without opening the application `[V: S10]`. |
| Reminder design | Dose reminders; follow-up reminder if a dose is not logged 30 minutes after the first notification (iOS 17 and later); on Apple Watch a third answer, "Remind Me in 10 Minutes"; a notification when the device time zone changes, asking the user to confirm the schedule `[V: S10, S11]`. Schedules: specific days of the week, time of day, or a cycle; cyclical schedules need iOS 18 `[V: S10]`. State of Mind reminders during the day or at the end of the day `[V*: S12]`. |
| Visual and status language | Medications are drawn as coloured pill shapes `[V: S10]`. State of Mind uses an abstract animated shape whose colour and form change along the valence slider `[A]`. Sleep Score (watchOS 26): 0 to 100 with bands, after watchOS 26.2: Very Low 0 to 40, Low 41 to 60, OK 61 to 80, High 81 to 95, Very High 96 to 100 `[V*: S14]`. |
| Insight and correlation | Per-medication count of taken and skipped `[V: S10]`. State of Mind charts set against lifestyle factors: exercise, sleep, time in daylight, mindful minutes `[V*: S12]`. PHQ-9 and GAD-7 questionnaires are offered inside Health `[V*: S12]`. |
| Sharing and caregivers | Health Sharing with family and friends can include medications `[V: S10]`. Drug-interaction information between listed medications is available in the US only, and custom medications are excluded from the check `[V: S10]`. |
| Export | "Export PDF" of the current medication list `[V: S10]`; "Export All Health Data" produces XML `[V*: S13]` (format notes in section 7). |
| Monetisation | Free with the device. |

Transferable ideas: the 30-minute follow-up and the 10-minute snooze as defaults; the time-zone-change prompt; a two-step mood entry (valence first, optional words and causes second); archive instead of delete for a discontinued medication, keeping its history `[V: S10]`. Not transferable: interaction checking and clinical questionnaires (section 8).

### 2.3 Google Fit and Health Connect

| Aspect | Findings |
|---|---|
| Status | The Google Fit APIs, including the REST API, are scheduled for end of service by the end of 2026; Google directs developers to Health Connect or the Google Health API `[V*: S18]`. Google Fit is therefore not a target for integration. |
| Role of Health Connect | An on-device Android data store shared between applications with per-type permissions. It is not a cloud API; a server-side bot cannot call it `[V: S16]`. |
| Data types (complete category list) | Activity, Body Measurement, Cycle Tracking, Nutrition, Sleep, Vitals, Wellness `[V: S16]`. Types relevant to Dosegram: Hydration (interval, volume), Nutrition (interval, meal type, energy and about 40 nutrient totals including caffeine), Sleep session (interval with stages), Weight, Height, Body fat, Blood pressure (systolic, diastolic, body position, measurement location), Blood glucose (level, specimen source, meal type, relation to meal), Body temperature, Basal body temperature, Heart rate, Resting heart rate, Oxygen saturation, Menstruation flow and period, Mindfulness session `[V: S16]`. |
| Absent types | No medication record and no mood record appear in the table `[V: S16]`. |
| Export | Scheduled export daily, weekly or monthly on Android 14 or later, to a cloud-storage application, as a ZIP named "Health Connect.zip" by default; health records are not exported `[V: S15]`. |

Transferable ideas: the Health Connect record shapes are a ready, vendor-neutral vocabulary for Dosegram's own domain model (interval versus instantaneous records; blood glucose with relation-to-meal; hydration as a volume over an interval). Adopting compatible field names reduces later import and export work `[A]`.

### 2.4 Fitbit, now Google Health

| Aspect | Findings |
|---|---|
| Status | The Fitbit application was renamed Google Health from 19 May 2026; Fitbit Premium became Google Health Premium at about 9.99 USD per month or about 99 USD per year, and the Gemini-based Google Health Coach is a paid feature `[V*: S21]`. |
| Sleep score | 0 to 100 with ranges Excellent 90 to 100, Good 80 to 89, Fair 60 to 79, Poor below 60; most users average between 72 and 83. Six metrics: sleep duration, time to sound sleep, sound sleep, restlessness (stirs under 5 minutes), full awakenings (over 5 minutes), interruptions `[V: S19]`. |
| Sleep profile | A monthly analysis on ten metrics, a "sleep animal" from a set of six, and guidance; needs at least 14 nights of wear in a month; delivered on the first of each month; paid `[V*: S20]`. |
| Visual and status language | Score bands with words, monthly animal archetype, weekly and monthly recaps `[V*: S20]`. |
| Export | Fitbit account data export (CSV or JSON by period and type) and Google Takeout under "Google Health" (JSON) `[V*: S22]`. |
| Monetisation | Hardware plus subscription; advanced insights and coaching are paid `[V: S19]`, `[V*: S21]`. |

Transferable ideas: publishing the score bands and the typical range, which makes a number meaningful; a monthly recap delivered on a fixed date, gated on a minimum number of logged nights.

## 3. Category teardowns

### 3.1 Medication

| Aspect | Medisafe | MyTherapy | Round Health | Samsung and Apple (see 2.1, 2.2) |
|---|---|---|---|---|
| Core loop | Reminder, mark taken or skipped, adherence history `[V*: S23]` | Reminder, confirm, plus daily to-do list that also holds measurements and symptom checks `[V*: S24]` | Reminder window opens, user confirms inside the window `[V: S25]` | Notification, Taken or Skipped |
| Data captured | Medications, doses, refills, measurements `[V*: S23]` | Medications, intake log, symptoms and pain, mood, blood pressure, weight, blood sugar and more than 20 other measurements `[V*: S24]` | Medications, doses, refill counts `[V: S25]` | See above |
| Friction tricks | Drug database search `[A]` | One combined daily list `[V*: S24]` | Minimal single-screen design `[A]` | Label and bottle scanning |
| Reminder design | Persistent reminders; missed-dose alert to a designated "Medfriend" `[V*: S23]` | Supports complex dosing schemes; prescription and refill reminders `[V*: S24]` | "Reminder windows" tailored to the medicine; persistent notifications that repeat inside the window if the first is missed; refill alert fixed at 5 pills remaining according to a user review `[V: S25]` | 30-minute follow-up, 10-minute snooze, time-zone prompt |
| Visual language | Pill shapes and colours `[A]` | Checklist, progress, charts `[A]` | Circular dose markers `[A]` | Pill shapes |
| Insight | Adherence reports; interaction warnings `[V*: S23]` | Health report combining plan, intake, symptoms and measurements `[V*: S24]` | Basic history `[A]` | Taken and skipped counts |
| Sharing and caregivers | Medfriend: a family member or caregiver is notified of a missed dose; the number of Medfriends is unlimited on the paid tier `[V*: S23]` | One-time code to share plan and diary with a clinician; PDF report by e-mail `[V*: S24]` | Account sync; reviews report the developer's site and sync as unavailable `[V: S25]` | Apple Health Sharing |
| Export | Reports `[A]` | PDF health report `[V*: S24]` | None found `[A]` | Apple PDF medication list |
| Monetisation | Premium about 4.99 USD per month or 39.99 USD per year in the US; one competitor source says the free tier is now limited to two medications and that the product is paid-only outside the US from 1 January 2026 `[V*: S23]` | Free; funded by pharmaceutical-industry partnerships `[A]` | Free; last App Store update 20 November 2024 `[V: S25]` | Free |

Observed failure modes worth designing against, all from Round Health user reviews `[V: S25]`: wrong remaining-quantity arithmetic on an every-other-day schedule; a late "taken" tap being recorded at the tap time instead of the real intake time; loss of data when the vendor's sync service disappeared. Dosegram's existing dose lifecycle should keep a separate "taken at" time that the user can correct, and the export must not depend on the service staying online.

### 3.2 Water

| Aspect | WaterMinder | Plant Nanny | Waterllama |
|---|---|---|---|
| Core loop | Tap a preset cup, watch a body-shaped figure fill `[V: S26]` | Log a glass, a virtual plant is watered and grows `[V*: S27]` | Log a drink, a character fills; join challenges `[V*: S28]` |
| Data captured | Volume, drink type, time `[V: S26]` | Volume, time `[V*: S27]` | Volume, drink type, time `[V*: S28]` |
| Friction tricks | Widgets and watch complication that log without opening the application; preset containers `[V: S26]` | One-tap glass `[A]` | Preset drinks `[A]` |
| Goal design | Goal from body weight or a personal goal; the developer states it is not a medical application and that the suggestion is only an estimate `[V: S26]` | Goal from weight and activity `[A]` | Personalised daily goal `[V*: S28]` |
| Reminder design | Interval reminders; user reviews report missed reminders `[V: S26]` | Reminders tied to the plant needing water `[A]` | Reminders `[A]` |
| Visual language | More than 50 characters that fill with water `[V: S26]` | Plants (cacti, carnations and others) with a progress bar `[V*: S27]`; the plant wilts when the user does not log `[A]` | Llama and other animal characters `[V*: S28]` |
| Engagement | History and achievements `[A]` | Self-challenges and monthly hydration milestones `[V*: S27]` | Interactive challenges (for example a week without sugary drinks) `[V*: S28]`, `[A]` |
| Contention | A reviewer objects that every beverage counts fully towards the water goal `[V: S26]` | | |
| Monetisation | Free with in-app purchases; yearly premium about 19.49 CAD, one-time purchase about 34.99 USD in one storefront `[V*: S26]` | Free with in-app purchases `[V*: S27]` | Freemium `[V*: S28]` |

Transferable ideas: two or three preset volumes that the user can edit; a fill visual that reaches 100 percent; a user-editable goal with a plainly worded estimate disclaimer; a per-drink hydration factor as an optional setting, off by default `[A]`. Not transferable: a character that suffers when the user does not log (section 6.2).

### 3.3 Food

| Aspect | MyFitnessPal | Cronometer | Lose It | Yazio | Photo-first loggers (Cal AI, SnapCalorie) |
|---|---|---|---|---|---|
| Core loop | Search or scan, pick serving, diary total against calorie goal `[A]` | Same, with micronutrient targets `[V*: S30]` | Same, with a daily budget `[V*: S31]` | Same, plus fasting timer `[V*: S32]` | Photograph the plate, accept an estimate `[V*: S33]` |
| Data captured | Foods, servings, calories, macros, exercise, weight `[A]` | Up to 84 nutrients, biometrics, exercise `[V*: S30]` | Foods, calories, weight, health metrics `[V*: S31]` | Foods, calories, fasting windows `[V*: S32]` | Photo, estimated calories and macros |
| Friction tricks | Database of about 14 million foods; barcode scan; recipe import; AI logging `[V*: S29]` | Curated database with fewer crowd-sourced entries `[A]` | "Snap It" photo logging and "Say It" voice logging, available to free members to try `[V*: S31]` | AI photo logging and barcode scanner, both in the paid tier `[V*: S32]` | Photo only |
| Visual language | Remaining-calories equation, macro rings `[A]` | Nutrient target bars `[A]` | Budget bar, logging streak `[V*: S31]` | Fasting countdown ring, about 20 fasting plans including 16:8 and 5:2 `[V*: S32]` | Per-meal card |
| Insight | Weekly digest `[A]` | Nutrient gap reports `[A]` | Patterns `[A]` | Recipes (more than 3000) `[V*: S32]` | None |
| Export | CSV export `[A]` | Raw data (servings, exercises, biometrics) as CSV for all users; printable PDF report on the paid tier `[V*: S30]` | Export `[A]` | Not found | Not found |
| Monetisation | Premium about 79.99 USD per year and a higher "Premium+" tier about 99.99 USD per year; barcode scanner moved to paid tiers `[V*: S29]` | Gold about 59.88 USD per year (sources disagree) `[V*: S30]` | Premium about 39.99 USD per year (sources disagree) `[V*: S31]` | PRO about 30 to 48 EUR or USD per year (sources disagree) `[V*: S32]` | Subscription `[A]` |

Accuracy evidence: the NIH test described in section 1.1 covered MyFitnessPal, Lose It, Cal AI and Appediet; mean underestimates were 327, 333, 345 and 252 kcal per meal respectively, fat was underestimated by about 30 g, and carbohydrate estimates were better than fat or protein estimates `[V*: S33]`. Cal AI's own claim is 90 percent accuracy on visible foods; SnapCalorie advertises under 20 percent error; neither claim was found independently confirmed `[V*: S33]`.

Open data for a food feature:

| Source | Terms |
|---|---|
| Open Food Facts | More than 3 000 000 products in 182 countries `[V*: S34]`. Limits: 15 requests per minute per IP for product reads, 10 requests per minute per IP for search. A custom User-Agent of the form `AppName/Version (ContactEmail)` is requested. Database under the Open Database License, contents under the Database Contents License, product images under CC BY-SA `[V: S34]`. Barcode lookup path pattern `/api/v3.6/product/<barcode>.json` on `https://world.openfoodfacts.org` `[V: S34]`. |
| USDA FoodData Central | Default limit 1000 requests per hour per IP with a registered key; the shared demonstration key is limited to 30 requests per hour and 50 per day; data is public domain under CC0 1.0 with a request for credit `[V*: S35]`. |

Design consequences: the share-alike terms of Open Food Facts apply to derived databases, so Dosegram should cache looked-up products keyed by barcode, keep attribution, and keep user diary entries (which are the user's data, not a derived database) separate `[A]`; this needs a short licence review before build. The contact address in the User-Agent must be a project role mailbox, not a personal address.

### 3.4 Mood

| Aspect | Daylio | How We Feel | Finch | Bearable | Apple State of Mind (see 2.2) |
|---|---|---|---|---|---|
| Core loop | Pick one of five moods, tap activity icons, optional note `[V*: S36]` | Pick a quadrant, pick a word, tag context, get a regulation strategy `[V*: S37]` | Set daily self-care goals, check in, send a virtual pet on an "adventure", read its story in the evening `[V: S38]` | Rate mood, symptoms and factors each day; read correlations `[V*: S39]` | Slider, words, associations |
| Data captured | Mood on a five-point scale, activities from a preset or custom list, note `[V*: S36]` | Emotion word from 144, placed on two axes: energy and pleasantness, in four coloured quadrants (red, blue, yellow, green) `[V*: S37]` | Goals completed, mood, reflections, breathing exercises, quizzes `[V: S38]` | Mood, symptoms, sleep, medication, food, factors, significant events `[V*: S39]` | See 2.2 |
| Friction tricks | Entry possible without typing `[V*: S36]` | Two taps to a precise word `[A]` | The pet turns chores into a narrative `[V: S38]` | Customisable form `[A]` | Lock-screen prompt |
| Reminder design | Daily reminder at a chosen time `[A]` | Scheduled check-ins `[V*: S37]` | Morning and evening rituals `[A]` | Reminders `[A]` | Daytime or end-of-day |
| Visual language | Five coloured faces; "Year in Pixels" calendar; charts `[V*: S36]` | Colour-coded matrix `[V*: S37]` | A bird-like pet that grows `[V: S38]` | Calendar and timeline views `[V*: S39]` | Abstract shape |
| Insight | Statistics, mood-activity correlations (advanced statistics paid) `[V*: S36]` | Patterns by tag `[A]` | Mood analytics (paid) `[V*: S38]` | Correlations between factors and symptoms (paid) `[V*: S39]` | Lifestyle-factor overlays |
| Export | CSV free, PDF paid `[V*: S36]` | Not found | Not found | CSV of all data free, PDF paid `[V*: S39]` | Health export |
| Monetisation | About 4.99 USD per month, 35.99 USD per year, lifetime 59.99 USD `[V*: S36]` | Free; operated by a non-profit, The How We Feel Project, Inc., with roots at Yale's Center for Emotional Intelligence `[V*: S37]` | Free with "Plus" tiers from about 5.99 to 69.99 USD `[V*: S38]` | About 6.99 USD per month or 34.99 USD per year `[V*: S39]` | Free |

Transferable ideas: the two-axis model (valence and energy) gives a natural parameterisation for a server-rendered "mood blob" image: valence drives hue and smoothness, energy drives size and spikiness, so an original shape can be generated with no borrowed artwork `[A]`. A five-step valence tap as the mandatory first step keeps the entry to one tap; energy, words and causes are optional second steps. The year-in-pixels calendar is a single image and suits chat delivery.

### 3.5 Sleep

| Aspect | Sleep Cycle | Oura | Samsung, Fitbit, Apple (see section 2) |
|---|---|---|---|
| Core loop | Phone on the bedside table, microphone analysis overnight, smart alarm in the morning, morning report `[V*: S40]` | Ring worn overnight; three scores each morning `[V*: S41]` | Watch or ring overnight; score in the morning |
| Data captured | Sleep stages inferred from sound, snoring, sleep talking, coughing; sleep notes `[V*: S40]` | Sleep, readiness and activity; chronotype `[V*: S41]` | Stages, oxygen, snoring |
| Friction tricks | No hardware `[V*: S40]` | Passive `[V*: S41]` | Passive |
| Visual language | Sleep graph, sleep goal and score `[V*: S40]` | Readiness 0 to 100 from seven contributors, two of which are sleep and sleep balance; a sleep score above 85 raises readiness `[V*: S41]` | Scores, animals, balance polygon |
| Insight | Effect of tagged "sleep notes" (for example caffeine) on sleep quality `[A]` | Tags and trends; AI weekly insights (paid) `[V*: S41]` | Coaching |
| Monetisation | Premium about 39.99 USD per year (sources disagree) `[V*: S40]` | Membership 5.99 USD per month or 69.99 USD per year; without it only the three daily scores, battery level and explore content are shown `[V*: S41]` | Device, plus Google Health Premium |

What can be built without sensors: bedtime, wake time and duration from an explicit log; subjective sleep quality (one tap in the morning); regularity of bed and wake times across a week; evening tags (caffeine, alcohol, late meal, screen time) for later correlation. What cannot be built: stages, snoring, oxygen, awakenings counted objectively, or any 0 to 100 score that implies measurement.

### 3.6 Cycle tracking

| Aspect | Flo | Clue | Natural Cycles |
|---|---|---|---|
| Core loop | Log period days and symptoms, read predictions and content `[A]` | Log period and symptoms, read predictions and cycle analysis `[V*: S43]` | Measure temperature each morning, receive a fertility status `[V*: S43]` |
| Regulatory position | Wellness positioning `[A]` | The "Clue Conceive" mode is described as a medical-device feature; the application states it is not for contraception `[V*: S43]` | FDA De Novo in August 2018 and class II; CE marked; 93 percent effective with typical use and 98 percent with perfect use `[V*: S43]` |
| Privacy history | FTC settlement announced 13 January 2021 over disclosure of health information to analytics providers including Facebook and Google; in 2025 a federal jury found Meta liable under California law for collecting Flo users' data, and Flo settled on 31 July 2025 `[V*: S42]`. "Anonymous Mode" since September 2022 `[V*: S42]`. | | |
| Monetisation | Subscription `[A]` | Clue Plus about 39.99 USD per year `[V*: S43]` | Subscription `[A]` |

Consequence for Dosegram: cycle data is the most litigated category of consumer health data, and prediction features move directly into medical-device territory in the EU (section 8.1). A period log without fertility output is feasible; it is ranked "Could", and fertile-window prediction is rejected.

### 3.7 Symptoms and vitals

| Idea | Source applications | Findings |
|---|---|---|
| Symptom diary with severity | Bearable, MyTherapy | Daily severity rating per user-defined symptom; calendar and timeline `[V*: S24, S39]`. |
| Measurements | MyTherapy (blood pressure, weight, blood sugar and more than 20 others) `[V*: S24]`; Health Connect record shapes `[V: S16]` | Manual entry with a timestamp and, for glucose, relation to a meal. |
| Trend display | All | Line chart with a moving average `[A]`. |
| Clinician hand-over | MyTherapy PDF report and one-time share code `[V*: S24]`; Apple PDF medication list `[V: S10]` | The export, not the interpretation, is the product. |
| Regulated neighbours | Blood pressure monitors, glucose meters and any output used for treatment decisions such as medication dosing are named as not general wellness products `[V: S45]`; software that adds trend analysis or risk assessment to a home glucose meter is an example of medical device software in the EU `[V: S50]` | Dosegram may record what the user types and draw it. It must not classify a reading. |

### 3.8 Habits and streaks

| Idea | Source | Findings |
|---|---|---|
| Daily streak counter | Lose It logging streak `[V*: S31]`; Duolingo `[V*: S58]` | Streak designs work mainly through loss aversion: one survey of 859 streak designs concludes that streaks "are about losing" `[V*: S58]`. A Duolingo product manager reports that retention rises markedly once a seven-day streak is reached, and that flexibility such as streak freezes is a key lesson `[V*: S58]`. These figures originate from the vendor and are not independent. |
| Weekly consistency | Samsung sleep consistency (days this week within goal) `[V: S1]` | Counts successes in a window; a missed day does not reset anything. |
| Virtual pet | Finch `[V: S38]`, Plant Nanny `[V*: S27]` | A companion that reflects the user's activity. Finch frames the pet as returning with stories `[V: S38]`; Plant Nanny's plant depends on being watered `[V*: S27]`. |
| Minimum-data gates | Samsung (7 nights for an animal, 3 days for coaching) `[V: S1]`, `[V*: S2]`; Fitbit (14 nights for a monthly profile) `[V*: S20]` | A gate sets an honest short-term goal and prevents conclusions from too little data. |

### 3.9 Existing Telegram health bots and Mini Apps

All findings in this subsection are `[V*: S57]`: they come from product listings and launch pages, and neither traction nor maintenance status was checked.

| Product | What it does | Lesson |
|---|---|---|
| MedPlanner for Telegram | Free pill-reminder bot, positioned explicitly against a paid medication application | The price gap in section 1.1 is already being used as positioning. |
| MedPillReminder | Builds medication schedules from natural language or voice | Natural-language schedule entry is an expected feature in chat. |
| Hydro Mate (open source) | Scheduled drink reminders and logging buttons with preset container sizes | The preset-button pattern is the norm. |
| A water-tracking Mini App (freelance showcase) | Progress ring, several drink types, bot reminders, per-user settings | Bot for prompts, Mini App for the ring and settings. |
| LogIt2Win | Bot for water, sleep, meditation, blood sugar, mood and energy; "No sign-up, no password, no app install" | Zero-registration onboarding is the main selling point of the channel. |
| HealthZap | Weight, water, supplements and calories from Telegram or web | A web surface alongside the bot is common. |
| MyHealthLogger | Meals, symptoms, mood and sleep by text, photo or voice note | Multi-modal free-form capture is already present in the market. |

No established Telegram product was found that combines medication reminders with a caregiver loop, a Mini App registry, image reports and multi-domain tracking. That combination is the open position.

## 4. Cross-cutting patterns

### 4.1 Input-friction tricks, ranked by fit for Telegram

| Rank | Trick | Seen in | Telegram realisation |
|---|---|---|---|
| 1 | Answer inside the prompt | Apple, Round Health, Medisafe | Inline buttons on the reminder message: Taken, Skip, Snooze. |
| 2 | Preset quantities | WaterMinder, Hydro Mate | Inline or reply-keyboard buttons for two or three volumes, editable in the Mini App. |
| 3 | One tap first, detail optional | Daylio, Apple State of Mind | Five valence buttons; a second message offers optional tags. |
| 4 | Free text and voice | Lose It "Say It", MedPillReminder, MyHealthLogger | A message such as "300 ml water" or a voice note is parsed and echoed back with Confirm and Edit buttons. Parsing must never be silently trusted. |
| 5 | Photo as the entry | Samsung Scan Meal, Lose It "Snap It", Cal AI | A photo sent to the bot becomes a meal journal entry; estimation is optional and labelled as rough. |
| 6 | Repeat the last entry | Food applications generally `[A]` | "Same as yesterday's breakfast" and recent-items buttons. |
| 7 | Scanning | Apple label scan (US), Samsung bottle scan, barcode scanners | Photo of a package for a barcode, or the Mini App camera. Whether the Mini App scanner accepts retail barcodes is not established here `[A]`; see open question Q6. |
| 8 | Passive capture | Wearables | Not available. Substitutes are imports (section 7) and bot-interaction timestamps (section 9). |

### 4.2 Reminder design patterns

| Pattern | Evidence | Recommendation |
|---|---|---|
| Follow-up after 30 minutes if unanswered | `[V: S10]` | Adopt as the default for medication; one follow-up, then record the dose as missed according to the existing dose lifecycle. |
| Snooze of 10 minutes | `[V: S10]` | Adopt; offer 10, 30 and 60 minutes. |
| Reminder window instead of an instant | `[V: S25]` | Adopt for medication; the user defines a window and the bot nudges inside it. |
| Time-zone-change prompt | `[V: S10]` | Adopt; the bot cannot detect the device zone, so the trigger is a user action in the Mini App (which can read the browser time zone `[A]`) or a `/timezone` command. |
| Refill reminder from remaining count | `[V*: S6, S24]`, `[V: S25]` | Adopt with a user-set threshold, not a fixed one. |
| Escalation to another person | `[V*: S23]` | Adopt as an opt-in from both people (section 6.1). |
| Interval reminders for water | `[V: S26]` | Adopt with a hard daily cap and automatic silence once the goal is met. |
| End-of-day reflection | `[V*: S12]`, `[V: S38]` | Adopt as one combined evening message (mood, sleep intention, unlogged items) instead of one message per domain. |

Notification budget: Dosegram covers six domains. If each domain sends its own prompts the bot becomes noise and will be muted. Recommendation: a per-user daily budget with medication prompts exempt, and batching of all non-medication prompts into at most three scheduled touchpoints (morning, midday, evening) by default `[A]`.

### 4.3 Visual and status language

| Device | Seen in | Meaning carried | Fit for server-rendered chat images |
|---|---|---|---|
| Closed ring or filling vessel | WaterMinder, water Mini Apps, fasting timers | Progress to a daily goal | High. One number, one shape. |
| Score 0 to 100 with word bands | Fitbit, Apple, Oura, Samsung | Composite quality | Low for Dosegram. Requires sensor inputs; a self-report score would mislead. |
| Balance polygon | Samsung Sleep Balance | Several dimensions at once | Medium. Usable for a weekly overview across domains using percentages of the user's own goals. |
| Animal or character archetype | Samsung (8), Fitbit (6) | Monthly pattern label | Medium. Needs original illustration and careful, non-judgemental wording. |
| Abstract mood shape | Apple State of Mind, How We Feel colours | Valence and energy | High. Generated procedurally. |
| Calendar heat map | Daylio Year in Pixels | Long-term pattern | High. One image per month or year. |
| Pill shapes and colours | Apple, Medisafe | Recognition of a medication | Medium. Useful in the Mini App registry; in chat a text label is enough. |
| Checklists and to-do lists | MyTherapy | What remains today | High. This is the natural "today" message. |
| Weekly and monthly report | Fitbit, Samsung, Oura | Recap and reflection | High. The report card is the main image product. |
| Mascot | Finch, Plant Nanny, Waterllama | Emotional attachment | Low to medium. High illustration cost and dark-pattern risk. |

### 4.4 Insight and correlation features

| Feature | Seen in | Honest version for Dosegram |
|---|---|---|
| Factor-to-outcome correlation | Bearable, Daylio, Apple | "On days after 7 hours or more of logged sleep, your average mood was 3.9; on other days 3.1 (18 and 12 days)." Always show the counts, never use causal verbs, require a minimum number of days in each group. |
| Trend versus personal baseline | Oura, Apple | Compare this week with the user's own previous four weeks. |
| Adherence by time of day or weekday | Medisafe reports `[A]` | "Evening doses were logged late on 5 of 7 days." Descriptive only; no advice about changing the schedule. |
| AI coach | Samsung Health Assistant, Google Health Coach | Not adopted (section 10.2). |

## 5. Master feature matrix

Columns: input method in Telegram uses these codes: BM bot message (text or command), IB inline button, MA Mini App, PH photo, VO voice note, FW forwarded data or screenshot, IF import file. Feasibility is feasibility without sensors. Tier is the MoSCoW tier proposed for Dosegram: M must, S should, C could, W will not.

### 5.1 Medication

| Feature | Source applications | User value | Telegram input | Feasibility | Cost | Tier |
|---|---|---|---|---|---|---|
| Medication registry (name, form, strength, notes, archive) | Apple, Medisafe, MyTherapy, Samsung | One list of what the user takes | MA primary, BM fallback | High | M | M |
| Schedules: fixed times, days of week, every N days, cycles, as-needed | Apple, MyTherapy | Covers real regimens | MA | High | L (largely specified already in the existing UX document) | M |
| Reminder with Taken, Skip, Snooze | Apple, Round Health, Medisafe | The core loop | IB | High | M | M |
| Follow-up reminder and reminder window | Apple, Round Health | Fewer missed doses | automatic, IB | High | S | M |
| Corrected "taken at" time and late logging | Apple; Round Health failure mode | Accurate history | IB, MA | High | S | M |
| As-needed (PRN) dose log | Apple | Record of occasional use | BM, IB | High | S | M |
| Adherence statistics and calendar | Medisafe, Apple | Awareness | image, MA | High | M | M |
| Refill tracking and reminder | Samsung, MyTherapy, Round Health | Avoids running out | MA setup, IB confirm | High | M | S |
| Time-zone-change handling | Apple | Travel safety | MA, BM | High | M | S |
| Caregiver missed-dose notification (two-sided opt-in) | Medisafe | Support from a trusted person | BM invite link, IB consent | High | L | S |
| Medication list as PDF for a clinician | Apple, MyTherapy | Appointment hand-over | BM command, MA | High | M | S |
| Natural-language or voice schedule entry | MedPillReminder | Faster setup | BM, VO | Medium | L | C |
| Label or package photo to pre-fill a medication | Apple (US), Samsung | Faster setup | PH | Medium | L | C |
| Drug database with descriptions and side effects | Samsung (licensed content) | Reference | MA | Medium | XL (licensing) | W |
| Drug-interaction checking | Apple (US), Medisafe | Safety information | n/a | Medium | XL | W (regulatory, section 8) |

### 5.2 Water

| Feature | Source applications | User value | Telegram input | Feasibility | Cost | Tier |
|---|---|---|---|---|---|---|
| Quick-add preset volumes | WaterMinder, Hydro Mate | One-tap logging | IB, BM | High | S | M |
| Daily goal, user-editable, with estimate disclaimer | WaterMinder | Target | MA | High | S | M |
| Progress image (ring or vessel) | WaterMinder, water Mini Apps | Feedback | image | High | S | M |
| Interval reminders with cap and auto-silence at goal | WaterMinder, Plant Nanny | Habit | automatic | High | S | M |
| Drink types and optional hydration factor | WaterMinder, Waterllama | Realism | IB, MA | High | S | S |
| Caffeine and alcohol counts as separate tallies | Samsung (caffeine), Health Connect (caffeine total) | Feeds sleep correlations | IB | High | S | C |
| Hydration challenges against oneself | Plant Nanny, Waterllama | Variety | IB | High | M | C |

### 5.3 Food

| Feature | Source applications | User value | Telegram input | Feasibility | Cost | Tier |
|---|---|---|---|---|---|---|
| Meal journal: photo, meal type, free text, time | MyHealthLogger, Samsung | Low-friction record | PH, BM, IB | High | M | S |
| Regular-meal check (breakfast, lunch, dinner logged) | Samsung nutrition without full logging | Routine awareness | IB | High | S | S |
| Manual calorie and macro entry | All food applications | For users who already count | BM, MA | High | M | C |
| Recent and favourite meals | All food applications | Repeat entry | IB, MA | High | M | C |
| Packaged-food lookup by barcode (Open Food Facts) | MyFitnessPal, Yazio, Samsung | Accurate packaged items | MA scanner or typed code, PH | Medium | L | C |
| AI estimate from a photo, labelled as rough and editable | Lose It, Cal AI, Samsung Scan Meal | Speed | PH | Medium | XL (model cost and accuracy) | C |
| Full nutrient database and micronutrient targets | Cronometer, MyFitnessPal | Detailed nutrition | MA | Medium | XL | W |
| Fasting timer | Yazio | Eating-window tracking | IB | High | S | C |
| Weight-loss programme and calorie budget coaching | Lose It, MyFitnessPal | Weight management | n/a | Medium | L | W (eating-disorder risk, scope) |

### 5.4 Mood and mind

| Feature | Source applications | User value | Telegram input | Feasibility | Cost | Tier |
|---|---|---|---|---|---|---|
| One-tap mood check-in on a five-step valence scale | Daylio, Apple | Daily record | IB | High | S | M |
| Optional energy level, feeling words and causes | How We Feel, Apple | Precision | IB, MA | High | M | S |
| Mood blob status image | Apple (idea), How We Feel (colour model) | Recognisable feedback, shareable | image | High | M | M |
| Month and year calendar image | Daylio | Long-term pattern | image | High | M | S |
| Free-text or voice journal note | Daylio, Finch | Reflection | BM, VO | High | S (text), L (voice transcription) | S (text), C (voice) |
| Breathing exercise | Samsung, Finch | Short regulation tool | MA animation | High | M | C |
| Regulation suggestions matched to an emotion | How We Feel | Coping ideas | BM | Medium | M | C (generic, non-clinical wording only) |
| PHQ-9 or GAD-7 questionnaires with scoring | Apple | Screening | n/a | High | S | W (regulatory, section 8) |
| Virtual pet | Finch | Attachment | image | Medium | XL | W for the first releases |

### 5.5 Sleep

| Feature | Source applications | User value | Telegram input | Feasibility | Cost | Tier |
|---|---|---|---|---|---|---|
| Good-night and good-morning log (bedtime, wake time, duration) | Manual sleep logs in Samsung and Apple `[A]` | Sleep record | IB, BM | High | S | M |
| Morning quality rating (one tap) | Samsung "sleep satisfaction" | Subjective quality | IB | High | S | M |
| Activity-bounded sleep estimate, confirmed by the user | Owner's idea, adapted (section 9) | Record without effort | automatic, IB confirm | Medium | M | S |
| Weekly regularity and duration card | Samsung consistency | Routine awareness | image | High | S | S |
| Evening tags (caffeine, alcohol, late meal) | Sleep Cycle notes, Oura tags | Correlation input | IB | High | S | C |
| Monthly sleep-pattern archetype (original characters) | Samsung, Fitbit | Engagement | image | Medium | L (illustration) | C |
| Bedtime reminder | Samsung coaching, Apple `[A]` | Routine | automatic | High | S | S |
| Import of sleep sessions from Apple Health, Health Connect, Samsung Health, Google Health | Section 7 | Real measured sleep | IF via MA | Medium | L per format | S (Apple and Health Connect), C (others) |
| Sleep stages, snoring, oxygen, smart alarm | Sleep Cycle, wearables | Detailed analysis | n/a | Low | n/a | W |
| Sleep from Telegram "last seen" | Owner's idea, literal form | Passive capture | n/a | Low (not exposed to bots) | n/a | W (section 9) |

### 5.6 Body, vitals, symptoms and cycle

| Feature | Source applications | User value | Telegram input | Feasibility | Cost | Tier |
|---|---|---|---|---|---|---|
| Weight log with trend image | Samsung, MyTherapy, Lose It | Trend | BM, IB | High | S | S |
| Blood pressure, glucose, temperature, pulse manual log with trend image, no interpretation | MyTherapy, Health Connect shapes | Record for the user and clinician | BM, MA | High | M | S |
| Symptom diary with severity | Bearable, MyTherapy | Pattern record | IB, MA | High | M | S |
| Custom trackers (user-defined yes/no, count or scale) | Bearable, Finch goals | Covers everything else | MA setup, IB log | High | L | C |
| Reading a value from a device-screen photo | none found; `[A]` | Less typing | PH | Medium | L | C |
| Period log (dates, flow, symptoms) without predictions of fertility | Clue, Flo, Samsung | Record | IB, MA | High | M | C |
| Fertile-window or ovulation prediction, contraception | Clue Conceive, Natural Cycles | Family planning | n/a | Medium | XL | W (regulatory) |
| Threshold alerts on vitals | regulated devices | Warning | n/a | High | S | W (regulatory) |
| Steps, workouts, heart rate, oxygen | wearables | Activity | IF only | Low | L | W except as imported context |

### 5.7 Cross-domain, sharing, data and platform

| Feature | Source applications | User value | Telegram input | Feasibility | Cost | Tier |
|---|---|---|---|---|---|---|
| "Today" message: what is due and what is logged | MyTherapy to-do list | Orientation | BM, IB | High | M | M |
| Weekly report card image | Fitbit, Samsung, Oura | Recap | image | High | L | M |
| Monthly report with minimum-data gate | Fitbit sleep profile | Reflection | image | High | M | S |
| Correlation cards with counts | Bearable, Daylio, Apple | Self-knowledge | image | High | L | S |
| Mini App dashboard with history and charts | all platforms | Exploration | MA | High | L | M |
| Full data export (CSV and JSON), always free | Daylio, Bearable, Cronometer | Ownership, portability | BM, MA | High | M | M |
| Clinician report PDF across domains | MyTherapy | Appointment hand-over | BM, MA | High | L | S |
| Account deletion and erasure | Samsung erase flow `[V: S8]` | Control | BM, MA | High | M | M |
| Share a card to another chat (user-initiated) | Samsung Together (idea) | Social support | forward of an image | High | S | C |
| Forgiving consistency counter (days this week) | Samsung | Motivation without loss framing | image | High | S | S |
| Import from competitor CSV (Daylio, Bearable, Cronometer) | Section 7 | Switching | IF | High | M per format | C |
| Personal webhook for phone automations | none found; `[A]` | Semi-passive capture | HTTPS | Medium | M | C |
| Group challenges and leaderboards | Samsung Together | Competition | n/a | High | L | W |
| AI health assistant that gives advice | Samsung Health Assistant, Google Health Coach | Guidance | n/a | Medium | XL | W |
| Composite health, energy or readiness score | Samsung, Oura | Single number | n/a | Low | M | W |

## 6. Engagement and retention

### 6.1 Mechanics worth adopting

| # | Mechanic | Why it works | Guard rail |
|---|---|---|---|
| 1 | Answer-in-place prompts | Removes the application-open step that the native applications need `[A]` | Every button press is acknowledged by editing the message, so the chat shows the final state. |
| 2 | One-tap minimum, optional depth | Daylio and Apple both keep the mandatory part to one action `[V*: S36, S12]` | Never require the optional steps for a streak or a report. |
| 3 | Weekly consistency instead of a fragile chain | Counts successes in a window `[V: S1]`; avoids the loss-aversion engine of streaks `[V*: S58]` | Wording reports what happened ("5 of 7 days"), never what was lost. |
| 4 | Weekly report card on a fixed day | Recaps are a standard retention device in every platform studied | The card is sent only if the user logged something that week; otherwise one short, neutral message, then silence. |
| 5 | Minimum-data gates with visible progress | "3 more nights until your first sleep summary" sets a short goal `[V*: S2, S20]` | Gates must reflect a real statistical need, not an invented unlock. |
| 6 | Personal baselines | Comparing the user with their own past avoids population norms and medical framing `[A]` | No green or red judgement colours for health values. |
| 7 | Caregiver or buddy loop | Medisafe's Medfriend shows demand `[V*: S23]`; both people are already on Telegram | Consent from both sides, revocable by either at any time, shows only what the user chose (default: missed-dose status without medication names). |
| 8 | Evening wrap-up | One combined prompt instead of several `[A]` | Skippable with one tap; adapts if ignored. |
| 9 | Adaptive quieting | Reduce optional prompts after repeated non-response `[A]` | Never applies to medication reminders, which stay under explicit user control. |
| 10 | Clinician hand-over export | Gives the record a purpose outside the product `[V*: S24]` | The export contains the user's entries and descriptive statistics only. |
| 11 | Free data export | Trust; Daylio, Bearable and Cronometer keep CSV export free `[V*: S30, S36, S39]` | Export stays free under every monetisation option. |

### 6.2 Dark patterns to refuse

| # | Pattern | Seen in or risk | Reason to refuse |
|---|---|---|---|
| 1 | Rewards or streaks on medication intake | General streak design `[V*: S58]` | A reward for tapping "Taken" pays for false records. Medication history must stay truthful; consistency may be shown, never rewarded or made losable. |
| 2 | Guilt or shame notifications | Streak applications `[V*: S58]` | Health tracking users include people in distress; guilt messaging is harmful and drives muting. |
| 3 | A character that suffers or dies when the user does not log | Plant-care mechanic `[V*: S27]`, `[A]` | Punishes illness, travel and bad days. |
| 4 | Paid streak repair or paid removal of artificial limits on safety functions | Subscription pressure around reminders `[V*: S23]` | Reminders, logging and export are the safety core and stay free. |
| 5 | Moving a formerly free core feature behind a paywall | Barcode scanner `[V*: S29]`; photo logging `[V*: S32]` | Breaks trust; decide the free boundary before launch and keep it. |
| 6 | Third-party advertising or analytics on health events | Flo, GoodRx, Premom enforcement `[V*: S42]`, `[V: S44]` | Legal exposure under the FTC rule and GDPR Article 9, and a breach of user trust. No third-party trackers in the Mini App. |
| 7 | False precision | Photo calorie figures `[V*: S33]`; self-report "scores" | A wrong number presented as measured can change behaviour harmfully. Estimates are labelled and editable. |
| 8 | Public leaderboards and default-on sharing of health data | Social challenges `[V*: S7]` | Health data is special-category data `[V: S51]`; sharing is opt-in per item. |
| 9 | Weight-loss pressure: calorie-deficit nagging, moral colours for food | Calorie-budget applications `[A]` | Risk for users with disordered eating; outside the product's purpose. |
| 10 | Notification inflation | Any multi-domain tracker | Six domains multiplied by several prompts each will get the bot muted, which also silences medication reminders. |
| 11 | Obstructed cancellation or deletion | Subscription applications `[A]` | Deletion is one command and one confirmation. |
| 12 | Manufactured urgency ("your streak ends in 2 hours") | Streak applications `[V*: S58]` | Same harm as item 2. |

## 7. Data import and export routes that substitute for sensors

### 7.1 Import routes

| Route | How the user produces it | Format notes | Constraints and risks | Priority |
|---|---|---|---|---|
| Apple Health export | Health application, profile picture, "Export all health data" `[V*: S13]` | Apple states the export is XML `[V*: S13]`. Third-party descriptions agree on: a ZIP that unpacks to `apple_health_export/` with `export.xml` (root element `HealthData`, one `Record` element per sample with attributes including `type`, `sourceName`, `unit`, `startDate`, `endDate`, `value`), `export_cda.xml`, a `workout-routes/` folder of GPX files and an `electrocardiograms/` folder of CSV files. Dates look like `2026-05-01 09:14:23 -0700`. `Workout`, `Correlation` and `ClinicalRecord` elements also occur `[V*: S13]`. Identifiers follow HealthKit names, for example `HKQuantityTypeIdentifierHeartRate` `[V*: S13]`; sleep is expected under `HKCategoryTypeIdentifierSleepAnalysis`, water under `HKQuantityTypeIdentifierDietaryWater`, weight under `HKQuantityTypeIdentifierBodyMass` `[A]`. Whether medication doses and State of Mind entries appear in `export.xml` was not verified `[A]`. | No official schema; the structure has changed between iOS versions `[V*: S13]`. The export cannot be limited to a date range `[V*: S13]`. Typical unzipped sizes: 30 to 80 MB for a year of light use, 200 to 500 MB for five years with a watch, up to 1.5 GB `[V*: S13]`. Often exceeds the 20 MB download limit of the hosted Bot API `[V: S54]`, so the upload must go through the Mini App to the backend `[A]` or through a self-hosted Bot API server, which has no download size limit `[V: S54]`. Requires a streaming XML parser and a per-type allow-list so that only requested types are read and stored. | Should |
| Health Connect export | Settings, Health Connect, Manage data, Backup and restore; scheduled export to a cloud-storage application `[V: S15]` | ZIP named "Health Connect.zip" `[V: S15]` containing a single SQLite database with about 73 tables according to one developer's inspection `[V*: S17]`. Record types as listed in section 2.3 `[V: S16]`. | Android 14 or later `[V: S15]`. No published schema for the database `[A]`. Sources disagree on whether the file is encrypted; one technical report says it opens in any SQLite viewer, another describes it as encrypted `[V*: S17]`. Medical records excluded `[V: S15]`. No medication or mood types `[V: S16]`. A spike must confirm the table layout before any commitment. | Should, after a spike |
| Samsung Health export | Samsung Health, More options, Settings, "Download personal data"; files appear in My Files, Downloads, Samsung Health `[V: S8]` | Samsung's page does not state the format `[V: S8]`. Third-party descriptions: one CSV per category, named like `com.samsung.shealth.exercise.<timestamp>.csv`; some JSON and binary files for high-frequency data; UTF-8 byte-order mark; timestamps as large integers; no official column documentation; at least one metric (body water) reported missing `[V*: S9]`. | A folder, not a single file: the user must compress it or pick individual CSV files. Column semantics are undocumented. | Could |
| Google Health (formerly Fitbit) export | Google Takeout, product "Google Health", or the Fitbit account data export `[V*: S22]` | Takeout gives JSON; the account export offers CSV or JSON by period and type. Sleep: one JSON file per night with a `levels.data` array of entries (time, stage among wake, light, deep, rem, duration in seconds) and a `summary` `[V*: S22]`. | Folder and product names changed during the migration to Google `[V*: S22]`; users report incomplete exports `[V*: S22]`. Time-zone handling in the files is not documented in the source. | Could |
| Google Fit Takeout | Google Takeout | Not researched in detail. | The Google Fit API ends service at the end of 2026 `[V*: S18]`; low value. | Will not |
| Competitor CSV (Daylio, Bearable, Cronometer) | In-application export, free in all three `[V*: S30, S36, S39]` | CSV; column layouts were not inspected `[A]`. | Small files, well within the 20 MB limit. Each format needs a sample file before estimation. | Could |
| Dosegram CSV template | Downloaded from the bot, filled in a spreadsheet | Defined by Dosegram; should round-trip with Dosegram's own export. | None beyond validation. | Should (it is the inverse of the export) |
| Screenshot or forwarded summary | Screenshot of a sleep or step summary from another application, sent as a photo | Image; needs text recognition and per-application layouts | Fragile and error-prone; every extracted value needs user confirmation. | Could, late |
| Personal webhook | A phone automation (for example an iOS Shortcut or an Android automation tool) posts values to a per-user HTTPS endpoint with a revocable token | JSON defined by Dosegram | Feasibility of reading health samples from such automations is `[A]`; power-user feature; token handling is a security surface. | Could |

Decision proposed: build the Dosegram CSV template and the Apple Health importer first, restricted to sleep sessions, body mass and water. Rationale: Apple's export is the only route with a stable, user-visible, single-file procedure and broad third-party documentation; sleep is the domain where self-report is weakest. Rejected alternative: a native companion application that reads HealthKit or Health Connect directly. It would give continuous data, but it adds two mobile code bases and store review to a product whose premise is that Telegram is the only client.

### 7.2 Export routes from Dosegram

| Export | Format | Reason |
|---|---|---|
| Full account export | ZIP of CSV files plus one JSON file, one file per domain, ISO 8601 timestamps with offset | Portability and trust; mirrors the free CSV exports of Daylio, Bearable and Cronometer. |
| Clinician report | PDF: medication list, adherence table, selected measurement trends, symptom summary; no interpretation | Mirrors Apple "Export PDF" and the MyTherapy report `[V: S10]`, `[V*: S24]`. |
| Image cards | PNG | Native to chat. |
| Health Connect or Apple Health write-back | none | Not possible from a server; would need a native application `[A]`. |

Privacy note for all generated files: they contain medication names and health values. They are sent to the user's private chat only, are never logged, and the stored copy on the server is short-lived `[A]`; the retention period is a design decision for the security brief.

## 8. Regulatory lines

This section is research for product scoping. It is not legal advice, and the owner should obtain a legal review before a public launch in the EU or the US.

### 8.1 European Union: Medical Device Regulation (EU) 2017/745

| Point | Text or finding | Evidence |
|---|---|---|
| Definition | A medical device is, among other things, software "intended by the manufacturer to be used, alone or in combination, for human beings for one or more of the following specific medical purposes", the first of which is "diagnosis, prevention, monitoring, prediction, prognosis, treatment or alleviation of disease". | `[V: S49, S50]` |
| Wellness carve-out | Recital 19: "software intended for life-style and well-being purposes is not a medical device". The MDCG guidance repeats that software only intended for non-medical purposes, including "wellness or fitness apps", does not qualify as medical device software. | `[V: S49, S50]` |
| Intended purpose rules | Qualification depends on the intended purpose stated by the manufacturer, which includes labelling, instructions and promotional material `[A]` for the exact wording; the guidance stresses "the importance of crafting a clear intended purpose". | `[V: S50]` |
| Decision steps | Step 3 asks whether the software performs an action on data beyond "storage, archival, communication, simple search, lossless compression". Step 4 asks whether the action is for the benefit of individual patients. Step 5 asks whether it meets the definition of medical device software. | `[V: S50]` |
| Rule 11 | Software that provides information used to take decisions with diagnosis or therapeutic purposes is class IIa (IIb or III if an error could cause serious or irreversible harm). Software intended to monitor physiological processes is class IIa, or IIb for vital parameters where variations could result in immediate danger. All other medical device software is class I. | `[V: S50]` |
| Example: adherence | "MDSW intended to monitor medication adherence ... provides recommendations for adjustments of the treatment plan (e.g. next doses, next timing of doses)" is listed as medical device software. | `[V: S50]` |
| Example: mood | "MDSW intended to assess, monitor, and manage depression. The MDSW provides patients with questionnaires to track their mood, symptoms, and activities" is listed as medical device software. | `[V: S50]` |
| Example: glucose | An application that adds "trend analysis, personalized risk assessments, or health condition monitoring" to a home glucose meter is listed as medical device software. | `[V: S50]` |
| Example: fertility | An application that supports conception by calculating fertility status is class I under Rule 11c; software used for contraception is class IIb under Rule 15. | `[V: S50]` |
| Vital parameters | Respiration, heart rate, cerebral functions, blood gases, blood pressure and body temperature are named as vital physiological parameters. | `[V: S50]` |

How a tracker stays outside, applied to Dosegram:

| Function | Position | Reasoning |
|---|---|---|
| User-configured medication reminders and intake log | Outside, provided there is no recommendation | The software stores a schedule the user typed and communicates it back. The listed adherence example becomes a device through sensor input and treatment recommendations, neither of which Dosegram has. `[A]` as a legal conclusion. |
| Adherence percentage and calendar | Outside | Descriptive statistics of the user's own log, with no therapeutic recommendation. `[A]` |
| Dose suggestions, catch-up advice after a missed dose, interaction checks | Inside | Information used for therapeutic decisions; Rule 11a. Do not build. |
| Mood check-in and journal | Outside if worded as well-being | The depression example becomes a device by its intended purpose (assess, monitor, manage depression). Do not name conditions, do not score risk, do not offer clinical questionnaires. |
| Manual vitals log and chart | Outside if no interpretation | Recording and drawing. Classifying a reading or alerting on it is monitoring of a physiological process for a medical purpose; do not build. `[A]` |
| Sleep, water and food logs | Outside | Lifestyle and well-being. |
| Period log | Outside as a calendar record; inside once it calculates fertility status | See the fertility example. |

Other EU instruments:

| Instrument | Relevance | Evidence |
|---|---|---|
| GDPR Article 9 | "data concerning health" is a special category; processing is prohibited unless a ground such as explicit consent for specified purposes applies. Dosegram needs an explicit consent step at onboarding, per-purpose where purposes differ (for example caregiver sharing), and a data protection impact assessment `[A]`. | `[V: S51]` |
| European Health Data Space, Regulation (EU) 2025/327 | Contains provisions on labelling of wellness applications (Article 47) and their interoperability with electronic health record systems (Article 48). The label concerns wellness applications that claim such interoperability; Dosegram does not plan to claim it. | `[V*: S52]` |
| EU Artificial Intelligence Act | Possible relevance if AI estimation or emotion inference is added. Not researched here. | `[A]` |

### 8.2 United States: Food and Drug Administration

| Point | Text or finding | Evidence |
|---|---|---|
| Statutory exclusion | Section 520(o)(1)(B) of the FD&C Act excludes from the device definition software intended "for maintaining or encouraging a healthy lifestyle" and "unrelated to the diagnosis, cure, mitigation, prevention, or treatment of a disease or condition". | `[V: S45]` |
| General wellness guidance | "General Wellness: Policy for Low Risk Devices", issued 6 January 2026, superseding the 27 September 2019 version; docket FDA-2014-N-1039. | `[V: S45]` |
| Two factors | A general wellness product is "(1) ... intended for only general wellness use ... and (2) present[s] a low risk to the safety of users and other persons". | `[V: S45]` |
| First claim category | Claims without reference to disease, relating to weight management, physical fitness, relaxation or stress management, mental acuity, self-esteem, sleep management, or sexual function; for example "to track sleep trends" or to "encourage healthy eating". | `[V: S45]` |
| Second claim category | Claims that a healthy-lifestyle choice "may help to reduce the risk of" or "may help living well with" certain chronic diseases, where that role is generally accepted; for example software that "tracks your caloric intake and helps you manage a healthy eating plan". | `[V: S45]` |
| Claims outside | Claims to treat or diagnose obesity, to treat an eating disorder, or to help treat an anxiety disorder are examples that are not general wellness claims. | `[V: S45]` |
| Disqualifiers | A product is not a general wellness product if its labelling, advertising, user interface or functionality includes references to specific diseases, clinical conditions or diagnostic thresholds; alerts or prompts that recommend clinical action; treatment guidance; claims of clinical accuracy or equivalence; or intended-use statements targeting diagnosis, screening, monitoring or management of a disease. | `[V: S45]` |
| Named non-wellness products | Blood pressure monitors, glucose meters and continuous glucose monitors, ECG devices, and "any product that is intended to provide outputs used to make treatment decisions (e.g., medication dosing)". | `[V: S45]` |
| Permitted notification | A notice that evaluation by a healthcare professional "may be helpful" is allowed if it does not name a disease, does not call the output abnormal, includes no thresholds or treatment recommendations, and is not ongoing monitoring. | `[V: S45]` |
| Illustrative example 3 | A software function that monitors and records food consumption for weight management and alerts the user, a healthcare provider or a family member of unhealthy dietary activity "is not a device function". | `[V: S45]` |
| Medication reminders | FDA's list of functions under enforcement discretion includes "Software functions that keep track of medications and provide user-configured reminders for improved medication adherence". This means the function may meet the device definition but FDA does not intend to enforce requirements. The page was current as of 29 September 2022. | `[V: S46]` |
| Logging and trending | The same list covers trending of vital signs, collecting manually entered blood pressure data and sharing it by e-mail, and aggregating trends in personal health incidents. The list of functions that are not devices includes dietary logs and calorie counters, tracking normal sleep patterns, and recording data such as blood glucose and blood pressure. | `[V: S46, S47]` |

How a tracker stays outside, applied to Dosegram: keep every claim in the first category (track, log, remind, trend); never name a disease in product copy, bot messages, image cards or store-style descriptions; keep reminders "user-configured"; add no thresholds or alerts to measurements; add no dosing output. The caregiver alert for a missed dose is comparable to the family-member alert in illustrative example 3, but that example concerns diet; applying it to medication is `[A]`.

Other US instruments:

| Instrument | Relevance | Evidence |
|---|---|---|
| FTC Health Breach Notification Rule | Amended rule effective 29 July 2024 applies to most health applications outside HIPAA. The FTC treats unauthorised disclosure of health information, including to advertising vendors, as a breach requiring notification. First actions: GoodRx (February 2023) and Premom (2023). | `[V: S44]` |
| Washington My Health My Data Act | Effective 31 March 2024 (30 June 2024 for small businesses); covers wellness applications outside HIPAA; requires consent for collection and sharing beyond what the requested service needs; private right of action; first class action filed 10 February 2025. | `[V*: S53]` |
| HIPAA | Does not apply to a consumer tracker with no covered-entity relationship. | `[A]` |

### 8.3 Rules of thumb for all Dosegram copy and features

1. Describe functions with the verbs record, remind, show, count, compare with your own past, export. Do not use diagnose, treat, prevent, detect, monitor (in the clinical sense), risk, normal, abnormal, should take.
2. Never output a dose, a time to take a dose that the user did not enter, or advice after a missed dose. The standing text is that the user should follow the instructions of their prescriber or the package leaflet.
3. Never attach a judgement to a measured value.
4. Never name a disease or a condition in product-authored text. User-entered names and notes are the user's own content and are never logged.
5. Keep health data away from third parties: no advertising, no third-party analytics scripts in the Mini App, and model providers (if any are used for parsing or photo estimation) only under explicit, separate consent.

## 9. Sleep from Telegram activity: analysis of the owner's idea

| Option | Mechanism | Verdict |
|---|---|---|
| A. Read "last seen" through the Bot API | Not possible. No presence field on `User`, no presence update type; latest Bot API is 10.4 of 9 November 2026 `[V: S54]`. | Rejected: not available. |
| B. Read presence through a user-account (MTProto) client operated by the service | `userStatusOffline.was_online` exists `[V: S55]`. The service would need a logged-in user account that is allowed to see each user's status; users who restrict "Last Seen and Online" expose only approximate labels `[V*: S59]`; automation of user accounts for monitoring raises terms-of-service and account-ban risk `[A]`; it is covert-feeling surveillance of a health proxy. | Rejected: privacy, fragility and platform risk. |
| C. Explicit log | Evening message with a "Going to sleep" button; morning message with "I'm up" and a one-tap quality rating; times editable. | Adopt as the primary method. High accuracy for bedtime intent and wake time; cost S. |
| D. Activity-bounded estimate | Use the timestamps of the user's own interactions with the bot and Mini App: the last interaction before a long night-time gap and the first after it bound the possible sleep window. Present it as a question: "No activity here between 23:40 and 07:10. Log about 7 h 30 min of sleep?" with Confirm, Edit and Skip. | Adopt as a fallback for nights without an explicit log. It is the buildable form of the owner's idea. It systematically overestimates sleep, so it is never stored without confirmation and is labelled as an estimate from bot activity only. Cost M. |
| E. Import measured sleep | Apple Health or Health Connect file import (section 7). | Adopt as "Should" for users who own a wearable. |

Data note for option D: interaction timestamps are already produced by normal operation. Using them to infer sleep is a new purpose and needs its own consent toggle under the purpose-limitation logic of GDPR `[A]`.

## 10. Ranked recommendations

### 10.1 The fifteen ideas with the best value-to-cost ratio for a chat-native product

| Rank | Idea | Value | Cost | Why it ranks here |
|---|---|---|---|---|
| 1 | Medication reminder answered in place: Taken, Skip, Snooze, with a 30-minute follow-up and a reminder window | Very high | M | The proven core loop of every medication application, and the one Telegram executes better than a native notification. Builds on the existing dose lifecycle specification. |
| 2 | Water quick-add buttons with a progress image | High | S | Lowest-cost new domain; daily frequency creates the habit of opening the chat. |
| 3 | One-tap mood check-in with a generated mood blob | High | S to M | One tap, immediate visual reward, and the owner's requested status image. |
| 4 | "Today" message that is edited in place through the day | High | M | Replaces a dashboard for most users; keeps the chat tidy. |
| 5 | Weekly report card image across all domains | High | L | The main retention device and the main showcase of backend image generation. |
| 6 | Good-night and good-morning sleep log with one-tap quality | High | S | Delivers sleep tracking without sensors; pairs with the evening wrap-up. |
| 7 | Evening wrap-up: one message for mood, unlogged items and sleep intention | High | S | Controls notification load across six domains. |
| 8 | Refill countdown and reminder | High | M | Practical value; data already exists from logged doses. |
| 9 | Full free export and a clinician PDF | High | M | Trust, portability, and a purpose for the record outside the product. |
| 10 | Caregiver missed-dose notification with two-sided consent | High | L | The differentiator no Telegram bot found offers; both parties are already on the platform. |
| 11 | Calendar heat-map image for mood and adherence | Medium | M | One image conveys months of data. |
| 12 | Photo meal journal with meal-type buttons, no mandatory calories | Medium | M | Food tracking at the lowest friction, avoiding the accuracy problem. |
| 13 | Measurement logs (weight, blood pressure, glucose, temperature) with trend images and no interpretation | Medium | M | Completes the clinician report; simple record shapes exist in Health Connect. |
| 14 | Free-text quick logging with echo and confirm ("300 ml", "weight 81.4", "slept 7h") | Medium | M | Chat-native speed for experienced users; deterministic parsing first, a language model only later and only with consent. |
| 15 | Correlation cards with counts and minimum-data gates | Medium | L | The analytic pay-off of multi-domain tracking; only possible once items 1 to 6 produce data. |

### 10.2 Ten ideas to reject explicitly

| # | Idea | Reason |
|---|---|---|
| 1 | Drug-interaction checking, dose advice, missed-dose advice | Information for therapeutic decisions: class IIa or higher under Rule 11 in the EU `[V: S50]` and an output "used to make treatment decisions" in the US `[V: S45]`. Also needs a licensed, maintained drug database. Apple limits its interaction feature to the US `[V: S10]`. |
| 2 | Sleep tracking from Telegram "last seen" | Not exposed to bots `[V: S54]`; the client-API route is covert-feeling, fragile and risky (section 9). Replaced by options C and D. |
| 3 | Fertile-window, ovulation or contraception features | Medical device software in the EU, class I for conception support and class IIb for contraception `[V: S50]`; the most litigated data category `[V*: S42]`. |
| 4 | PHQ-9, GAD-7 or any scored mental-health assessment | Matches the EU example of software that assesses, monitors and manages depression `[V: S50]`; creates a duty of care that a bot cannot meet. |
| 5 | Threshold alerts or classifications on blood pressure, glucose, temperature or weight | Diagnostic thresholds and clinical-action prompts disqualify a product from the US wellness policy `[V: S45]`; monitoring of physiological processes is class IIa or IIb in the EU `[V: S50]`. |
| 6 | Composite "health score", "energy score" or "readiness" from self-report | The originals depend on sensor data `[V*: S4, S41]`; a self-report imitation is false precision. |
| 7 | Photo-based calorie counting as the primary or authoritative food method | About 33 percent mean underestimation in the only controlled test found `[V*: S33]`; per-photo model cost; risk of harm for users who rely on the number. An optional, labelled estimate remains a "Could". |
| 8 | Punitive gamification: losable medication streaks, suffering mascots, guilt notifications, paid repairs | Incentivises false records and harms vulnerable users (section 6.2). |
| 9 | AI health coach or assistant that gives personalised health advice | The platform vendors gate this behind paid tiers and wellness disclaimers `[V*: S5, S21]`; for a small product it carries regulatory and safety exposure without a reliable evidence base. Summaries of the user's own data in fixed templates are sufficient. |
| 10 | Sensor-dependent features: steps, workouts with GPS, heart rate, blood oxygen, snore detection, sleep stages, smart alarm | No sensor access from a bot or a Mini App `[A]`; imported values may be displayed as context but are not a product pillar. |

## 11. Open questions

| # | Question | Recommendation |
|---|---|---|
| Q1 | Does the owner accept that sleep tracking uses an explicit log plus a confirmed estimate from bot activity, instead of Telegram "last seen"? | Accept. The literal idea is not available to bots `[V: S54]`. |
| Q2 | Which jurisdictions will Dosegram be offered in at first: personal use, the EU, the US, or worldwide? | Decide before the specification is frozen. The feature set in this brief is chosen to stay outside device regulation in both the EU and the US; the consent and breach-notification design depends on the answer. |
| Q3 | Is the food domain a journal (photo, meal type, optional numbers) or a calorie counter (database, barcode, targets)? | Journal first. A calorie counter is an XL effort against entrenched products with databases of about 14 million foods `[V*: S29]`. |
| Q4 | Is a cycle log in scope? | Defer. If added, record only; no fertility output. |
| Q5 | May a language or vision model from a third-party provider process user messages, voice notes or meal photos? | Not by default. If adopted, require a separate explicit consent, send the minimum content, and prefer deterministic parsing for structured quick-logs. This choice also affects items 12 and 14 of the ranked list. |
| Q6 | Can the Mini App scan retail barcodes, and can it upload files of several hundred megabytes to the Dosegram backend? | Unknown here `[A]`. Assign to the Telegram platform brief or a spike; the Apple Health importer and the barcode feature depend on it. |
| Q7 | Is a mascot or character wanted? | Not in the first releases. The procedural mood blob and report cards deliver the visual identity at far lower cost and without the dark-pattern risk. Revisit after the weekly report exists. |
| Q8 | What is the free boundary if the product is ever monetised? | Fix it now: reminders, logging in every domain, the caregiver link and full export remain free. Candidates for payment are cosmetic themes, extended report history and model-based conveniences. |
| Q9 | Should the caregiver see medication names? | Default to no: the caregiver sees "a scheduled dose was not confirmed" and the time. The user may opt in to showing names per medication. |
| Q10 | Should imported wearable data be a first-class pillar or context only? | Context only: display imported sleep, weight and water next to self-reported data, clearly marked with its source. |
| Q11 | Does the Open Food Facts share-alike licence constrain how looked-up products are cached and combined with user entries? | Obtain a short licence review before building the barcode feature. |

## 12. Sources

All sources were accessed on 2026-11-12. "Primary" means the owner of the fact; "secondary" means press, a reviewer, a law firm or a competitor.

| ID | Source | Type |
|---|---|---|
| S1 | Samsung, "Use Samsung Health sleep monitoring on your Galaxy Watch", https://www.samsung.com/us/support/answer/ANS10003657/ | Primary |
| S2 | Samsung Newsroom, "Samsung Health Makes Sleep Coaching Easier to Start and Tailors Weekly Guidance", https://news.samsung.com/global/samsung-health-makes-sleep-coaching-easier-to-start-and-tailors-weekly-guidance (read through search summary) | Primary, indirect |
| S3 | 9to5Google, 14 October 2026, https://9to5google.com/2026/10/14/samsung-rolling-out-major-health-update-with-sleep-running-coach-meal-scanning/ | Secondary |
| S4 | TechRepublic, "Samsung Health Update Leaves Galaxy Watch Owners Waiting on Key AI Features", https://www.techrepublic.com/article/news-samsung-health-watch-features/ | Secondary |
| S5 | eWeek, "Samsung Health Assistant Beta Brings AI Guidance to Wearable Data", https://www.eweek.com/news/samsung-health-assistant-beta/ | Secondary |
| S6 | Samsung Newsroom, medication and health-records update, https://news.samsung.com/global/samsung-health-app-update-makes-accessing-health-records-managing-medications-and-food-tracking-easier ; Android Authority, https://androidauthority.com/samsung-health-medicine-tracking-3396256 | Primary, indirect; secondary |
| S7 | Samsung, "Use and manage trackers in Samsung Health", https://www.samsung.com/us/support/answer/ANS10001351 (read through search summary) | Primary, indirect |
| S8 | Samsung, "Download or delete your Samsung Health data", https://www.samsung.com/ca/support/apps-services/download-or-delete-your-samsung-health-data/ | Primary |
| S9 | Takeout Day, "How to export Samsung Health data", https://takeoutday.org/guides/how-to-export-samsung-health-data ; Samsung developer forum thread, https://forum.developer.samsung.com/t/understanding-personal-data-from-samsung-health-app/32406 | Secondary |
| S10 | Apple, "Add and log medications with iPhone, iPad, and Apple Watch", https://support.apple.com/en-ca/105064 | Primary |
| S11 | MacRumors, "iOS 17 Introduces Follow-Up Reminders to Take Your Medications", https://www.macrumors.com/2023/06/06/ios-17-follow-up-medication-reminders/ | Secondary |
| S12 | Apple Watch User Guide, "Log your state of mind", https://support.apple.com/guide/watch/apd7de0f5610/watchos ; WWDC 2024 session 10109 index, https://nonstrict.eu/wwdcindex/wwdc2024/10109/ (both read through search summaries) | Primary, indirect |
| S13 | Apple iPhone User Guide, health data sharing and export, https://support.apple.com/en-asia/guide/iphone/iph27f6325b2/14.0/ios (read through search summary) ; AI Health Export, "Apple Health XML format", https://www.aihealthexport.com/guides/apple-health-xml-format (vendor of a conversion tool) | Primary, indirect; secondary |
| S14 | 9to5Mac, "watchOS 26.2 Sleep Score changes", https://9to5mac.com/2025/11/04/watchos-26-2-sleep-score-changes-apple-watch/ ; AppleInsider, https://appleinsider.com/articles/25/09/12/how-sleep-score-works-on-apple-watch-with-watchos-26 | Secondary |
| S15 | Google, "Back up and restore your Health Connect data", https://support.google.com/android/answer/15323271?hl=EN-GB | Primary |
| S16 | Android Developers, "Health Connect data types", https://developer.android.com/health-and-fitness/health-connect/data-types | Primary |
| S17 | Android Authority, Health Connect backup reports, https://www.androidauthority.com/health-connect-data-backup-3498985/ ; developer note on the exported database, https://nelsonslog.wordpress.com/2025/11/03/looking-at-health-connect-data-for-inbody-etc (read through search summary) | Secondary |
| S18 | Android Developers, "Google Fit Migration FAQ", https://developer.android.com/health-and-fitness/health-connect/migration/fit/faq (read through search summary) | Primary, indirect |
| S19 | Google, Fitbit help, sleep score, https://support.google.com/fitbit/answer/14236513?hl=en | Primary |
| S20 | Google, "Improve your ZZZs with Fitbit Premium Sleep Profile", https://blog.google/products-and-platforms/devices/fitbit/sleep-profile/ (read through search summary) | Primary, indirect |
| S21 | Android Authority, Google Health Premium price and features, https://androidauthority.com/google-health-premium-price-inclusions-features-3664507/ ; Wareable, https://www.wareable.com/fitbit/fitbit-air-official-announcement-price-features-release-date | Secondary |
| S22 | Takeout Day, "Fitbit data export", https://takeoutday.org/guides/fitbit-data-export ; Fitbit community threads on incomplete exports, https://community.fitbit.com/t5/Product-Feedback/Downloaded-Fitbit-data-from-Google-Takeout-is-incorrect/m-p/5805050 | Secondary |
| S23 | App Store, Medisafe, https://apps.apple.com/app/id573916946 ; Caring Village review, https://caringvillage.com/blog/caregiver-tech/medication-reminder-apps/ ; Pillo (a competitor), https://pillo.care/blog/medisafe-not-free-what-to-do | Secondary |
| S24 | App Store, MyTherapy, https://apps.apple.com/de/app/mytherapy-medication-reminder/id662170995?l=en-GB ; MyTherapy site, https://mytherapyapp.com/the-app-for-multiple-sclerosis (read through search summary) | Primary, indirect |
| S25 | App Store, Round Health (listing and user reviews), https://apps.apple.com/us/app/round-health/id1059591124 (read through search summary) | Primary, indirect |
| S26 | App Store, WaterMinder (listing and user reviews), https://apps.apple.com/us/app/id653031147 (read through search summary) | Primary, indirect |
| S27 | Wikipedia, "Plant Nanny", https://en.wikipedia.org/wiki/Plant_Nanny ; App Store, https://apps.apple.com/cz/app/plant-nanny/id1424178757 | Secondary |
| S28 | Screensdesign showcase, Waterllama, https://screensdesign.com/showcase/water-tracker-waterllama | Secondary |
| S29 | FitBudd, "MyFitnessPal app cost", https://www.fitbudd.com/post/myfitnesspal-app-cost | Secondary |
| S30 | Cronometer forum, "Exporting Data", https://forums.cronometer.com/discussion/460/exporting-data ; third-party pricing comparisons (sources disagree) | Secondary |
| S31 | App Store, Lose It, https://apps.apple.com/us/app/lose-it-calorie-counter/id297368629 ; Lose It press release, https://www.newswire.com/news/lose-it-finds-ai-powered-logging-boosts-weight-loss-success-and-22557702 | Primary, indirect |
| S32 | App Store, Yazio, https://apps.apple.com/py/app/yazio-ai-calorie-tracker/id946099227?l=en-GB ; third-party pricing reviews (sources disagree) | Secondary |
| S33 | Healio, "AI photo-based calorie-tracking tools underestimate them by 33%", https://www.healio.com/news/primary-care/20260804/ai-photobased-calorietracking-tools-underestimate-them-by-33 ; HealthDay, https://www.healthday.com/health-news/nutrition/how-accurate-are-photo-based-calorie-apps-4-are-put-to-the-test | Secondary report of a conference abstract |
| S34 | Open Food Facts, API documentation, https://openfoodfacts.github.io/openfoodfacts-server/api/ ; product count, https://world.openfoodfacts.org/product-count (count read through search summary) | Primary |
| S35 | USDA, "FoodData Central API Guide", https://fdc.nal.usda.gov/api-guide ; api.data.gov developer manual, https://api.data.gov/docs/developer-manual (read through search summary) | Primary, indirect |
| S36 | Wikipedia, "Daylio", https://en.wikipedia.org/wiki/Daylio ; App Store, https://apps.apple.com/nz/app/daylio-journal/id1194023242 ; Choosing Therapy review, https://www.choosingtherapy.com/daylio-app-review/ | Secondary |
| S37 | Marc Brackett, "How We Feel App", https://marcbrackett.com/how-we-feel-app-3/ ; App Store, https://apps.apple.com/us/app/how-we-feel/id1562706384 ; Yale School of Medicine, https://medicine.yale.edu/news-article/the-how-we-feel-app-helping-emotions-work-for-us-not-against-us/ | Primary, indirect |
| S38 | App Store, Finch, https://apps.apple.com/app/1528595748 (read through search summary) | Primary, indirect |
| S39 | Bearable, https://bearable.app/symptom-tracker/ ; Choosing Therapy review, https://www.choosingtherapy.com/bearable-app-review/ | Primary, indirect; secondary |
| S40 | App Store, Sleep Cycle, https://apps.apple.com/dk/app/id320606217 (read through search summary) | Primary, indirect |
| S41 | Oura, "Readiness Score", https://ouraring.com/readiness-score ; Oura, "What to know 2026", https://ouraring.com/blog/oura-ring-what-to-know-2026/ (read through search summary) | Primary, indirect |
| S42 | Flo, "What is the FTC case", https://help.flo.health/hc/en-us/articles/6498089874324-What-is-the-FTC-case ; TechCrunch, 5 August 2025, https://techcrunch.com/2025/08/05/jury-rules-meta-violated-california-privacy-laws-by-collecting-menstrual-health-data-from-flo ; Flo privacy toolkit, https://flo.health/blog/your-privacy-toolkit-3-ways-to-protect-your-health-data | Primary and secondary |
| S43 | App Store, Clue, https://apps.apple.com/eg/app/clue/id657189652 ; BioSpace, Natural Cycles classification, https://www.biospace.com/fda-releases-final-classification-for-natural-cycles-the-first-and-only-birth-control-app-in-the-u-s | Secondary |
| S44 | Federal Register, "Health Breach Notification Rule", 30 May 2024, https://www.federalregister.gov/documents/2024/05/30/2024-10855/health-breach-notification-rule (read through search summary and law-firm analyses) | Primary, indirect |
| S45 | FDA, "General Wellness: Policy for Low Risk Devices", issued 6 January 2026, full text https://www.fda.gov/media/90652/download ; landing page https://www.fda.gov/regulatory-information/search-fda-guidance-documents/general-wellness-policy-low-risk-devices | Primary (full text read) |
| S46 | FDA, "Examples of Software Functions for Which the FDA Will Exercise Enforcement Discretion", content current as of 29 September 2022, https://www.fda.gov/medical-devices/device-software-functions-including-mobile-medical-applications/examples-software-functions-which-fda-will-exercise-enforcement-discretion | Primary |
| S47 | FDA, "Examples of Software Functions That Are NOT Medical Devices", content current as of 29 September 2022, https://www.fda.gov/medical-devices/device-software-functions-including-mobile-medical-applications/examples-software-functions-are-not-medical-devices | Primary |
| S48 | King and Spalding, "FDA Updates General Wellness and Clinical Decision Support Guidance Documents", https://www.kslaw.com/insights/articles/fda-updates-general-wellness-and-clinical-decision-support-guidance-documents | Secondary (used to cross-check S45) |
| S49 | Regulation (EU) 2017/745, EUR-Lex, https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32017R0745 (recital 19 and Article 2(1) read; Annex VIII read through S50) | Primary |
| S50 | MDCG 2019-11 rev.1, "Guidance on Qualification and Classification of Software in Regulation (EU) 2017/745 and Regulation (EU) 2017/746", June 2025, https://health.ec.europa.eu/document/download/b45335c5-1679-4c71-a91c-fc7a4d37f12b_en?filename=mdcg_2019_11_en.pdf (full text read). The document states that it is not legally binding. | Primary |
| S51 | GDPR Article 9, https://gdpr-info.eu/art-9-gdpr/ | Primary text, unofficial mirror |
| S52 | European Commission, Regulation (EU) 2025/327 on the European Health Data Space, https://health.ec.europa.eu/publications/regulation-eu-2025327-european-health-data-space-and-amending-directive-201124eu-and-regulation-eu_en ; Covington, Inside Privacy, https://www.insideprivacy.com/digital-health/ehds-series-4-the-european-health-data-spaces-implications-for-wellness-applications-and-medical-devices/ (read through search summary) | Primary, indirect; secondary |
| S53 | Kelley Drye, "My Health My Data: Washington's Health Data Privacy Revolution", https://www.kelleydrye.com/viewpoints/blogs/ad-law-access/my-health-my-data-washingtons-health-data-privacy-revolution (read through search summary) | Secondary |
| S54 | Telegram, "Telegram Bot API" (Bot API 10.4, 9 November 2026), https://core.telegram.org/bots/api | Primary (full page read for the `User` object, update types and `getFile`) |
| S55 | Telegram, MTProto schema, `userStatusOffline` and `userStatusOnline`, https://core.telegram.org/constructor/userStatusOffline | Primary, indirect |
| S56 | Telegram, "Bots FAQ", https://core.telegram.org/bots/faq | Primary |
| S57 | Product listings: MedPlanner for Telegram, https://www.producthunt.com/p/medpill-reminder-for-telegram/medplanner-for-telegram ; LogIt2Win, https://prd-logit2win.pages.dev/ ; HealthZap, https://trustmrr.com/startup/healthzap ; MyHealthLogger, https://www.producthunt.com/products/myhealthlogger ; water-tracking Mini App showcase, https://freelancehunt.com/en/showcase/work/telegram-mini-app-for-tracking-daily/2030599.html | Secondary |
| S58 | Mobbin, "Streaks are about losing: a study of 859 streak designs", https://mobbin.com/blog/streaks-are-about-losing-a-study-of-859-streak-designs ; summary of an interview with a Duolingo retention product manager, https://rosetta.to/u/lennyspodcast/behind-the-product-duolingo-streaks-jackson-shuttleworth-group-pm-retention-team | Secondary; vendor-originated figures |
| S59 | Telegram blog, "Hiding Last Seen Time - Done Right", https://telegram.org/blog/privacy-revolution (read through search summary of a translated mirror) ; How-To Geek, https://www.howtogeek.com/710383/how-to-hide-your-last-seen-online-time-in-telegram/ | Primary, indirect; secondary |

### 12.1 Known gaps in this research

| Gap | Effect | Suggested follow-up |
|---|---|---|
| Many application facts were read from store listings, reviews or search summaries, not from first-hand use of the applications. | Feature details marked `[V*]` or `[A]` may be out of date. | Hands-on review of the five or six applications that the design borrows most from (Apple Medications, Medisafe, Daylio, How We Feel, WaterMinder, Samsung Health). |
| No sample export files were inspected. | The format notes in section 7 are indicative only. | Obtain one real export of each format before estimating an importer. |
| Telegram Mini App capabilities (camera, barcode scanning, large uploads, time-zone access) were not researched here. | Open question Q6 and several matrix rows depend on them. | Covered by the Telegram platform brief or a spike. |
| The EU Artificial Intelligence Act, EU consumer-law rules on dark patterns and national health-data laws were not researched. | Possible additional obligations for AI features and engagement mechanics. | Legal review before public launch. |
| Pricing data is inconsistent across sources. | Do not quote competitor prices externally. | Re-check storefronts at the time of any positioning work. |
