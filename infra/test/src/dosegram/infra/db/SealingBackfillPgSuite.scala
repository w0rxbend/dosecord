package dosegram.infra.db

import com.zaxxer.hikari.HikariDataSource
import dosegram.contracts.HhMm
import dosegram.contracts.PlatformIdentity
import dosegram.core.domain.DoseSnapshot
import dosegram.core.domain.ReminderPolicy
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.contracts.Weekday
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.NewSchedule
import dosegram.core.ports.SealBroken
import dosegram.infra.LogFormat
import dosegram.infra.Telemetry
import dosegram.infra.crypto.DataKeys
import dosegram.infra.crypto.Kek
import dosegram.infra.crypto.KekRing
import org.flywaydb.core.Flyway
import scribe.Level
import scribe.LogRecord
import scribe.Logger
import scribe.output.LogOutput
import scribe.output.format.OutputFormat
import scribe.writer.Writer
import upickle.default.write

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

/** ROADMAP G2.4 and G2.4b on Testcontainers Postgres 18: the backfill of the owner's live database. Each test starts
  * from an empty database at V9, the schema the live database is at, and seeds it the way the V9 release wrote it: the
  * account through the production identity and account repositories, the schedule through the production schedule
  * repository, and every Class A row with the statement of the V9 release (commit 7d890c3), because no release after
  * V9 writes a plaintext column. Then [[SchemaUpgrade]] runs as `dosegram migrate` and every start run it.
  */
class SealingBackfillPgSuite extends PgSuite:

  override protected def migrateOnStart: Boolean = false

  private val t0 = Instant.parse("2026-11-14T09:00:00Z")
  private val day = LocalDate.parse("2026-11-14")
  private val zone = ZoneId.of("Europe/Kyiv")
  private val kek = KekRing(Kek("live-2026", Array.tabulate[Byte](Kek.Bytes)(i => (i * 13 + 5).toByte)), Nil)
  private val kekText = Base64.getEncoder.encodeToString(Array.tabulate[Byte](Kek.Bytes)(i => (i * 13 + 5).toByte))

  // Canaries: each appears once in the seed and must appear nowhere afterwards.
  private val medicationName = "CanaryMedicationName Forte"
  private val instructions = "canary-instructions-after-food"
  private val doseNote = "canary-dose-note"
  private val mealWords = "canary meal words"
  private val otherWords = "canary other words"
  private val exportBytes = "canary-export-file-bytes".getBytes(UTF_8)
  private val renderBytes = "canary-render-bytes".getBytes(UTF_8)
  private val response = """{"name":"canary-idempotent-response"}"""
  private val canaries =
    List(medicationName, instructions, doseNote, mealWords, otherWords, String(exportBytes, UTF_8),
      String(renderBytes, UTF_8), "canary-idempotent-response")

  private var databases = 0

  /** An empty database of the suite's container, migrated to `version`. */
  private def databaseAt(version: String): (String, HikariDataSource) =
    databases += 1
    val name = s"live_$databases"
    withConnection(_.createStatement().execute(s"CREATE DATABASE $name"))
    val url = pg.jdbcUrl.replace(s"/${pg.databaseName}", s"/$name")
    val ds = Database.pooled(url, pg.username, pg.password)
    Flyway.configure().dataSource(ds).target(version).load().migrate()
    // This release's unit of work takes the worker role of V14 on every transaction, and the seeds use it to write
    // rows as an earlier release did, so the role is created first; roles belong to the cluster, not the database.
    withConnection(
      _.createStatement().execute(
        """DO $$ BEGIN
             IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dosegram_worker') THEN
               CREATE ROLE dosegram_worker NOLOGIN;
             END IF;
           END $$;
           GRANT dosegram_app TO dosegram_worker"""
      )
    )
    (name, ds)

  private final case class Seed(
      account: UUID,
      medication: UUID,
      schedule: UUID,
      occurrence: UUID,
      exportJob: UUID,
      idempotencyKey: UUID
  )

  /** The live database as the V9 release left it. */
  private def seedAtV9(ds: HikariDataSource, extraMeals: Int = 0): Seed =
    val uow = PgUnitOfWork(ds, MutableClock(t0), DataKeys(kek, MutableClock(t0)))
    val account = uow.transaction: tx =>
      val principal = tx.identities.resolve(PlatformIdentity("telegram", "7001", None), t0)
      tx.accounts.createAccount(principal.identityId, zone.getId, t0).uuid
    val medication = UUID.randomUUID()
    val schedule = UUID.randomUUID()
    val occurrence = UUID.randomUUID()
    val exportJob = UUID.randomUUID()
    val idempotencyKey = UUID.randomUUID()
    val snapshot = DoseSnapshot(medicationName, Some(BigDecimal(400)), Some("mg"), Some(instructions))
    val rule = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
    val conn = ds.getConnection
    try
      given Connection = conn
      // PgMedicationRepository.insert of the V9 release.
      sql"""INSERT INTO medications (id, user_id, name, dose_amount, dose_unit, instructions, status,
                                     created_at, updated_at)
            VALUES ($medication, $account, $medicationName, ${BigDecimal(400)}, ${"mg"},
                    $instructions, 'active', $t0, $t0)""".execute()
      PgScheduleRepository(conn).insert(NewSchedule(schedule, medication, account, "fixed_times", zone, true, day), t0)
      // PgScheduleRevisionRepository.append of the V9 release.
      sql"""INSERT INTO schedule_revisions
              (id, schedule_id, revision, effective_from, tz, rule, reminder_policy, dose_snapshot,
               created_by, reason, created_at)
            VALUES (${UUID.randomUUID()}, $schedule, 1, $t0, ${zone.getId},
                    ${Jsonb(write(rule))}, ${Jsonb(write(ReminderPolicy.Default))}, ${Jsonb(write(snapshot))},
                    'user', ${Option.empty[String]}, $t0)""".execute()
      sql"""INSERT INTO dose_occurrences
              (id, account_id, medication_id, schedule_id, revision, origin, local_date, slot_key, tz,
               scheduled_for, due_window_start, due_window_end, miss_deadline, status, next_action_at, dose_snapshot)
            VALUES ($occurrence, $account, $medication, $schedule, 1, 'scheduled', $day, 't0900', ${zone.getId},
                    $t0, $t0, ${t0.plusSeconds(3600)}, ${t0.plusSeconds(7200)}, 'pending'::occ_status,
                    $t0, ${Jsonb(write(snapshot))})""".execute()
      // PgDoseActionRepository.append of the V9 release, with a note.
      sql"""INSERT INTO dose_actions
              (id, occurrence_id, account_id, seq, action, actor_type, occurred_at, prior_status, new_status,
               note, correlation_id)
            VALUES (${UUID.randomUUID()}, $occurrence, $account, 1, 'reminder_sent'::dose_action, 'system', $t0,
                    'pending'::occ_status, 'due'::occ_status, $doseNote, 'c-1')""".execute()
      // PgMealRepository.insert of the V9 release: the same words twice, other words once.
      val words = List(mealWords, otherWords, mealWords) ++ List.fill(extraMeals)(otherWords)
      words.zipWithIndex.foreach: (text, i) =>
        sql"""INSERT INTO meals
                (id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, meal_type,
                 description, created_at)
              VALUES (${UUID.randomUUID()}, $account, ${t0.plusSeconds(60L * i)}, $day, ${zone.getId}, 'bot',
                      ${s"meal-$i"}, ${Option.empty[UUID]}, 'lunch', $text, $t0)""".execute()
      // PgExportRepository.create and complete of the V9 release.
      sql"""INSERT INTO export_jobs (id, account_id, kind, params, status, created_at, confirmed_at, file_name, mime,
                                     byte_size, content, ready_at, expires_at)
            VALUES ($exportJob, $account, 'full_zip', ${Jsonb("{}")}, 'ready', $t0, $t0, 'export.zip',
                    'application/zip', ${exportBytes.length}, $exportBytes, $t0, ${t0.plusSeconds(900)})""".execute()
      // PgRenderAssets.put and PgIdempotencyKeys.reserve and complete of the V9 release.
      sql"""INSERT INTO render_assets (asset_key, mime, byte_size, bytes, created_at, purge_after)
            VALUES ('card.canary', 'image/png', ${renderBytes.length}, $renderBytes, $t0,
                    ${t0.plusSeconds(3600)})""".execute()
      sql"""INSERT INTO idempotency_keys (account_id, key, request_hash, response_json, created_at)
            VALUES ($account, $idempotencyKey, 'hash-1', $response, $t0)""".execute()
    finally conn.close()
    Seed(account, medication, schedule, occurrence, exportJob, idempotencyKey)

  private def dump(database: String): String =
    val result = pg.container.execInContainer("pg_dump", "-U", pg.username, "-d", database, "--no-owner")
    assertEquals(result.getExitCode, 0, result.getStderr)
    result.getStdout

  /** The audit rows of the key events, as event type and actor. */
  private def keyEvents(ds: HikariDataSource): List[String] =
    val conn = ds.getConnection
    try
      given Connection = conn
      sql"""SELECT event_type || ' ' || actor_type || ':' || actor_ref FROM audit_log
            WHERE event_type LIKE 'key.%' ORDER BY occurred_at, id""".query[String]()
    finally conn.close()

  private def withConnectionOf[A](ds: HikariDataSource)(f: Connection => A): A =
    val conn = ds.getConnection
    try f(conn)
    finally conn.close()

  private def hex(bytes: Array[Byte]): String = HexFormat.of().formatHex(bytes)

  /** Every log line written at any level while `f` runs, library lines included. */
  private def logged[A](f: => A): (A, List[String]) =
    val lines = ConcurrentLinkedQueue[String]()
    val writer = new Writer:
      override def write(record: LogRecord, output: LogOutput, outputFormat: OutputFormat): Unit =
        lines.add(output.plainText)
    val previous = Logger.root
    Logger.root.clearHandlers().withMinimumLevel(Level.Trace)
      .withHandler(Telemetry.handler(LogFormat.Plain, writer, Level.Trace)).replace()
    try (f, lines.asScala.toList)
    finally previous.replace()

  test("the upgrade seals every value of the V9 database, empties the plaintext and the release reads it all back"):
    val (database, ds) = databaseAt("9")
    try
      val seed = seedAtV9(ds)
      val (result, lines) = logged(SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ()))
      assertEquals(result.schemaVersion, "22")
      assertEquals(result.migrationsExecuted, 11)
      assertEquals(result.valuesSealed, 1 + 1 + 2 + 1 + 3 + 1 + 1 + 1 + 1 + 1, "name, instructions, two snapshots, the note, three meals, the file, the image, the answer, the dose unit, the export's parameters")
      assertEquals(keyEvents(ds), List("key.backfill.completed system:migrate"), "the backfill is audited once V12 exists")

      val uow = PgUnitOfWork(ds, MutableClock(t0), DataKeys(kek, MutableClock(t0)))
      uow.transaction: tx =>
        val medication = tx.medications.get(seed.medication).getOrElse(fail("medication"))
        assertEquals(medication.name, medicationName)
        assertEquals(medication.instructions, Some(instructions))
        assertEquals(medication.doseUnit, Some("mg"))
        assertEquals(medication.nameNorm, "canarymedicationname forte")
        // Find-or-create still offers the update path (G2.4), and the picker still finds by prefix.
        assertEquals(tx.medications.findByNameNorm(seed.account, "canarymedicationname forte").map(_.id),
          Some(seed.medication))
        assertEquals(tx.medications.searchByNameNormPrefix(seed.account, "canarymed", 5).map(_.id),
          List(seed.medication))
        assertEquals(tx.revisions.latest(seed.schedule).map(_.doseSnapshot.medicationName), Some(medicationName))
        assertEquals(tx.occurrences.get(seed.occurrence).flatMap(_.doseSnapshot).flatMap(_.instructions),
          Some(instructions))
        assertEquals(tx.doseActions.listForOccurrence(seed.occurrence).flatMap(_.note), List(doseNote))
        assertEquals(tx.tracking.meals.listLive(seed.account, DateRange.single(day)).flatMap(_.text).map(_.value),
          List(mealWords, otherWords, mealWords))
        assertEquals(tx.tracking.meals.recentTexts(seed.account, MealType.Lunch, 5).map(_.value),
          List(mealWords, otherWords))
        assertEquals(tx.exports.file(seed.account, seed.exportJob, t0).map(_.bytes.toList), Some(exportBytes.toList))
      assertEquals(PgRenderAssets(ds, DataKeys(kek, MutableClock(t0))).load("card.canary", t0).map(_.toList),
        Some(renderBytes.toList))
      val stored = PgIdempotencyKeys(ds, DataKeys(kek, MutableClock(t0))).find(seed.account, seed.idempotencyKey)
      assertEquals(stored.flatMap(_.outcome).map(_.responseJson), Some(response))

      val dumped = dump(database)
      canaries.foreach(canary => assert(!dumped.contains(canary), s"the dump holds '$canary'"))
      List(exportBytes, renderBytes).foreach(bytes => assert(!dumped.contains(hex(bytes)), "the dump holds bytes"))
      val output = lines.mkString("\n")
      (kekText :: canaries).foreach(secret => assert(!output.contains(secret), s"a log line holds '$secret'"))
    finally ds.close()

  test("a start whose V12 fails after the backfill keeps V10, and the next start still records the backfill"):
    val (_, ds) = databaseAt("9")
    try
      seedAtV9(ds)
      // Something in the way of V12, which creates audit_log.
      val conn = ds.getConnection
      try conn.createStatement().execute("CREATE TABLE audit_log (in_the_way int)")
      finally conn.close()
      intercept[Exception](SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ()))
      assertEquals(Flyway.configure().dataSource(ds).load().info().current().getVersion.getVersion, "10",
        "V11 rolls back with V12")
      val again = ds.getConnection
      try again.createStatement().execute("DROP TABLE audit_log")
      finally again.close()
      val result = SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ())
      assertEquals((result.schemaVersion, result.valuesSealed), ("22", 2),
        "the first start sealed every V10 value; the dose unit and the export's parameters wait for V19 and V22, which only this start reaches")
      assertEquals(keyEvents(ds), List("key.backfill.completed system:migrate"))
    finally ds.close()

  test("the upgrade from V12 seals the queued messages and stored answers, keeping their chat and their images"):
    val (database, ds) = databaseAt("12")
    try
      val uow = PgUnitOfWork(ds, MutableClock(t0), DataKeys(kek, MutableClock(t0)))
      val account = uow.transaction: tx =>
        val principal = tx.identities.resolve(PlatformIdentity("telegram", "7002", None), t0)
        tx.accounts.createAccount(principal.identityId, zone.getId, t0).uuid
      val (reminder, onboarding) = (UUID.randomUUID(), UUID.randomUUID())
      val reminderPayload =
        s"""{"body":[{"s":"$medicationName"}],"attachment":{"assetKey":"card.canary-sent"},"dedupeKey":"r"}"""
      val onboardingPayload = s"""{"body":[{"s":"$instructions"}],"deliveryChat":{"chatId":"5550","vendor":"telegram"}}"""
      val (reply, onboardingReply) = (s"""{"toast":"$doseNote"}""", s"""{"toast":"$mealWords"}""")
      val conn = ds.getConnection
      try
        given Connection = conn
        // PgOutboxRepository.enqueue, PgInboundInbox.push and PgInboundEventRepository.complete of the V12 release.
        List((reminder, Some(account), reminderPayload), (onboarding, None, onboardingPayload)).foreach:
          (id, owner, payload) =>
            sql"""INSERT INTO outbox_messages (id, send_key, op, kind, vendor, account_id, payload, importance, status,
                                               next_attempt_at)
                  VALUES ($id, ${id.toString}, 'send', 'interaction_reply', 'telegram', $owner, ${Jsonb(payload)},
                          'interaction_reply', 'queued', $t0)""".execute()
        sql"""INSERT INTO inbound_inbox (vendor, vendor_event_id, chat_key, sequence, body, status, received_at,
                                         finished_at)
              VALUES ('telegram', 'telegram:update:2', '5550', 2, NULL, 'processed', $t0, $t0)""".execute()
        List(("telegram:update:1", Some(account), reply), ("telegram:update:2", None, onboardingReply)).foreach:
          (key, owner, answer) =>
            sql"""INSERT INTO inbound_events (vendor, vendor_event_id, event_id, account_id, received_at, reply,
                                              processed_at)
                  VALUES ('telegram', $key, ${UUID.randomUUID()}, $owner, $t0, ${Jsonb(answer)}, $t0)""".execute()
      finally conn.close()

      val lines = List.newBuilder[String]
      val result = SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), line => lines += line)
      assertEquals((result.schemaVersion, result.migrationsExecuted, result.valuesSealed), ("22", 8, 4))
      assert(lines.result().contains("sealing: vacuumed outbox_messages, inbound_events"), lines.result().toString)

      val check = ds.getConnection
      try
        given Connection = check
        assertEquals(
          sql"SELECT count(*) FROM outbox_messages WHERE payload IS NOT NULL OR payload_enc IS NULL".queryOne[Int](),
          Some(0)
        )
        assertEquals(
          sql"SELECT count(*) FROM inbound_events WHERE reply IS NOT NULL OR reply_enc IS NULL".queryOne[Int](),
          Some(0)
        )
        given RowMapper[(String, String)] = rs => (rs.getString(1), rs.getString(2))
        assertEquals(
          sql"""SELECT coalesce(chat_key, '-'), array_to_string(asset_keys, ',') FROM outbox_messages
                ORDER BY account_id NULLS LAST""".query[(String, String)](),
          List("-" -> "card.canary-sent", "5550" -> "")
        )
        assertEquals(
          sql"SELECT vendor_event_id, coalesce(chat_key, '-') FROM inbound_events ORDER BY 1".query[(String, String)](),
          List("telegram:update:1" -> "-", "telegram:update:2" -> "5550")
        )
        val keys = DataKeys(kek, MutableClock(t0)).on(check)
        given RowMapper[String] = rs =>
          PgOutboxRepository.payloadOf(keys, rs, rs.uuid("id"), Option(rs.getObject("account_id", classOf[UUID])))
        assertEquals(
          sql"""SELECT id, account_id, payload, payload_enc FROM outbox_messages
                ORDER BY account_id NULLS LAST""".query[String]().map(ujson.read(_)),
          List(reminderPayload, onboardingPayload).map(ujson.read(_))
        )
      finally check.close()
      val stored = PgUnitOfWork(ds, MutableClock(t0), DataKeys(kek, MutableClock(t0))).transaction: tx =>
        List("telegram:update:1", "telegram:update:2").flatMap(tx.inboundEvents.find("telegram", _)).flatMap(_.reply)
      assertEquals(stored.map(ujson.read(_)), List(reply, onboardingReply).map(ujson.read(_)))
      val dumped = dump(database)
      List(medicationName, instructions, doseNote, mealWords)
        .foreach(canary => assert(!dumped.contains(canary), s"the dump holds '$canary'"))
    finally ds.close()

  test("the upgrade from V18 seals the chat state, the event log, the inbox, a reminder and a dose unit"):
    val (database, ds) = databaseAt("18")
    try
      val keys = DataKeys(kek, MutableClock(t0))
      val uow = PgUnitOfWork(ds, MutableClock(t0), keys)
      val account = uow.transaction: tx =>
        val principal = tx.identities.resolve(PlatformIdentity("telegram", "7003", None), t0)
        tx.accounts.createAccount(principal.identityId, zone.getId, t0).uuid
      val (session, medication, reminder) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
      val (slot, onboardingSlot, event, onboardingEvent) =
        (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
      val displayName = "CanaryDisplayName"
      val draft = s"""{"name":"$medicationName"}"""
      val conn = ds.getConnection
      try
        given Connection = conn
        // The statements of the V18 release: every one of these columns took its value as plain text or JSON.
        sql"""INSERT INTO inbound_inbox (vendor, vendor_event_id, chat_key, sequence, body, received_at)
              VALUES ('telegram', 'telegram:update:3', '7003', 3,
                      ${s"""{"message":{"text":"$medicationName","from":{"first_name":"$displayName"}}}"""},
                      $t0)""".execute()
        sql"""INSERT INTO conversation_sessions (id, principal_key, vendor, chat_id, flow, step, data, last_prompt,
                                                 expires_at)
              VALUES ($session, ${account.toString}, 'telegram', '7003', 'medication.add', 'dose', ${Jsonb(draft)},
                      ${Jsonb(s"""{"text":"$instructions"}""")}, ${t0.plusSeconds(3600)})""".execute()
        sql"""INSERT INTO form_runs (session_id, form_id, answers)
              VALUES ($session, 'medication.add', ${Jsonb(draft)})""".execute()
        List((slot, Some(account)), (onboardingSlot, None)).foreach: (id, owner) =>
          sql"""INSERT INTO callback_slots (id, account_id, chat, payload)
                VALUES ($id, $owner, ${Jsonb("""{"chatId":"7003"}""")}, ${Jsonb(s"""{"words":"$doseNote"}""")})"""
            .execute()
        List((event, Some(account)), (onboardingEvent, None)).foreach: (id, owner) =>
          sql"""INSERT INTO domain_events (id, type, source, account_id, actor, occurred_at, data)
                VALUES ($id, 'dosegram.test.v1', 'test', $owner,
                        ${Jsonb(s"""{"vendorUsername":"$displayName"}""")}, $t0,
                        ${Jsonb(s"""{"data":{"name":"$medicationName"}}""")})""".execute()
        sql"""INSERT INTO reminders (id, account_id, title, next_due_at)
              VALUES ($reminder, $account, $mealWords, $t0)""".execute()
        val sealer = keys.on(conn)
        sql"""INSERT INTO medications (id, user_id, name_enc, name_hmac, dose_unit, status)
              VALUES ($medication, $account,
                      ${sealer.sealText(SealedColumns.MedicationName.forAccount(account, medication), "Plain")},
                      ${sealer.lookup(SealedColumns.MedicationName, account, "plain")}, $otherWords, 'active')"""
          .execute()
        sql"UPDATE users SET display_name = $displayName WHERE id = $account".execute()
        sql"UPDATE platform_identities SET vendor_username = $displayName WHERE user_id = $account".execute()
      finally conn.close()

      val scanned = List(medicationName, instructions, doseNote, mealWords, otherWords, displayName)
      val before = withConnectionOf(ds)(CanaryScan.columns(_, scanned)).map(hit => s"${hit.table}.${hit.column}").toSet
      assertEquals(
        before,
        Set("inbound_inbox.body", "conversation_sessions.data", "conversation_sessions.last_prompt", "form_runs.answers",
          "callback_slots.payload", "domain_events.data", "domain_events.actor", "reminders.title",
          "medications.dose_unit", "users.display_name", "platform_identities.vendor_username"),
        "the scan finds every plaintext the V18 release kept"
      )

      val lines = List.newBuilder[String]
      val result = SchemaUpgrade.run(ds, keys, line => lines += line)
      assertEquals((result.schemaVersion, result.migrationsExecuted), ("22", 2))
      assertEquals(result.valuesSealed, 1 + 2 + 1 + 2 + 4 + 1 + 1, "inbox, session, form, slots, events, reminder, unit")
      assert(lines.result().exists(_.startsWith("sealing: vacuumed ")), lines.result().toString)

      uow.transaction: tx =>
        assertEquals(tx.sessions.loadForUpdate(account.toString, "telegram", "7003").map(s => ujson.read(s.data)),
          Some(ujson.read(draft)))
        assertEquals(tx.formRuns.loadForUpdate(session).map(r => ujson.read(r.answers)), Some(ujson.read(draft)))
        assertEquals(List(slot, onboardingSlot).flatMap(tx.slots.loadForUpdate).map(s => ujson.read(s.payload)("words").str),
          List(doseNote, doseNote))
        assertEquals(tx.medications.get(medication).flatMap(_.doseUnit), Some(otherWords))
      assertEquals(PgInboundInbox(ds, keys).pending(10, 10).map(_.entry.body.contains(displayName)), List(true))
      val check = ds.getConnection
      try
        given Connection = check
        val opener = keys.on(check)
        given RowMapper[String] = rs => opener.openText(SealedColumns.ReminderTitle.forAccount(account, reminder),
          rs.getBytes(1))
        assertEquals(sql"SELECT title_enc FROM reminders".query[String](), List(mealWords))
        given RowMapper[(Option[UUID], UUID, Array[Byte])] = rs =>
          (Option(rs.getObject(1, classOf[UUID])), rs.uuid("id"), rs.getBytes(3))
        val events = sql"SELECT account_id, id, data_enc FROM domain_events ORDER BY account_id NULLS LAST"
          .query[(Option[UUID], UUID, Array[Byte])]()
        assertEquals(
          events.map((owner, id, bytes) => ujson.read(opener.openText(SealedColumns.EventData.forRow(owner, id), bytes))),
          List.fill(2)(ujson.read(s"""{"data":{"name":"$medicationName"}}"""))
        )
        assertEquals(CanaryScan.columns(check, scanned), Nil, "a column holds a canary")
      finally check.close()
      val dumped = dump(database)
      scanned.foreach(canary => assert(!dumped.contains(canary), s"the dump holds '$canary'"))
      val again = SchemaUpgrade.run(ds, keys, _ => ())
      assertEquals(again, UpgradeResult(0, "22", 0, 0), "a second start finds nothing to seal")
    finally ds.close()

  test("the upgrade from V19 seals the file ids and the export parameters, and the release reads both back"):
    val (database, ds) = databaseAt("19")
    try
      val keys = DataKeys(kek, MutableClock(t0))
      val uow = PgUnitOfWork(ds, MutableClock(t0), keys)
      val account = uow.transaction: tx =>
        val principal = tx.identities.resolve(PlatformIdentity("telegram", "7004", None), t0)
        tx.accounts.createAccount(principal.identityId, zone.getId, t0).uuid
      val (fileId, exportFileId) = ("AgACAgIAAxkBAAIBCanaryFileId", "BQACAgIAAxkBAAIBCanaryExportFileId")
      val summary = UUID.randomUUID()
      val (from, to) = (LocalDate.parse("1987-03-14"), LocalDate.parse("1987-04-12"))
      val conn = ds.getConnection
      try
        given Connection = conn
        // PgTelegramFileCache.put and PgExportRepository.create of the V19 release.
        List(("card.canary", "photo", fileId), (s"export.$summary", "document", exportFileId)).foreach:
          (asset, kind, id) =>
            sql"""INSERT INTO telegram_file_cache (asset_key, bot_id, media_kind, file_id, created_at, last_used_at)
                  VALUES ($asset, 7000000004, $kind, $id, $t0, $t0)""".execute()
        sql"""INSERT INTO export_jobs (id, account_id, kind, params, status, created_at)
              VALUES ($summary, $account, 'record_summary_pdf', ${Jsonb(s"""{"from":"$from","to":"$to"}""")},
                      'awaiting_confirmation', $t0)""".execute()
      finally conn.close()

      val scanned = List(fileId, exportFileId, from.toString, to.toString)
      val before = withConnectionOf(ds)(CanaryScan.columns(_, scanned)).map(hit => s"${hit.table}.${hit.column}").toSet
      assertEquals(before, Set("telegram_file_cache.file_id", "export_jobs.params"),
        "the scan finds every plaintext the V19 release kept")

      val lines = List.newBuilder[String]
      val result = SchemaUpgrade.run(ds, keys, line => lines += line)
      assertEquals((result.schemaVersion, result.migrationsExecuted, result.valuesSealed), ("22", 1, 3))
      assert(lines.result().contains("sealing: vacuumed telegram_file_cache, export_jobs"), lines.result().toString)

      val cache = PgTelegramFileCache(ds, 7000000004L, keys)
      assertEquals(cache.get("card.canary", "photo", t0), Some(fileId))
      assertEquals(cache.get(s"export.$summary", "document", t0), Some(exportFileId))
      assertEquals(uow.transaction(_.exports.find(account, summary)).flatMap(_.period),
        Some(DateRange.between(from, to)))
      val check = ds.getConnection
      try
        given Connection = check
        assertEquals(
          sql"SELECT count(*) FROM telegram_file_cache WHERE file_id IS NOT NULL OR file_id_enc IS NULL".queryOne[Int](),
          Some(0)
        )
        assertEquals(
          sql"SELECT count(*) FROM export_jobs WHERE params IS NOT NULL OR params_enc IS NULL".queryOne[Int](),
          Some(0)
        )
        assertEquals(CanaryScan.columns(check, scanned), Nil, "a column holds a canary")
        // A sealed file id moved to another asset's row does not open there (SEC-01).
        sql"""UPDATE telegram_file_cache SET file_id_enc = (SELECT file_id_enc FROM telegram_file_cache
                                                             WHERE asset_key = 'card.canary')
              WHERE asset_key = ${s"export.$summary"}""".execute()
      finally check.close()
      intercept[SealBroken](cache.get(s"export.$summary", "document", t0))
      val dumped = dump(database)
      scanned.foreach(canary => assert(!dumped.contains(canary), s"the dump holds '$canary'"))
      assertEquals(SchemaUpgrade.run(ds, keys, _ => ()), UpgradeResult(0, "22", 0, 0), "a second start seals nothing")
    finally ds.close()

  test("a second start finds nothing to seal and applies nothing"):
    val (_, ds) = databaseAt("9")
    try
      seedAtV9(ds)
      SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ())
      val again = SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ())
      assertEquals(again, UpgradeResult(0, "22", 0, 0))
      assertEquals(keyEvents(ds), List("key.backfill.completed system:migrate"), "a run that seals nothing is not audited")
    finally ds.close()

  test("a backfill that stops part-way keeps V10 and the plaintext; the next start resumes it"):
    val (_, ds) = databaseAt("9")
    try
      val seed = seedAtV9(ds, extraMeals = 2 * SealingBackfill.BatchRows)
      Flyway.configure().dataSource(ds).target("10").load().migrate()
      // The process dies right after the meals (the seventh column) were sealed and verified.
      var columns = 0
      intercept[IllegalStateException]:
        SealingBackfill(ds, DataKeys(kek, MutableClock(t0))).run(SealingStage.Expand, _ =>
          columns += 1
          if columns == 7 then throw IllegalStateException("the process stops"))
      val version = Flyway.configure().dataSource(ds).load().info().current().getVersion.getVersion
      assertEquals(version, "10")
      val result = SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ())
      assertEquals(result.schemaVersion, "22")
      assertEquals(result.valuesSealed, 1 + 1 + 1 + 1 + 1, "only the export, the image, the stored answer, the dose unit (V19) and the export's parameters (V22) were left")
      val meals = PgUnitOfWork(ds, MutableClock(t0), DataKeys(kek, MutableClock(t0)))
        .transaction(_.tracking.meals.listLive(seed.account, DateRange.single(day)))
      assertEquals(meals.size, 3 + 2 * SealingBackfill.BatchRows)
    finally ds.close()

  test("a value the previous release changed after a stopped run sealed it is sealed again from the plaintext"):
    val (_, ds) = databaseAt("9")
    try
      val seed = seedAtV9(ds)
      Flyway.configure().dataSource(ds).target("10").load().migrate()
      SealingBackfill(ds, DataKeys(kek, MutableClock(t0))).run(SealingStage.Expand, _ => ())
      // The V9 release, started again on V10, renames the medication (PgMedicationRepository.replace of V9).
      val conn = ds.getConnection
      try
        given Connection = conn
        sql"UPDATE medications SET name = 'Renamed Canary', updated_at = $t0 WHERE id = ${seed.medication}".execute()
      finally conn.close()
      val result = SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ())
      assertEquals(result.valuesSealed, 1 + 1 + 1, "the renamed medication, its dose unit at V19 and the export's parameters at V22")
      PgUnitOfWork(ds, MutableClock(t0), DataKeys(kek, MutableClock(t0))).transaction: tx =>
        assertEquals(tx.medications.get(seed.medication).map(_.name), Some("Renamed Canary"))
        assertEquals(tx.medications.findByNameNorm(seed.account, "renamed canary").map(_.id), Some(seed.medication))
        assertEquals(tx.medications.findByNameNorm(seed.account, "canarymedicationname forte"), None)
    finally ds.close()

  test("V11 refuses to empty a plaintext column whose values are not sealed, and changes nothing"):
    val (_, ds) = databaseAt("9")
    try
      seedAtV9(ds)
      Flyway.configure().dataSource(ds).target("10").load().migrate()
      val refused = intercept[Exception](Flyway.configure().dataSource(ds).load().migrate())
      assert(Iterator.iterate[Throwable](refused)(_.getCause).takeWhile(_ != null).exists(e =>
        Option(e.getMessage).exists(_.contains("not sealed yet"))), refused.toString)
      assertEquals(Flyway.configure().dataSource(ds).load().info().current().getVersion.getVersion, "10")
      val conn = ds.getConnection
      try
        given Connection = conn
        assertEquals(sql"SELECT name FROM medications".queryOne[String](), Some(medicationName))
      finally conn.close()
    finally ds.close()

  test("after V11 the schema refuses a plaintext value in every sealed column"):
    val (_, ds) = databaseAt("9")
    try
      val seed = seedAtV9(ds)
      SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ())
      val conn = ds.getConnection
      try
        given Connection = conn
        val e = intercept[SQLException]:
          sql"UPDATE medications SET name = 'plain' WHERE id = ${seed.medication}".execute()
        assert(e.getMessage.contains("medications_name_sealed"), e.getMessage)
        val constraints = sql"""SELECT conname::text FROM pg_constraint WHERE conname LIKE '%\_sealed'"""
          .query[String]()
          .toSet
        assertEquals(constraints, SealedColumns.all.map(c => s"${c.table}_${c.plain}_sealed").toSet)
      finally conn.close()
    finally ds.close()

  test("the upgrade refuses, naming the key id only, when the key-encryption key that wraps the data keys is gone"):
    val (_, ds) = databaseAt("9")
    try
      seedAtV9(ds)
      SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ())
      val stranger = KekRing(Kek("other", Array.fill[Byte](Kek.Bytes)(1)), Nil)
      val refused = intercept[IllegalStateException]:
        SchemaUpgrade.run(ds, DataKeys(stranger, MutableClock(t0)), _ => ())
      assert(refused.getMessage.contains("'live-2026'"), refused.getMessage)
      assert(!refused.getMessage.contains(kekText), refused.getMessage)
    finally ds.close()
