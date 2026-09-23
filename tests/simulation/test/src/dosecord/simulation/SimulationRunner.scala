package dosecord.simulation

import dosecord.infra.db.Database
import dosecord.infra.db.Migrator
import dosecord.infra.db.MutableClock
import dosecord.infra.db.RowMapper
import dosecord.infra.db.instant
import dosecord.infra.db.localDate
import dosecord.infra.db.sql

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import scala.io.Source
import scala.language.implicitConversions
import scala.util.Using

/** Runs the M1.11 scenario end to end on a fresh database and produces the two goldens' content: the dispatch
  * transcript (what the console adapters sent, with control maps) and the final stats. Shared by the suite and the
  * generator so the golden can never be produced by a different code path than the one under test.
  */
object SimulationRunner:

  final case class Invariants(
      deadLetters: Long,
      failedPermanent: Long,
      unknownOutage: Long,
      unknownUndelivered: Long,
      unknownRemaining: Long,
      digestsSent: Long,
      quarantined: Long,
      userActionsWithoutIdempotencyKey: Long,
      manualLogs: Long,
      openAtEnd: Long
  )

  final case class Result(transcript: String, stats: String, invariants: Invariants)

  def run(dataSource: DataSource): Result = ox.supervised:
    val clock = MutableClock(Scenario.T0)
    val transcript = SimTranscript(clock)
    val harness = SimulationHarness(dataSource, clock, transcript)
    Scenario.personas.foreach: spec =>
      harness.seedPersona(
        spec.personaId,
        spec.zone,
        spec.medicationName,
        spec.rule,
        spec.doseAmount,
        spec.doseUnit,
        spec.instructions
      )
    harness.startEngine()
    harness.run(Scenario.script, Scenario.End)
    val aliases = buildAliases(dataSource, harness)
    val (stats, invariants) = renderStats(dataSource, harness)
    Result(transcript.render(aliases, harness.codec), stats, invariants)

  // ---------- Aliases ----------

  private final case class OccRow(
      id: UUID,
      accountId: UUID,
      localDate: LocalDate,
      slotKey: String,
      scheduledFor: Instant,
      origin: String
  )

  private def buildAliases(dataSource: DataSource, harness: SimulationHarness): Aliases =
    val accounts = harness.personaIds.map(id => harness.persona(id).accountId -> id).toMap
    val rows = withConnection(dataSource): conn =>
      given Connection = conn
      given RowMapper[OccRow] = rs =>
        OccRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("account_id")),
          rs.localDate("local_date"), rs.getString("slot_key"), rs.instant("scheduled_for"),
          rs.getString("origin"))
      sql"""SELECT id, account_id, local_date, slot_key, scheduled_for, origin FROM dose_occurrences
            ORDER BY account_id, local_date, scheduled_for, slot_key""".query[OccRow]()
    val counts = scala.collection.mutable.HashMap.empty[String, Int]
    val occurrences = rows.map: row =>
      val persona = accounts(row.accountId)
      val base =
        if row.origin == "manual" then s"$persona-${row.localDate}-manual"
        else s"$persona-${row.localDate}-${row.slotKey}"
      val n = counts.getOrElse(base, 0) + 1
      counts += base -> n
      row.id -> (if n == 1 then base else s"$base-$n")
    Aliases(occurrences.toMap, accounts)

  // ---------- Stats ----------

  private def renderStats(dataSource: DataSource, harness: SimulationHarness): (String, Invariants) =
    val personas = harness.personaIds
    val accountOf = personas.map(id => id -> harness.persona(id).accountId).toMap
    val b = StringBuilder()
    b ++= "# M1.11 30-day scenario simulation — final stats (golden; regenerate with\n"
    b ++= "# ./mill tests.simulation.test.runMain dosecord.simulation.simulationGoldenGenerate)\n"
    b ++= s"window: ${Scenario.T0} .. ${Scenario.End} (30 days, virtual clock)\n"
    b ++= s"outage: ${Scenario.outageStart} .. ${Scenario.outageEnd} (6 h, day 12)\n"
    b ++= "personas: p1 daily 09:00 (travels UTC->Africa/Lagos->America/Phoenix, pause/resume, same-day edit), " +
      "p2 twice daily 09:07/21:07, p3 Mon/Wed/Fri 09:23, p4 as-needed via /log\n"
    b ++= "pending-personas (need M7 rule kinds, not implemented):\n"
    Scenario.pendingPersonas.foreach(p => b ++= s"  - $p\n")

    def counts(label: String, query: String): List[(String, Long)] =
      val rows = withConnection(dataSource): conn =>
        val st = conn.createStatement()
        try
          val rs = st.executeQuery(query)
          val out = List.newBuilder[(String, Long)]
          while rs.next() do out += (rs.getString(1) -> rs.getLong(2))
          out.result()
        finally st.close()
      b ++= s"$label:\n"
      rows.foreach((k, v) => b ++= s"  $k=$v\n")
      rows

    // occurrences by persona and status, restricted to the 30-day window
    val byPersonaStatus = withConnection(dataSource): conn =>
      given Connection = conn
      given RowMapper[(UUID, String, Long)] = rs =>
        (UUID.fromString(rs.getString("account_id")), rs.getString("status"), rs.getLong("c"))
      sql"""SELECT account_id, status::text AS status, count(*) AS c FROM dose_occurrences
            WHERE scheduled_for < ${Scenario.End} GROUP BY account_id, status ORDER BY account_id, status"""
        .query[(UUID, String, Long)]()
    b ++= "occurrences by persona and status (scheduled_for < end):\n"
    personas.foreach: p =>
      val mine = byPersonaStatus.filter(_._1 == accountOf(p))
      val cells = mine.map((_, s, c) => s"$s=$c").sorted.mkString(" ")
      b ++= s"  $p: $cells\n"

    val cancelled = withConnection(dataSource): conn =>
      given Connection = conn
      given RowMapper[(UUID, String, Long)] = rs =>
        (UUID.fromString(rs.getString("account_id")), rs.getString("cancel_reason"), rs.getLong("c"))
      sql"""SELECT account_id, cancel_reason, count(*) AS c FROM dose_occurrences
            WHERE status = 'cancelled' GROUP BY account_id, cancel_reason ORDER BY account_id, cancel_reason"""
        .query[(UUID, String, Long)]()
    b ++= "cancelled by persona and reason:\n"
    personas.foreach: p =>
      val mine = cancelled.filter(_._1 == accountOf(p)).map((_, r, c) => s"$r=$c").sorted.mkString(" ")
      b ++= s"  $p: $mine\n"

    val unknowns = counts("unknown by reason",
      "SELECT unknown_reason, count(*) FROM dose_occurrences WHERE status = 'unknown' GROUP BY unknown_reason ORDER BY unknown_reason")
    val outbox = counts("outbox by kind and status",
      "SELECT kind || '/' || status, count(*) FROM outbox_messages GROUP BY kind, status ORDER BY kind, status")
    counts("dose_actions by action and actor",
      "SELECT action::text || '/' || actor_type, count(*) FROM dose_actions GROUP BY action, actor_type ORDER BY action, actor_type")
    counts("domain_events by type",
      "SELECT type, count(*) FROM domain_events GROUP BY type ORDER BY type")
    counts("manual occurrences",
      "SELECT origin, count(*) FROM dose_occurrences WHERE origin = 'manual' GROUP BY origin")

    val dead = scalar(dataSource, "SELECT count(*) FROM outbox_messages WHERE status = 'dead'")
    val failedPermanent = scalar(dataSource, "SELECT count(*) FROM outbox_messages WHERE status = 'failed_permanent'")
    val quarantined = scalar(dataSource, "SELECT count(*) FROM dose_occurrences WHERE error_count > 0")
    val noKey = scalar(dataSource,
      "SELECT count(*) FROM dose_actions WHERE actor_type = 'user' AND idempotency_key IS NULL")
    val manualLogs = scalar(dataSource, "SELECT count(*) FROM dose_occurrences WHERE origin = 'manual'")
    val markedUnknown = scalar(dataSource, "SELECT count(*) FROM dose_actions WHERE action = 'marked_unknown'")
    val unknownRemaining = scalar(dataSource, "SELECT count(*) FROM dose_occurrences WHERE status = 'unknown'")
    val openAtEnd = withConnection(dataSource): conn =>
      given Connection = conn
      sql"""SELECT count(*) FROM dose_occurrences
            WHERE scheduled_for < ${Scenario.End} AND status IN ('pending', 'due', 'snoozed')"""
        .queryOne[Long]()
        .getOrElse(0L)
    b ++= s"dead-letters: $dead\n"
    b ++= s"marked-unknown: $markedUnknown\n"
    b ++= s"open-at-end: $openAtEnd\n"

    val invariants = Invariants(
      deadLetters = dead,
      failedPermanent = failedPermanent,
      unknownOutage = markedUnknown,
      unknownUndelivered = unknowns.find(_._1 == "undelivered").map(_._2).getOrElse(0L),
      unknownRemaining = unknownRemaining,
      digestsSent = outbox.find(_._1 == "digest/sent").map(_._2).getOrElse(0L),
      quarantined = quarantined,
      userActionsWithoutIdempotencyKey = noKey,
      manualLogs = manualLogs,
      openAtEnd = openAtEnd
    )
    (b.toString, invariants)
  end renderStats

  private def scalar(dataSource: DataSource, query: String): Long = withConnection(dataSource): conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query)
      rs.next()
      rs.getLong(1)
    finally st.close()

  private def withConnection[A](dataSource: DataSource)(f: Connection => A): A =
    val conn = dataSource.getConnection
    try f(conn)
    finally conn.close()

  // ---------- Golden files ----------

  val TranscriptResource = "/goldens/simulation-transcript.golden"
  val StatsResource = "/goldens/simulation-stats.golden"
  val TranscriptFile = "tests/simulation/test/resources/goldens/simulation-transcript.golden"
  val StatsFile = "tests/simulation/test/resources/goldens/simulation-stats.golden"

  def loadGolden(resource: String): String =
    val stream = Option(getClass.getResourceAsStream(resource))
      .getOrElse(throw IllegalStateException(s"$resource is not on the test classpath"))
    Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).get

/** Regenerates the simulation goldens:
  * `./mill tests.simulation.test.runMain dosecord.simulation.simulationGoldenGenerate`.
  */
@main def simulationGoldenGenerate(): Unit =
  val container = com.dimafeng.testcontainers.PostgreSQLContainer.Def(
    dockerImageName = org.testcontainers.utility.DockerImageName.parse("postgres:18-alpine")
  ).start()
  try
    val dataSource = Database.pooled(container.jdbcUrl, container.username, container.password)
    try
      Migrator(dataSource).migrate()
      val result = SimulationRunner.run(dataSource)
      Seq(
        SimulationRunner.TranscriptFile -> result.transcript,
        SimulationRunner.StatsFile -> result.stats
      ).foreach { (file, content) =>
        val target = Path.of(file)
        Option(target.getParent).foreach(Files.createDirectories(_))
        Files.writeString(target, content)
        println(s"wrote $target")
      }
    finally dataSource.close()
  finally container.stop()
