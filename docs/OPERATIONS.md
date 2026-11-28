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
chat analytics track (merge `880f4f6`), image round 4 (merge `3dccd25`) and the fix of the
verification of the integration (`f8dced7`: the buttons of an old `/stats meds` card ask for the
medication consent once it is withdrawn). Every step is the
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
   The month card offers "Show as image" only once one area has records on 14 days of the
   month; before that it says how many days are missing and has no image button.
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

Rollback: stop the bot and start `LIVE_WT="$PWD/.live/wt-34c4733" .live/run.sh`. Flyway
logs `Schema "public" has a version (23) that is newer than the latest available migration (22) !`
and starts; while no account has turned the morning summary on, `34c4733` answers as before and
no restore is needed. Once a `report_daily` plan exists, `34c4733` still starts and answers
`/readyz`, `/meds`, `/stats` and a water entry, but its prompt loop logs
`prompt plan <id> failed: IllegalStateException` on every pass and `/reminders` gets no answer:
the update is quarantined after repeated errors and the owner alert says so. Then restore the
backup of step 1 into an empty database instead, losing what was recorded after the deploy:
`docker exec -u postgres <pg> createdb -U dosegram -O dosegram dosegram_restore`, then
`docker exec -i -u postgres <pg> pg_restore -U dosegram -d dosegram_restore --exit-on-error < <dump>`,
and start `34c4733` with `DATABASE_URL` naming `dosegram_restore` (schema 22, no plan of that
kind). Buttons the head sent (`/stats meds`, a meal edit) may get no answer from `34c4733`.

Rehearsed on 2026-11-27 against a throwaway `postgres:18-alpine` container on a free port, with
a key file (`KEK_FILE`) and a purge list of mode 600, `ENVIRONMENT=production`, as in section 16:
`34c4733`, started as `Main` starts with the production assembly on the fake wire and resvg
drawing, built a V22 database through the chat in two starts (onboarding in English, a
medication with a name and instructions at 09:00 and 13:00, the 09:00 reminder tapped Taken,
water with the work-hours plan, mood, a night, a meal with words, the images consent with the
week image and "Water, 7 days", mood images, the record summary export of 30 days, the 13:00 reminder left
open, an add-medication form left after its first answer). Then steps 1 to 4 as written with
`f8dced7` (the assembly started with `java -jar out/app/assembly.dest/out.jar run` on the
console adapter in place of the token): after a start and SIGTERM of `34c4733`, the head logged
`Migrating schema "public" to version "23 - morning summary prompt kind"`, 23 `sealing:` lines
with `sealed 0`, `migrate: 1 migration(s) applied, schema 23, 0 value(s) sealed, 0 key(s) rewrapped`
and `purge: replayed 0`; `/readyz` answered 200; SIGTERM ended with `shutdown: pool closed` and
exit 0; a second start logged `migrate: 0 migration(s) applied, schema 23`. The head on the fake
wire over that database found one account and one identity, listed the sealed medication name
with no onboarding question, answered nine buttons sent before the upgrade, `/privacy`,
`/today`, `/stats 30` with "Trends", `/stats trends` and its image, `/reminders` with
"Morning summary: off", `/stats meds` with the medication and its strip, a water entry and a
mood check-in with its image; nothing was quarantined and no outbox row died; the key file
opened every sealed file id (6), the medication name, its instructions and the meal words. A
scan of every column (594), a `pg_dump` and the WAL held none of the typed words, the first
name or a file id; 45 tables have forced row-level security and `dosegram_app` without a claim
saw no row. The rollback start of `34c4733` logged `schema 23`, answered `/readyz` with 200,
answered `/meds`, `/reminders`, `/stats` and a water entry on the fake wire, and stopped
cleanly. With a morning summary turned on by the head, `34c4733` behaved as the rollback
paragraph says, and `34c4733` over the step-1 backup restored into `dosegram_restore` logged
`schema 22`, answered `/readyz` with 200 and stopped cleanly. Not rehearsed: a real client, the
Telegram transport, the month image over live data (the seeded month is below the 14-day gate),
and renaming the restored database in place of changing `DATABASE_URL`.

## 18. Deploying `main` after the third integration of 2026-11-27 over the live V23 database (3d9e9c3 to the head, no migration)

The live bot runs `3d9e9c3` from `.live/run.sh` (long polling, `.live/wt-3d9e9c3`), and its
database is at V23. The head of `main` adds no migration: the migration files and
`scripts/frozen-migrations.sha256` are byte for byte those of `3d9e9c3`, and no environment
variable is added or made required. The commits since `3d9e9c3` are the fixes of the new-user
review (`d577750`: one dose count on the `/stats meds` strip, "no record" doses on `/today`,
Ukrainian counted nouns, the water offer after the goal, Europe/Kyiv suggested in Ukrainian
onboarding, "your records" as the sleep source), image round 5 (merge `aac9ee1`) and the Mini
App go-live rehearsal (merge `6f7fa3d`), and the fixes of the verification of the integration:
the weekly summary preset named "Monday at 09:00", the day it is sent, in place of "Sunday
evening"; "Your latest mood check-in" for a gallery of one; and a report's card or album always
sent before its text (the morning summary's text once arrived first). Every step is the owner's;
`<sha>` is the head of `main` at or after the commit that adds the rehearsal record below, and
`<pg>` is `dosegram-live-pg`:

1. Back up, with the bot still running:
   `umask 077; docker exec -u postgres <pg> pg_dump -U dosegram -d dosegram_v2 -Fc > <off-host dir>/dosegram-v23-$(date -u +%F).dump`.
2. `stat -c %a .live/purge-list` prints `600`, or the file does not exist. Note the number of
   quarantined updates: `SELECT count(*) FROM inbound_inbox WHERE status = 'quarantined';`.
3. Build the release next to the running one:
   `git worktree add --detach .live/wt-<sha> <sha>`, then `(cd .live/wt-<sha> && ./mill app.assembly)`.
4. Stop the running bot (Ctrl-C or SIGTERM to its `java` process; the log ends with
   `shutdown: pool closed`). Start the new release with the same script:
   `LIVE_WT="$PWD/.live/wt-<sha>" .live/run.sh`. Its log shows the 23 `sealing:` lines of section
   16 with `sealed 0`, `migrate: 0 migration(s) applied, schema 23, 0 value(s) sealed, 0 key(s) rewrapped`,
   `purge: replayed 0 deletion(s) of the purge list` and the health listener on `127.0.0.1:18081`.
5. Check, read-only (`docker exec -u postgres <pg> psql -U dosegram -d dosegram_v2 -tA -c '...'`):
   `SELECT max(version::int) FROM flyway_schema_history WHERE success;` still returns 23; the
   quarantined count is the number of step 2. Then `curl -fsS http://127.0.0.1:18081/readyz`
   answers 200; in the chat `/mood history` lists the latest check-ins (with the mood consent)
   and its "Show as image" sends the gallery (with the images consent); `/stats meds` with
   "Show as image" counts the same answered doses in the text, the caption and the card.
6. Delete the old worktree only after a day of normal use: `git worktree remove .live/wt-3d9e9c3`.

What changes in the chat, none of it checked on a real client yet:

- With the images consent, the weekly report on Monday is an album of two: the week card, then
  the week highlights (IMG-25). It is still one unprompted image message.
- `/mood history` (also `/mood історія`) and its gallery (IMG-26, action id 474).
- Dark and discreet personal cards use the new Rye `#a86614` and Beet `#b45689` from their next
  render; light cards and the mood blobs are unchanged, so shared mood file ids stay valid.
- The fixes of `d577750` listed above.
- `/reminders` names the weekly summary "Monday at 09:00" ("Понеділок о 09:00"), the time it was
  always sent; a plan already on is unchanged (ADR-023 addendum of 2026-11-28).
- The morning summary, the weekly and the monthly report send their card or album first, then the
  text, every time: each image falls due a millisecond before the text, and a claimed batch goes
  out in the order its rows fell due.

The Mini App stays off: `.live/run.sh` keeps `export MINIAPP_ENABLED=false` until the owner
makes the one-time edit and the go-live steps of `docs/DEPLOY_MINIAPP.md` (DNS, a proxy from
`docker/proxy/`, the Mini App build per release, `MINI_APP_URL`, `scripts/miniapp-preflight.sh`,
then BotFather). That switch is independent of this deploy and can follow it on any later start.

Rollback: stop the bot and start `LIVE_WT="$PWD/.live/wt-3d9e9c3" .live/run.sh`. There is no
schema change, so no restore is needed; it logs `schema 23`. Buttons the head sent ("Show as
image" under `/mood history`, action id 474) may get no answer from `3d9e9c3`.

Rehearsed on 2026-11-27 against a throwaway `postgres:18-alpine` container on a free port, with
a key file (`KEK_FILE`, mode 600) and a purge-list path in a directory of mode 700,
`ENVIRONMENT=production`, as in section 17. `3d9e9c3`, started as `Main` starts with its
production assembly on the fake wire and resvg drawing, built a V23 database through the chat in
two starts (the first logged `migrate: 21 migration(s) applied, schema 23`): onboarding in
English, a medication with a name and instructions at 09:00 and 13:00, the 09:00 reminder tapped
Taken, water with the work-hours plan, mood four times, a night, a meal with words, the images
consent with the week image, "Water, 7 days", `/stats meds` and its strip, the record summary
export of 30 days, the weekly summary turned on from `/reminders`, the 13:00 reminder left open,
an add-medication form left after its first answer. Then steps 1 to 4 as written, with the
assemblies started by `java -jar out/app/assembly.dest/out.jar run` on the console adapter in
place of the token: `3d9e9c3` started and stopped (`/readyz` 200, SIGTERM, `shutdown: pool
closed`, exit 0); the `pg_dump -Fc` backup was written with mode 600; no purge list existed and
no update was quarantined; the head logged the 23 `sealing:` lines with `sealed 0`,
`migrate: 0 migration(s) applied, schema 23, 0 value(s) sealed, 0 key(s) rewrapped` and
`purge: replayed 0 deletion(s) of the purge list`, answered `/readyz` with 200 and stopped with
exit 0, twice. Step 5: the flyway maximum stayed 23 and the quarantined count 0. The head on the
fake wire over that database found one account and one identity, sent the catch-up digest of the
two doses that passed while no release ran, listed the sealed medication name with no onboarding
question, answered eleven buttons sent by `3d9e9c3` (Taken, "+ 200 ml", "7 days", the export file,
Undo, Skip, "30 days", "Pause for today", "Show as image" of the strip, "By medication", "Water,
7 days") with an edit, a toast or an image, answered `/privacy`, `/today` (the passed doses as
"no record (service outage)" with "I took it"), `/mood history` with the four seeded check-ins
and its gallery as a photo, `/stats meds` and its strip, `/reminders` with the weekly summary on,
a water entry and a mood prompt; stepped on to Monday 30 Nov 09:15, it sent the weekly report of
23 to 29 Nov as an album of two (the week card, then the week highlights) from the plan
`3d9e9c3` had turned on. Nothing was quarantined and no outbox row died; the key file opened
every sealed file id (8), the medication name, its instructions and the meal words. A scan of
every column (594), a `pg_dump` and the data directory with its WAL held none of the typed words,
the first name or a file id (the zone name, the positive control, was found); 45 tables have
forced row-level security and `dosegram_app` without a claim saw no row of `users`,
`medications` or `mood_checkins`. Rollback: `3d9e9c3` over the upgraded database logged
`migrate: 0 migration(s) applied, schema 23`, answered `/readyz` with 200, answered `/meds`,
`/mood`, `/reminders`, `/stats` and a water entry on the fake wire with nothing quarantined or
dead, and stopped cleanly; the head started again after it with the same lines. The verification
ran on `f53c4b3`; the head with the fixes above then started over the same database with the
same lines and answered `/meds`, `/mood`, `/reminders` ("Weekly summary: Monday at 09:00, next
Mon 09:00"), `/stats` and a water entry with nothing quarantined or dead. Not rehearsed: a
real client, the Telegram transport, the weekly album over live data, and the answer of
`3d9e9c3` to a gallery button (action id 474). The rehearsal's fake wire numbers messages from 1
in every run, so a mood prompt of the head reused a seeded prompt's number and its tap was
answered "Already recorded"; Telegram never reuses a message id in a chat.

## 19. Deploying `main` with V24 over the live V23 database (3d9e9c3 to the head)

Section 18 deploys `b1c73da` and no migration. The head after it adds V24 (`c01d700`) and three
copy and chat fixes: the messages of one answer reach the chat in their order even when one must
be retried (`outbox_messages.follows`, ARCHITECTURE section 10.17); the Ukrainian
add-medication summary shows "1000 МО" as `/meds` does (`e6cb71c`); a Taken, Skip, Snooze, Undo
or correction tapped on a reminder card leaves only the edited card, with no short message
beside it (`f331220`); `/history` says "not confirmed" for a missed dose, and the Mini App's dose
charts say "still to answer" (`c360ba6`). No environment variable is added or made required.

V24 is one `ALTER TABLE outbox_messages ADD COLUMN follows uuid`: nullable, no default, so
PostgreSQL changes only the catalogue and rewrites no row. Every step is the owner's; `<sha>` is
the head of `main` and `<pg>` is `dosegram-live-pg`:

1. Steps 1 to 3 of section 18 (backup, purge-list mode and quarantined count, the worktree and
   `./mill app.assembly` of `<sha>`); the backup file is the V23 restore point.
2. Stop the running bot and start `LIVE_WT="$PWD/.live/wt-<sha>" .live/run.sh`. Its log shows the
   `sealing:` lines with `sealed 0`, then `migrate: 1 migration(s) applied, schema 24, 0 value(s)
   sealed, 0 key(s) rewrapped`.
3. Check, read-only: `SELECT max(version::int) FROM flyway_schema_history WHERE success;` returns
   24; `SELECT count(*) FROM outbox_messages WHERE follows IS NOT NULL AND status IN ('queued',
   'sending', 'failed_retry');` falls back to 0 within a minute of the last answer; the quarantined
   count is that of step 1; `curl -fsS http://127.0.0.1:18081/readyz` answers 200. In the chat:
   `/stats meds` with "Show as image" sends the strip pages in order, and a Skip on a reminder
   card edits that card and sends nothing else.

Rollback without a restore: stop the bot and start the previous release. Flyway ignores an
applied migration that the older release does not have, the older code does not write
`follows`, and its claim does not read it, so rows queued by the head are sent in the older
claim's order. Restore the backup of step 1 only if V23 itself must come back.

Not rehearsed: this section was written from the code and the tests (`OutboxDispatcherSuite`,
`ReplyOrderSuite`, `SealingBackfillPgSuite` and `RlsSuite` upgrade to schema 24); no throwaway
copy of a V23 database was upgraded and rolled back, and nothing ran on a real client or the
Telegram transport.

## 20. V26 of analytics round 6: the "What's new" note (supervisor decision, 2026-11-28)

Analytics round 6 (branch `feat/analytics-6`) adds `V26__whats_new_notice.sql`: two columns on
`users`, `whats_new_seen integer` (added with the default 0, which fills every existing row, then
the default is dropped, so a later account starts empty) and `whats_new_enabled boolean NOT NULL
DEFAULT true`. Both are catalogue-only changes in PostgreSQL 11 and later: no row is rewritten.
No environment variable is added. After the deploy, the owner's account gets the note once, after
the answer to its next command (BOT_UX section 6.5); "Don't show these" turns it off.

Section 21 is the deploy of the head with V24, V25 and V26 together.

Ordering: the image track of the same round was assigned V25. Deploy V26 only together with V25
when that track added one, or with no V25 at all: Flyway refuses a V25 found later as older than
the applied V26 (`Detected resolved migration not applied to database`), and repairing that is
not allowed (ADR-002 addendum). The checks after the start, read-only:
`SELECT max(version::int) FROM flyway_schema_history WHERE success;` returns 26;
`SELECT count(*) FROM users WHERE whats_new_seen IS NULL;` returns 0 until a new account exists.

Rollback without a restore: the previous release ignores both columns. A restart of the head
after it sends nothing twice, because the account's `whats_new_seen` is already the release.

Not rehearsed: written from the code and `WhatsNewPgSuite`; no copy of the live database was
upgraded, and nothing was sent to a real client.

## 21. Deploying `main` after the integration of 2026-11-28 over the live V23 database (V24, V25 and V26 in one start)

This section replaces sections 19 and 20 as the owner's deploy: the head of `main` carries V24
(section 19), V25 (the animated mood setting, merge of `feat/animated-blob`) and V26 (the
"What's new" note, merge of `feat/analytics-6`), and one start applies the three in order. The
live database is at V23, so no applied version is newer than V25 and Flyway applies V25 before
V26 as usual. Do not deploy a release that carries V26 without V25 (section 20).

What the three migrations do, each a catalogue-only change that rewrites no row:

| Migration | Change |
|---|---|
| `V24__outbox_reply_order.sql` | `outbox_messages.follows uuid`, nullable |
| `V25__animated_mood_setting.sql` | `users.animated_mood boolean NOT NULL DEFAULT false` |
| `V26__whats_new_notice.sql` | `users.whats_new_seen integer` (0 for every existing row, then no default) and `users.whats_new_enabled boolean NOT NULL DEFAULT true` |

No environment variable is added or made required, and the live host needs no new binary: the
video encoder of the animated mood (JCodec 0.2.5, pure Java) is inside the assembly jar, and the
frames are drawn by the rasteriser the host already uses for the still cards (`RESVG_PATH`, or
`RENDER_WORKER_PATH` when set). A host without resvg can fetch the pinned 0.49.0 with
`scripts/fetch-resvg.sh out/resvg` (it checks the release checksum and also builds
`dosegram-raster` with cargo) and point `RESVG_PATH` at `out/resvg/resvg`. The Dockerfile is
unchanged.

Every step is the owner's; `<sha>` is the head of `main` at or after the commit that adds the
rehearsal record below, and `<pg>` is `dosegram-live-pg`. These are the steps the rehearsal of
2026-11-28 followed, in this order:

1. Back up, with the bot still running:
   `umask 077; docker exec -u postgres <pg> pg_dump -U dosegram -d dosegram_v2 -Fc > <off-host dir>/dosegram-v23-$(date -u +%F).dump`.
   The file has mode 600; it is the V23 restore point.
2. `stat -c %a .live/purge-list` prints `600`, or the file does not exist. Note the number of
   quarantined updates:
   `docker exec -u postgres <pg> psql -U dosegram -d dosegram_v2 -tA -c "SELECT count(*) FROM inbound_inbox WHERE status = 'quarantined';"`.
3. Build the release next to the running one:
   `git worktree add --detach .live/wt-<sha> <sha>`, then `(cd .live/wt-<sha> && ./mill app.assembly)`.
4. Stop the running bot (Ctrl-C or SIGTERM to its `java` process; the log ends with
   `shutdown: pool closed`), then start `LIVE_WT="$PWD/.live/wt-<sha>" .live/run.sh`. Its log
   shows the 23 `sealing:` lines with `sealed 0 value(s)`, then
   `migrate: 3 migration(s) applied, schema 26, 0 value(s) sealed, 0 key(s) rewrapped`,
   `purge: replayed 0 deletion(s) of the purge list` and the health listener on
   `127.0.0.1:18081`.
5. Check, read-only (`docker exec -u postgres <pg> psql -U dosegram -d dosegram_v2 -tA -c '...'`):
   - `SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version::int > 23;`
     returns `24,25,26`;
   - `SELECT count(*) FROM users WHERE whats_new_seen IS NULL;` returns 0;
   - `SELECT count(*) FROM users WHERE animated_mood;` returns 0;
   - the quarantined count is that of step 2, and `curl -fsS http://127.0.0.1:18081/readyz`
     answers 200.
6. In the chat: the answer to the next command (not `/start`) is followed once by the "What's
   new" note with "Don't show these" (BOT_UX section 6.5), and the command after it by no note;
   `/stats year` lists the year per area; its "Show as image" sends the one-year card (IMG-30)
   after the images consent; `/stats meds` with "Show as image" sends the strip pages in order
   (V24); a mood check-in is still answered with the still mood card, because "Animated mood" is
   off for every account and can be turned on only from the Mini App settings, which are off on
   live.
7. Delete the old worktree only after a day of normal use.

Rollback without a restore: stop the bot and start the previous release,
`LIVE_WT="$PWD/.live/wt-b1c73da" .live/run.sh`. It logs
`migrate: 0 migration(s) applied, schema 26` (Flyway reports the applied schema, and accepts
migrations newer than the release), and it ignores the three columns: the older code neither
writes `follows`, `animated_mood` or the two note columns nor reads them. A restart of the head
after a rollback sends the note no second time, because `whats_new_seen` is already the release.
Restore the backup of step 1 only if V23 itself must come back.

Rehearsed on 2026-11-28 against a throwaway `postgres:18-alpine` container on a free port, with a
key file (`KEK_FILE`, mode 600) and a purge-list path in a directory of mode 700, and
`ENVIRONMENT=production`. `b1c73da`, assembled as `Main` assembles it (settings from the
environment, `SchemaUpgrade` with the key, the purge-list replay, `Launch.start`) with the
Telegram adapter on the fake wire, a stepped clock and resvg 0.49.0 drawing, built a V23 database
through the chat; its first start logged `migrate: 21 migration(s) applied, schema 23`. Two
accounts: one in English (Europe/London) with onboarding, two medications with a name, a dose and
instructions at 09:00, 13:00 and 20:00, water with the work-hours plan, mood, a night, a meal with
words, the 09:00 reminder tapped Taken, the 13:00 one snoozed and then skipped, the images consent
with the week image, "Water, 7 days", the `/mood history` gallery, the morning summary turned on,
`/stats meds` and its strip, an export, the 20:00 dose left open and an add-medication form left
after its first answer; one in Ukrainian (Europe/Kyiv) with water and mood. Then steps 1 to 5 as
written, with the assemblies started by `java -jar out/app/assembly.dest/out.jar run` on the
console adapter in place of `.live/run.sh` and the token: `b1c73da` started (`/readyz` 200); the
backup was written with mode 600 while it ran; no purge list existed and no update was
quarantined; SIGTERM ended it with `shutdown: pool closed`. The head logged the 23 `sealing:`
lines with `sealed 0 value(s)`, `migrate: 3 migration(s) applied, schema 26, 0 value(s) sealed,
0 key(s) rewrapped` and `purge: replayed 0 deletion(s) of the purge list`, answered `/readyz`
with 200, and the five checks of step 5 returned `24,25,26`, 0, 0, 0 and 200; it stopped cleanly
and a second start logged `migrate: 0 migration(s) applied, schema 26`. Step 6, on the fake wire:
the head found the English account with no onboarding question, sent the catch-up digest of the
doses that passed while no release ran, listed both sealed medication names in `/meds` and
followed that answer once with the "What's new" note; `/today` and every later command had no
note, and `/whatsnew` showed it again. `/stats year` answered with the year and its card as a
photo captioned with its text equivalent, `/stats meds` with its strip, a mood check-in with the
still card, and the next morning's summary came at 08:03, its card before its text. Buttons sent
by `b1c73da` were answered: Taken on the open 20:00 dose ("When did you take it?"), "+ 200 ml" on
a closed water reminder (a toast), "7 days" and "14 days" (edits), "Show as image" of the strip
and "Water, 7 days" (images), the expired export file ("This file has expired") and Undo past its
window. Nothing was quarantined and no outbox row died. 45 tables have forced row-level security,
and `dosegram_app` without a claim saw no row of `users`, `medications`, `mood_checkins` or
`rendered_messages`. Rollback: `b1c73da` over the upgraded database logged
`migrate: 0 migration(s) applied, schema 26`, answered `/readyz` with 200, answered `/meds`, a
mood check-in (with its card), `/reminders`, `/stats`, a water entry and `/today` on the fake wire
with nothing quarantined or dead, and stopped cleanly; the head then started again with the same
lines, answered `/meds`, `/stats year` and `/today`, and sent no second note. The rehearsal ran
on `53714c1`; the commits after it change only the Mini App's default night (MINI_APP
MA-SLEEP-6a) and these documents, so the assembly the owner builds starts the same way.

Found by the rehearsal, not caused by this head (supervisor decision, 2026-11-28: recorded, not
fixed in this deploy): a scan of every column (598), a `pg_dump` and the data directory with its
WAL found the medication names in plaintext in `rendered_messages.choice_map`, the stored button
labels of `/meds` ("Pause · <name>"), written by `b1c73da` as much as by the head; the live
database holds the owner's medication names there today. The instructions, the meal words, the
unfinished form's answer, the first names and the file ids were found nowhere, and the zone name
(the positive control) was found. Sealing or dropping those labels is a G2.4b follow-up with its
own migration and backfill. Also seen, also older than this head: the morning summary's text
put a dose of Monday 20:00 that a late catch-up marked missed on Saturday into Saturday's doses
("0 of 2 taken · not confirmed 2"), while its card counted Saturday's one scheduled dose.

Not rehearsed: a real client, the Telegram transport, the animated mood in a chat (a stepped
clock makes the report worker claim the request after its deadline, so it sent the text; the
loops themselves were encoded by `MotionRenderer` with the pinned worker and decoded by ffmpeg as
H.264, 512 by 512, 60 frames, 3 seconds), and the weekly album over live data.

## 22. Deploying V27 (sealed button labels and card pages) over the live V26 database

V27 (`V27__sealed_labels_and_card_pages.sql`) closes the finding of section 21: the button labels
kept with each sent message (`rendered_messages.choice_map`, among them the `/meds` toggles
"Pause · <name>") and the pages of an image waiting to be drawn (`report_requests.params`: its
SVG, its text equivalent and its caption, which can name a medication) were plain JSON. V27 adds
`choice_map_enc` and `params_enc` next to them; the start applies V27 and right after it seals each
value the previous release left, emptying its plaintext in the same statement
(`SealingStage.Labels`), then vacuums the two tables. A finished request keeps no pages: V27 empties
the `{}` that finished requests kept, and the release empties both columns when it finishes one.
No new environment variable, no new binary. Supervisor decision, 2026-11-28: the labels are sealed
with the row's account key (or the `transport` key for an onboarding message sent before the
account existed), rather than rebuilt at render, so a typed label of an older message still
resolves. Every step is the owner's; `<pg>` is `dosegram-live-pg`:

1. Back up as in step 1 of section 21 (`dosegram-v26-$(date -u +%F).dump`, mode 600). It is the
   V26 restore point.
2. Build and start the head as in steps 3 and 4 of section 21. Its log shows 25 `sealing:` lines;
   those of `rendered_messages.choice_map` and `report_requests.params` say how many values were
   sealed, then `sealing: vacuumed rendered_messages` (with `report_requests` when a request was
   waiting) and `migrate: 1 migration(s) applied, schema 27`. The head also counts every dose on
   its scheduled day (ADR-012 addendum of 2026-11-28): its first start logs
   `rollups: queued N day(s) stored by an earlier version` and rebuilds the stored daily figures;
   a later start logs no such line.
3. Check, read-only: `SELECT count(*) FROM rendered_messages WHERE choice_map IS NOT NULL;` and
   `SELECT count(*) FROM report_requests WHERE params IS NOT NULL;` return 0, and `/readyz`
   answers 200. In the chat, `/meds` lists the medications with their named toggles.
4. Clear the older WAL as in section 14 ("The WAL after sealing"): the WAL written before this
   start and the sealing's full-page images still hold the labels.

Rollback without a restore: wait until
`SELECT count(*) FROM report_requests WHERE status IN ('queued', 'rendering');` returns 0 (the
previous release cannot read a sealed request), stop the bot and start the previous release. It
logs `migrate: 0 migration(s) applied, schema 27` and writes plaintext labels again, which the
next start of the head seals; until then a typed number or label on a message the head sent does
not resolve. Restore the backup of step 1 only if V26 itself must come back.

## 23. Deploying `main` after the integration of 2026-11-28 over the live V26 database (V27, the settings hub and mood stickers)

This section replaces section 22 as the owner's deploy: the head of `main` carries V27 (section
22), the chat settings hub (merge of `feat/chat-settings-hub`) and the mood stickers with the
deadline of an animated answer (merge of `feat/mood-stickers`). Neither track added a migration,
so V27 is the only one; V28 and V29 stay unused. No environment variable is added or made
required, no binary is added and the Dockerfile is unchanged. Every step is the owner's;
`<sha>` is the head of `main` at or after the commit that adds the rehearsal record below, and
`<pg>` is `dosegram-live-pg`. These are the steps the rehearsal of 2026-11-28 (second round)
followed, in this order:

1. Back up, with the bot still running:
   `umask 077; docker exec -u postgres <pg> pg_dump -U dosegram -d dosegram_v2 -Fc > <off-host dir>/dosegram-v26-$(date -u +%F).dump`.
   The file has mode 600; it is the V26 restore point.
2. `stat -c %a .live/purge-list` prints `600`, or the file does not exist. Note the number of
   quarantined updates:
   `docker exec -u postgres <pg> psql -U dosegram -d dosegram_v2 -tA -c "SELECT count(*) FROM inbound_inbox WHERE status = 'quarantined';"`.
3. Build the release next to the running one:
   `git worktree add --detach .live/wt-<sha> <sha>`, then `(cd .live/wt-<sha> && ./mill app.assembly)`.
4. Stop the running bot (Ctrl-C or SIGTERM to its `java` process; the log ends with
   `shutdown: pool closed`), then start `LIVE_WT="$PWD/.live/wt-<sha>" .live/run.sh`. Its log
   shows 25 `sealing:` lines; those of `rendered_messages.choice_map` and `report_requests.params`
   say how many values were sealed (every kept button label; a waiting image request only if one
   was queued when the bot stopped), then `sealing: vacuumed rendered_messages` (with
   `, report_requests` when a request was sealed),
   `migrate: 1 migration(s) applied, schema 27, <n> value(s) sealed, 0 key(s) rewrapped`,
   `purge: replayed 0 deletion(s) of the purge list`,
   `rollups: queued <n> day(s) stored by an earlier version` (the daily figures rebuilt under the
   ADR-012 addendum of 2026-11-28) and the health listener on `127.0.0.1:18081`.
5. Check, read-only (`docker exec -u postgres <pg> psql -U dosegram -d dosegram_v2 -tA -c '...'`):
   - `SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version::int > 26;`
     returns `27`;
   - `SELECT count(*) FROM rendered_messages WHERE choice_map IS NOT NULL;` and
     `SELECT count(*) FROM report_requests WHERE params IS NOT NULL;` return 0;
   - the quarantined count is that of step 2, and `curl -fsS http://127.0.0.1:18081/readyz`
     answers 200.
6. In the chat:
   - `/meds` lists the medications with their named toggles ("Pause · <name>"), and the answer
     to this first command (not `/start`) is followed once by the "What's new" note of release
     2, which names `/settings` (BOT_UX section 6.5); the next command has no note, and
     `/whatsnew` shows it again. The command menu lists `/settings` (registered with
     `setMyCommands` at start-up);
   - `/settings` sends one card with the language, time zone, day, images and reports sections;
     a tap edits that card in place and its first line reads "Saved. X: new (was old)."; with the
     mood consent the card shows "Animated mood", the first chat switch for it (BOT_UX section
     17.1);
   - with "Animated mood" on and discreet mode off, a mood check-in is answered with the moving
     blob (`sendAnimation`); a late answer is the still card when its still is stored
     (ARCHITECTURE section 12.1);
   - `/mood stickers` after the images consent answers "Your mood stickers are ready" with the
     "Add the stickers" button, the link to the account's own set of five blob stickers,
     `mood_<16 hex>_by_<bot username>`; a second request updates the same set. Without the
     rasteriser it answers "Mood stickers are not available here." The name carries the bot's
     username, so a renamed bot cannot find or delete the sets made under the old name;
   - buttons sent by the previous release still answer ("Pause · <name>" on an old `/meds`
     list, Taken on an open dose, "7 days" and "Show as image" on an old `/stats meds`).
7. Clear the older WAL as in section 14 ("The WAL after sealing"): the WAL written before this
   start and the sealing's full-page images still hold the labels.
8. Delete the old worktree only after a day of normal use.

Audit behaviour changes with the settings hub: re-picking the same language or time zone, or
saving unchanged Mini App settings, writes no audit row, and changes to "Counts in reports" and the
"What's new" switch write rows with the codes `counts` and `whats_new`, which the existing
`changed_fields` check accepts.

Rollback without a restore: wait until
`SELECT count(*) FROM report_requests WHERE status IN ('queued', 'rendering');` returns 0 (the
previous release cannot read a sealed request), stop the bot and start
`LIVE_WT="$PWD/.live/wt-4a9b03a" .live/run.sh`. It logs
`migrate: 0 migration(s) applied, schema 27` and answers `/readyz` with 200. It writes plaintext
labels and pages again, which the next start of the head seals (its log then shows the sealed
counts and, because the previous release writes daily figures of the older version, the
`rollups: queued` line once more); until then a typed number or label on a message the head sent
does not resolve. A sticker set already made stays on Telegram until the account is deleted from
a release that has the stickers. Restore the backup of step 1 only if V26 itself must come back.

Rehearsed on 2026-11-28 (second round) against a throwaway `postgres:18-alpine` container on a
free port, with a key file (`KEK_FILE`, mode 600), a purge-list path in a directory of mode 700
and `ENVIRONMENT=production`. `4a9b03a`, assembled as `Main` assembles it (settings from the
environment, `SchemaUpgrade` with the key, the purge-list replay, `Launch.start`) with the
Telegram adapter on the fake wire, a stepped clock and resvg 0.49.0 drawing, built a V26 database
through the chat (`migrate: 24 migration(s) applied, schema 26`): two accounts, one in English
(Europe/London) with onboarding, two medications with a name, a dose and instructions, `/meds`
listing both ("Pause · <name>"), water with a plan, mood, a night, a meal with words, a dose
taken, one snoozed then skipped, the images consent with two cards, the mood gallery, the morning
summary, `/stats meds` and its strip, an export, a dose left open and an add-medication form left
after its first answer; one in Ukrainian (Europe/Kyiv) with water and mood. The database then held
41 plaintext label sets, 2 of them naming a medication, and one image request left `queued` by
halting the release right after its tap. Steps 1 to 5 as written, with the assemblies started by
`java -jar out/app/assembly.dest/out.jar run` on the console adapter in place of `.live/run.sh`:
`4a9b03a` answered `/readyz` with 200, the backup was written with mode 600 while it ran (a
`pg_restore` of it into a scratch database gave schema 26 and the 41 plaintext label sets), no
purge list existed, nothing was quarantined, and SIGTERM ended it with `shutdown: pool closed`.
The head (`067c3e5`) logged the 25 `sealing:` lines with `rendered_messages.choice_map sealed 41
value(s)` and `report_requests.params sealed 1 value(s)`, `sealing: vacuumed rendered_messages,
report_requests`, `migrate: 1 migration(s) applied, schema 27, 42 value(s) sealed, 0 key(s)
rewrapped`, `purge: replayed 0 deletion(s) of the purge list` and `rollups: queued 14 day(s)
stored by an earlier version`; the checks of step 5 returned `27`, 0, 0, 0 and 200. Every sealed
label set (41) opened through the release's own `PgRenderedMessageRepository` and key service,
two of them naming a medication. A scan of every column (600) and a `pg_dump`, as text and as
hex, found no medication name, instruction, unfinished form answer, meal words or first name; the
zone name (the positive control) was found. 45 tables have forced row-level security,
`rendered_messages` and `report_requests` among them, and `dosegram_app` without a claim saw no
row of `users`, `medications`, `mood_checkins`, `rendered_messages` or `report_requests`. Step 6,
on the fake wire: no onboarding question, the catch-up digest, both names in `/meds` followed
once by the release 2 note, no note after `/today`; `/settings` with "Animated mood" turned on
and off and "Counts" turned off, each edit "Saved. X: new (was old)."; `/mood stickers` created
`mood_<16 hex>_by_<bot username>` and answered with its link; `/stats year` with its card,
`/stats meds` with its strip, a mood check-in, a water entry, `/reminders`, `/whatsnew`, the next
morning's summary; and the old buttons of step 6 answered. Nothing was quarantined and no outbox
row died. A second start logged `migrate: 0 migration(s) applied, schema 27` and no `rollups:`
line, answered `/readyz` with 200 and stopped cleanly. Rollback: with no request waiting,
`4a9b03a` over the upgraded database logged `migrate: 0 migration(s) applied, schema 27`,
answered `/readyz` with 200, then on the fake wire `/meds`, a mood check-in with its card,
`/reminders`, `/stats`, a water entry and `/today`, with nothing quarantined or dead, and left one
more request waiting. The head's next start sealed what the previous release had written (8
label sets, 2 request parameters), every one opened again, its report worker opened the waiting
request it had sealed and sent it as its text (late), it logged `rollups: queued 13 day(s)` and it sent no second note. The final
scan of every column and the `pg_dump` again found no canary.

Not rehearsed: a real client, the Telegram transport, the WAL step, and `sendAnimation` in a
chat (the stepped clock made the report worker claim the check-in after its 9-second deadline,
so it sent the stored still card, as ARCHITECTURE section 12.1 says; `CaptureLaunchSuite` covers
the moving answer on the fake wire with a controlled clock). Found by the rehearsal and fixed in
the commit after it (supervisor decision, 2026-11-28, BOT_UX section 12): `/stats year` read
before the day's first answer counted today, whose only dose was still open, as a day with
records ("Days with records: 2 of 332" beside the card's "1 of 332"); a day whose only doses are
open no longer counts. That commit changes only that count, so the assembly the owner builds
starts the same way.

## 24. Deploying `main` with the leftovers of the verification of 2026-11-28 over the live V27 database

No migration (V28 stays unused), no environment variable, no binary and no Dockerfile change: the steps of
section 23 apply as written, except that the start logs `migrate: 0 migration(s) applied, schema 27` and no
`sealing:` line counts a value unless the bot was rolled back in between. What changes for the owner
(supervisor decisions, 2026-11-28):

- "Days with records" counts by one rule everywhere: `/stats month`, the monthly report and card, the Mini App
  month, the medication block's 14-day gate and the week's hit days leave out a day whose only doses have no
  record (an outage) or are still open, as `/stats year` already did; the month says "Not counted: N days whose
  only doses have no record (a service outage)" when it has such days (BOT_UX section 12). A month can therefore
  show fewer days with records, and a medication block that met its gate on outage days no longer does.
- An image answer the bot was down for is no longer deleted unsent an hour after it was asked: on the same local
  day it is sent at the next start as its text with "Sent late: you asked for this at HH:MM."; from an earlier
  day it is not sent and is counted in `dosegram_report_requests_dropped_total{template}`, its row finished
  `text_fallback` with `error_class` `Stale` (ARCHITECTURE section 12.1). Check after a start:
  `SELECT status, error_class, count(*) FROM report_requests GROUP BY 1, 2;` (codes only).
- Rollback safety from this release on: a later release's outbox rows of a kind this one cannot send stay
  `queued` with no attempt spent, no `dead` row and no owner alert, and a start after a rollback empties the
  `'{}'` the release before V27 leaves in finished requests instead of sealing it (its log line then ends with
  `emptied <n> finished`; ARCHITECTURE section 7.5). It does not make `4a9b03a` itself safe: rolled back to it,
  a `sticker_set_removal` row still goes `dead` after eight attempts and raises the alert, so the rollback of
  section 23 stays as written.

## 25. Deploying `main` after the integration of 2026-11-28, third round, over the live V27 database

This section replaces section 24 as the owner's deploy: the head of `main` carries the leftovers of the
verification of 2026-11-28 (section 24), the year and patterns in the Mini App (merge of `feat/miniapp-year`)
and images on time and checked (merge of `feat/images-on-time`). Neither track added a migration (V28, V29 and V30
stay unused), an environment variable, a binary or a Dockerfile change. The steps of section 23 apply as written
with `<sha>` the head of `main` at or after the commit that adds this section, except that the start logs
`migrate: 0 migration(s) applied, schema 27` and no `sealing:` line counts a value unless the bot was rolled back
in between. What changes for the owner (supervisor decisions, 2026-11-28):

- Everything of section 24 (one rule for "days with records", the late note of an image answer, the rollback
  safety of later outbox rows).
- A new worker fork, the report pre-render, runs at the reminder poll: for each enabled report plan due within 30
  minutes it draws the pages of the morning summary, the weekly album with its highlights and the monthly pages
  with the poster into `render_assets` (kept one hour), with the existing `RESVG_PATH` or `RENDER_WORKER_PATH`. At
  the send time nothing is drawn: a report sends its images only when every page was drawn ahead, else it goes as
  its text and each missing page counts in `dosegram_image_text_fallback_total{template}`. A report decided late
  after downtime is not drawn ahead and goes as text. Check after the next scheduled report:
  `curl -fsS http://127.0.0.1:18081/metrics | grep dosegram_image_text_fallback_total` (codes and counts only).
- The morning summary at any time: `/reminders summary 07:45` (read as leniently as every typed time) and a
  "Morning summary" button on the `/settings` card with eight times from 06:30 to 10:00 and "Turn off"; a time in
  the quiet hours is refused. A whole hour keeps the preset id `report.daily.HH`, another time is stored as
  `report.daily.HHMM`. Action ids 564 and 565 are new; buttons of the previous release still answer.
- Image changes from the greyscale check (VISUALS section 6.7): the late dose glyph has a spruce outline, a partial
  calendar cell an `ink3` outline, and the month's typical sleep times and water goal tick are drawn behind the
  bars.
- The Mini App (off on live) gains the Year tab, the IR-05 pattern card, the R5 domain colours and the "Counts in
  reports" and "What's new" switches; its settings version now covers both, so a session holding an old version
  gets one `412 version_conflict` and re-reads. Nothing of it is visible until `docs/DEPLOY_MINIAPP.md` is done.
- CI gains the job `image-review` (actions pinned by commit); its first run, on `aa3c6c0`, was green.

In the chat, besides step 6 of section 23: `/settings` shows "Morning summary"; picking 07:30 edits the card into
"Saved. Morning summary: 07:30 (was off)." (or the previous time), and `/reminders` lists the summary at that time.

Rollback without a restore: as in section 23, to `LIVE_WT="$PWD/.live/wt-cd8f39a" .live/run.sh`. That release shows
a stored `report.daily.HHMM` plan by its raw preset id in `/reminders`, but sends it at the stored time (the plan
is kept as its times, not as its id); it draws report cards at the send time again; the pre-render's rows in
`render_assets` are purged within the hour.

## 26. Deploying `main` after the verification of 2026-11-28, third round, over the live V27 database

This section replaces section 25 as the owner's deploy. The head of `main` at or after the commit that adds this
section carries everything of section 25 and two fixes of this verification: the health, webhook and Mini App
listeners give their connections SO_REUSEADDR, so a restart binds its port at once although the stopped process
closed kept-alive connections (`d749207`), and a test helper (`cc8058d`, no production change). There is no
migration (V28, V29 and V30 stay unused), no environment variable, no binary and no Dockerfile change. Every step is
the owner's; `<sha>` is that head and `<pg>` is `dosegram-live-pg`. These are the steps the rehearsal below followed,
in this order:

1. Back up, with the bot still running:
   `umask 077; docker exec -u postgres <pg> pg_dump -U dosegram -d dosegram_v2 -Fc > <off-host dir>/dosegram-v27-$(date -u +%F).dump`.
   The file has mode 600; it is the V27 restore point.
2. `stat -c %a .live/purge-list` prints `600`, or the file does not exist. Note the number of quarantined updates:
   `docker exec -u postgres <pg> psql -U dosegram -d dosegram_v2 -tA -c "SELECT count(*) FROM inbound_inbox WHERE status = 'quarantined';"`.
3. Build the release next to the running one:
   `git worktree add --detach .live/wt-<sha> <sha>`, then `(cd .live/wt-<sha> && ./mill app.assembly)`.
4. `ss -tn state established '( sport = :18081 )'` lists no connection. The running release (`cd8f39a`) does not
   have the fix: if a client keeps a connection to its health port open when it stops, the new start fails within
   the next minute with `bind(..) failed with error(-98): Address already in use` and exits. Requests made with
   `curl` close their own connection and leave nothing behind. If the start fails that way, wait 60 seconds and start
   again; nothing was changed by the failed start.
5. Stop the running bot (Ctrl-C or SIGTERM to its `java` process; the log ends with `shutdown: pool closed`), then
   start `LIVE_WT="$PWD/.live/wt-<sha>" .live/run.sh`. Its log shows 25 `sealing:` lines, each `sealed 0 value(s)`,
   `migrate: 0 migration(s) applied, schema 27, 0 value(s) sealed, 0 key(s) rewrapped`,
   `purge: replayed 0 deletion(s) of the purge list`, no `rollups:` line, and
   `/healthz, /readyz and /metrics listening on 127.0.0.1:18081`.
6. Check, read-only (`docker exec -u postgres <pg> psql -U dosegram -d dosegram_v2 -tA -c '...'`):
   - `SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success AND version::int > 26;`
     returns `27`;
   - the quarantined count is that of step 2, and `curl -fsS http://127.0.0.1:18081/readyz` answers 200;
   - `SELECT status, error_class, count(*) FROM report_requests GROUP BY 1, 2;` (codes only). An image request that
     waited while the bot was down is answered at the start as its text (`text_fallback`, `Late`), or dropped from an
     earlier day (`Stale`, section 24).
7. In the chat:
   - `/meds` lists the medications by name; no onboarding question;
   - `/settings` shows "Morning summary: HH:MM"; the button opens eight times from 06:30 to 10:00 and "Turn off";
     07:30 edits the card into "Saved. Morning summary: 07:30 (was HH:MM)."; 06:30 is refused with "06:30 is in
     your quiet hours (22:00–07:00), ...";
   - `/reminders summary 07:45` answers "Every day at 07:45 is on." and the hub lists "Morning summary: Every day at
     07:45"; `/reminders summary 23:30` is refused the same way;
   - buttons sent by the previous release still answer ("Pause · <name>" on an old `/meds` list, Taken on an old
     dose, "7 days" and "Show as image" on an old `/stats meds`).
8. In the 30 minutes before the next scheduled report,
   `SELECT count(*) FROM render_assets WHERE asset_key LIKE 'report.%';` rises above 0 (the pre-render). After it,
   `curl -fsS http://127.0.0.1:18081/metrics | grep dosegram_image_text_fallback_total` (codes and counts only)
   shows no new count for the report's templates when its images arrived. On a day with the weekly or monthly
   report the morning summary is sent without its card (VISUALS VA-PS-03), and that is not counted.
9. Clear the older WAL as in section 14 only if the bot was rolled back to `4a9b03a` since the deploy of V27.
10. Delete the old worktree only after a day of normal use.

Rollback without a restore: wait until
`SELECT count(*) FROM report_requests WHERE status IN ('queued', 'rendering');` returns 0, check step 4 again, stop
the bot and start `LIVE_WT="$PWD/.live/wt-cd8f39a" .live/run.sh`. It logs
`migrate: 0 migration(s) applied, schema 27` and answers `/readyz` with 200. `/reminders` shows a summary stored at
a time that is not a whole hour by its raw preset id ("Morning summary: report.daily.0745"), and still sends it at
that time, with its card drawn at the send time. Restore the backup of step 1 only if the database itself must go
back.

Rehearsed on 2026-11-28 (third round) against a throwaway `postgres:18-alpine` container on a free port, with a key
file (`KEK_FILE`, mode 600), a purge-list path in a directory of mode 700 and `ENVIRONMENT=production`. `cd8f39a`,
assembled as `Main` assembles it with the Telegram adapter on the fake wire, a stepped clock and resvg 0.49.0
drawing, built a V27 database through the chat (`migrate: 25 migration(s) applied, schema 27`): two accounts, one in
English (Europe/London) with onboarding, two medications with a name, a dose and instructions, water with a plan,
mood, a night, a meal with words, a dose taken, one snoozed then skipped, the images consent with cards, the mood
gallery, `/stats meds` with its strip, an export, the morning summary at 08:00 and the weekly report (Monday 09:00)
on, a dose left open and an add-medication form left after its first answer; one in Ukrainian (Europe/Kyiv) with
water and mood. Then, with the assemblies started by `java -jar out/app/assembly.dest/out.jar run` on the console
adapter in place of `.live/run.sh`: `cd8f39a` answered `/readyz` with 200, the backup of step 1 was written with mode
600 while it ran (a `pg_restore` of it into a scratch database gave schema 27, 2 users and 42 sealed label sets), no
purge list existed, nothing was quarantined, and SIGTERM ended it with `shutdown: pool closed`. One "Show as image"
request was left `rendering` by halting `cd8f39a` right after the tap. The head (`36e64a4`) logged
`migrate: 0 migration(s) applied, schema 27, 0 value(s) sealed, 0 key(s) rewrapped`, 25 `sealing:` lines with 0
values, no `rollups:` line, answered `/readyz` with 200, and answered the waiting request as its text
(`text_fallback`, `Late`); a second start did the same and stopped cleanly. On the fake wire, from Sunday 18:00 to
the next Tuesday: no onboarding question, both names in `/meds`, the catch-up digest, step 7 as written (the card
edit "Saved. Morning summary: 07:30 (was 08:00).", both refusals, "Every day at 07:45 is on."), a mood check-in with
its card, a water entry, and the four old buttons answered. Monday: the pre-render drew the summary's card before
07:45 and the summary went without it (the weekly report was due that day); before 09:00 it drew the week card and
the highlights, and the weekly report arrived at 09:00 as an album of 2 images (`sendMediaGroup`, both uploaded) with
its text. Tuesday: the card drawn before 07:45 arrived with the summary (`sendPhoto`). Every sealed label set (75)
opened through the release's own repository and key service, 3 of them naming a medication; nothing was
quarantined and no outbox row died. A scan of every column (600) and a `pg_dump`, as text and as hex, found no
medication name, instruction, unfinished form answer, meal words or first name; the zone name (the positive
control) was found. 45 tables have forced row-level security, and `dosegram_app` without a claim saw no row of
`users`, `medications`, `mood_checkins`, `rendered_messages`, `report_requests`, `hydration_entries` or
`sleep_episodes`. Rollback: `cd8f39a` over the upgraded database logged `migrate: 0 migration(s) applied, schema 27`,
answered `/readyz` with 200, then on the fake wire `/meds`, a mood check-in with its card, `/reminders` (the raw
`report.daily.0745`), `/stats`, a water entry and `/today`, and the next morning sent the 07:45 summary with its
card. The head again over that database: no sealed value, no second note, `/meds` with both names; a clean restart
answered `/readyz` with 200; the final scan of every column and the `pg_dump` again found no canary.

Found by the rehearsal and fixed in `d749207`: a start within a minute of a stop failed to bind the health port and
exited whenever a client had kept a connection to it open (the harness's own `/metrics` read did). The final head
(`d749207`), assembled the same way over the rehearsal database: with a kept-alive client on its health port,
stopped and started at once, it bound the port and answered `/readyz` with 200; started at once after `cd8f39a` was
stopped with a kept-alive client, it failed with "Address already in use" (step 4), and after `cd8f39a` was stopped
with only `curl` requests, it bound at once. Not rehearsed: a real client, the Telegram transport, the WAL step.

## 27. Deploying `main` after the fourth round of 2026-11-28 over the live V27 database

This section replaces section 26 as the owner's deploy. The head of `main` at or after the commit that adds this
section carries everything of section 26 and the fixes of the fourth round (supervisor decisions, 2026-11-28). There
is no migration (V28, V29 and V30 stay unused), no environment variable, no binary and no Dockerfile change: the steps
of section 26 apply as written with `<sha>` that head. What changes for the owner:

- `scripts/restore-drill.sh` creates, in its throwaway database, every role the dump's grants and policies name
  (`dosegram_app`, `dosegram_erasure`, `dosegram_worker` and the role that ran the migrations) before it restores;
  before, it stopped at `role "dosegram_worker" does not exist` on any backup taken at V14 or later, the live ones
  included. Nothing else of the drill changed.
- The pre-render draws nothing for a report that will go as its text: on a day with the weekly or monthly report,
  sent or due, the morning summary's card is no longer drawn (step 8 of section 26 then counts fewer
  `render_assets` rows before the summary). A weekly report after the day's monthly one is not drawn either.
- The pages drawn ahead are keyed `report.<16 hex>.<64 hex>`, the first part a digest of the account alone, and an
  account deletion deletes the account's pages drawn and not yet sent, and their upload handles, in its own
  transaction instead of leaving them to the hourly purge. A page the previous release drew in the 30 minutes before
  the restart has the old key and is not found: a report due within those 30 minutes goes once as its text, counted
  in `dosegram_image_text_fallback_total{template}`; the old rows are purged within the hour. The query of step 8,
  `asset_key LIKE 'report.%'`, still counts the new keys.
- The outbox claim takes only the ops this release knows, so after a rollback a newer release's row with a new op
  stays `queued` (no attempt, no `dead` row, no alert) instead of making every claim fail (ARCHITECTURE section 7.5).
- The month card's first page names the days whose only doses have no record, as the month text does:
  "(not counted: N outage days)" beside the days hero, and the full sentence in its text equivalent (VISUALS section
  6.6). A month without such days looks as before.
- A queued report picture whose bytes were purged while the bot was down is still sent as its text equivalent in
  its place, before the report's text (checked on the fake wire; no change in behaviour).

In the chat, besides step 7 of section 26: `/stats month` and the monthly report of a month with outage-only days
show the "Not counted" line, and its card the bracketed note. Rollback without a restore: as in section 26.

## 28. Deploying `main` after the integration of 2026-11-28, fourth round, over the live V27 database

This section replaces section 27 as the owner's deploy. The live bot runs `26b3f4a` (the deploy of section 26). The
head of `main` at or after the commit that adds this section carries everything of section 27 and two merged tracks:
two periods compared (`1a32faf`) and the native Mini App (`096b74f`). There is no migration (V28, V29 and V30 stay
unused), no new table, no environment variable, no setting, no binary and no Dockerfile change. Every step is the
owner's; `<sha>` is that head and `<pg>` is `dosegram-live-pg`.

1. Back up as in step 1 of section 26 (the V27 restore point), and note the quarantined count as in its step 2.
2. Build the release next to the running one as in step 3 of section 26:
   `git worktree add --detach .live/wt-<sha> <sha>`, then `(cd .live/wt-<sha> && ./mill app.assembly)`. The
   container image, if used instead, also carries the new Mini App bundle.
3. Stop `26b3f4a` as in step 5 of section 26 and start `LIVE_WT="$PWD/.live/wt-<sha>" .live/run.sh`. Step 4 of
   section 26 is not needed: `26b3f4a` already binds its ports with SO_REUSEADDR. The log shows what step 5 of
   section 26 lists: 25 `sealing:` lines with 0 values, `migrate: 0 migration(s) applied, schema 27, 0 value(s)
   sealed, 0 key(s) rewrapped`, `purge: replayed 0 deletion(s) of the purge list`, no `rollups:` line, and the
   health listener on `127.0.0.1:18081`.
4. Check, read-only, as in step 6 of section 26: no version above 27 in `flyway_schema_history`, the quarantined
   count unchanged, `/readyz` 200.
5. In the chat, besides steps 7 and 8 of section 26 and the changes listed in section 27:
   - `/stats compare` answers "Compare two periods" with last week and this week, one line per consented area, and
     ends with the sentence that it does not show a cause; "This month" sets this month beside the month before;
     "Pick two months" offers the current month and the eleven before it;
   - "Show as image" on it sends the card IMG-27 (with the images consent; without it the consent card, and after
     the consent the compare card), its text equivalent when resvg or the font pack is missing;
   - the 30-day `/stats` "Trends" card has a "Compare periods" button;
   - buttons sent by `26b3f4a` still answer (as in step 7 of section 26).
6. Nothing changes on the Mini App while it is off (`MINIAPP_ENABLED` unset): `GET /api/v1/insights/compare` and
   the new Mini App screens are reached only after `docs/DEPLOY_MINIAPP.md`.
7. Delete the old worktree only after a day of normal use.

Rollback without a restore: wait until
`SELECT count(*) FROM report_requests WHERE status IN ('queued', 'rendering');` returns 0, stop the bot and start
`LIVE_WT="$PWD/.live/wt-26b3f4a" .live/run.sh`. It logs `migrate: 0 migration(s) applied, schema 27` and answers
`/readyz` with 200. The compare buttons (action ids 576 to 578) sent by the head carry ids `26b3f4a` does not know
and are answered with the toast "This button no longer works. Open /today." (rehearsed). An images consent card
sent by the head for the compare card carries `Pending(5, code)` with a nonzero code; `26b3f4a` reads every kind 5
as the seven-day `/stats` card (`Pending.of`, by reading its code; not exercised), so its "Confirm" shows that card in
place of IMG-27. Restore the backup of step 1 only if the database itself must go back.

Rehearsed on 2026-11-28 (verification of the fourth-round integration, head `9ac3b88`) against a throwaway
`postgres:18-alpine` container on a free port, with a key file (`KEK_FILE`, mode 600) in a directory of mode 700, a
purge-list path there and `ENVIRONMENT=production`. `26b3f4a`, assembled as `Main` assembles it with the Telegram
adapter on the fake wire, a stepped clock and resvg 0.49.0 drawing, built a V27 database through the chat
(`migrate: 25 migration(s) applied, schema 27`): one account in English (Europe/London) with onboarding, two
medications with a name, a dose and instructions, water with a plan, mood, a night, a meal with words, a dose taken,
one snoozed then skipped, the images consent with cards, the mood gallery, `/stats meds` with its strip, an export,
the morning summary at 08:00 and the weekly report (Monday 09:00) on, an add-medication form left after its first
answer; one in Ukrainian (Europe/Kyiv) with water and mood. On Sunday and Monday morning `26b3f4a` drew the weekly
report's two pages ahead and sent the 08:00 summary as text; then, with its outbox held, it put the weekly report
(the album of 2 images and its text) and the 09:00 dose reminder in the queue at 09:00 and stopped with all three
queued. The steps above were then followed, with `java -jar out/app/assembly.dest/out.jar run` on the console adapter
in place of `.live/run.sh`:

1. `26b3f4a` answered `/readyz` with 200; the step-1 backup was written with mode 600 while it ran (a `pg_restore` of
   it into a scratch database gave schema 27, 2 users and the 3 queued rows); no purge list existed; nothing was
   quarantined; SIGTERM ended it with `shutdown: pool closed`.
2. The head logged 25 `sealing:` lines with 0 values, `migrate: 0 migration(s) applied, schema 27, 0 value(s) sealed,
   0 key(s) rewrapped`, `purge: replayed 0 deletion(s) of the purge list`, no `rollups:` line, and answered `/readyz`
   with 200; `flyway_schema_history` held no version above 27; a second start did the same and stopped cleanly.
3. On the fake wire from Monday 09:01: the queued weekly report arrived as an album of 2 images (`sendMediaGroup`,
   both uploaded) followed by its text, and the queued reminder; no onboarding question; `/meds` with both names;
   four buttons sent by `26b3f4a` answered (Taken, "7 days" and "Show as image" of `/stats meds`, "Pause for today"
   on a closed water reminder with "This reminder is already closed."); `/stats compare` as step 5 describes it, with
   "This month", "Pick two months" (Nov 2026 to Dec 2025, then the second month), the card IMG-27 as a photo
   captioned with its text, and "Compare periods" on the 30-day trends card. Tuesday: the summary's card was drawn
   before 08:00 and arrived with the summary (`sendPhoto`). No image went as its text, no outbox row died, nothing
   was quarantined.
4. Every sealed label set (76) opened through the head's own repository and key service, 3 of them naming a
   medication. A scan of every column (600) and a `pg_dump`, as text and as hex, found no medication name,
   instruction, unfinished form answer, meal words or first name; the zone name (the positive control) was found.
   45 tables have forced row-level security, and `dosegram_app` without a claim saw no row of `users`,
   `medications`, `mood_checkins`, `rendered_messages`, `report_requests`, `hydration_entries`, `sleep_episodes` or
   the account rows of `outbox_messages`.
5. Rollback: with no report request queued or rendering, `26b3f4a` over the upgraded database logged
   `migrate: 0 migration(s) applied, schema 27`, answered `/readyz` with 200, answered the three compare buttons the
   head had sent with the stale-button toast, then `/meds`, a mood check-in with its card, `/reminders`, `/stats`, a
   water entry and `/today`, and sent the next morning's 08:00 summary with its card.
6. The head again over that database: `/meds` with both names and `/stats compare`; a clean restart answered
   `/readyz` with 200; the final scan of every column and the `pg_dump` again found no canary.

Not rehearsed: a real client, the Telegram transport, the step-1 backup taken off the host.
