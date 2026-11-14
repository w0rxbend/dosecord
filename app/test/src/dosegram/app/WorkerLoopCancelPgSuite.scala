package dosegram.app

import dosegram.core.ports.Clock
import dosegram.core.scheduling.Materialiser
import dosegram.core.scheduling.ReminderLoop
import dosegram.infra.db.PgNotifyWake
import dosegram.infra.db.PgSuite
import dosegram.infra.db.PgUnitOfWork
import ox.fork
import ox.supervised

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom

/** The stability review of 2026-11-14: a worker kept alive across a failed pass must still end when its scope is
  * cancelled, wherever the interrupt lands: in the database work of a pass, or in the wait for a wake.
  */
class WorkerLoopCancelPgSuite extends PgSuite:

  private val Rounds = 200

  test("cancelling the scope ends the reminder loop wherever the interrupt lands in its pass"):
    val clock = Clock.system
    val log = ConcurrentLinkedQueue[String]()
    val passes = WorkerLoop.standard(line => log.add(line): Unit)
    (1 to Rounds).foreach: round =>
      val wake = PgNotifyWake(pg.jdbcUrl, pg.username, pg.password)
      val loop = ReminderLoop(
        PgUnitOfWork(dataSource, clock),
        Materialiser(PgUnitOfWork(dataSource, clock), clock),
        wake,
        clock,
        instance = "cancel-pg",
        pollInterval = Duration.ofMillis(2)
      )
      val scope = Thread
        .ofVirtual()
        .start: () =>
          supervised:
            val _ = fork(passes.repeat("reminder loop")(() => { loop.step(); true }))
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 40))
      try
        scope.join(10000)
        assert(!scope.isAlive, s"round $round: the scope ended after its body; worker log: ${log.toArray.toList}")
      finally wake.close()

  test("cancelling the scope ends a wait for a wake that the interrupt never reached"):
    // The hang of 2026-11-14: a scope joined the reminder loop for more than ten minutes while the loop's fork sat in
    // the wake's blocking read. Here the pass loses the interrupt on purpose before it waits, as any code that catches
    // an InterruptedException would; only closing the wake on cancel can end the wait.
    val wake = PgNotifyWake(pg.jdbcUrl, pg.username, pg.password)
    val passes = WorkerLoop.standard(_ => ())
    val swallowing = CountDownLatch(1)
    val scope = Thread
      .ofVirtual()
      .start: () =>
        supervised:
          Workers.closeOnCancel(wake)
          val _ = fork:
            passes.repeat("reminder loop"): () =>
              swallowing.countDown()
              try Thread.sleep(Long.MaxValue)
              catch case _: InterruptedException => () // the interrupt is lost here
              wake.awaitOrTimeout(Duration.ofMinutes(10))
              true
          swallowing.await()
    try
      scope.join(10000)
      assert(!scope.isAlive, "the scope ended within 10 s of its body")
    finally wake.close()
