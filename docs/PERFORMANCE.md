# Performance

Status: measured on 2026-11-15 on the `feat/perf-privacy` branch (the migration that merged as V19); the image
render time (hot spot 4, section 4.1) again on 2026-11-15 on the `feat/renderer-perf-vitals` branch. Each number comes from a probe in
the repository, run by hand. Section 2 says how to run them. Nothing in the test suites asserts on a time, because
the numbers depend on the host. The query counts of section 3 do have a test.

## 1. Conditions

- Host: a 16-thread Linux workstation with 64 GB of memory, shared with other build agents. The load average was 9 to
  17 during the runs. Absolute numbers are therefore pessimistic and noisy. Each before and after pair was run back to
  back, on the same host and the same data.
- Database: Testcontainers `postgres:18-alpine`, migrated through V19, reached over the Docker bridge. A round trip
  costs about 0.1 to 0.2 ms, so a request's time follows how many statements it runs more than the server time of
  those statements.
- The JVM runs as the tests run it, through `./mill <module>.test.runMain`, without the container's heap limit.
- Data: synthetic only. Medication names, words and the person's name are probe strings.

## 2. Probes

| Probe | Command | What it measures |
|---|---|---|
| Render | `./mill app.test.runMain dosegram.app.renderProbe <resvg> $PWD/report-renderer/fonts 30 [<dosegram-raster>]` | Each status card laid out and drawn through `StatusImages.from`, the production drawing path: by one resvg 0.49.0 process per card, or with the fourth argument by the long-lived worker (ADR-022 addendum of 2026-11-15) |
| Chat round trip | `./mill app.test.runMain dosegram.app.chatRoundTripProbe 30` | The process of `ProductionPathSuite` on the fake Telegram wire: `/start`, a medication, then `/help` and `/today`, timed from the update entering the wire to its inbox row, its answer's outbox row and its `sendMessage` |
| API latency | `./mill api.test.runMain dosegram.api.apiLatencyProbe 50` | The API's routes on the real Netty interpreter (the `ApiSuite` harness) for one account with 90 days of data, all written through the production services: 182 doses, 541 water entries, 271 meals, 91 mood check-ins, 91 nights and 364 rollups. Each route gets 10 warm-up calls and 50 measured calls, plus one call with its statements counted |
| Reminder loop | `./mill infra.test.runMain dosegram.infra.perf.reminderTickProbe 1000` | 1000 accounts, each with a daily 09:00 dose and a delivery channel (seeded through `ScheduleLifecycle`), plus 90 days of dose history each (91,000 more rows). Then the loop's `tickOnce` while idle, and busy from 09:05 until every due dose is claimed |
| RSS | `scripts/rss-check.sh` against the compose stack (section 4.5) | The resident memory of the app container while idle, and right after it has sent 1000 reminders seeded by `dosegram.infra.perf.loadSeed` |

`StatementLog` (in `infra/test`) counts the statements a probe's own database runs, with their server time, by
turning on `log_min_duration_statement = 0` for one call. It reads only the statement text and never the parameters.

## 3. The hot spots fixed

All four changes leave behaviour as it was. The suites of the standard gate pass unchanged.

| # | Hot spot | Found by | Fix | Before | After |
|---|---|---|---|---|---|
| 1 | The account's primary delivery channels (`SELECT dc.id, pi.vendor, pi.dm_channel_id`) are read once for every reminder, prompt and digest. `delivery_channels` had no index on `account_id`, so each lookup read the whole table under its row-level security policy | Reminder loop, statement times | `ix_delivery_channels_account (account_id, priority)` in V19 | 57.5 ms of 92.1 ms of server time for one batch of 50 doses, 1.15 ms per lookup | 1.4 ms of 31.2 ms, 0.03 ms per lookup |
| 2 | One transaction read the account's time zone, settings and consents again for every domain it touched: 14 time-zone and 12 consent reads for one Insights overview, and 9 time-zone and 13 settings reads around one chat update | API latency, statement counts | `TransactionReads`: `PgAccountRepository` and `PgConsentLedger` read each value once per transaction. A write through the owning repository forgets it, and so does an erasure or a rolled-back savepoint (`TransactionReadsPgSuite`) | Insights overview: 59 statements, p50 12.0 to 13.0 ms | 39 statements, p50 10.2 to 11.0 ms |
| 3 | For each due dose the loop asks for the schedule's next open dose (`SELECT MIN(scheduled_for) ... WHERE schedule_id = ? AND status IN (open)`). The only index on the schedule also covered its whole history, so the lookup grew with the schedule's age | Reminder loop, statement times | `ix_occ_schedule_open (schedule_id, scheduled_for) WHERE status IN ('pending', 'due', 'snoozed')` in V19 | 5.3 ms of server time per 50 doses with 90 days of history | 0.9 ms |
| 4 | One resvg process per card. Its own `--perf` breakdown of the daily summary: fonts 0.2 ms, tree with shaped text 6.5 ms, drawing 6.6 ms, PNG encoding 34 ms; starting and ending the process from the JVM adds about 15 ms. The earlier reading of this table, that loading the fonts cost 38 to 50 ms, was wrong | Render, resvg `--perf` | `ResvgWorkerPool` and the worker `dosegram-raster` (ADR-022 addendum): two long-lived processes, fonts loaded once, the PNG encoded with the fast setting. Same pixels: the 234 pixel goldens pass unchanged through the worker | card.day p50 50.7 ms, p95 58.1 ms | card.day p50 12.0 ms, p95 12.6 ms |

Together, hot spots 1 and 3 halve the busy reminder loop: 2.98 to 3.44 ms per due dose before (1000 doses in 2.98 to
3.44 s) and 1.57 to 1.63 ms after (the 950 doses after the batch whose statements are counted, in 1.49 to 1.55 s).
The figures come from two runs each.

Looked at and left as it is:

- What remains of a card's time after hot spot 4 is resvg's own work: building the tree with shaped text and drawing,
  about 6 ms each for the daily summary (from the command line's `--perf`), and the fast PNG encoding, which was not
  timed on its own; the worker's 12 ms for that card is about the sum of the first two. Layout takes 0.5 to 0.7 ms. A shared
  card (the mood blob) is drawn once and then served from `render_assets` in 0.5 ms, and a sent image is sent again
  by its cached `file_id`.
- The busy loop still runs 14 statements per dose (decision, transition, action, outbox, domain event, savepoints). The
  largest remaining share is the `UPDATE dose_occurrences`, at 11 ms of server time per 50 doses, which is index
  upkeep on the hot table. Batching the per-dose reads across a batch would change the loop's ports, so it was not
  done here.
- Two key reads per account on the first open after the cache expires (the version of a sealed value, then the
  active version). This costs 1.7 ms of server time per 50 new accounts, which is small next to the rest.
- The Insights overview refreshes the rollups of the current period and the previous one separately: 8 refresh pairs
  per request. Merging them would change the statistics service's interface. That would save about 1.5 ms.

## 4. Measurements after the fixes

### 4.1 Image render time per card

These are the production drawing, 30 samples per card after 3 warm-up draws, the three runs back to back on the same
host (load average 8 to 9). "Process" is one resvg 0.49.0 process per card, built from the release tarball with the
production profile, as the image builds it. "Worker, balanced" is the worker with the PNG setting of the resvg command
line; it was built for this measurement only and shows what the long-lived process alone saves. "Worker" is what
ships.

| Probe | Process p50 | Process p95 | Worker, balanced p50 | Worker, balanced p95 | Worker p50 | Worker p95 |
|---|---|---|---|---|---|---|
| card.mood (shared, fresh store) | 41.5 | 45.0 | 30.1 | 33.9 | 11.4 | 14.6 |
| card.mood (shared, stored) | 0.5 | 0.7 | 0.6 | 0.9 | 0.6 | 0.8 |
| card.water | 36.3 | 38.4 | 22.6 | 25.4 | 8.0 | 9.5 |
| card.day | 50.7 | 58.1 | 35.2 | 39.5 | 12.0 | 12.6 |
| card.sleep (14 nights) | 43.6 | 47.8 | 28.9 | 33.6 | 10.7 | 11.1 |
| card.food.day | 35.9 | 38.1 | 20.4 | 23.3 | 6.9 | 7.1 |
| layout only, card.day | 0.6 | 0.8 | 0.6 | 1.3 | 0.5 | 0.7 |

All times in ms. An earlier pair of runs at a load average of 12 gave the process 38.5 to 61.7 ms at p50 and the
worker 7.7 to 13.1 ms. The fast setting makes the files larger: over the 74 light card goldens the median is 2.0
times the balanced size, and the largest card is 372 KB (229 KB before), against the 2 MB output cap. One worker's
resident memory is 13 MB after it has drawn every card, and it is not part of the JVM's figure in section 4.5.

### 4.2 Chat round trip on the fake wire

| Probe | Samples | p50 ms | p95 ms | max ms |
|---|---|---|---|---|
| /help: update to inbox row | 30 | 16.6 | 17.3 | 17.3 |
| /help: inbox row to outbox row | 30 | 4.2 | 5.5 | 7.7 |
| /help: update to sendMessage | 30 | 20.8 | 22.2 | 25.3 |
| /today: update to inbox row | 30 | 15.1 | 15.9 | 20.2 |
| /today: inbox row to outbox row | 30 | 5.3 | 6.9 | 9.6 |
| /today: update to sendMessage | 30 | 20.6 | 25.0 | 27.5 |

Most of the round trip is the fake wire, whose `getUpdates` returns at most every 20 ms (`FakeTelegramTransport`
`idlePoll`). The process's own part is the step from the inbox row to the outbox row, 4 to 5 ms. The outbox
dispatcher sends within 0.3 ms after that. Before the fixes, the same steps took 4.3 ms (`/help`) and 5.2 ms (`/today`)
at p50. That is within the noise: the chat path gained no measurable time.

### 4.3 API latency, one account with 90 days of data

| Probe | Samples | p50 ms | p95 ms | max ms | Statements |
|---|---|---|---|---|---|
| GET /today | 50 | 3.4 | 4.3 | 6.2 | 14 |
| GET /days/{date}, 30 days ago | 50 | 2.5 | 3.2 | 3.5 | 14 |
| GET /insights/overview?range=7 | 50 | 10.2 | 12.8 | 14.5 | 39 |
| GET /insights/overview?range=30 | 50 | 11.0 | 15.6 | 17.9 | 39 |
| GET /insights/overview?range=90 | 50 | 10.6 | 16.2 | 19.2 | 39 |
| GET /insights/calendar medication | 50 | 2.6 | 3.3 | 3.4 | 16 |
| GET /insights/calendar hydration | 50 | 2.3 | 2.7 | 3.4 | 16 |
| GET /insights/calendar nutrition | 50 | 2.3 | 2.6 | 2.7 | 16 |
| GET /insights/month | 50 | 4.0 | 4.8 | 6.8 | 27 |

The numbers before the fixes, from the same probe:

| Probe | p50 ms | p95 ms | Statements |
|---|---|---|---|
| GET /today | 3.3 | 4.0 | 14 |
| GET /insights/overview?range=7 | 12.0 | 13.7 | 59 |
| GET /insights/overview?range=30 | 13.0 | 15.8 | 59 |
| GET /insights/overview?range=90 | 12.8 | 15.5 | 59 |
| GET /insights/calendar medication | 3.1 | 4.3 | 18 |
| GET /insights/month | 4.2 | 5.3 | 29 |

### 4.4 Reminder loop with 1000 accounts

| Probe | Samples | p50 ms | p95 ms | max ms |
|---|---|---|---|---|
| idle tickOnce, before | 50 | 1.0 | 1.2 | 1.4 |
| idle tickOnce, after | 50 | 1.1 | 1.3 | 1.5 |
| busy tickOnce (batch of 50), before | 20 | 143.3 | 183.9 | 222.8 |
| busy tickOnce (batch of 50), after | 19 | 74.1 | 95.5 | 95.5 |

An idle tick is the materialiser's safety net, an empty claim and the heartbeat. It does not depend on the number of
accounts. A busy batch of 50 doses runs 714 statements. For ARCHITECTURE 7.6, which plans about 500 reminders a minute,
1000 doses due in the same minute take about 1.6 s of loop time.

### 4.5 RSS

The image is `docker build -f docker/Dockerfile` of this branch, so it has `-Xmx256m`, the serial collector and the
class-data archive. The stack is `docker/compose.yml` with the console adapter, started under its own project name,
with the ports moved by an override file so it does not clash with another stack on the host:

```
COMPOSE_PROJECT_NAME=perfpriv docker compose -f docker/compose.yml -f <override> up -d --no-build --wait
COMPOSE_PROJECT_NAME=perfpriv scripts/rss-check.sh 200
```

| State | RSS |
|---|---|
| Idle, 90 s after start | 142 MB (145,404 kB), `rss-check: PASS` at the 200 MB limit |
| Busy: highest of 90 samples, one every 2 s, while 1000 reminders seeded by `loadSeed` fell due in the same minute | 182 MB (186,496 kB) |
| Idle again, right after the burst | 171 MB (174,608 kB), `rss-check: PASS` |

All 1000 reminders reached `sent`, between 00:36:09 and 00:37:45 UTC (96 s from the first send to the last). Why the
console path took that long was not examined. The heap grew during the burst and the serial collector does not give it back at once, so the RSS after the
burst stays above the idle figure measured at start. Both stay under the 200 MB gate of the image gate.

The image of the `feat/renderer-perf-vitals` branch, which adds the worker and sets `RENDER_WORKER_PATH`, idled at
138 MB and then 144 MB (141,760 kB and 147,004 kB, both `rss-check: PASS`) under its own project name. No worker runs
until the first card is drawn, so the idle figure has none; each worker that has drawn adds about 13 MB outside the
JVM (section 4.1).
