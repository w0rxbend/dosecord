# Deploying encryption at rest

Status: written on 2026-11-14 with ROADMAP G2.4 and G2.4b (supervisor decisions, owner asleep); merged to `main` with
the audit log (V12) the same day, so the head schema is 12. The whole deploy of that `main`, in order, is
[OPERATIONS.md](OPERATIONS.md) section 8. The steps were run
against Testcontainers databases seeded as the V9 release wrote them (`SealingBackfillPgSuite`), not against the
owner's live database. The design is ARCHITECTURE section 15.2 and its "As built" notes.

## 1. What the release changes

- Migration V10 adds `account_keys`, `service_keys` and a sealed column (`*_enc`) next to every Class A column that
  holds a value today: medication name, alias and instructions, the dose snapshots of revisions and doses, dose notes,
  meal words, the export file, the bytes of a drawn image and the stored answer of a creating request.
- At every start, and in `dosegram migrate`, the process takes the migration lock, applies V10, seals every value
  that is still only in plaintext (200 rows per transaction, each value opened again before it is written), compares
  every sealed copy with its plaintext, then applies V11. V11 refuses to run while a value is unsealed; it empties the
  plaintext columns and adds a CHECK that keeps them empty.
- A start that stops part-way leaves the schema at V10 with every plaintext value still in place. The next start
  resumes where it stopped.
- Production refuses to start without `KEK`.

## 2. The new setting

| Variable | Value | Note |
|---|---|---|
| `KEK_FILE` | path to a file holding `<id>:<base64 of 32 bytes>` | Or `KEK` with the same value; prefer the file. The id names the key in `account_keys.kek_id`: 1 to 32 of `A-Z a-z 0-9 _ -`, not `development` |

The file must be outside the database volume and outside every backup of it (ARCHITECTURE SEC-02): whoever holds a
backup and the key holds the data. Without the key the sealed data cannot be read by anyone, the owner included.

Outside production a missing `KEK` falls back to the development key, which is derived from a fixed label and is no
secret. A deployment that runs with `ENVIRONMENT=development` must still set `KEK_FILE`, or its data is sealed with
a key anyone can derive.

## 3. Steps for the live deploy

1. Make the key, on the host, as the user that runs the bot:

   ```sh
   umask 077
   printf 'kek-2026:%s\n' "$(openssl rand -base64 32)" > /path/outside/the/db/volume/dosegram-kek
   chmod 0400 /path/outside/the/db/volume/dosegram-kek
   ```

2. Make two offline copies of that file and keep them in two separate places (ROADMAP OA-9). Losing the key loses
   every sealed value for good.
3. Take a backup of the database (`pg_dump` of the live container) and keep it until step 8 has passed.
4. Stop the running bot. The previous release writes plaintext, which the database refuses after V11.
5. Add `KEK_FILE=/path/outside/the/db/volume/dosegram-kek` to the bot's environment, next to `DATABASE_URL`.
6. Optional, to watch the upgrade on its own: run `dosegram migrate` with the same environment. It prints one line per
   sealed column (`sealing: medications.name sealed N value(s), verified N`) and then
   `migrate: N migration(s) applied, schema 12, N value(s) sealed, 0 key(s) rewrapped`; from the live database at V8 that
   is 4 migrations (V9 to V12; V12 is the audit log of ROADMAP G2.16). No line carries a value.
7. Start the new release as usual. Its start runs the same step; on an upgraded database it finds nothing to do.
8. Check:
   - `SELECT max(version::int) FROM flyway_schema_history WHERE success;` returns 12.
   - `SELECT count(*) FROM medications WHERE name IS NOT NULL;` returns 0.
   - `/today` and the Mini App medication list show the names, `/food` shows recent words, and an export opens.

## 4. When something goes wrong

- The start stops before V11 (a value that does not seal, a database error): the schema is at V10 and every
  plaintext value is still there. The previous release runs against V10 unchanged, so starting it again is a
  rollback. Fix the cause and start the new release again; it resumes.
- The start stops after V11 with "is wrapped by key-encryption key '...', which is not configured": `KEK` names
  another key than the one the data keys were wrapped with. Put the right key file back.
- A rollback to the previous release after V11 is not possible: the plaintext columns are empty. Restore the backup
  of step 3 instead, which loses what was recorded since.

## 5. Rotating the key-encryption key

1. Make a new key file as in step 1 of section 3, with a new id.
2. Set `KEK` (or the file) to the new key first and the old one after it, comma-separated:
   `kek-2027:<new>,kek-2026:<old>`.
3. Restart. The start rewraps every data key that the old key wraps (`N key(s) rewrapped`).
4. Remove the old key from the setting, restart, and destroy the old key and its copies once a backup taken after
   step 3 exists.
