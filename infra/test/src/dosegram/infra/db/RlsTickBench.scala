package dosegram.infra.db

import com.dimafeng.testcontainers.PostgreSQLContainer
import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.ports.Wake
import dosegram.core.scheduling.Materialiser
import dosegram.core.scheduling.ReminderLoop
import dosegram.infra.crypto.DataKeys
import org.testcontainers.utility.DockerImageName

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The cost of row-level security on the reminder loop (ROADMAP G2.3, ARCHITECTURE A15): seeds `accounts` accounts,
  * each with one medication due at every full hour from 08:00 to 20:00, then drains the loop at five past each hour and
  * prints the duration of each drain, and, when the schema has V14, the plan of the claim query. Evidence, not a test:
  * run it on the commit before the change and on the change, `./mill infra.test.runMain dosegram.infra.db.RlsTickBench
  * 300`, and compare the medians.
  */
object RlsTickBench:

  private val monday = Instant.parse("2026-09-21T05:00:00Z")
  private val utc = ZoneId.of("UTC")
  private val hours = (8 to 20).toList

  def main(args: Array[String]): Unit =
    val accounts = args.headOption.fold(300)(_.toInt)
    val container = PostgreSQLContainer.Def(dockerImageName = DockerImageName.parse("postgres:18-alpine")).start()
    try
      val dataSource = Database.pooled(container.jdbcUrl, container.username, container.password)
      try
        Migrator(dataSource).migrate()
        val clock = MutableClock(monday)
        val fixtures = Fixtures(dataSource)
        val rule = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, hours.map(h => HhMm.unsafe(f"$h%02d:00")))))
        (1 to accounts).foreach: i =>
          val account = fixtures.account()
          fixtures.schedule(clock, account, s"Med$i", rule, utc)
          fixtures.deliveryChannel(account, "console", s"dm:$i", monday)
        analyze(dataSource)
        val uow = PgUnitOfWork(dataSource, clock, DataKeys.development)
        val loop = ReminderLoop(uow, Materialiser(uow, clock), Wake.polling, clock, instance = "bench")
        val drains = hours.map: hour =>
          clock.at = monday.plus(Duration.ofHours(hour.toLong - 5).plusMinutes(5))
          val started = System.nanoTime()
          var claimed = loop.tick(clock.now())
          var total = claimed
          while claimed > 0 do
            claimed = loop.tick(clock.now())
            total += claimed
          ((System.nanoTime() - started) / 1_000_000L, total)
        val measured = drains.drop(1).map(_._1).sorted
        println(s"rls-bench: accounts=$accounts drains(ms, doses)=${drains.mkString(" ")}")
        println(s"rls-bench: median=${measured(measured.size / 2)} ms min=${measured.head} ms max=${measured.last} ms")
        explainClaim(dataSource)
      finally dataSource.close()
    finally container.stop()

  private def analyze(dataSource: javax.sql.DataSource): Unit =
    val conn = dataSource.getConnection
    try conn.createStatement().execute("ANALYZE")
    finally conn.close()

  /** The plan of the claim query of [[PgOccurrenceRepository.claimDue]] as the worker and as one account. */
  private def explainClaim(dataSource: javax.sql.DataSource): Unit =
    val conn = dataSource.getConnection
    try
      val hasRls = conn.createStatement().executeQuery("SELECT to_regrole('dosegram_worker') IS NOT NULL")
      hasRls.next()
      val setups =
        if hasRls.getBoolean(1) then
          List(
            "worker" -> "SET LOCAL ROLE dosegram_worker",
            "account" -> ("SET LOCAL ROLE dosegram_app; SELECT set_config('app.account_id', " +
              "(SELECT account_id::text FROM dose_occurrences LIMIT 1), true)")
          )
        else List("owner" -> "SELECT 1")
      setups.foreach: (label, setup) =>
        conn.setAutoCommit(false)
        conn.createStatement().execute(setup)
        val plan = conn
          .createStatement()
          .executeQuery(
            """EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF)
               SELECT id FROM dose_occurrences
               WHERE status IN ('pending', 'due', 'snoozed') AND next_action_at <= '2026-09-22T00:00:00Z'
                 AND error_count < 3
               ORDER BY next_action_at, id LIMIT 50 FOR UPDATE SKIP LOCKED"""
          )
        val lines = Iterator.continually(plan).takeWhile(_.next()).map(_.getString(1)).toList
        println(s"rls-bench: plan as $label:\n  ${lines.mkString("\n  ")}")
        conn.rollback()
    finally conn.close()
end RlsTickBench
