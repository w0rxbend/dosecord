package dosegram.app

import dosegram.contracts.ChatAdapter
import dosegram.core.application.Application
import dosegram.core.application.exports.ExportMessages
import dosegram.core.application.exports.ExportRequests
import dosegram.core.application.exports.ExportWorker
import dosegram.core.application.exports.RecordSummaries
import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.insights.ReportComposer
import dosegram.core.application.insights.RollupService
import dosegram.core.application.insights.StatsQueryService
import dosegram.core.application.reminders.PromptMessages
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.SessionSweeper
import dosegram.core.ports.IdSource
import dosegram.core.ports.LoopMetrics
import dosegram.core.ports.Wake
import dosegram.core.scheduling.CatalogueOutboxRenderer
import dosegram.core.scheduling.Materialiser
import dosegram.core.scheduling.OutboxDispatcher
import dosegram.core.scheduling.PromptLoop
import dosegram.core.scheduling.PromptOutboxRenderer
import dosegram.core.scheduling.ReminderLoop
import dosegram.core.scheduling.TokenBuckets
import dosegram.infra.Metrics
import dosegram.infra.MicrometerLoopMetrics
import dosegram.infra.MicrometerOutboxMetrics
import dosegram.infra.Settings
import dosegram.infra.db.PgInboundInbox
import dosegram.infra.db.PgNotifyWake
import dosegram.infra.db.PgRenderAssets
import dosegram.infra.db.PgTelegramFileCache
import dosegram.infra.db.PgUnitOfWork
import dosegram.infra.db.PgVendorLocks
import ox.Ox
import ox.fork
import ox.never
import ox.useCloseableInScope

import java.time.Duration
import javax.sql.DataSource

/** The worker half of the process (docs/dosegram/ARCHITECTURE.md section 4.1): the wake listener, the materialiser, the
  * reminder loop, one outbox dispatcher per vendor under that vendor's ownership lock, the session sweeper, the rollup
  * worker and the purge of the image stores. Each runs in a fork of the root scope. A failed pass of a worker is logged
  * and retried after a backoff ([[WorkerLoop]]), so a database blip does not stop the process; a failure of a step the
  * start depends on (the wake connection, the first materialiser pass, the ownership lock) still fails the scope and
  * the process restarts (DESIGN.md section 3).
  *
  * Only the forks that call a vendor go through the [[ClaimGate]]: the reminder loop, the materialiser, the rollup
  * worker and the purge write to the database alone, one transaction per unit, so cancelling them with the scope loses
  * nothing.
  */
final class Workers(settings: Settings, dataSource: DataSource, parts: Launch.Parts):

  private val clock = parts.clock
  private val uow = PgUnitOfWork(dataSource, clock)
  private val codec = CallbackCodec(settings.callbackKeys)

  /** Starts every worker fork in the order of DESIGN.md section 12: wake listener, materialiser, reminder loop, outbox
    * dispatchers, session sweeper.
    */
  def start(gate: ClaimGate, log: String => Unit)(using Ox): Unit =
    val passes = WorkerLoop.standard(log)
    val wake = useCloseableInScope(PgNotifyWake(settings.databaseUrl))
    Workers.closeOnCancel(wake)
    val loopMetrics = MicrometerLoopMetrics()
    val materialiser = Materialiser(uow, clock, loopMetrics)
    startMaterialiser(materialiser, passes)
    startReminderLoop(materialiser, wake, loopMetrics, passes)
    parts.adapters.foreach((vendor, adapter) => startDispatcher(vendor, adapter, gate, passes))
    startSessionSweeper(gate, passes)
    startRollupWorker(passes)
    startImagePurge(passes)
    startPromptLoop(log, passes)
    startInboxPurge(passes)
    startExportWorker(log, passes)
    log(s"workers started: outbox vendors=${parts.adapters.keys.mkString(",")}")

  /** The first pass finishes before the reminder loop starts, so rows that an outage swallowed exist before anything is
    * claimed (DESIGN.md section 7.5); later passes follow the materialiser period.
    */
  private def startMaterialiser(materialiser: Materialiser, passes: WorkerLoop)(using Ox): Unit =
    materialiser.runOnce()
    fork:
      passes.repeat("materialiser"): () =>
        Thread.sleep(parts.cadence.materialiserPeriod.toMillis)
        materialiser.runOnce()
        true
    ()

  private def startReminderLoop(materialiser: Materialiser, wake: Wake, metrics: LoopMetrics, passes: WorkerLoop)(using
      Ox
  ): Unit =
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
    fork:
      passes.repeat("reminder loop"): () =>
        loop.step()
        true
    ()

  private def startDispatcher(vendor: String, adapter: ChatAdapter, gate: ClaimGate, passes: WorkerLoop)(using
      Ox
  ): Unit =
    val dispatcher = OutboxDispatcher(
      uow,
      Map(vendor -> adapter),
      PromptOutboxRenderer(
        uow,
        CatalogueOutboxRenderer(uow, codec, clock),
        Set(PromptLoop.OutboxKind, ExportRequests.OutboxKind)
      ),
      clock,
      metrics = MicrometerOutboxMetrics(),
      buckets = Some(TokenBuckets(clock, TokenBuckets.defaults))
    )
    fork:
      // Ownership before the first claim (ADR-009): a second process that hosts this vendor waits here.
      useCloseableInScope(PgVendorLocks(dataSource, Seq(vendor))).lockAll()
      passes.repeat(s"outbox dispatcher $vendor"):
        Workers.whileClaiming(gate, parts.cadence.outboxIdlePoll)(() => dispatcher.dispatchOnce())
    ()

  private def startSessionSweeper(gate: ClaimGate, passes: WorkerLoop)(using Ox): Unit =
    val sweeper = SessionSweeper(uow, parts.adapters, codec, clock)
    fork:
      passes.repeat("session sweeper"):
        Workers.whileClaiming(gate, parts.cadence.sessionSweepPeriod): () =>
          val report = sweeper.sweepOnce()
          report.graced + report.aborted
    ()

  /** Drains the rollup queue (ARCHITECTURE section 11.1, INS-01): a day a write made dirty is recomputed here, so a
    * read finds it ready. A read still refreshes the days of its own range, so it never shows a stale one either way.
    */
  private def startRollupWorker(passes: WorkerLoop)(using Ox): Unit =
    val rollups = RollupService(uow, clock)
    fork:
      passes.repeat("rollup worker"): () =>
        if rollups.drain(Workers.RollupBatch) == 0 then Thread.sleep(parts.cadence.rollupIdlePoll.toMillis)
        true
    ()

  /** The deleting jobs of the image stores (ROADMAP G1.10): the bytes of an image one hour after it was drawn, and a
    * file id that no send has used for [[Workers.FileIdUnusedFor]].
    */
  private def startImagePurge(passes: WorkerLoop)(using Ox): Unit =
    val assets = PgRenderAssets(dataSource)
    // Pruning is by last use, whichever bot the file id belongs to.
    val fileIds = PgTelegramFileCache(dataSource, botId = 0L)
    fork:
      passes.repeat("image purge"): () =>
        val now = clock.now()
        assets.purge(now)
        fileIds.forgetUnusedSince(now.minus(Workers.FileIdUnusedFor))
        Thread.sleep(parts.cadence.imagePurgePeriod.toMillis)
        true
    ()

  /** The deleting job of the inbound stores (ROADMAP G1.7, ARCHITECTURE section 15.6): inbox bodies after 24 hours,
    * inbox and `inbound_events` rows after 7 days. A processed body is deleted when it is processed.
    */
  private def startInboxPurge(passes: WorkerLoop)(using Ox): Unit =
    val inbox = PgInboundInbox(dataSource)
    fork:
      passes.repeat("inbox purge"): () =>
        inbox.purge(clock.now())
        Thread.sleep(parts.cadence.inboxPurgePeriod.toMillis)
        true
    ()

  /** The prompt loop (ADR-023): water, mood and sleep prompts through the outbox, at the reminder poll. A plan that
    * fails is logged by id and error class only, and the others go on.
    */
  private def startPromptLoop(log: String => Unit, passes: WorkerLoop)(using Ox): Unit =
    val loop = PromptLoop(
      uow,
      clock,
      IdSource.uuidV7(java.security.SecureRandom()),
      PromptMessages(codec),
      HydrationService(clock, IdSource.uuidV7(java.security.SecureRandom())),
      Application.reminderSettings(clock),
      ReportComposer(StatsQueryService(RollupService(uow, clock), clock), parts.images.cards, codec),
      (plan, error) => log(s"prompt plan $plan failed: ${error.getClass.getSimpleName}")
    )
    fork:
      passes.repeat("prompt loop"): () =>
        loop.tick()
        Thread.sleep(parts.cadence.reminderPoll.toMillis)
        true
    ()

  /** The export worker and its deleting job (ROADMAP G3.10): builds the confirmed exports one by one, and empties every
    * file 15 minutes after it was ready. The record summary needs the font pack of the image settings.
    */
  private def startExportWorker(log: String => Unit, passes: WorkerLoop)(using Ox): Unit =
    val worker = ExportWorker(
      uow,
      clock,
      IdSource.uuidV7(java.security.SecureRandom()),
      RecordSummaries(RollupService(uow, clock)),
      PdfRecordSummaryRenderer.from(settings.render),
      ExportMessages(codec),
      log
    )
    fork:
      passes.repeat("export worker"): () =>
        worker.sweep()
        if !worker.runOnce() then Thread.sleep(parts.cadence.exportIdlePoll.toMillis)
        true
    ()

object Workers:

  /** Closes `wake` as soon as the scope is cancelled, not only after every fork has ended: the scope interrupts this
    * fork together with the others, and closing the wake ends a blocking wait for a NOTIFY even when the interrupt of
    * the waiting fork was lost (the shutdown hang of 2026-11-14).
    */
  private[app] def closeOnCancel(wake: AutoCloseable)(using Ox): Unit =
    fork:
      try never
      finally wake.close()
    ()

  /** The dirty keys one rollup step takes. */
  private val RollupBatch = 100

  /** A file id that nothing has sent for this long is forgotten; the next send uploads the image again. */
  val FileIdUnusedFor: Duration = Duration.ofDays(30)

  /** How long each worker fork waits between passes that found no work. The reminder loop also wakes on NOTIFY. */
  final case class Cadence(
      reminderPoll: Duration,
      materialiserPeriod: Duration,
      outboxIdlePoll: Duration,
      sessionSweepPeriod: Duration,
      rollupIdlePoll: Duration,
      imagePurgePeriod: Duration,
      // The 24-hour and 7-day limits of the inbound stores hold to within this.
      inboxPurgePeriod: Duration = Duration.ofMinutes(1),
      // An export is ready within 10 minutes (AC-IMX-2); its file is deleted to within this after 15 minutes.
      exportIdlePoll: Duration = Duration.ofSeconds(5)
  )

  object Cadence:

    /** The reminder poll and the 15-minute materialiser are DESIGN.md sections 7.4 and 7.2. No document fixes the other
      * two: one second bounds the delay between a committed outbox row and its send, one minute is the granularity of
      * the 30-minute session idle limit. The rollup poll and the image purge are explained at their values.
      */
    val Production: Cadence = Cadence(
      reminderPoll = ReminderLoop.PollInterval,
      materialiserPeriod = Duration.ofMinutes(15),
      outboxIdlePoll = Duration.ofSeconds(1),
      sessionSweepPeriod = Duration.ofMinutes(1),
      // A read refreshes its own days, so the worker only keeps the queue short; the purge keeps the one-hour promise
      // of the image bytes to within a minute.
      rollupIdlePoll = Duration.ofSeconds(5),
      imagePurgePeriod = Duration.ofMinutes(1)
    )

  /** One pass for [[WorkerLoop.repeat]]: runs `pass` (which returns the number of units it worked on) as one claimed
    * unit and waits `idle` after a pass that found nothing. False once claiming has stopped, which ends the loop.
    */
  private def whileClaiming(gate: ClaimGate, idle: Duration)(pass: () => Int): () => Boolean = () =>
    gate.whileClaiming(pass()) match
      case None         => false
      case Some(worked) =>
        if worked == 0 then Thread.sleep(idle.toMillis)
        true
