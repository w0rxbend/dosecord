# Operations

Status: written on 2026-11-14 with the ops track (ROADMAP G0.8, a first part of G2.18, G2.19 without
its live steps). Every script here was run against throwaway databases only, never against the
owner's database. The decisions behind it are in ARCHITECTURE sections 17.3 and 18.4.

The rules that apply to every step: no command prints a message body, a medication name, a note,
a payload or an error text from a row; no secret goes into a command line that a shell history
keeps; the backup private key never lives on the host that runs the bot.

## 1. Health of the running process

The operations listener (`HEALTH_ADDRESS`, `HEALTH_PORT`, loopback and 8080 by default) is never
routed by the proxy.

| Path | Answers |
|---|---|
| `/healthz` | 200 `ok` while the process runs (the container health check) |
| `/readyz` | 200 when ready, 503 when not, with a JSON body (below) |
| `/metrics` | The Prometheus exposition of the process's registry |

`/readyz` body:

```json
{"status":"ready","database":"up","outbox_pending":0,"outbox_oldest_pending_seconds":0}
```

`outbox_pending` counts the outbox rows whose attempt is due and that are not yet delivered
(`queued`, `failed_retry`, `sending`). The process is not ready when the database does not answer
(`"database":"down"`, nothing else in the body) or when the oldest of those rows fell due more
than 5 minutes ago: sending has stopped.

Outbox metrics: `dosegram_outbox_sent_total{vendor,kind}`, `dosegram_outbox_retried_total{vendor,reason}`,
`dosegram_outbox_failed_total{vendor,reason}`, `dosegram_outbox_dead_total{vendor,reason}`, where
`reason` is one of `rate_limited`, `retryable`, `too_old`, `channel_fatal`, `permanent`,
`unrenderable`. A retried, failed or dead row is also one log line such as
`outbox row dead: vendor=telegram kind=reminder reason=channel_fatal`.

```sh
curl -fsS http://127.0.0.1:8080/readyz
curl -fsS http://127.0.0.1:8080/metrics | grep '^dosegram_outbox_'
```

## 2. Owner alerts

A dead outbox row or a quarantined inbound update sends one silent message that starts with
`Ops:` to the owner's chat, at most once per condition per hour; the next alert counts every row
since the last one. The chat is `OWNER_ALERT_CHAT` (a Telegram chat id), else the private chat of
the only user in `TELEGRAM_ALLOWED_USER_IDS`. With several allowlisted users and no
`OWNER_ALERT_CHAT`, no alert is sent and the start-up log says `owner alerts off`. The alert goes
straight to Telegram, not through the outbox, so it arrives when the outbox is what is failing.
An alert says that something died, not what: look with section 3.

## 3. Live smoke report

`scripts/live-smoke.sh` reads the database and prints the outbox (rows due and unsent, the age of
the oldest, rows dead in the last 24 hours by kind), the inbox (pending, oldest, quarantined in the
last 24 hours), each worker's last heartbeat, the age of the last reminder sent (an outbox row of
kind `reminder`), the last reminder, catch-up digest or missed notice sent, the time to the next
dose action, the doses marked unknown in the last 24 hours, and, as a check, the dose rows the
reminder loop failed on in the last 24 hours (`error_count > 0`). "last reminder sent: never" with
a next dose action ahead is no fault (supervisor decision, 2026-11-15). Every query is a `SELECT`. It exits 0 when every check is green, 1 when one is red, 2 when it cannot read the
database.

For the live database in its container (the owner runs this; agents do not):

```sh
PG_CONTAINER=<postgres container> PGUSER=<role> PGDATABASE=<database> scripts/live-smoke.sh
```

`PG_CONTAINER` runs `psql` inside that container with `docker exec`, so no password is needed on
the command line. Without it the script uses `psql` on the host, or a throwaway client container,
with the libpq variables `PGHOST`, `PGPORT`, `PGUSER`, `PGDATABASE` and a `PGPASSFILE`.
Thresholds: `SMOKE_MAX_OUTBOX_AGE` (300 s), `SMOKE_MAX_HEARTBEAT_AGE` (120 s),
`SMOKE_MAX_INBOX_AGE` (300 s).

## 4. Encrypted nightly backup

Recovery point: up to 24 hours, one backup a night. Continuous WAL archiving arrives with G11.9.

### 4.1 The backup key, once, on a machine other than the host

```sh
gpg --quick-gen-key 'dosegram backup <backup@your-domain>' default default never
gpg --armor --export 'dosegram backup' > dosegram-backup.pub.asc
gpg --armor --export-secret-keys 'dosegram backup' > dosegram-backup.key.asc
```

Keep `dosegram-backup.key.asc` and its passphrase offline, in two places, never on the host and
never next to the backups: without it no backup can be read, and with it every backup can. Copy
only `dosegram-backup.pub.asc` to the host. The key-encryption key of field encryption (G2.4b) is
not in the database and so not in the backup; it is kept the same way, separately.

### 4.2 Each night, on the host

```sh
BACKUP_DIR=/srv/dosegram/backups \
BACKUP_GPG_RECIPIENT_FILE=/srv/dosegram/dosegram-backup.pub.asc \
PG_CONTAINER=<postgres container> PGUSER=<role> PGDATABASE=<database> \
scripts/backup.sh
```

It writes `dosegram-<UTC stamp>.dump.gpg` and its `.sha256`, never a plain dump, and then deletes
backups older than `BACKUP_RETENTION_DAYS` (35). A cron line, at 03:15 host time:

```
15 3 * * * cd /srv/dosegram/app && BACKUP_DIR=/srv/dosegram/backups BACKUP_GPG_RECIPIENT_FILE=/srv/dosegram/dosegram-backup.pub.asc PG_CONTAINER=<postgres container> PGUSER=<role> PGDATABASE=<database> scripts/backup.sh >> /var/log/dosegram-backup.log 2>&1
```

Off-host storage (OA-7) is the owner's choice of place; copy the directory after the backup, for
example with `rclone sync /srv/dosegram/backups <remote>:dosegram-backups`, keeping the remote's own
retention no longer than 35 days so that deletions take effect.

## 5. Restore drill

Run it once a month and after every change to the schema, on a machine that has Docker, GnuPG and
the private key, never on the host's live database. It starts its own PostgreSQL container on the
loopback interface with its data in memory, and removes it at the end.

```sh
BACKUP_GPG_PRIVATE_KEY_FILE=dosegram-backup.key.asc \
BACKUP_GPG_PASSPHRASE_FILE=passphrase.txt \
scripts/restore-drill.sh /path/to/dosegram-20261114T031500Z.dump.gpg
```

It checks the `.sha256` when it lies next to the backup, restores, compares the rows of every table
with the rows the dump carries, and runs `flyway validate` with the repository's migrations
(migrations newer than the backup are allowed). With `PURGE_LIST_FILE=<copy of the live purge
list>` it also counts the deleted accounts the backup still holds
(`restore-drill: purge list: N of M listed account(s) in this backup`): those are the accounts the
first start of a real restore deletes again (section 6). It ends with `restore-drill: PASS`. Not
checked yet: the decryption of a canary account (G2.4b).

`scripts/test-ops-scripts.sh` runs the backup, the drill and the live smoke report end to end on
throwaway databases; CI runs it in the job `ops-scripts`.

## 6. A real restore

There is no automated procedure yet. Until the outbox reconciliation of OPS-01 exists, a restored
database may hold queued reminders for doses whose time has passed, so do not start the bot on it
unattended:

1. Stop the bot. Keep the old database volume; never drop it.
2. Restore the chosen backup into a new database with `pg_restore --no-owner`, after creating the
   roles `dosegram_app` and `dosegram_erasure`, as `scripts/restore-drill.sh` does, and check that
   the login role may take `dosegram_erasure` (step 3 of section 8).
3. Run `dosegram migrate` against it with the live `PURGE_LIST_PATH` and `KEK_FILE`. It applies newer
   migrations and then replays the purge list: every account deleted since the backup was taken is
   deleted again, with a new deletion receipt and audit row (`purge: replayed N deletion(s) of the
   purge list`). Every start replays the list too, so a start without this step still deletes them
   before it serves; a replay that cannot delete (no erasure role) stops the start.
4. Run `scripts/live-smoke.sh` against it and read the due outbox rows before the bot starts.
5. Start the bot on the new database.

## 7. Secret scanning

CI runs `scripts/secret-scan.sh` (gitleaks 8.30.1 with `.gitleaks.toml`) over the whole history on
every push and pull request. Before each commit, install the hook once:

```sh
pre-commit install            # with the pre-commit tool
# or, without it:
printf '#!/bin/sh\nexec scripts/secret-scan.sh --staged\n' > .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

A finding fails the scan with the value redacted. If it is a real secret, rotate it first: removing
it from the history does not make it secret again. `.gitleaks-baseline.json` lists the findings of
the history before G0.8, all test values; a test value added later goes into the rules' allowlists
in `.gitleaks.toml`, not into the baseline. `scripts/test-secret-scan.sh` proves that a seeded bot
token, webhook secret and key fail both scans.

Every action in `.github/workflows/` is pinned by commit, with its version as a comment; Renovate
moves the commit and the comment together. The gitleaks download is pinned by version and
checksum; a Renovate update of the version needs the new checksum in the same pull request.

## 8. Deploying `main` of 2026-11-14 to the live host (V8 to V12)

The live database is at V8. This `main` brings V9 (inventory keys), V10 and V11 (encryption at rest,
[DEPLOY_ENCRYPTION.md](DEPLOY_ENCRYPTION.md)) and V12 (audit log and deletion), the readiness and
metrics paths, and the owner alerts. Every step is the owner's; none was run against the live
database. In order:

1. Make the key-encryption key file outside the database volume and every backup of it, as the
   user that runs the bot (`umask 077`, `printf 'kek-2026:%s\n' "$(openssl rand -base64 32)"`,
   `chmod 0400`), and two offline copies in separate places (OA-9). Losing it loses every sealed
   value.
2. Choose a persistent path for the purge list outside the database volume and its backups,
   writable by the bot's user (for example next to the key file, not the same file).
3. Check that the database login role may take the erasure role:
   `SELECT rolsuper OR pg_has_role(current_user, 'dosegram_erasure', 'MEMBER') FROM pg_roles WHERE rolname = current_user;`
   must return `t` (the role exists since V1). If it returns `f`:
   `GRANT dosegram_erasure TO <login role>;`. Without it every deletion fails and
   deletes nothing.
4. Back up the live database with `pg_dump` and keep that file until step 9 has passed. It holds
   plaintext: write it under `umask 077` (a plain `pg_dump` file is readable by every local user),
   keep it off shared storage, and delete it once the first encrypted nightly backup of
   section 4 has passed a restore drill (supervisor decision, 2026-11-14).
5. Stop the running bot. The previous release writes plaintext, which V11 then refuses.
6. Add to the bot's environment: `KEK_FILE=<path of step 1>` (the app reads `KEK`, or the file
   `KEK_FILE` names; `KEK` wins when both are set; also under
   `ENVIRONMENT=development`, or the data is sealed with the public development key) and
   `PURGE_LIST_PATH=<path of step 2>`. Optional: `OWNER_ALERT_CHAT=<chat id>`; without it the alerts
   go to the only user of `TELEGRAM_ALLOWED_USER_IDS`.
7. Optional: run `dosegram migrate` with the same environment and read its lines: one
   `sealing: <table>.<column> sealed N value(s), verified N` per column, then
   `migrate: 4 migration(s) applied, schema 12, N value(s) sealed, 0 key(s) rewrapped`.
8. Start the new release as usual; its start finds nothing left to do.
9. Check, read-only:
   - `SELECT max(version::int) FROM flyway_schema_history WHERE success;` returns 12.
   - `SELECT count(*) FROM medications WHERE name IS NOT NULL;` returns 0.
   - `SELECT event_type FROM audit_log WHERE event_type = 'key.backfill.completed';` returns one row
     if the database held a sealed value's plaintext.
   - `curl -fsS http://127.0.0.1:<HEALTH_PORT>/readyz` answers 200; `scripts/live-smoke.sh` exits 0.
   - `/today`, the Mini App medication list, `/food` recent words and an export show the real values.
10. Schedule the nightly backup and copy it off the host (section 4, OA-7), and install the
    secret-scan hook (section 7).

Rollback: a start that fails before V11 leaves V10 with every plaintext value, and the previous
release can be started again. After V11, restore the backup of step 4.

## 9. Deploying V13 (sealed transport), and VACUUM after sealing and deletion

V13 seals the text of queued messages (`outbox_messages.payload`) and the stored answers
(`inbound_events.reply`), which held medication names and instructions as plaintext, and keeps the
chat of each onboarding row so that an account deletion takes those rows too. The start applies
V13 and then seals every value the previous release left, emptying its plaintext in the same
statement. Every step is the owner's:

1. Back up the database as in step 4 of section 8 (`umask 077`, off shared storage).
2. Stop the running bot, then start the new release. Its log shows
   `sealing: outbox_messages.payload sealed N value(s), verified 0`, the same for
   `inbound_events.reply`, and `sealing: vacuumed outbox_messages, inbound_events`.
3. Check, read-only: `SELECT max(version::int) FROM flyway_schema_history WHERE success;` returns
   13; `SELECT count(*) FROM outbox_messages WHERE payload IS NOT NULL;` and
   `SELECT count(*) FROM inbound_events WHERE reply IS NOT NULL;` return 0.

Rollback: the previous release cannot read a sealed payload. Restore the backup of step 1.

**VACUUM (supervisor decision, 2026-11-14).** Sealing a value and deleting a row leave the old row
version in the table file until Postgres reuses its space, and that version holds the plaintext or
the deleted record. The start runs a plain `VACUUM (ANALYZE)` of each table it sealed a value in:
it takes no lock that stops the bot, and it frees the old versions for reuse, but it does not
overwrite them at once. A role that does not own a table gets a warning from Postgres and that
table is skipped; the log line names the tables it ran on. To remove the old versions from the
files, the owner runs, with the bot stopped (VACUUM FULL locks each table while it rewrites it):

```sh
docker exec <postgres container> psql -U <owner role> -d <database> -c \
  'VACUUM (FULL, ANALYZE) outbox_messages, inbound_events, medications, schedule_revisions, dose_occurrences, dose_actions, meals, export_jobs, render_assets, idempotency_keys, conversation_sessions, form_runs, inbound_inbox;'
```

The last three tables are still plaintext stores (the answers of an open chat form and the body of
a received update): their live rows are deleted or emptied once the form or the update is done, but
the old row versions keep the typed text, a medication name or the words of a meal included, until
they are rewritten. The upgrade rehearsal of 2026-11-14 found those versions in the files after the
shorter list above had run.

Run it once after the deploy of V11 and of V13, and after an account deletion when the deleted
records must leave the files before autovacuum reuses their space. It does not reach the WAL or the
backups: those age out with the WAL recycling and the 35 days of backup retention (SEC-06), and a
restore replays the purge list (section 6).

## 10. Deploying V14 (row-level security)

V14 forces row-level security on every account-owned table (ROADMAP G2.3, ADR-021). From this
release on, every transaction of the bot takes `dosegram_app` with the account as the claim, or
`dosegram_worker` for the work that crosses accounts; serving code never runs as the login role.
V14 revokes no grant and changes no row. It applies after V13 (section 9). Every step is the
owner's:

1. Back up the database as in step 4 of section 8 (`umask 077`, off shared storage).
2. Find out whether the login role is a superuser, read-only:
   `SELECT rolsuper FROM pg_roles WHERE rolname = current_user;`
   - `t` (the stock container image): nothing to grant. Go to step 3.
   - `f`: V14 grants `dosegram_app`, `dosegram_worker` and `dosegram_erasure` to the login role when
     that role created them in V1. If it did not, the start refuses to serve and its last line is
     `dosegram: the database login role may not take the serving roles; run GRANT dosegram_app, dosegram_worker TO "<login role>";`.
     Run that statement as a superuser, together with `GRANT dosegram_erasure TO "<login role>";`
     when step 3 of section 8 returned `f`, and start again.
3. Stop the running bot, then start the new release. Its log shows
   `migrate: 1 migration(s) applied, schema 14, 0 value(s) sealed, 0 key(s) rewrapped`.
4. Check, read-only:
   - `SELECT max(version::int) FROM flyway_schema_history WHERE success;` returns 14.
   - `SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relrowsecurity AND c.relforcerowsecurity;`
     returns 43.
   - `SELECT rolname FROM pg_roles WHERE rolname IN ('dosegram_app', 'dosegram_worker', 'dosegram_erasure') AND (rolsuper OR rolbypassrls OR rolcanlogin);`
     returns no row.
   - `BEGIN; SET LOCAL ROLE dosegram_app; SELECT count(*) FROM medications; ROLLBACK;` returns 0:
     without the claim the application role reads nothing.
   - `curl -fsS http://127.0.0.1:<HEALTH_PORT>/readyz` answers 200; `scripts/live-smoke.sh` exits 0.
   - `/today`, `/water`, the Mini App medication list and an export show the real values.

Rollback: start the previous release. It runs as the login role, which the `schema_owner` policy
admits (or which bypasses the policies as a superuser), and Flyway ignores the applied V14 as a
future migration. No restore is needed.

The counts above are those of a release with V14 alone. The `main` of the integration of
2026-11-14 also carries V15 to V17: section 11 has its steps and its counts.

## 11. Deploying `main` after the integration of 2026-11-14 (V13 to V17 in one start)

The live database is at V12. The head of `main` carries V13 (section 9), V14 (section 10),
V15 (imports: `import_jobs`, `import_staging`, the import indexes of the entry tables and the
sleep origin `import_file`), V16 (the suppress reason `bedtime`, the check-in kind `nap_start`,
`users.gamification_enabled`) and V17 (the four row-level security policies on the two import
tables). The start applies them in order. Flyway runs without `outOfOrder`, so a release that has
V16 but not V14 and V15 (commit `6ca39ec` of `main`), or V15 and V16 but not V14 (`5e74c20`), must
never start against the live database: V14 could not be applied after it. Deploy `3d84fd6` or a
later commit (supervisor decision, 2026-11-14). No new environment variable. Every step is the
owner's:

1. Back up the database as in step 4 of section 8 (`umask 077`, off shared storage), and keep the
   file until step 4 below has passed.
2. Check the login role, read-only, as in step 2 of section 10 and step 3 of section 8. Run the
   GRANT statements they name as a superuser when a check returns `f`.
3. Stop the running bot, then start the new release from a fresh worktree of that commit. Its log
   shows the sealing of `outbox_messages.payload` and `inbound_events.reply` and
   `sealing: vacuumed outbox_messages, inbound_events` (section 9), then
   `migrate: 5 migration(s) applied, schema 17, N value(s) sealed, 0 key(s) rewrapped` and
   `purge: replayed N deletion(s) of the purge list`.
4. Check, read-only:
   - `SELECT max(version::int) FROM flyway_schema_history WHERE success;` returns 17.
   - `SELECT count(*) FROM outbox_messages WHERE payload IS NOT NULL;` and
     `SELECT count(*) FROM inbound_events WHERE reply IS NOT NULL;` return 0.
   - The row-level security count query of section 10 returns 45 (43 from V14, 2 from V17), and
     `SELECT count(*) FROM pg_policies WHERE tablename IN ('import_jobs', 'import_staging');`
     returns 8.
   - The role query of section 10 returns no row, and
     `BEGIN; SET LOCAL ROLE dosegram_app; SELECT count(*) FROM medications; ROLLBACK;` returns 0.
   - `SELECT count(*) FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'gamification_enabled';`
     returns 1.
   - `curl -fsS http://127.0.0.1:<HEALTH_PORT>/readyz` answers 200; `scripts/live-smoke.sh` exits 0.
   - `/today`, `/water`, `/sleep`, `/stats`, the Mini App medication list and an export show the
     real values.
5. Optional, with the bot stopped: the `VACUUM (FULL, ANALYZE)` of section 9.

What changes for the user after this deploy, none of it checked on a live client yet:

- The weekly report of a new or re-applied plan goes on Monday at 09:00; stored plans keep their
  time. On the first eligible Monday 09:00 (or the 1st at 10:00), an account with no report plan
  gets one offer message. Three unused reports in a row pause that report, with a Resume button.
- The monthly report's pages go as one album (`sendMediaGroup`). `/stats` has "Share a card".
- "Going to sleep" holds nudges and reports for at most 12 hours; "Nap" and "Adjust times" exist.
- `/import` is a hidden command that asks for the `import` consent at first use; the Mini App
  import page needs `MINIAPP_ENABLED`. The health worker uses the Trusted Types policy
  `dosegram-health-worker`: a `trusted-types` CSP directive added later must name it.

After the deploy, check once with a real client: the album, the share card, the report offer.

Rollback: restore the backup of step 1 and start the previous release. The previous release
cannot read a payload V13 sealed. Starting an older release against the V17 schema without a
restore was not tested.

## 12. Deploying V18 (inbox failure reasons, narrower grants), and the start-up checks of 2026-11-15

The live database is at V17. V18 adds `inbound_inbox.failure_reason`, revokes every grant of the
serving roles on `flyway_schema_history`, and leaves `dosegram_app` only INSERT on `audit_log`
(`dosegram_worker` reads it for the export). The start also does two things it did not do before
(supervisor decisions, 2026-11-15):

- It moves a sequence that is behind its column and logs `sequences: <sequence> was behind
  <table.column>; moved to N`. On 2026-11-14 `domain_events_seq_seq` was behind the rows copied
  into `dosegram_v2` (the copy had no `setval`): that morning's reminder and two button taps
  failed with SQL state 23505. The failures have since used up the copied numbers, so the start
  is expected to log nothing for the live database. A copy made with `pg_dump` and `pg_restore`
  sets every sequence; a hand-made `COPY` does not.
- It refuses to serve when the file of `PURGE_LIST_PATH` exists and its group or other users may
  read or write it, and names the fix: `chmod 600 <path>`. The first deletion creates the file with
  mode 0600.

The key-encryption key is read from `KEK`, or from the file that `KEK_FILE` names (the Docker
secret convention, preferred: the value stays out of the process environment and shell history).
When both are set, `KEK` wins. `.env.example`, section 8 step 6 and
[DEPLOY_ENCRYPTION.md](DEPLOY_ENCRYPTION.md) use `KEK_FILE`.

Steps, all the owner's:

1. Back up the database as in step 4 of section 8.
2. `stat -c %a <PURGE_LIST_PATH>` prints `600` (or the file does not exist yet); otherwise
   `chmod 600 <PURGE_LIST_PATH>`.
3. Start the new release. Expect `migrate: 1 migration(s) applied, schema 18` and no `sequences:`
   line.
4. Check, read-only: `SELECT has_table_privilege('dosegram_app', 'audit_log', 'SELECT');` returns
   `f`; `scripts/live-smoke.sh` reports the next dose action, and its red line "no dose row failed
   in the reminder loop" clears 24 hours after the last failure.

Rollback: restore the backup of step 1 and start the previous release.

## 13. Deploying `main` after the integration of 2026-11-15 (V18 and V19 in one start)

The live database is at V17 and the live bot runs `dba2825`. The head of `main` carries V18
(section 12) and V19 (`V19__sealed_chat_state.sql`, merge `2526cbe` and `be54ef6`), plus the image
round 3 (merge `a8dc02e`) and the Mini App round (merge `794dad1`). The start applies V18 and
V19 in order. V19 was written as V21 on its branch and merged as V19 before any database applied
it; no file named V21 exists (supervisor decision, 2026-11-15, ARCHITECTURE section 10.17).
No new environment variable and no Dockerfile change. Every step is the owner's:

1. Back up the database as in step 4 of section 8 (`umask 077`, off shared storage), and keep the
   file until step 5 below has passed. There is no downgrade past V19 without this backup: the
   previous release cannot read a sealed session, slot or inbox body, and the new CHECKs on
   `users.display_name` and `platform_identities.vendor_username` refuse the vendor name an older
   release writes.
2. Step 2 of section 12: `stat -c %a <PURGE_LIST_PATH>` prints `600`, or the file does not exist.
3. Rebuild the container image (or the assembly) from the new commit: it carries the Mini App
   bundle, which now draws the blob's glints and cheeks, answers doses and shares a card.
4. Stop the running bot, then start the new release from a fresh worktree of that commit. The
   start needs the existing `KEK` (or `KEK_FILE`). Its log shows, in this order:
   - `sealing: <table>.<column> sealed N value(s), verified N` for `inbound_inbox.body`,
     `callback_slots.payload`, `domain_events.data` and `domain_events.actor` (on 2026-11-15 the
     live database held 2 inbox bodies, 49 slot payloads and 7 events; no session, form run,
     reminder title or dose unit; no display name or vendor name). The start prints one such
     line for every sealed column of every stage, V10 and V13 included; those of the earlier
     stages say `sealed 0`;
   - `sealing: vacuumed ...` naming the tables it sealed (a plain `VACUUM (ANALYZE)`);
   - no `sequences:` line (section 12);
   - `migrate: 2 migration(s) applied, schema 19, N value(s) sealed, 0 key(s) rewrapped`;
   - `purge: replayed N deletion(s) of the purge list`.
   V19 also creates `ix_delivery_channels_account` and `ix_occ_schedule_open` without
   `CONCURRENTLY`; the tables are small.
5. Check, read-only:
   - `SELECT max(version::int) FROM flyway_schema_history WHERE success;` returns 19.
   - `SELECT count(*) FROM inbound_inbox WHERE body IS NOT NULL;`,
     `SELECT count(*) FROM callback_slots WHERE payload IS NOT NULL;` and
     `SELECT count(*) FROM domain_events WHERE data IS NOT NULL OR actor IS NOT NULL;` return 0.
   - `SELECT has_table_privilege('dosegram_app', 'audit_log', 'SELECT');` returns `f` (V18).
   - `curl -fsS http://127.0.0.1:<HEALTH_PORT>/readyz` answers 200; `scripts/live-smoke.sh`
     exits 0.
   - A button tapped before the deploy still answers (its slot was sealed in place), `/today`,
     `/stats` and the Mini App Today show the real values.
6. Optional, with the bot stopped: the `VACUUM (FULL, ANALYZE)` of section 9 on
   `inbound_inbox`, `callback_slots` and `domain_events`, which rewrites the pages that still hold
   the old plaintext.

What changes for the user after this deploy, none of it checked on a live client yet:

- Every mood image and every card with a blob looks different (blob revision `dg-blob-2`). Shared
  mood images use new cache keys (`mood.dg-blob-2.<state>…`), so each state, theme and language
  is uploaded once more on first use; the old `telegram_file_cache` rows are no longer read and
  need no cleaning. The Mini App fetches the new blob asset once.
- The seven-day `/stats` card and `/today` carry "Show as image": the seven days send the week at
  a glance (IMG-19), today the daily summary.
- With `MINIAPP_ENABLED`: Today answers a dose (Taken, Skip, Snooze, Undo) through
  `POST /api/v1/occurrences/{id}/actions`, stored in `dose_actions` with the vendor `miniapp`;
  the chat's reminder is closed through the outbox. Insights has "Share a card"
  (`GET /api/v1/report-cards`, `POST /api/v1/report-cards/{kind}/deliveries`), which needs the
  images consent and answers 422 `image_unavailable` without `RESVG_PATH` and
  `RENDER_FONTS_DIR`.

After the deploy, check once with a real client: a dose answered in the Mini App closes the
chat's reminder (MA-TODAY-4), a shared card arrives, "Show as image" on `/stats` sends the week.

Rollback: restore the backup of step 1 and start `dba2825`.

Rehearsed on 2026-11-15 against a throwaway database (verification of `main` at `c5c047c`): a
V17 database made by `dba2825` with a key file, through the chat on the fake wire (onboarding, a
medication with instructions, a taken reminder, water, mood, sleep, a meal with words, an export,
an add-medication form left after its first answer). Steps 1, 2, 4, 5 and 6 as written; the
start logged the lines of step 4 (27 values sealed, among them the open form's answers, its
session and 15 slot payloads), then the head on the fake wire finished the open form, answered
four buttons sent before the upgrade, sent a reminder and recorded its Taken. A scan of every
column of every table and a `pg_dump` held none of the typed words; `dosegram_app` saw no row
without an account; `/readyz` answered 200 and `scripts/live-smoke.sh` exited 0. The WAL files
still held the typed words written before the upgrade (ROADMAP G2.4b, the WAL dump test is open).
Not rehearsed: step 3 (the container image), step 8 (a real client) and the rollback.

## 14. Deploying V22 (sealed file ids and export parameters), and the WAL after sealing

V22 (`V22__sealed_file_ids_and_export_parameters.sql`) seals the last plaintext Class A columns of
ROADMAP G2.4b: `telegram_file_cache.file_id` (the Bot API's handle of an uploaded card or export
file) and `export_jobs.params` (the period of a record summary, which the step-up of a Mini App
request confirms). The start applies V22 right after V19 and seals each value the previous
release left, emptying its plaintext in the same statement. No V20 or V21 exists on any branch:
they were never used, and Flyway (no `outOfOrder`) would refuse them on a database at V22, so the
next migration is V23 (ARCHITECTURE section 10.17, rule 1). No new environment variable. Every step is the owner's:

1. Back up the database as in step 4 of section 8 (`umask 077`, off shared storage). There is
   no downgrade past V22 without it: the previous release cannot read a sealed file id or
   export's parameters.
2. Stop the running bot, then start the new release. Its log shows
   `sealing: telegram_file_cache.file_id sealed N value(s), verified 0`, the same for
   `export_jobs.params`, and `sealing: vacuumed telegram_file_cache, export_jobs` (or the one of
   the two that held a value).
3. Check, read-only: `SELECT max(version::int) FROM flyway_schema_history WHERE success;`
   returns at least 22; `SELECT count(*) FROM telegram_file_cache WHERE file_id IS NOT NULL;` and
   `SELECT count(*) FROM export_jobs WHERE params IS NOT NULL;` return 0. A mood image sent
   before the deploy is sent again by its file id, without a new upload.

Rollback: restore the backup of step 1.

**The WAL after sealing (supervisor decision, 2026-11-15; ARCHITECTURE section 15.2).** The WAL
this release writes holds no Class A value in plaintext. The WAL written before a sealing start,
and the sealing itself (its full-page images keep the old row versions), does: in `pg_wal`
until its segments are removed, and in a recycled segment until it is written over. To clear
`pg_wal` after a sealing start, with the bot running or not:

```sh
docker exec <postgres container> psql -U <superuser> -d <database> \
  -c 'ALTER SYSTEM SET wal_recycle = off' -c 'SELECT pg_reload_conf()' \
  -c 'SELECT pg_switch_wal()' -c 'CHECKPOINT'
```

The switch closes the segment that holds the older records, and the checkpoint deletes every
segment before the new one instead of renaming it for reuse. Later full-page images of the
sealed pages hold no plaintext, because the start's VACUUM pruned the old row versions. `wal_recycle` may stay off (each new segment is then created rather than reused, a small
cost at this write rate) or be set back with `ALTER SYSTEM RESET wal_recycle`. It does not
reach the disk's free blocks, archived WAL or the base backups, which keep what they hold for
the 35 days of backup retention (SEC-06). `WalSealingPgSuite` rehearses these steps.

## 15. Deploying `main` of 2026-11-27 over the live V19 database (5b03df7 to the head, V22)

The live bot runs `5b03df7` from `.live/run.sh` (long polling, `.live/wt-5b03df7`), and its
database is at V19. The head of `main` adds one migration, V22 (section 14), and no environment
variable the live start needs. `RENDER_WORKER_PATH` is new and optional: without it every image
is drawn by one resvg process, as today. No Dockerfile step is needed for the live host, which
runs the assembly. Every step is the owner's; `<sha>` is the head of `main` at or after the
commit that adds this section, and `<pg>` is `dosegram-live-pg`:

1. Back up, with the bot still running:
   `umask 077; docker exec -u postgres <pg> pg_dump -U dosegram -d dosegram_v2 -Fc > <off-host dir>/dosegram-v19-$(date -u +%F).dump`.
   Keep the file until step 6 has passed: there is no downgrade past V22 without it.
2. `stat -c %a .live/purge-list` prints `600`, or the file does not exist.
3. Build the release next to the running one:
   `git worktree add --detach .live/wt-<sha> <sha>`, then `(cd .live/wt-<sha> && ./mill app.assembly)`.
4. Stop the running bot (Ctrl-C or SIGTERM to its `java` process; the log ends with
   `shutdown: pool closed`). Start the new release with the same script:
   `LIVE_WT="$PWD/.live/wt-<sha>" .live/run.sh`. Its log shows, in this order:
   - `Migrating schema "public" to version "22 - sealed file ids and export parameters"`;
   - `sealing: telegram_file_cache.file_id sealed N value(s), verified 0` (on 2026-11-26 the live
     database held 3 file ids) and `sealing: export_jobs.params sealed 0 value(s), verified 0`
     (it held no export); one such line for every sealed column of every stage, `sealed 0` for
     the earlier ones;
   - `sealing: vacuumed telegram_file_cache` (and `export_jobs` when one was sealed);
   - `migrate: 1 migration(s) applied, schema 22, N value(s) sealed, 0 key(s) rewrapped`;
   - `purge: replayed 0 deletion(s) of the purge list`.
5. Check, read-only (`docker exec -u postgres <pg> psql -U dosegram -d dosegram_v2 -tA -c '...'`):
   - `SELECT max(version::int) FROM flyway_schema_history WHERE success;` returns 22;
   - `SELECT count(*) FROM telegram_file_cache WHERE file_id IS NOT NULL;` and
     `SELECT count(*) FROM export_jobs WHERE params IS NOT NULL;` return 0;
   - `curl -fsS http://127.0.0.1:18081/readyz` answers 200;
   - in the chat: `/meds` lists the medications without any onboarding question, a button sent
     before the deploy still answers, and a mood check-in in a state already sent comes back as
     an image without a new upload.
6. Clear the WAL of the old file ids (section 14, "The WAL after sealing").
7. Delete the old worktree only after a day of normal use: `git worktree remove .live/wt-5b03df7`.

What changes in the chat after this deploy, none of it checked on a real client yet: the
seven-day `/stats` also sends "Water, 7 days", "Sleep, 7 nights" and "Mood by weekday"
(IMG-20 to IMG-22); the medication presets add every 2 weeks, every 6 h from waking and weekdays
plus weekend (ROADMAP G9.8, G9.9); the Ukrainian copy is the reviewed text; the refill notice
carries its card.

Rollback: stop the bot, restore the backup of step 1 into an empty database, start
`LIVE_WT="$PWD/.live/wt-5b03df7" .live/run.sh`. `5b03df7` cannot read a sealed file id.

Rehearsed on 2026-11-27 against a throwaway `postgres:18-alpine` container: `5b03df7`, started
as `Main` starts with `ENVIRONMENT=production`, a key file and a purge list of mode 600, built a
V19 database through the chat on the fake wire (onboarding, a medication with a name and
instructions, its reminder tapped Taken, water with its image, mood with its image, a night, a
meal with words, a record summary export, an add-medication form left after its first answer).
Steps 1, 2 and 4 as written, with the head assembly on the console adapter in place of the
token: the start logged the lines of step 4 (2 file ids and 1 export's parameters sealed),
`/readyz` answered 200, SIGTERM ended with `shutdown: pool closed` and exit 0, and a second start
logged `migrate: 0 migration(s) applied, schema 22`. The head on the fake wire then listed the
sealed medication name with no onboarding question, finished the open form, answered seven
buttons sent before the upgrade, and sent the mood image by its sealed file id without an upload.
A scan of every column, a `pg_dump` and, after step 6, the WAL held none of the typed words or
file ids; `dosegram_app` without a claim saw no row; 45 tables have forced row-level security.
Not rehearsed: a real client, the Telegram transport, the rollback.

## 16. Deploying `main` after the integration of 2026-11-27 over the live V22 database (50cda8f to the head, no migration)

The live bot runs `50cda8f` from `.live/run.sh` (long polling, `.live/wt-50cda8f`), and its
database is at V22. The head of `main` adds no migration, no table and no environment variable:
the start applies nothing and the schema stays at 22. The commits since `50cda8f` are the five
fixes of the verification of 2026-11-27 (`52a1ff9` to `3a71c00`), the new-user review
(`feat/new-user-review`, merge `c96eb1c`) and the trends and share builder
(`feat/trends-and-share`, merge `1fbc57d`), and the two fixes of the verification of the
integration (`87632ae`, the pixel hashes of the trends card; `2f3d01d`, `/today` labels). The Mini
App stays off on live (no https `MINI_APP_URL`), so the share builder and the Insights changes are
not reachable there. Every step is the owner's; `<sha>` is the head of `main` at or after the
commit that adds the rehearsal record below, and `<pg>` is `dosegram-live-pg`:

1. Back up, with the bot still running:
   `umask 077; docker exec -u postgres <pg> pg_dump -U dosegram -d dosegram_v2 -Fc > <off-host dir>/dosegram-v22-$(date -u +%F).dump`.
2. `stat -c %a .live/purge-list` prints `600`, or the file does not exist. Note the number of
   quarantined updates: `SELECT count(*) FROM inbound_inbox WHERE status = 'quarantined';`.
3. Build the release next to the running one:
   `git worktree add --detach .live/wt-<sha> <sha>`, then `(cd .live/wt-<sha> && ./mill app.assembly)`.
4. Stop the running bot (Ctrl-C or SIGTERM to its `java` process; the log ends with
   `shutdown: pool closed`). Start the new release with the same script:
   `LIVE_WT="$PWD/.live/wt-<sha>" .live/run.sh`. Its log shows
   `Schema "public" is up to date. No migration necessary.`; one
   `sealing: <table>.<column> sealed 0 value(s), verified 0` line for each of the 23 sealed
   columns; `migrate: 0 migration(s) applied, schema 22, 0 value(s) sealed, 0 key(s) rewrapped`;
   `purge: replayed 0 deletion(s) of the purge list`; and
   `/healthz, /readyz and /metrics listening on 127.0.0.1:18081`. A line `sealed N` with N above 0
   means a plaintext value was found and sealed: the start is still good, but say so in the
   deploy note.
5. Check, read-only (`docker exec -u postgres <pg> psql -U dosegram -d dosegram_v2 -tA -c '...'`):
   `SELECT max(version::int) FROM flyway_schema_history WHERE success;` returns 22;
   the quarantined count is the number of step 2. Then `curl -fsS http://127.0.0.1:18081/readyz` answers 200; in the chat, `/meds` lists
   the medications with no onboarding question, `/privacy` answers
   (it failed with no reply for an account holding the images or comparisons consent), `/today`
   lists the day in time order with timed buttons, `/stats 30` has a "Trends" button, and
   `/stats trends` answers with the figures of each consented domain.
6. Delete the old worktree only after a day of normal use: `git worktree remove .live/wt-50cda8f`.

What changes in the chat, none of it checked on a real client yet:

- Fixed quarantine paths: a Ukrainian user confirming the water consent after a typed amount
  (a plan name past the button limit), and `/privacy` for an account holding the images or
  comparisons consent (withdraw labels past the limit). Updates already quarantined stay
  quarantined; they are not replayed.
- Copy: shorter water plan names in both languages; withdraw buttons read "Withdraw: images" and
  similar; onboarding and the add-medication wizard read their buttons in the reader's language,
  and the form heading reads "Add medication (1/2)"; onboarding with medication ends with
  "Add medication" and "Today" buttons; `/meds` rows name their medication when there are two
  or more; the Ukrainian weekday heads of the week cards are two letters; two open doses at one
  time on `/today` name their medication, a long name shortened before "Taken" or "Skip".
- Reminders: a repeat says "Scheduled 13:00 · reminder 2 of 3", the last one says it is the last,
  an earlier card says "Reminded again below.", a snoozed card says "Snoozed until HH:MM."; the
  missed notice is always silent and carries the safety sentence.
- A night typed before the sleep consent is held for 30 minutes and recorded at Confirm; a late
  Undo on a meal or a night names the chat's own way while no Mini App opens.
- The consent notice is version 2; a grant under version 1 stays in force with no new prompt.
- Trends: `/stats trends` and "Trends" on the 30-day `/stats` (action id 411), with "30 days",
  "90 days" and "Show as image" (IMG-23, sent after the images consent; it needs `RESVG_PATH`
  and `RENDER_FONTS_DIR`, else the text is sent).

Rollback: stop the bot and start `LIVE_WT="$PWD/.live/wt-50cda8f" .live/run.sh`; the schema is
unchanged, so no restore is needed. A "Trends" button or a held night created by the head may get
no answer from `50cda8f`.

Rehearsed on 2026-11-27 against a throwaway `postgres:18-alpine` container on a free port, with
a key file and a purge list of mode 600, as `.live/run.sh` starts the bot (`ENVIRONMENT=production`,
the same variables): `50cda8f`, started as `Main` starts with the production assembly on the fake
wire, built a V22 database through the chat (onboarding in English, a medication with a name and
instructions at 09:00 and 13:00, the 09:00 reminder tapped Taken, water with the work-hours plan,
mood, a night, a meal with words, the images consent and "Water, 7 days" as an image, the record
summary export, the 13:00 reminder left open, an add-medication form left after its first
answer), in two starts. Then steps 1 to 4 as written, with `faf63bf` and again with `2f3d01d`, the
head assembly on the console adapter in place of the token: each start logged the lines of step
4, `/readyz` answered 200, SIGTERM ended with `shutdown: pool closed` and exit 0, and a second
start logged the same lines. The head on the fake wire, over that database, then
listed the sealed medication name with no onboarding question, answered nine buttons sent before
the upgrade (the catch-up Taken recorded the open 13:00 dose; the others answered with an edit or
a toast), answered `/privacy`, `/today`, `/stats 30` with "Trends", `/stats trends` and its image,
a water entry and a mood check-in with its image. No update was quarantined and no outbox row
died; the key file opened every sealed file id, the medication name, its instructions and the
meal words. A scan of every column (594), a `pg_dump` and the WAL held none of the typed words,
the first name or a file id; 45 tables have forced row-level security and `dosegram_app` without
a claim saw no row. The rollback start of `50cda8f` over that database logged
`migrate: 0 migration(s) applied, schema 22`, answered `/readyz` with 200 and stopped cleanly.
Not rehearsed: a real client, the Telegram transport, the rollback's answers to buttons the head
sent. The images drawn on the stepped clock of the seed were sent as text ("Late"), so the reuse of
a mood image by its file id was not shown.

## 17. Deploying `main` after the second integration of 2026-11-27 over the live V22 database (34c4733 to the head, V23)

The live bot runs `34c4733` from `.live/run.sh` (long polling, `.live/wt-34c4733`), and its
database is at V22. The head of `main` adds one migration, `V23__morning_summary_prompt_kind.sql`:
it replaces the CHECK on `prompt_schedules.kind` with the same list plus `report_daily`, changes
no row and adds no table, sealed column or environment variable. The commits since `34c4733`
are nine fixes and features on `main` (`168cfd1` to `69359f0`: the local rasteriser fetch, sleep
refusals with "Adjust times", a nudge that waits for the previous send, edit or delete a recent
meal with Undo, one card per dose, a second consent request replacing the open card, Cancel alone
on a flow's first step, "Export my data" on `/privacy`, the open-dose wording of `/stats`), the
chat analytics track (merge `880f4f6`) and image round 4 (merge `3dccd25`). Every step is the
owner's; `<sha>` is the head of `main` at or after the commit that adds this section, and `<pg>`
is `dosegram-live-pg`:

1. Back up, with the bot still running:
   `umask 077; docker exec -u postgres <pg> pg_dump -U dosegram -d dosegram_v2 -Fc > <off-host dir>/dosegram-v22-$(date -u +%F).dump`.
2. `stat -c %a .live/purge-list` prints `600`, or the file does not exist. Note the number of
   quarantined updates: `SELECT count(*) FROM inbound_inbox WHERE status = 'quarantined';`.
3. Build the release next to the running one:
   `git worktree add --detach .live/wt-<sha> <sha>`, then `(cd .live/wt-<sha> && ./mill app.assembly)`.
4. Stop the running bot (Ctrl-C or SIGTERM to its `java` process; the log ends with
   `shutdown: pool closed`). Start the new release with the same script:
   `LIVE_WT="$PWD/.live/wt-<sha>" .live/run.sh`. Its log shows Flyway applying version 23
   ("morning summary prompt kind"); the 23 `sealing: <table>.<column> sealed 0 value(s)` lines of
   section 16; `migrate: 1 migration(s) applied, schema 23, 0 value(s) sealed, 0 key(s) rewrapped`;
   `purge: replayed 0 deletion(s) of the purge list`; and the health listener on
   `127.0.0.1:18081`. A second start logs `migrate: 0 migration(s) applied, schema 23`.
5. Check, read-only (`docker exec -u postgres <pg> psql -U dosegram -d dosegram_v2 -tA -c '...'`):
   `SELECT max(version::int) FROM flyway_schema_history WHERE success;` returns 23; the
   quarantined count is the number of step 2. Then `curl -fsS http://127.0.0.1:18081/readyz`
   answers 200; in the chat `/reminders` lists "Morning summary", `/stats meds` lists the
   medications in registry order (with the medication consent), and `/stats month` with
   "Show as image" ends with the month in one image when the month has non-medication records.
6. Delete the old worktree only after a day of normal use: `git worktree remove .live/wt-34c4733`.

What changes in the chat, none of it checked on a real client yet:

- The morning summary is off until turned on: `/reminders` → "Morning summary" → 07:00, 08:00,
  09:00 or 10:00 → "Turn on". It sends yesterday at that time, silently, inside the daily budget;
  in quiet hours it is skipped that day, not held.
- `/stats meds` and "By medication" on every `/stats` card (action ids 442, 443); its image is
  the strip IMG-07 with "Item N" in place of names (images consent).
- The monthly report album and "Show as image" on `/stats month` gain one photo, the month in
  one image (IMG-24); it never shows medication.
- In discreet mode an image is captioned with its card's generic line ("Your day so far.",
  "By medication."), not its text; the alt text and the text fallback are unchanged. Report
  requests queued before the deploy read back without the new key.
- The calendar heat classes also differ by fill level; an empty water or sleep week says
  "No data"; a weekday with no check-in says "No check-in".
- The nine `main` commits listed above.

Rollback: stop the bot and start `LIVE_WT="$PWD/.live/wt-34c4733" .live/run.sh`. Flyway's
default treats the applied V23 as a future version and ignores it, so while no account has turned
the morning summary on, no restore should be needed (not rehearsed). Once a `report_daily` plan
exists, `34c4733` throws "unknown prompt kind 'report_daily'" wherever it reads a plan or prompt
of that kind, the prompt loop included; then restore the backup of step 1 into an empty database
instead, losing what was recorded after the deploy. Buttons the head sent (`/stats meds`, a meal
edit) may get no answer from `34c4733`.

Not rehearsed (supervisor decision, 2026-11-27): this section was written at the integration
without a rehearsal over a seeded V22 database. The upgrade from earlier schemas to V23 runs in
`SealingBackfillPgSuite` and `RlsSuite`, the head's plans with `report_daily` in
`PromptLoopPgSuite` and `NewUserWalkSuite`, and `scripts/flyway-validate.sh` passed against a
throwaway `postgres:18-alpine`. Not checked: a real client, the Telegram transport, the rollback.
