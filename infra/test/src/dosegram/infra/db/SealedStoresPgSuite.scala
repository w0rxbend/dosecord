package dosegram.infra.db

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.application.nutrition.LogMeal
import dosegram.core.application.nutrition.MealInput
import dosegram.core.application.nutrition.NutritionService
import dosegram.core.domain.EntrySource
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.ExportFile
import dosegram.core.ports.ExportKind
import dosegram.core.ports.ExportStatus
import dosegram.core.ports.IdSource
import dosegram.core.ports.NewExportJob
import dosegram.infra.crypto.DataKeys

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.random.RandomGenerator
import scala.language.implicitConversions

/** ROADMAP G2.4 and G2.4b on Testcontainers Postgres 18, from the production write paths of this release: a schedule
  * through `ScheduleLifecycle` (medication, revision and materialised doses), a meal through `NutritionService`, an
  * export file, a drawn image and a stored answer through their stores. A dump of the whole database holds none of the
  * canaries. The schema test keeps every text, JSON or byte column of a sealed table either sealed, a lookup hash, or
  * named here as not Class A (LB-06).
  */
class SealedStoresPgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-11-14T07:00:00Z")
  private lazy val fixtures = Fixtures(dataSource)

  test("a database dump after the production write paths holds no canary of a sealed store"):
    val clock = MutableClock(t0)
    val account = fixtures.medicationAccount("Europe/Kyiv")
    val rule = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
    fixtures.schedule(clock, account, "CanaryPill Retard", rule, ZoneId.of("Europe/Kyiv"),
      doseAmount = Some(BigDecimal(5)), doseUnit = Some("mg"), instructions = Some("canary-with-water"))
    val uow = TransactionalTracking.everyPurposeGranted(PgUnitOfWork(dataSource, clock, DataKeys.development))
    val words = MealText.of("canary-soup-words").toOption.flatten
    uow
      .transaction(NutritionService(clock, IdSource.uuidV7(RandomGenerator.getDefault))
        .log(_, LogMeal(account, MealInput(MealType.Lunch, words, None), EntrySource.Bot, "meal-1")))
      .fold(refusal => fail(s"refused: $refusal"), identity)
    val exportId = UUID.randomUUID()
    val plain = PgUnitOfWork(dataSource, clock, DataKeys.development)
    plain.transaction: tx =>
      tx.exports.create(NewExportJob(exportId, account, ExportKind.FullArchive, None, ExportStatus.Queued, t0))
      tx.exports.complete(exportId, ExportFile("e.zip", "application/zip", "canary-zip".getBytes(UTF_8)), t0,
        t0.plusSeconds(600))
    PgRenderAssets(dataSource, DataKeys.development).put("card.dump", "canary-png".getBytes(UTF_8), t0)
    val answers = PgIdempotencyKeys(dataSource, DataKeys.development)
    val key = UUID.randomUUID()
    assert(answers.reserve(account, key, t0))
    answers.complete(account, key, IdempotencyOutcome("h", """{"name":"canary-answer"}"""), t0)

    plain.transaction: tx =>
      assertEquals(tx.medications.listForAccount(account).map(_.name), List("CanaryPill Retard"))
      assertEquals(tx.exports.file(account, exportId, t0).map(b => String(b.bytes, UTF_8)), Some("canary-zip"))
    val result = pg.container.execInContainer("pg_dump", "-U", pg.username, "-d", pg.databaseName, "--no-owner")
    assertEquals(result.getExitCode, 0, result.getStderr)
    val dumped = result.getStdout.toLowerCase
    List("canarypill", "canary-with-water", "canary-soup-words", "canary-zip", "canary-png", "canary-answer")
      .foreach(canary => assert(!dumped.contains(canary), s"the dump holds '$canary'"))

  test("every text, JSON or byte column of a sealed table is sealed, a lookup hash, or named as not Class A"):
    val sealedTables = SealedColumns.all.map(_.table).toSet
    val columns = withConnection: conn =>
      given Connection = conn
      given RowMapper[(String, String)] = rs => (rs.getString(1), rs.getString(2))
      sql"""SELECT table_name::text, column_name::text FROM information_schema.columns
            WHERE table_schema = 'public' AND data_type IN ('text', 'jsonb', 'json', 'bytea', 'character varying')"""
        .query[(String, String)]()
        .filter((table, _) => sealedTables(table))
    val sealedOrLookup = SealedColumns.all.flatMap: column =>
      List(column.table -> column.plain, column.table -> column.encrypted) ++
        column.lookup.map(spec => column.table -> spec.column)
    val unclassified = columns.toSet -- sealedOrLookup -- SealedStoresPgSuite.NotClassA.toList.flatMap((table, names) =>
      names.map(table -> _))
    assertEquals(unclassified, Set.empty[(String, String)], "seal the column (SealedColumns, a new migration) or list it")

object SealedStoresPgSuite:

  /** The text, JSON and byte columns of the sealed tables that hold no Class A content: codes, zones, keys, hashes. */
  val NotClassA: Map[String, Set[String]] = Map(
    // name_norm is generated from name and empty since V11. dose_unit is the unit of the strength: sealing it is a
    // step of G2.4b that is not done (ARCHITECTURE section 15.2, "As built").
    "medications" -> Set("name_norm", "dose_unit", "status"),
    "schedule_revisions" -> Set("tz", "rule", "reminder_policy", "created_by", "reason"),
    "dose_occurrences" -> Set("origin", "slot_key", "tz", "dst_kind", "cancel_reason", "unknown_reason", "missed_reason"),
    "dose_actions" -> Set("actor_type", "vendor", "platform_message_id", "interaction_id", "idempotency_key",
      "reason_code", "correlation_id", "metadata"),
    "meals" -> Set("tz", "source", "client_key", "external_ref_hmac", "meal_type", "void_reason"),
    "export_jobs" -> Set("kind", "params", "status", "file_name", "mime"),
    "render_assets" -> Set("asset_key", "mime"),
    "idempotency_keys" -> Set("request_hash")
  )
