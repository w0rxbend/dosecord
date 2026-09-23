package dosecord.simulation

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dosecord.infra.db.Database
import dosecord.infra.db.Migrator
import org.testcontainers.utility.DockerImageName

/** ROADMAP M1.11 acceptance on Testcontainers Postgres 18: the 30-day scenario simulation's dispatch transcript and
  * final stats match the committed goldens, and the run's invariants hold — zero dead letters, zero quarantined rows,
  * exactly one digest per outage-affected account with `unknown(outage)` rows, every user action idempotent. A `decide`
  * or loop change that alters any dispatched message, timing or count fails here unless the goldens are regenerated
  * and reviewed (`./mill tests.simulation.test.runMain dosecord.simulation.simulationGoldenGenerate`).
  */
class SimulationSuite extends munit.FunSuite, TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def(
    dockerImageName = DockerImageName.parse("postgres:18-alpine")
  )

  private def pg: PostgreSQLContainer = withContainers { case c: PostgreSQLContainer => c }

  private lazy val dataSource = Database.pooled(pg.jdbcUrl, pg.username, pg.password)

  override def beforeAll(): Unit =
    super.beforeAll()
    Migrator(dataSource).migrate()

  override def afterAll(): Unit =
    if dataSource != null then dataSource.close()
    super.afterAll()

  test("30-day scenario simulation transcript and stats match the goldens"):
    val result = SimulationRunner.run(dataSource)
    assertEquals(
      result.transcript,
      SimulationRunner.loadGolden(SimulationRunner.TranscriptResource),
      "dispatch transcript drifted; regenerate with " +
        "./mill tests.simulation.test.runMain dosecord.simulation.simulationGoldenGenerate"
    )
    assertEquals(
      result.stats,
      SimulationRunner.loadGolden(SimulationRunner.StatsResource),
      "final stats drifted; regenerate with " +
        "./mill tests.simulation.test.runMain dosecord.simulation.simulationGoldenGenerate"
    )

    val i = result.invariants
    assertEquals(i.deadLetters, 0L, "no dead letters in 30 simulated days")
    assertEquals(i.failedPermanent, 0L, "no permanent outbox failures")
    assertEquals(i.quarantined, 0L, "no quarantined occurrences")
    assertEquals(i.unknownOutage, 3L, "the day-12 outage marks p1, p2 and p3 unknown(outage)")
    assertEquals(i.unknownUndelivered, 0L, "no undelivered unknowns: the worker was healthy or down, never mute")
    assertEquals(i.unknownRemaining, 1L, "only p3's day-12 dose stays unknown (p1 took it, p2 skipped)")
    assertEquals(i.digestsSent, 3L, "exactly one digest per outage-affected account")
    assertEquals(i.userActionsWithoutIdempotencyKey, 0L, "every user action row carries an idempotency key")
    assertEquals(i.manualLogs, 4L, "p4's four /log doses")
    assertEquals(i.openAtEnd, 0L, "every dose inside the 30-day window is resolved")
