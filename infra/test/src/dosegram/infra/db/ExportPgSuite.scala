package dosegram.infra.db

import dosegram.infra.crypto.DataKeys
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentTaps
import dosegram.core.ports.ExportFile
import dosegram.core.ports.ExportKind
import dosegram.core.ports.ExportRepository
import dosegram.core.ports.ExportStatus
import dosegram.core.ports.NewExportJob
import dosegram.core.ports.TicketRedemption
import dosegram.core.ports.TicketRefusal

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipInputStream
import scala.language.implicitConversions

/** ROADMAP G3.10 on Testcontainers Postgres 18: the archive of a fixture account holds every record of every domain,
  * as JSON that matches its schema and as CSV; the export registry knows every table; a job lives 15 minutes after
  * ready; a download ticket is bound to one export, one address prefix and five requests.
  */
class ExportPgSuite extends PgSuite:

  private val now = Instant.parse("2026-11-12T18:00:00Z")
  private val zone = ZoneId.of("Europe/Kyiv")
  private lazy val fixtures = Fixtures(dataSource)

  private def uow(at: Instant): PgUnitOfWork = PgUnitOfWork(dataSource, () => at, DataKeys.development)

  private def unzip(bytes: Array[Byte]): Map[String, String] =
    val in = ZipInputStream(ByteArrayInputStream(bytes))
    try Iterator.continually(in.getNextEntry).takeWhile(_ != null).map(e => e.getName -> String(in.readAllBytes(), UTF_8)).toMap
    finally in.close()

  /** The rows of a covered table that belong to the account, counted straight from the table. */
  private def ownedRows(account: UUID, table: String): Int =
    withConnection: conn =>
      val select = ExportCoverage.tables(table) match
        case Coverage.Owned(column)   => s"SELECT count(*) FROM $table WHERE $column = ?"
        case Coverage.Selected(query) => s"SELECT count(*) FROM ($query) AS selected"
        case Coverage.Excluded(_)     => fail(s"$table is not exported")
      val statement = conn.prepareStatement(select)
      try
        statement.setObject(1, account)
        val rs = statement.executeQuery()
        rs.next()
        rs.getInt(1)
      finally statement.close()

  test("every table of the schema has an export decision: a new table without one fails here"):
    val tables = withConnection(AccountArchive.baseTables)
    val undecided = tables.collect { case (table, false) if !ExportCoverage.tables.contains(table) => table }
    assertEquals(undecided, Nil, "add each table to ExportCoverage.tables: Owned, Selected or Excluded with a reason")
    val stale = ExportCoverage.tables.keySet -- tables.map(_._1)
    assertEquals(stale, Set.empty[String], "the registry names tables the schema does not have")

  test("the archive of a fixture account holds every record of every covered table, and nothing of another account"):
    val fixture = ExportFixture(dataSource, now, zone).seed()
    val other = ExportFixture(dataSource, now, zone).seed()
    val file = uow(now).transaction(_.exports.archive(fixture.account, now))
    assertEquals(file.fileName, "dosegram-export-20261112.zip")
    assertEquals(file.mime, "application/zip")
    val entries = unzip(file.bytes)
    val document = ujson.read(entries("export.json"))
    assertEquals(document("format").str, "dosegram-export")
    assertEquals(document("timeZone").str, "Europe/Kyiv")
    val exported = document("tables").obj
    val covered = ExportCoverage.tables.collect { case (table, Coverage.Owned(_) | Coverage.Selected(_)) => table }
    covered.foreach: table =>
      val rows = exported.getOrElse(table, fail(s"$table is missing from export.json")).arr
      assertEquals(rows.size, ownedRows(fixture.account, table), s"rows of $table")
      val csvLines = entries(s"csv/$table.csv").split("\r\n").length
      assertEquals(csvLines, rows.size + 1, s"csv/$table.csv has a header and one line per row")
    List("medications", "dose_occurrences", "hydration_entries", "mood_checkins", "sleep_episodes", "meals",
      "consent_events").foreach(table => assert(exported(table).arr.nonEmpty, s"the fixture has $table"))
    assert(covered.toSet("meals"), "meals are declared in ExportCoverage")
    assert(entries("export.json").contains(fixture.mealWords), "meal words are included: the export is for the owner")
    assert(entries("export.json").contains(fixture.medicationName), "names are included: the export is for the owner")
    assert(!entries("export.json").contains(other.account.toString), "no row of another account")
    assert(entries("README.txt").contains("Recorded entries only. Not medical advice."))

  test("export.json matches the schema in the archive: every row has exactly the declared columns and types"):
    val fixture = ExportFixture(dataSource, now, zone).seed()
    val entries = unzip(uow(now).transaction(_.exports.archive(fixture.account, now)).bytes)
    val schema = ujson.read(entries("schema.json"))
    val tables = schema("properties")("tables")("properties").obj
    ujson.read(entries("export.json"))("tables").obj.foreach: (table, rows) =>
      val item = tables(table)("items")
      val declared = item("properties").obj
      rows.arr.foreach: row =>
        assertEquals(row.obj.keySet, declared.keySet, s"columns of $table")
        row.obj.foreach: (column, value) =>
          val allowed = declared(column).obj.get("type").map(_.arr.map(_.str).toSet)
          val actual = jsonType(value)
          // JSON Schema: an integer is a number.
          allowed.foreach(types => assert(types(actual) || (actual == "integer" && types("number")), s"$table.$column"))

  test("instants carry the offset of the account zone"):
    val fixture = ExportFixture(dataSource, now, zone).seed()
    val document = ujson.read(unzip(uow(now).transaction(_.exports.archive(fixture.account, now)).bytes)("export.json"))
    val createdAt = document("tables")("mood_checkins")(0)("occurred_at").str
    assert(createdAt.endsWith("+02:00"), createdAt)

  test("a job is ready for 15 minutes; then its bytes are gone and it reads expired"):
    val account = fixtures.account()
    val id = UUID.randomUUID()
    val file = ExportFile("x.zip", "application/zip", Array[Byte](1, 2, 3))
    uow(now).transaction: tx =>
      tx.exports.create(NewExportJob(id, account, ExportKind.FullArchive, None, ExportStatus.Queued, now))
      assertEquals(tx.exports.claimQueued().map(_.id), Some(id))
      tx.exports.complete(id, file, now, now.plus(ExportRepository.FileLifetime))
    val later = now.plus(Duration.ofMinutes(14))
    uow(later).transaction: tx =>
      assertEquals(tx.exports.file(account, id, later).map(_.bytes.toList), Some(List[Byte](1, 2, 3)))
      assertEquals(tx.exports.file(fixtures.account(), id, later), None, "another account never reaches it")
    val expiry = now.plus(Duration.ofMinutes(15))
    uow(expiry).transaction: tx =>
      assertEquals(tx.exports.expire(expiry, ExportRepository.ConfirmationWindow), 1)
      assertEquals(tx.exports.find(account, id).map(_.status), Some(ExportStatus.Expired))
      assertEquals(tx.exports.fileForDelivery(id, now), None, "the bytes are deleted")

  test("a request that waits for its confirmation ends after 10 minutes; a confirmed one is queued"):
    val account = fixtures.account()
    val waiting = UUID.randomUUID()
    val confirmed = UUID.randomUUID()
    uow(now).transaction: tx =>
      List(waiting, confirmed).foreach: id =>
        tx.exports.create(NewExportJob(id, account, ExportKind.FullArchive, None, ExportStatus.AwaitingConfirmation, now))
      assert(tx.exports.confirm(account, confirmed, now))
      assert(!tx.exports.confirm(account, confirmed, now), "a second confirmation does nothing")
    val later = now.plus(Duration.ofMinutes(10))
    uow(later).transaction: tx =>
      assertEquals(tx.exports.expire(later, ExportRepository.ConfirmationWindow), 1)
      assertEquals(tx.exports.find(account, waiting).map(_.status), Some(ExportStatus.Expired))
      assertEquals(tx.exports.find(account, confirmed).map(_.status), Some(ExportStatus.Queued))

  test("a ticket gives the file five times from the prefix of its first request, then nothing"):
    val account = fixtures.account()
    val id = UUID.randomUUID()
    val hash = Array.fill[Byte](32)(7)
    uow(now).transaction: tx =>
      tx.exports.create(NewExportJob(id, account, ExportKind.FullArchive, None, ExportStatus.Queued, now))
      tx.exports.claimQueued()
      tx.exports.complete(id, ExportFile("x.zip", "application/zip", Array[Byte](9)), now, now.plus(Duration.ofMinutes(15)))
      tx.exports.issueTicket(account, id, hash, now, now.plus(ExportRepository.TicketLifetime))
    def redeem(prefix: String, at: Instant = now): Either[TicketRefusal, List[Byte]] =
      uow(at).transaction(_.exports.redeem(TicketRedemption(hash, prefix), at)).map(_.bytes.toList)
    assertEquals(redeem("203.0.113"), Right(List[Byte](9)))
    assertEquals(redeem("198.51.100"), Left(TicketRefusal.OtherAddress))
    (2 to 5).foreach(_ => assertEquals(redeem("203.0.113"), Right(List[Byte](9))))
    assertEquals(redeem("203.0.113"), Left(TicketRefusal.RequestsSpent))
    assertEquals(uow(now).transaction(_.exports.find(account, id).map(_.status)), Some(ExportStatus.Downloaded))
    assertEquals(
      uow(now).transaction(_.exports.redeem(TicketRedemption(Array.fill[Byte](32)(8), "203.0.113"), now)),
      Left(TicketRefusal.Unknown)
    )

  test("a ticket expires 15 minutes after it was issued"):
    val account = fixtures.account()
    val id = UUID.randomUUID()
    val hash = Array.fill[Byte](32)(3)
    uow(now).transaction: tx =>
      tx.exports.create(NewExportJob(id, account, ExportKind.FullArchive, None, ExportStatus.Queued, now))
      tx.exports.claimQueued()
      tx.exports.complete(id, ExportFile("x.zip", "application/zip", Array[Byte](9)), now, now.plus(Duration.ofMinutes(15)))
      tx.exports.issueTicket(account, id, hash, now, now.plus(ExportRepository.TicketLifetime))
    val expiry = now.plus(ExportRepository.TicketLifetime)
    assertEquals(
      uow(expiry).transaction(_.exports.redeem(TicketRedemption(hash, "203.0.113"), expiry)),
      Left(TicketRefusal.Expired)
    )

  test("sending the file into the chat records the per-export consent with the export as its subject"):
    val account = fixtures.account()
    val id = UUID.randomUUID()
    uow(now).transaction: tx =>
      tx.exports.create(NewExportJob(id, account, ExportKind.FullArchive, None, ExportStatus.Queued, now))
      tx.exports.recordChatConsent(account, id, ConsentNotice(1, "warning", "en"), ConsentTaps(now, now))
    withConnection: conn =>
      given Connection = conn
      val subjects = sql"""SELECT subject_ref FROM consent_events
                           WHERE account_id = $account AND purpose = 'export_to_chat' AND action = 'granted'"""
        .query[UUID]()
      assertEquals(subjects, List(id))

  test("the daily limit counts the requests of the last 24 hours"):
    val account = fixtures.account()
    uow(now).transaction: tx =>
      List(now.minus(Duration.ofHours(25)), now.minus(Duration.ofHours(2)), now).foreach: at =>
        tx.exports.create(NewExportJob(UUID.randomUUID(), account, ExportKind.FullArchive, None, ExportStatus.Queued, at))
      assertEquals(tx.exports.requestedSince(account, now.minus(Duration.ofHours(24))).size, 2)

  private def jsonType(value: ujson.Value): String = value match
    case ujson.Null    => "null"
    case _: ujson.Str  => "string"
    case _: ujson.Bool => "boolean"
    case ujson.Num(n)  => if n.isWhole then "integer" else "number"
    case _: ujson.Arr  => "array"
    case _: ujson.Obj  => "object"
end ExportPgSuite
