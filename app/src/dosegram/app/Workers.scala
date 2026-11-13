package dosegram.app

import dosegram.contracts.ChatAdapter
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.SessionSweeper
import dosegram.core.ports.LoopMetrics
import dosegram.core.ports.Wake
import dosegram.core.scheduling.CatalogueOutboxRenderer
import dosegram.core.scheduling.Materialiser
import dosegram.core.scheduling.OutboxDispatcher
import dosegram.core.scheduling.ReminderLoop
import dosegram.core.scheduling.TokenBuckets
import dosegram.infra.Metrics
import dosegram.infra.MicrometerLoopMetrics
import dosegram.infra.MicrometerOutboxMetrics
import dosegram.infra.Settings
import dosegram.infra.db.PgNotifyWake
import dosegram.infra.db.PgUnitOfWork
import dosegram.infra.db.PgVendorLocks
import ox.Ox
import ox.fork
import ox.useCloseableInScope

import java.time.Duration
import javax.sql.DataSource
import scala.annotation.tailrec

/** The worker half of the process (docs/dosegram/ARCHITECTURE.md section 4.1): the wake listener, the materialiser, the
  * reminder loop, one outbox dispatcher per vendor under that vendor's ownership lock, and the session sweeper. Each
  * runs in a fork of the root scope, so a failing fork fails the scope and the process restarts (DESIGN.md section 3).
  *
  * Only the forks that call a vendor go through the [[ClaimGate]]: the reminder loop and the materialiser write to the
  * database alone, one transaction per unit, so cancelling them with the scope loses nothing.
  */
final class Workers(settings: Settings, dataSource: DataSource, parts: Launch.Parts):

  private val clock = parts.clock
  private val uow = PgUnitOfWork(dataSource, clock)
  private val codec = CallbackCodec(settings.callbackKeys)

  /** Starts every worker fork in the order of DESIGN.md section 12: wake listener, materialiser, reminder loop, outbox
    * dispatchers, session sweeper.
    */
  def start(gate: ClaimGate, log: String => Unit)(using Ox): Unit =
    val wake = useCloseableInScope(PgNotifyWake(settings.databaseUrl))
    val loopMetrics = MicrometerLoopMetrics()
    val materialiser = Materialiser(uow, clock, loopMetrics)
    startMaterialiser(materialiser)
    startReminderLoop(materialiser, wake, loopMetrics)
    parts.adapters.foreach((vendor, adapter) => startDispatcher(vendor, adapter, gate))
    startSessionSweeper(gate)
    log(s"workers started: outbox vendors=${parts.adapters.keys.mkString(",")}")

  /** The first pass finishes before the reminder loop starts, so rows that an outage swallowed exist before anything is
    * claimed (DESIGN.md section 7.5); later passes follow the materialiser period.
    */
  private def startMaterialiser(materialiser: Materialiser)(using Ox): Unit =
    materialiser.runOnce()
    fork:
      while true do
        Thread.sleep(parts.cadence.materialiserPeriod.toMillis)
        materialiser.runOnce()
    ()

  private def startReminderLoop(materialiser: Materialiser, wake: Wake, metrics: LoopMetrics)(using Ox): Unit =
    val loop = ReminderLoop(
      uow,
      materialiser,
      wake,
      clock,
      instance = settings.instanceId,
      role = settings.role.envName,
      pollInterval = parts.cadence.reminderPoll,
      metrics = metrics
    )
    Metrics.registerSchedulerTickAge(settings.instanceId, clock, loop.lastTickCompletedAt)
    fork(loop.run())
    ()

  private def startDispatcher(vendor: String, adapter: ChatAdapter, gate: ClaimGate)(using Ox): Unit =
    val dispatcher = OutboxDispatcher(
      uow,
      Map(vendor -> adapter),
      CatalogueOutboxRenderer(uow, codec, clock),
      clock,
      metrics = MicrometerOutboxMetrics(),
      buckets = Some(TokenBuckets(clock, TokenBuckets.defaults))
    )
    fork:
      // Ownership before the first claim (ADR-009): a second process that hosts this vendor waits here.
      useCloseableInScope(PgVendorLocks(dataSource, Seq(vendor))).lockAll()
      Workers.repeatWhileClaiming(gate, parts.cadence.outboxIdlePoll)(() => dispatcher.dispatchOnce())
    ()

  private def startSessionSweeper(gate: ClaimGate)(using Ox): Unit =
    val sweeper = SessionSweeper(uow, parts.adapters, codec, clock)
    fork:
      Workers.repeatWhileClaiming(gate, parts.cadence.sessionSweepPeriod): () =>
        val report = sweeper.sweepOnce()
        report.graced + report.aborted
    ()

object Workers:

  /** How long each worker fork waits between passes that found no work. The reminder loop also wakes on NOTIFY. */
  final case class Cadence(
      reminderPoll: Duration,
      materialiserPeriod: Duration,
      outboxIdlePoll: Duration,
      sessionSweepPeriod: Duration
  )

  object Cadence:

    /** The reminder poll and the 15-minute materialiser are DESIGN.md sections 7.4 and 7.2. No document fixes the other
      * two: one second bounds the delay between a committed outbox row and its send, one minute is the granularity of
      * the 30-minute session idle limit.
      */
    val Production: Cadence = Cadence(
      reminderPoll = ReminderLoop.PollInterval,
      materialiserPeriod = Duration.ofMinutes(15),
      outboxIdlePoll = Duration.ofSeconds(1),
      sessionSweepPeriod = Duration.ofMinutes(1)
    )

  /** Repeats `pass` (which returns the number of units it worked on) until claiming stops, waiting `idle` after a pass
    * that found nothing.
    */
  @tailrec
  private def repeatWhileClaiming(gate: ClaimGate, idle: Duration)(pass: () => Int): Unit =
    gate.whileClaiming(pass()) match
      case None         => ()
      case Some(worked) =>
        if worked == 0 then Thread.sleep(idle.toMillis)
        repeatWhileClaiming(gate, idle)(pass)
