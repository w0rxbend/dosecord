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
(migrations newer than the backup are allowed). It ends with `restore-drill: PASS`. Not checked
yet: the replay of the purge list (G2.17) and the decryption of a canary account (G2.4b).

`scripts/test-ops-scripts.sh` runs the backup, the drill and the live smoke report end to end on
throwaway databases; CI runs it in the job `ops-scripts`.

## 6. A real restore

There is no automated procedure yet. Until the outbox reconciliation of OPS-01 exists, a restored
database may hold queued reminders for doses whose time has passed, so do not start the bot on it
unattended:

1. Stop the bot. Keep the old database volume; never drop it.
2. Restore the chosen backup into a new database with `pg_restore --no-owner`, after creating the
   roles `dosegram_app` and `dosegram_erasure`, as `scripts/restore-drill.sh` does.
3. Run `scripts/live-smoke.sh` against it and read the due outbox rows before the bot starts.
4. Start the bot on the new database; it applies newer migrations at start.

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
