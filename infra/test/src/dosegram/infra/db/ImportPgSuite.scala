package dosegram.infra.db

import dosegram.core.application.hydration.LogWater
import dosegram.core.application.imports.ImportBatch
import dosegram.core.application.imports.ImportRefusal
import dosegram.core.application.imports.ImportService
import dosegram.core.application.sleep.EnterSleep
import dosegram.core.application.sleep.SleepInput
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.imports.DeviceRecord
import dosegram.core.domain.imports.DomainTally
import dosegram.core.domain.imports.ImportSource
import dosegram.core.domain.imports.RecordIdentity
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepOrigin
import dosegram.core.domain.sleep.SleepTier
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.DataDomain
import dosegram.core.ports.IdSource
import dosegram.core.ports.ImportJob
import dosegram.core.ports.ImportStatus
import dosegram.infra.crypto.DataKeys

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.random.RandomGenerator
import scala.language.implicitConversions

/** ROADMAP G10.5 and G10.7 on Testcontainers Postgres 18, through the product's code paths: an export of a fixture
  * account imported into an empty account gives the same records (AC-IMX-7); the same file twice gives no duplicate
  * (AC-IMX-5); undo voids exactly the import's entries (AC-IMX-6); the `import` consent and each domain's consent gate
  * what is stored; a device night overlapping the user's own is not merged into it; nothing of a record is kept in the
  * clear, and nothing staged outlives the commit, the cancel or a day.
  */
class ImportPgSuite extends PgSuite:

  private val now = Instant.parse("2026-11-12T18:00:00Z")
  private val zone = ZoneId.of("Europe/Kyiv")
  private lazy val fixtures = Fixtures(dataSource)
  private val clock = MutableClock(now)
  private lazy val uow = PgUnitOfWork(dataSource, clock, DataKeys.development)
  private val ids = IdSource.uuidV7(RandomGenerator.getDefault)
  private lazy val imports = ImportService(clock, ids)
  private val everything =
    List(ConsentPurpose.Import, ConsentPurpose.Hydration, ConsentPurpose.Mood, ConsentPurpose.Sleep, ConsentPurpose.Nutrition)
  private val tables = List("hydration_entries", "mood_checkins", "sleep_episodes", "meals")
  private val range = DateRange.between(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-30"))

  private def account(purposes: ConsentPurpose*): UUID =
    val id = fixtures.account(zone.getId)
    fixtures.grant(id, purposes*)
    id

  /** The export's CSV of each entry table of `account`, as the archive writes it. */
  private def exportedCsv(account: UUID): List[String] =
    withConnection: conn =>
      AccountArchive
        .read(conn, DataKeys.development.on(conn), account, zone)
        .filter(table => tables.contains(table.name))
        .map(ArchiveCsv.of)

  private def run[A](f: dosegram.core.ports.Tx => Either[ImportRefusal, A]): A =
    uow.transaction(f).fold(refusal => fail(s"refused: $refusal"), identity)

  private def importCsv(account: UUID, files: List[String]): ImportJob =
    val job = run(imports.start(_, account, ImportSource.DosegramCsv))
    files.foreach(file => run(imports.addBatch(_, account, job.id, ImportBatch.CsvRows(file, firstLine = 2))))
    run(imports.commit(_, account, job.id))

  /** The identities of the account's live entries, domain by domain, read through the domain repositories. */
  private def live(account: UUID): Map[DataDomain, Set[String]] =
    uow.transaction: tx =>
      Map(
        DataDomain.Hydration -> tx.tracking.hydration.listLive(account, range).map(RecordIdentity.of).toSet,
        DataDomain.Mood -> tx.tracking.mood.listLive(account, range).map(RecordIdentity.of).toSet,
        DataDomain.Sleep -> tx.tracking.sleep.liveEpisodes(account, range).map(RecordIdentity.of).toSet,
        DataDomain.Nutrition -> tx.tracking.meals.listLive(account, range).map(RecordIdentity.of).toSet
      )

  private def count(sql: String, account: UUID): Int =
    withConnection: conn =>
      val statement = conn.prepareStatement(sql)
      try
        statement.setObject(1, account)
        val rs = statement.executeQuery()
        rs.next()
        rs.getInt(1)
      finally statement.close()

  private def staging(account: UUID): Int = count("SELECT count(*) FROM import_staging WHERE account_id = ?", account)

  test("an export imported unchanged into an empty account gives the same records (AC-IMX-7, R-IMX-13)"):
    val source = ExportFixture(dataSource, now, zone).seed()
    val target = account(everything*)
    val job = importCsv(target, exportedCsv(source.account))
    assertEquals(job.status, ImportStatus.Committed)
    val expected = live(source.account)
    assertEquals(live(target), expected)
    assertEquals(expected(DataDomain.Hydration).size, ExportFixture.Days)
    assertEquals(expected(DataDomain.Nutrition).size, ExportFixture.Meals)
    assertEquals(job.counts.tally(DataDomain.Hydration).imported, ExportFixture.Days)
    assertEquals(job.counts.tally(DataDomain.Sleep).imported, ExportFixture.Days)
    // Imported entries carry their source, the job and the identity hash; the meal words are sealed, not plain.
    assertEquals(count("SELECT count(*) FROM meals WHERE account_id = ? AND source = 'import' AND import_job_id IS NOT NULL " +
      "AND external_ref_hmac IS NOT NULL AND description IS NULL", target), ExportFixture.Meals)
    val words = uow.transaction(_.tracking.meals.listLive(target, range)).flatMap(_.text.map(_.value)).distinct
    assertEquals(words, List(source.mealWords))
    val nights = uow.transaction(_.tracking.sleep.liveEpisodes(target, range))
    assert(nights.forall(n => n.source == EntrySource.Import && n.origin == SleepOrigin.ImportFile))
    assertEquals(staging(target), 0, "the commit empties the staging")

  test("the same file twice gives no duplicate, and undo voids exactly the import's entries (AC-IMX-5, AC-IMX-6)"):
    val source = ExportFixture(dataSource, now, zone).seed()
    val target = account(everything*)
    val tracking = TransactionalTracking(uow, clock, ids)
    tracking.water.log(LogWater(target, WaterAmount.ofMillilitres(333).toOption.get, EntryDay.Today, EntrySource.Bot, "own"))
    val files = exportedCsv(source.account)
    val first = importCsv(target, files)
    val afterFirst = live(target)
    val second = importCsv(target, files)
    assertEquals(live(target), afterFirst)
    assertEquals(second.counts.tally(DataDomain.Hydration), DomainTally(fresh = 0, duplicate = ExportFixture.Days))
    assertEquals(second.counts.domains.values.map(_.imported).sum, 0)

    val undone = run(imports.remove(_, target, first.id))
    assertEquals(undone.status, ImportStatus.Undone)
    assertEquals(live(target)(DataDomain.Hydration).size, 1, "the user's own drink stays")
    assertEquals(live(target).values.map(_.size).sum, 1)
    assertEquals(count("SELECT count(*) FROM hydration_entries WHERE account_id = ? AND void_reason = 'deleted'", target),
      ExportFixture.Days)
    assertEquals(live(source.account).values.map(_.size).sum, afterFirst.values.map(_.size).sum - 1)
    assertEquals(uow.transaction(imports.remove(_, target, first.id)), Left(ImportRefusal.AlreadyClosed))
    // After the undo the same records can be imported again.
    val third = importCsv(target, files)
    assertEquals(live(target), afterFirst)
    assertEquals(third.counts.tally(DataDomain.Hydration).imported, ExportFixture.Days)

  test("the import consent gates the import, and a record of a domain without its consent is counted, not stored"):
    val without = account(ConsentPurpose.Hydration)
    assertEquals(uow.transaction(imports.start(_, without, ImportSource.DosegramCsv)), Left(ImportRefusal.ConsentMissing))
    val partial = account(ConsentPurpose.Import, ConsentPurpose.Hydration)
    val job = run(imports.start(_, partial, ImportSource.DosegramCsv))
    val preview = run(imports.addBatch(_, partial, job.id, ImportBatch.CsvRows(
      "occurred_at,valence\n2026-11-10T20:00:00Z,4\n2026-11-11T20:00:00Z,3\n", firstLine = 2)))
    assertEquals(preview.counts.withoutConsent, 2)
    assertEquals(preview.counts.staged, 0)
    assertEquals(
      uow.transaction(imports.addBatch(_, partial, job.id, ImportBatch.DeviceRecords(Nil, 0, 1))),
      Left(ImportRefusal.WrongSource)
    )
    assertEquals(
      uow.transaction(imports.addBatch(_, partial, job.id, ImportBatch.CsvRows("name\nx\n", 2))),
      Left(ImportRefusal.UnknownColumns)
    )
    run(imports.commit(_, partial, job.id))
    assertEquals(count("SELECT count(*) FROM mood_checkins WHERE account_id = ?", partial), 0)

  test("device records: tier 3 nights kept apart from the user's own, ignored types counted, a re-import adds nothing"):
    val target = account(ConsentPurpose.Import, ConsentPurpose.Hydration, ConsentPurpose.Sleep)
    val tracking = TransactionalTracking(uow, clock, ids)
    val ownWake = Instant.parse("2026-11-10T05:00:00Z")
    tracking.uow.transaction(tracking.sleepService.enter(_, EnterSleep(SleepInput(target, EntrySource.Bot, "own-night"),
      SleepKind.Main, ownWake.minus(Duration.ofHours(8)), ownWake)))
    val batch = ImportBatch.DeviceRecords(
      List(
        DeviceRecord.Water(Instant.parse("2026-11-10T07:00:00Z"), 250),
        DeviceRecord.Water(Instant.parse("2026-11-10T07:00:00Z"), 250),
        DeviceRecord.Water(Instant.parse("2026-11-10T09:00:00Z"), 9000),
        DeviceRecord.Sleep(Instant.parse("2026-11-09T21:10:00Z"), Instant.parse("2026-11-10T04:50:00Z"), SleepKind.Main),
        DeviceRecord.Sleep(Instant.parse("2026-11-10T12:00:00Z"), Instant.parse("2026-11-10T12:30:00Z"), SleepKind.Nap),
        DeviceRecord.Sleep(Instant.parse("2026-11-10T21:00:00Z"), Instant.parse("2026-11-11T05:00:00Z"), SleepKind.Main)
      ),
      ignored = 5,
      firstIndex = 1
    )
    val job = run(imports.start(_, target, ImportSource.AppleHealth))
    val preview = run(imports.addBatch(_, target, job.id, batch))
    assertEquals(preview.counts.tally(DataDomain.Hydration), DomainTally(fresh = 1, duplicate = 1))
    assertEquals(preview.counts.tally(DataDomain.Sleep), DomainTally(fresh = 2, conflict = 1))
    assertEquals(preview.counts.invalid, 1)
    assertEquals(preview.counts.errors.map(e => (e.line, e.code)), List(3 -> "out_of_range"))
    assertEquals(preview.counts.ignored, 5)
    assertEquals(preview.counts.from, Some(LocalDate.parse("2026-11-10")))
    assertEquals(preview.counts.to, Some(LocalDate.parse("2026-11-11")))
    val committed = run(imports.commit(_, target, job.id))
    assertEquals(committed.counts.tally(DataDomain.Sleep).imported, 2)
    val nights = uow.transaction(_.tracking.sleep.liveEpisodes(target, range))
    assertEquals(nights.map(_.tier.number).sorted, List(SleepTier.Explicit.number, SleepTier.Sensor.number,
      SleepTier.Sensor.number))
    assertEquals(nights.count(_.source == EntrySource.Bot), 1, "the user's own night is unchanged")

    val again = run(imports.start(_, target, ImportSource.AppleHealth))
    run(imports.addBatch(_, target, again.id, batch))
    val repeated = run(imports.commit(_, target, again.id))
    assertEquals(repeated.counts.domains.values.map(_.imported).sum, 0)
    assertEquals(uow.transaction(_.tracking.sleep.liveEpisodes(target, range)).size, 3)

  test("staged records are sealed; cancel and the sweep empty the staging; commit and undo write audit rows"):
    val target = account(everything*)
    val job = run(imports.start(_, target, ImportSource.DosegramCsv))
    run(imports.addBatch(_, target, job.id, ImportBatch.CsvRows(
      "occurred_at,meal_type,description\n2026-11-10T10:00:00Z,lunch,canary-import-words\n", firstLine = 2)))
    assertEquals(staging(target), 1)
    val payload = withConnection: conn =>
      given Connection = conn
      given RowMapper[Array[Byte]] = _.getBytes(1)
      sql"SELECT payload_enc FROM import_staging WHERE account_id = $target".queryOne[Array[Byte]]().get
    assert(!String(payload, UTF_8).contains("canary"), "the staged record is sealed")
    run(imports.remove(_, target, job.id))
    assertEquals(staging(target), 0, "a cancel empties the staging")

    val abandoned = run(imports.start(_, target, ImportSource.DosegramCsv))
    run(imports.addBatch(_, target, abandoned.id, ImportBatch.CsvRows("occurred_at,amount_ml\n2026-11-10T10:00:00Z,200\n", 2)))
    clock.advance(Duration.ofHours(25))
    try
      assert(uow.transaction(imports.sweep) >= 1)
      assertEquals(staging(target), 0, "the sweep empties an import left open for a day")
      assertEquals(uow.transaction(imports.find(_, target, abandoned.id)).map(_.status), Right(ImportStatus.Cancelled))
    finally clock.at = now

    val committed = importCsv(target, List("occurred_at,amount_ml\n2026-11-10T10:00:00Z,200\n"))
    clock.advance(Duration.ofMinutes(1))
    try run(imports.remove(_, target, committed.id))
    finally clock.at = now
    val audit = withConnection: conn =>
      given Connection = conn
      given RowMapper[(String, String)] = rs => (rs.getString(1), rs.getString(2))
      sql"""SELECT event_type, r::text FROM audit_log AS r WHERE subject_ref = $target AND object_id = ${committed.id}
            ORDER BY occurred_at, id""".query[(String, String)]()
    assertEquals(audit.map(_._1), List("data.import.committed", "data.import.undone"))
    assert(audit.forall(!_._2.contains("canary")))
    assertEquals(uow.transaction(imports.recent(_, target)).map(_.status).toSet,
      Set(ImportStatus.Undone, ImportStatus.Cancelled))
end ImportPgSuite
