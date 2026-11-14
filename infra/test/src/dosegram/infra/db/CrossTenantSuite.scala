package dosegram.infra.db

import dosegram.contracts.*
import dosegram.core.domain.EntrySource
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.VoidReason
import dosegram.core.domain.hydration.EnteredAmount
import dosegram.core.domain.hydration.HydrationEntry
import dosegram.core.domain.hydration.HydrationUnit
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.DateRange
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.MedicationEdit
import dosegram.core.ports.MedicationStatus
import dosegram.core.scheduling.CreateSchedule
import dosegram.core.scheduling.ScheduleLifecycle
import dosegram.infra.FilePurgeList

import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import ox.discard
import scala.language.implicitConversions

/** ROADMAP G2.3, ARCHITECTURE API-10 and SEC-08 on Testcontainers Postgres 18: two users onboarded through the chat
  * record in every domain as the product writes them. Then, acting as account A, no account-owned table and no
  * repository reads, changes or deletes a row of account B, and the application role without a claim reads no row at
  * all. The tables come from the catalogue, so a table added with its policy is covered without a change here.
  */
class CrossTenantSuite extends PgSuite:

  private final case class World(a: UUID, b: UUID)

  private lazy val world: World =
    val purge = FilePurgeList(Files.createTempFile("purge-list", ".txt"))
    ox.supervised:
      val clock = MutableClock(ProductChatRig.Start)
      def recorded(user: String): UUID =
        val rig = ProductChatRig(dataSource, clock, purge, user)
        val account = rig.onboard()
        rig.line("250 ml")
        rig.line("mood 4")
        rig.consent()
        rig.line("slept 23:30 7:10")
        rig.consent()
        rig.line("slept 23:30 7:10")
        rig.line("lunch: soup")
        rig.consent()
        rig.line("/meds")
        rig.consent()
        val everyDay = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
        ScheduleLifecycle(rig.uow, clock).create(CreateSchedule(account, s"Med $user", everyDay, ZoneId.of("Europe/Kyiv")))
        rig.line("/export")
        rig.tap("Export all your records", "Export my data")
        account
      World(recorded("ct-a"), recorded("ct-b"))

  /** Each account-owned table with the column that names its account; `schedule_revisions` names it through its
    * schedule and has none.
    */
  private def protectedTables: List[(String, Option[String])] =
    withConnection: conn =>
      given Connection = conn
      sql"""SELECT p.tablename,
                   (SELECT c.column_name FROM information_schema.columns c
                    WHERE c.table_schema = 'public' AND c.table_name = p.tablename
                      AND c.column_name IN ('account_id', 'user_id')
                    UNION ALL SELECT 'id' WHERE p.tablename = 'users' LIMIT 1)
            FROM pg_policies p
            WHERE p.schemaname = 'public' AND p.policyname = 'tenant_isolation'
            ORDER BY p.tablename"""
        .query[(String, Option[String])]()

  private given RowMapper[(String, Option[String])] = rs => (rs.getString(1), Option(rs.getString(2)))

  private def count(conn: Connection, query: String): Long =
    val rs = conn.createStatement().executeQuery(query)
    rs.next()
    rs.getLong(1)

  /** Rows of `table` naming `account`, read by the schema owner, which row-level security does not limit here. */
  private def ownerCount(table: String, column: String, account: UUID): Long =
    withConnection(count(_, s"SELECT count(*) FROM $table WHERE $column = '$account'"))

  /** Runs `statement` in a savepoint; the error message when Postgres refuses it, or the row count. */
  private def attempt(conn: Connection, statement: String): Either[String, Int] =
    val savepoint = conn.setSavepoint()
    try Right(conn.createStatement().executeUpdate(statement))
    catch
      case e: SQLException =>
        conn.rollback(savepoint)
        Left(e.getMessage)

  /** A transaction of the application role for `account`, exactly as the unit of work opens one, rolled back after. */
  private def actingAs[A](account: Option[UUID])(body: Connection => A): A =
    val conn = dataSource.getConnection
    try
      conn.setAutoCommit(false)
      account match
        case Some(id) => RowSecurity.asAccount(conn, id)
        case None     => conn.createStatement().execute("SET LOCAL ROLE dosegram_app")
      body(conn)
    finally
      conn.rollback()
      conn.close()

  test("account B has rows in the stores every domain writes, so each check below has something to hide"):
    val b = world.b
    List(
      "users" -> "id",
      "platform_identities" -> "user_id",
      "consent_events" -> "account_id",
      "medications" -> "user_id",
      "medication_schedules" -> "user_id",
      "dose_occurrences" -> "account_id",
      "hydration_entries" -> "account_id",
      "mood_checkins" -> "account_id",
      "sleep_episodes" -> "account_id",
      "meals" -> "account_id",
      "export_jobs" -> "account_id",
      "outbox_messages" -> "account_id",
      "inbound_events" -> "account_id",
      "account_keys" -> "account_id"
    ).foreach: (table, column) =>
      assert(ownerCount(table, column, b) > 0, s"$table holds no row of account B")

  test("as account A, every account-owned table yields only A's rows and changes or deletes no row of B"):
    val World(a, b) = world
    actingAs(Some(a)): conn =>
      protectedTables.foreach:
        case (table, Some(column)) =>
          val visible = count(conn, s"SELECT count(*) FROM $table")
          assertEquals(visible, ownerCount(table, column, a), s"$table shows A exactly A's rows")
          assertEquals(count(conn, s"SELECT count(*) FROM $table WHERE $column = '$b'"), 0L, table)
          assertEquals(attempt(conn, s"UPDATE $table SET $column = $column WHERE $column = '$b'").getOrElse(0), 0, table)
          assertEquals(attempt(conn, s"DELETE FROM $table WHERE $column = '$b'").getOrElse(0), 0, table)
          if visible > 0 && table != "users" then
            val moved = attempt(conn, s"UPDATE $table SET $column = '$b' WHERE $column = '$a'")
            assert(moved.isLeft, s"$table: a row of A moved to B: $moved")
        case (table, None) =>
          val owned = withConnection(
            count(
              _,
              s"""SELECT count(*) FROM $table r JOIN medication_schedules s ON s.id = r.schedule_id
                  WHERE s.user_id = '$a'"""
            )
          )
          assertEquals(count(conn, s"SELECT count(*) FROM $table"), owned, s"$table shows A exactly A's rows")
    assert(ownerCount("users", "id", b) == 1L, "account B is unchanged")

  test("the application role without a claim reads no row of any account-owned table"):
    world.discard
    actingAs(None): conn =>
      protectedTables.foreach: (table, _) =>
        assertEquals(count(conn, s"SELECT count(*) FROM $table"), 0L, table)
      val refused = attempt(conn, s"UPDATE users SET locale = 'uk' WHERE id = '${world.b}'")
      assertEquals(refused.getOrElse(0), 0)

  test("every repository group, called as account A with identifiers of account B, finds and changes nothing"):
    val World(a, b) = world
    val ids = withConnection: conn =>
      given Connection = conn
      def one(query: SqlQuery): UUID = query.queryOne[UUID]().getOrElse(fail("account B has no such row"))
      Map(
        "medication" -> one(sql"SELECT id FROM medications WHERE user_id = $b LIMIT 1"),
        "schedule" -> one(sql"SELECT id FROM medication_schedules WHERE user_id = $b LIMIT 1"),
        "occurrence" -> one(sql"SELECT id FROM dose_occurrences WHERE account_id = $b LIMIT 1"),
        "water" -> one(sql"SELECT id FROM hydration_entries WHERE account_id = $b LIMIT 1"),
        "mood" -> one(sql"SELECT id FROM mood_checkins WHERE account_id = $b LIMIT 1"),
        "night" -> one(sql"SELECT id FROM sleep_episodes WHERE account_id = $b LIMIT 1"),
        "meal" -> one(sql"SELECT id FROM meals WHERE account_id = $b LIMIT 1"),
        "export" -> one(sql"SELECT id FROM export_jobs WHERE account_id = $b LIMIT 1")
      )
    val uow = Fixtures(dataSource).uow
    val now = ProductChatRig.Start.plus(Duration.ofDays(1))
    val days = DateRange.between(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-31"))
    intercept[RolledBack.type]:
      uow.transactionAs(a): tx =>
        assertEquals(tx.accounts.timezoneOf(b), None)
        assertEquals(tx.accounts.settingsOf(b), None)
        assertEquals(tx.identities.find(PlatformIdentity("fake", "ct-b", None)), None)
        assertEquals(tx.consents.granted(b), Nil)
        assert(!tx.consents.isGranted(b, ConsentPurpose.Hydration))
        assertEquals(tx.medications.get(ids("medication")), None)
        assertEquals(tx.medications.listAllForAccount(b), Nil)
        assertEquals(tx.schedules.get(ids("schedule")), None)
        assertEquals(tx.schedules.getForUpdate(ids("schedule")), None)
        assertEquals(tx.schedules.listForMedication(ids("medication")), Nil)
        assertEquals(tx.revisions.latest(ids("schedule")), None)
        assertEquals(tx.revisions.list(ids("schedule")), Nil)
        assertEquals(tx.occurrences.get(ids("occurrence")), None)
        assertEquals(tx.occurrences.lockById(ids("occurrence")), None)
        assertEquals(tx.occurrences.listBySchedule(ids("schedule")), Nil)
        assertEquals(tx.occurrences.listForAccountBetween(b, days.start, days.end), Nil)
        assert(tx.occurrences.claimDue(now, 100).forall(_.accountId == a), "the claim of A holds no dose of B")
        assertEquals(tx.tracking.hydration.get(b, ids("water")), None)
        assertEquals(tx.tracking.hydration.listLive(b, days), Nil)
        assertEquals(tx.tracking.mood.get(b, ids("mood")), None)
        assertEquals(tx.tracking.sleep.episode(b, ids("night")), None)
        assertEquals(tx.tracking.sleep.liveEpisodes(b, days), Nil)
        assertEquals(tx.tracking.meals.get(b, ids("meal")), None)
        assertEquals(tx.tracking.meals.plan(b), None)
        assertEquals(tx.exports.find(b, ids("export")), None)
        assertEquals(tx.exports.recent(b, 10), Nil)
        assertEquals(tx.exports.fileForDelivery(ids("export"), now), None)
        assertEquals(tx.inboundEvents.find("fake", "ct-b:1"), None)
        // Writes: nothing of B changes, and nothing is written in B's name.
        assert(!tx.tracking.hydration.void(b, ids("water"), VoidReason.Deleted, now))
        assert(!tx.tracking.mood.void(b, ids("mood"), VoidReason.Deleted, now))
        assert(!tx.tracking.meals.void(b, ids("meal"), VoidReason.Deleted, now))
        assert(!tx.exports.confirm(b, ids("export"), now))
        val moved = intercept[IllegalArgumentException](tx.savepoint(tx.accounts.setTimezone(b, "UTC", now)))
        assert(moved.getMessage.contains("vanished"), "account B does not exist for A")
        val edit = MedicationEdit(ids("medication"), b, now, "Renamed", None, None, None, MedicationStatus.Active)
        // The edit seals the new name with B's data key first, which A can neither read nor create.
        val replaced = scala.util.Try(tx.savepoint(tx.medications.replace(edit, now)))
        assert(replaced.failed.toOption.exists(_.getMessage.contains("row-level security")), s"replaced: $replaced")
        val written = scala.util.Try(tx.savepoint(tx.tracking.hydration.insert(waterOf(b, now))))
        assert(written.failed.toOption.exists(_.getMessage.contains("row-level security")), s"written as B: $written")
        throw RolledBack
    withConnection: conn =>
      given Connection = conn
      assertEquals(sql"SELECT timezone FROM users WHERE id = $b".queryOne[String](), Some("Europe/Kyiv"))
      assertEquals(sql"SELECT count(*) FROM hydration_entries WHERE account_id = $b AND voided_at IS NULL".queryOne[Long](), Some(1L))

  test("a transaction bound to one account refuses to act for another"):
    val World(a, b) = world
    val refused = intercept[IllegalStateException]:
      Fixtures(dataSource).uow.transactionAs(a)(_.actAs(b))
    assert(refused.getMessage.contains("already bound"))

  private object RolledBack extends RuntimeException("rolled back on purpose")

  private def waterOf(account: UUID, at: java.time.Instant): HydrationEntry =
    HydrationEntry(
      id = UUID.randomUUID(),
      accountId = account,
      occurredAt = at,
      localDate = LocalDate.parse("2026-09-22"),
      zone = ZoneId.of("Europe/Kyiv"),
      amount = WaterAmount(300, EnteredAmount(BigDecimal(300), HydrationUnit.Millilitre)),
      source = EntrySource.Bot,
      clientKey = Some("cross-tenant"),
      createdAt = at,
      voidedAt = None
    )

end CrossTenantSuite
