package dosegram.infra.db

import com.dimafeng.testcontainers.PostgreSQLContainer
import dosegram.core.domain.insights.DateRange
import dosegram.infra.crypto.DataKeys
import org.flywaydb.core.Flyway
import org.testcontainers.utility.DockerImageName

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import scala.language.implicitConversions

/** ROADMAP G2.3, ARCHITECTURE DATA-02 and SEC-09: the registry of row-level security, the roles, the start-up check of
  * the login role, and the upgrade in place of a populated database, by a superuser and by a schema owner that is not
  * one.
  */
class RlsSuite extends PgSuite:

  /** Tables without an account of their own, each with the reason (ARCHITECTURE section 15.3). A new table is either
    * account-owned, with its policies, or added here with its reason; the registry test fails otherwise.
    */
  private val notAccountOwned: Map[String, String] = Map(
    "audit_log" -> "append-only operator log; subjects are pseudonymous references (SEC-10)",
    "deletion_receipts" -> "the receipt of a deleted account outlives the account (SEC-16)",
    "flyway_schema_history" -> "migration bookkeeping",
    "inbound_inbox" -> "keyed by chat before any account is known; bodies sealed with a service key",
    "render_assets" -> "image bytes keyed by asset, sealed with the render service key",
    "telegram_file_cache" -> "file ids keyed by asset",
    "service_keys" -> "service data keys, wrapped by the key-encryption key",
    "worker_heartbeat" -> "one row per process",
    "conversation_sessions" -> "keyed by chat principal; a wizard runs before the account exists",
    "form_runs" -> "keyed by conversation session"
  )

  /** Account-owned tables whose account column is not `account_id` or `user_id`. */
  private val accountKeyOf: Map[String, String] = Map("users" -> "id", "schedule_revisions" -> "schedule_id")

  private val policies = Set("tenant_isolation", "cross_account_worker", "erasure_job", "schema_owner")

  /** Every gap of the registry: a table neither protected nor listed, or protected without its four policies. */
  private def registryGaps(conn: Connection): List[String] =
    given Connection = conn
    given RowMapper[(String, Boolean, Boolean, Boolean, String)] = rs =>
      (rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), Option(rs.getString(5)).getOrElse(""))
    sql"""SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity,
                 EXISTS (SELECT 1 FROM information_schema.columns k
                         WHERE k.table_schema = 'public' AND k.table_name = c.relname
                           AND k.column_name IN ('account_id', 'user_id')),
                 (SELECT string_agg(p.policyname || ':' || array_to_string(p.roles, '+') || ':' || coalesce(p.qual, ''), '|')
                  FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname)
          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY c.relname"""
      .query[(String, Boolean, Boolean, Boolean, String)]()
      .flatMap: (table, enabled, forced, hasAccountColumn, described) =>
        val owned = hasAccountColumn || accountKeyOf.contains(table)
        val found = described.split('|').filter(_.nonEmpty).map(_.takeWhile(_ != ':')).toSet
        val tenant = described.split('|').find(_.startsWith("tenant_isolation:dosegram_app:"))
        if !owned then Option.unless(notAccountOwned.contains(table))(s"$table: no account column and not listed")
        else if notAccountOwned.contains(table) then Some(s"$table: listed as not account-owned but has an account")
        else if !(enabled && forced) then Some(s"$table: row-level security not enabled and forced")
        else if found != policies then Some(s"$table: policies ${found.toList.sorted}")
        else if !tenant.exists(_.contains("dosegram_current_account()")) then Some(s"$table: tenant policy not on the claim")
        else None

  test("every account-owned table has forced row-level security and its four policies; the rest are listed"):
    assertEquals(withConnection(registryGaps), Nil)

  test("the registry fails for a new table with an account column and no policy"):
    withConnection: conn =>
      conn.createStatement().execute("CREATE TABLE rls_probe (id uuid PRIMARY KEY, account_id uuid NOT NULL)")
      try assertEquals(registryGaps(conn), List("rls_probe: row-level security not enabled and forced"))
      finally conn.createStatement().execute("DROP TABLE rls_probe")

  test("the registry fails for a new table without an account column that is not listed"):
    withConnection: conn =>
      conn.createStatement().execute("CREATE TABLE rls_probe (id uuid PRIMARY KEY)")
      try assertEquals(registryGaps(conn), List("rls_probe: no account column and not listed"))
      finally conn.createStatement().execute("DROP TABLE rls_probe")

  test("no application role is a superuser, bypasses row-level security, may log in or owns a table (SEC-09)"):
    withConnection: conn =>
      given Connection = conn
      given RowMapper[(String, Boolean, Boolean, Boolean, Long)] = rs =>
        (rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), rs.getLong(5))
      val roles = sql"""SELECT r.rolname, r.rolsuper, r.rolbypassrls, r.rolcanlogin,
                               (SELECT count(*) FROM pg_class c WHERE c.relowner = r.oid)
                        FROM pg_roles r
                        WHERE r.rolname IN ('dosegram_app', 'dosegram_worker', 'dosegram_erasure')
                        ORDER BY r.rolname""".query[(String, Boolean, Boolean, Boolean, Long)]()
      assertEquals(
        roles,
        List("dosegram_app", "dosegram_erasure", "dosegram_worker").map(name => (name, false, false, false, 0L))
      )

  test("the serving roles append to audit_log without reading it, and never touch the migration history (V18)"):
    withConnection: conn =>
      val checks = for
        role <- List("dosegram_app", "dosegram_worker", "dosegram_erasure")
        table <- List("flyway_schema_history", "audit_log")
        privilege <- List("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE")
      yield s"SELECT '$role $table:$privilege' WHERE has_table_privilege('$role', '$table', '$privilege')"
      val rs = conn.createStatement().executeQuery(checks.mkString(" UNION ALL ")) // the suite's own query
      val granted = Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList.sorted
      assertEquals(
        granted,
        List(
          "dosegram_app audit_log:INSERT",
          "dosegram_erasure audit_log:DELETE",
          "dosegram_erasure audit_log:SELECT",
          "dosegram_worker audit_log:INSERT",
          "dosegram_worker audit_log:SELECT"
        )
      )

  test("the start-up check names the GRANT when the login role may not take the serving roles"):
    withConnection(_.createStatement().execute("CREATE ROLE rls_outsider LOGIN PASSWORD 'outsider'"))
    val outsider = DriverManager.getConnection(pg.jdbcUrl, "rls_outsider", "outsider")
    try
      assertEquals(
        RowSecurity.missingGrant(outsider),
        Some("GRANT dosegram_app, dosegram_worker TO \"rls_outsider\";")
      )
    finally outsider.close()
    assertEquals(withConnection(RowSecurity.missingGrant), None, "a superuser may take every role")

  test("a connection broken under a transaction surfaces the failure that broke it, and the pool serves on"):
    val failure = intercept[java.sql.SQLException]:
      RowSecurity.transaction(dataSource, None): conn =>
        given Connection = conn
        val pid = sql"SELECT pg_backend_pid()".queryOne[Int]().get
        withFreshConnection(_.createStatement().execute(s"SELECT pg_terminate_backend($pid)"))
        sql"SELECT 1".queryOne[Int]()
    assertEquals(failure.getSQLState, "57P01", "the terminated connection's own failure, not the rollback's")
    assertEquals(failure.getSuppressed.map(_.getClass.getName).toList, List("java.sql.SQLException"), "the rollback")
    assertEquals(dataSource.getHikariPoolMXBean.getActiveConnections, 0)
    assertEquals(RowSecurity.transaction(dataSource, None)(conn => withSql(conn)), Some(1))

  private def withSql(conn: Connection): Option[Int] =
    given Connection = conn
    sql"SELECT 1".queryOne[Int]()

  private val at = java.time.Instant.parse("2026-11-14T08:00:00Z")
  private val day = java.time.LocalDate.parse("2026-11-14")

  /** Two accounts with a water entry each and a reply of the onboarding without an account, as V13 wrote them. */
  private def seedAtV13(conn: Connection): (UUID, UUID) =
    given Connection = conn
    val (a, b) = (UUID.randomUUID(), UUID.randomUUID())
    List(a, b).foreach: account =>
      sql"INSERT INTO users (id, status) VALUES ($account, 'active')".execute()
      sql"""INSERT INTO hydration_entries (id, account_id, occurred_at, local_date, tz, source, amount_ml,
                                           entered_value, entered_unit, created_at)
            VALUES (${UUID.randomUUID()}, $account, $at, $day, 'UTC', 'bot', 250, ${BigDecimal(250)}, 'ml',
                    $at)""".execute()
    sql"""INSERT INTO inbound_events (vendor, vendor_event_id, event_id, received_at)
          VALUES ('telegram', 'onboarding:1', ${UUID.randomUUID()}, $at)""".execute()
    (a, b)

  /** What a serving transaction sees after the upgrade: A's entry only, bound; every account, across accounts. */
  private def assertServing(uow: PgUnitOfWork, a: UUID, b: UUID): Unit =
    val today = DateRange.single(day)
    assertEquals(uow.transactionAs(a)(_.tracking.hydration.listLive(a, today).size), 1)
    assertEquals(uow.transactionAs(a)(_.tracking.hydration.listLive(b, today)), Nil)
    assertEquals(uow.transaction(_.tracking.hydration.listLive(b, today).size), 1)
    assert(uow.transaction(_.inboundEvents.find("telegram", "onboarding:1")).isDefined)

  test("a populated V13 database upgrades in place under a superuser login, and serving is scoped at once"):
    withConnection(_.createStatement().execute("CREATE DATABASE rls_upgrade"))
    val url = pg.jdbcUrl.replace(s"/${pg.databaseName}", "/rls_upgrade")
    val ds = Database.pooled(url, pg.username, pg.password)
    try
      Flyway.configure().dataSource(ds).target("13").load().migrate()
      val (a, b) = withConnectionOf(ds)(seedAtV13)
      val result = SchemaUpgrade.run(ds, DataKeys.development, _ => ())
      assertEquals((result.migrationsExecuted, result.schemaVersion), (12, "27"))
      assertEquals(withConnectionOf(ds)(c => countOf(c, "SELECT count(*) FROM hydration_entries")), 2L, "the owner sees all")
      assertServing(PgUnitOfWork(ds, MutableClock(at), DataKeys.development), a, b)
    finally ds.close()

  /** A cluster of its own for the schema owner: the owner creates the roles in V1 as on a fresh host, so it may grant
    * them in V14. It starts with the suite, so the test's time limit measures the upgrade, not a container start.
    */
  private var ownerCluster: Option[PostgreSQLContainer] = None

  override def beforeAll(): Unit =
    super.beforeAll()
    ownerCluster = Some(PostgreSQLContainer.Def(dockerImageName = DockerImageName.parse("postgres:18-alpine")).start())

  override def afterAll(): Unit =
    try ownerCluster.foreach(_.stop())
    finally super.afterAll()

  test("a schema owner that is not a superuser upgrades in place, joins the serving roles and keeps every row"):
    val cluster = ownerCluster.getOrElse(fail("the schema owner's cluster did not start"))
    val admin = DriverManager.getConnection(cluster.jdbcUrl, cluster.username, cluster.password)
    try
      admin.createStatement().execute("CREATE ROLE schema_owner LOGIN CREATEROLE PASSWORD 'owner'")
      admin.createStatement().execute("CREATE DATABASE owned OWNER schema_owner")
    finally admin.close()
    val url = cluster.jdbcUrl.replace(s"/${cluster.databaseName}", "/owned")
    val ds = Database.pooled(url, "schema_owner", "owner")
    try
      Flyway.configure().dataSource(ds).target("13").load().migrate()
      val (a, b) = withConnectionOf(ds)(seedAtV13)
      SchemaUpgrade.run(ds, DataKeys.development, _ => ())
      withConnectionOf(ds): conn =>
        assertEquals(RowSecurity.missingGrant(conn), None, "V14 made the owner a member of the serving roles")
        assertEquals(countOf(conn, "SELECT count(*) FROM hydration_entries"), 2L, "the owner's start-up stages see all")
        assertEquals(countOf(conn, "SELECT count(*) FROM inbound_events"), 1L)
      assertServing(PgUnitOfWork(ds, MutableClock(at), DataKeys.development), a, b)
    finally ds.close()

  private def withConnectionOf[A](ds: javax.sql.DataSource)(f: Connection => A): A =
    val conn = ds.getConnection
    try f(conn)
    finally conn.close()

  private def countOf(conn: Connection, query: String): Long =
    val rs = conn.createStatement().executeQuery(query)
    rs.next()
    rs.getLong(1)
end RlsSuite
