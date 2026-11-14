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
last 24 hours), each worker's last heartbeat and the age of the last reminder sent. Every query is
a `SELECT`. It exits 0 when every check is green, 1 when one is red, 2 when it cannot read the
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
6. Add to the bot's environment: `KEK_FILE=<path of step 1>` (also under
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
  'VACUUM (FULL, ANALYZE) outbox_messages, inbound_events, medications, schedule_revisions, dose_occurrences, dose_actions, meals, export_jobs, render_assets, idempotency_keys;'
```

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
