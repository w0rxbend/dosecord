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
      assertEquals(result.schemaVersion, "12")
      assertEquals(result.migrationsExecuted, 3)
      assertEquals(result.valuesSealed, 1 + 1 + 2 + 1 + 3 + 1 + 1 + 1, "name, instructions, two snapshots, the note, three meals, the file, the image, the answer")

      val uow = PgUnitOfWork(ds, MutableClock(t0), DataKeys(kek, MutableClock(t0)))
      uow.transaction: tx =>
        val medication = tx.medications.get(seed.medication).getOrElse(fail("medication"))
        assertEquals(medication.name, medicationName)
        assertEquals(medication.instructions, Some(instructions))
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

  test("a second start finds nothing to seal and applies nothing"):
    val (_, ds) = databaseAt("9")
    try
      seedAtV9(ds)
      SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ())
      val again = SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ())
      assertEquals(again, UpgradeResult(0, "12", 0, 0))
    finally ds.close()

  test("a backfill that stops part-way keeps V10 and the plaintext; the next start resumes it"):
    val (_, ds) = databaseAt("9")
    try
      val seed = seedAtV9(ds, extraMeals = 2 * SealingBackfill.BatchRows)
      Flyway.configure().dataSource(ds).target("10").load().migrate()
      // The process dies right after the meals (the seventh column) were sealed and verified.
      var columns = 0
      intercept[IllegalStateException]:
        SealingBackfill(ds, DataKeys(kek, MutableClock(t0))).run: _ =>
          columns += 1
          if columns == 7 then throw IllegalStateException("the process stops")
      val version = Flyway.configure().dataSource(ds).load().info().current().getVersion.getVersion
      assertEquals(version, "10")
      val result = SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ())
      assertEquals(result.schemaVersion, "12")
      assertEquals(result.valuesSealed, 1 + 1 + 1, "only the export, the image and the stored answer were left")
      val meals = PgUnitOfWork(ds, MutableClock(t0), DataKeys(kek, MutableClock(t0)))
        .transaction(_.tracking.meals.listLive(seed.account, DateRange.single(day)))
      assertEquals(meals.size, 3 + 2 * SealingBackfill.BatchRows)
    finally ds.close()

  test("a value the previous release changed after a stopped run sealed it is sealed again from the plaintext"):
    val (_, ds) = databaseAt("9")
    try
      val seed = seedAtV9(ds)
      Flyway.configure().dataSource(ds).target("10").load().migrate()
      SealingBackfill(ds, DataKeys(kek, MutableClock(t0))).run(_ => ())
      // The V9 release, started again on V10, renames the medication (PgMedicationRepository.replace of V9).
      val conn = ds.getConnection
      try
        given Connection = conn
        sql"UPDATE medications SET name = 'Renamed Canary', updated_at = $t0 WHERE id = ${seed.medication}".execute()
      finally conn.close()
      val result = SchemaUpgrade.run(ds, DataKeys(kek, MutableClock(t0)), _ => ())
      assertEquals(result.valuesSealed, 1, "the renamed medication only")
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
