package dosegram.core.application

import dosegram.contracts.*
import dosegram.core.chat.*
import dosegram.core.application.capture.CaptureParts
import dosegram.core.chat.MediatorFakes.*
import dosegram.core.domain.DstKind
import dosegram.core.domain.Occurrence
import dosegram.core.domain.OccurrenceCandidate
import dosegram.core.domain.ReminderPolicy
import dosegram.core.domain.Rule
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.MenuCopy
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.ports.*
import dosegram.core.scheduling.CatalogueOutboxRenderer
import dosegram.core.scheduling.CreateSchedule
import dosegram.core.scheduling.Materialiser
import dosegram.core.scheduling.OutboxDispatcher
import dosegram.core.scheduling.ReminderLoop
import dosegram.core.scheduling.ScheduleLifecycle
import ox.Ox

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import scala.io.Source
import scala.util.Using

/** The transcript rig of [[MedicationWizardSuite]]: the real mediator, the M1.9 [[Application]] composition and the
  * real flows over the in-memory ports and a `FakeAdapter` on the console or Telegram profile. Drives console-grammar
  * lines and raw taps (with a recording interaction handle), and collects the adapter's ops.
  */
private final class MedicationWizardRig(
    profile: MedicationWizardRig.Profile,
    beforeSend: RenderedMessage => Unit = _ => (),
    captureParts: Option[UnitOfWork => Clock => CaptureParts] = None
)(using Ox):
  import MedicationWizardRig.*

  val uow = InMemoryUnitOfWork()
  val clock = FixedClock(t0)
  private val fake = FakeAdapter(profile.capabilities, vendor = "fake")
  private val adapter = SyncAdapter(fake, beforeSend)
  private val adapters: Map[String, ChatAdapter] = Map("fake" -> adapter)
  private val barrier = DeliveryBarrier(uow)
  // The product composition; a suite that looks at the cards hands in its own parts.
  private val mediator =
    val parts = captureParts
      .map(_(uow)(clock))
      .getOrElse(CaptureParts.over(uow, clock, SequentialIds(), StatusCards.textOnly))
    val handler = Application.handler(uow, adapters, codec, clock)(parts)
    ChatMediator(barrier.uow, adapters, codec, handler, clock, CommandRegistry.withCaptureByName)

  private var seq = 0
  private val handles = scala.collection.mutable.ListBuffer.empty[CapturingInteractionHandle]

  private def newHandle(): CapturingInteractionHandle =
    val handle = CapturingInteractionHandle()
    handles += handle
    handle

  // ---------- Driving ----------

  /** Feeds one console-grammar line through the mediator and returns the newly printed chunks. */
  def line(input: String): List[String] =
    val body =
      if input.length > 1 && input.startsWith("/") then
        Inbound.CommandInvoked(input.drop(1).takeWhile(c => !c.isWhitespace), Map.empty, input)
      else Inbound.MessageReceived(input, None, truncated = false)
    pushAndCollect(body, None).collect { case VendorOp.Send(_, m, _, _) => m.chunks }.flatten

  def command(name: String): List[String] =
    pushAndCollect(Inbound.CommandInvoked(name, Map.empty, s"/$name"), None)
      .collect { case VendorOp.Send(_, m, _, _) => m.chunks }.flatten

  /** Taps the choice labelled `label` on the latest send whose chunks contain `containing`; a fresh capturing handle
    * stands in for the live vendor interaction.
    */
  def tapLabel(containing: String, label: String): List[String] =
    val wire = wireOf(containing, label)
    tap(wire)

  def tap(wire: String): List[String] =
    val (_, chunks) = tapWithHandle(wire)
    chunks

  private[application] def tapWithHandle(wire: String): (CapturingInteractionHandle, List[String]) =
    val handle = newHandle()
    val chunks = pushAndCollect(Inbound.InteractionSubmitted(refOf(wire), None), Some(handle))
      .collect { case VendorOp.Send(_, m, _, _) => m.chunks }.flatten
    (handle, chunks)

  /** Taps `label` on the latest message whose text contains `containing`, with that message as the source of the tap
    * (as a vendor with buttons reports it), and returns the ops of the answer.
    */
  def tapOn(containing: String, label: String): List[VendorOp] =
    val (source, rendered) = fake.synchronized(fake.sent).reverse
      .find((_, message) => message.chunks.exists(_.contains(containing)) && message.choiceMap.exists(_.label == label))
      .getOrElse(throw new NoSuchElementException(s"no message containing '$containing' with choice '$label'"))
    val wire = rendered.choiceMap.find(_.label == label).get.callback
    pushAndCollect(Inbound.InteractionSubmitted(refOf(wire), Some(source)), Some(newHandle()))

  // ---------- The M1.6/M1.7 machinery over the same stores (intake-flow tests) ----------

  /** One reminder-loop tick: claims due rows and enqueues its dispatches. */
  def tickLoop(): Int =
    ReminderLoop(uow, Materialiser(uow, clock), Wake.polling, clock, "rig").tick(clock.now())

  /** One outbox dispatch pass: sends/edits/finalizes the enqueued rows through the FakeAdapter. */
  def dispatchOnce(): Int =
    OutboxDispatcher(uow, adapters, CatalogueOutboxRenderer(uow, codec, clock), clock).dispatchOnce()

  /** A quoted reply to console message `messageId` (the console's `#<n> <text>` grammar), through the mediator. */
  def quoted(messageId: String, text: String): List[VendorOp] =
    pushAndCollect(
      Inbound.MessageReceived(text, Some(MessageHandle("fake", "dm:user-1", messageId)), truncated = false),
      None
    )

  // ---------- Console-profile helpers ----------

  def bootstrapConsole(): Unit =
    line("/start")
    line("1")  // Medication
    line("1")  // I agree
    line("1")  // Confirm
    line("13") // UTC+03:00 — Europe/Kyiv
    line("1")  // Yes

  def startWizardConsole(): Unit =
    line("/menu")
    line("2") // Medications
    line("2") // Add medication

  def answerFormConsole(): Unit =
    line("Vitamin D")
    line("1000 IU")
    line("09:00")
    line("with breakfast")

  // ---------- Telegram-profile helpers ----------

  def bootstrapTelegram(): Unit =
    command("start")
    tapLabel("Welcome to Dosegram", "Medication")
    tapLabel("I agree that", ConsentCopy.Agree)
    tapLabel("Please confirm", ConsentCopy.Confirm)
    tapLabel("Pick your timezone", "UTC+03:00 — Europe/Kyiv")
    tapLabel("It is", Labels.Yes)

  def startWizardTelegram(times: String = "09:00", instructions: String = "with breakfast"): Unit =
    command("menu")
    tapLabel(MenuCopy.mainPrompt, "Medications")
    tapLabel("Medications", "Add medication")
    List("Vitamin D", "1000 IU", times, instructions).foreach(line)

  // ---------- Restart ----------

  /** A fresh engine over the same stores (the process "restarts"); its `resumePending` re-sends stored prompts. */
  def restartEngine(): WizardEngine =
    WizardEngine(
      flows = Application.flows(clock, ScheduleLifecycle(uow, clock)),
      flowCommands = Map.empty,
      codec = codec,
      clock = clock,
      inner = RecordingHandler(_ => Reply.empty),
      uow = uow,
      adapters = adapters
    )

  // ---------- Seeding (menu tests) ----------

  /** A linked account (as the create flow would leave it). */
  /** An account as onboarding leaves it when the user chose Medication. */
  def seedAccount(): UUID = seedAccount(List(ConsentPurpose.Medication))

  def seedAccount(consents: List[ConsentPurpose]): UUID =
    val principal = uow.identities.resolve(actor, t0)
    val account = uow.accounts.createAccount(principal.identityId, "Europe/Kyiv", t0).uuid
    consents.foreach(uow.consents.grant(account, _, ConsentNotice(1, "seeded for the test", "en"), ConsentTaps(t0, t0)))
    account

  /** A medication + schedule + revision 1 through the real lifecycle (materialises the 48 h horizon). */
  def seedSchedule(
      accountId: UUID,
      name: String,
      rule: Rule,
      zone: ZoneId,
      dose: (Option[BigDecimal], Option[String]) = (None, None),
      instructions: Option[String] = None,
      tzFollowsUser: Boolean = true
  ) =
    ScheduleLifecycle(uow, clock)
      .create(
        CreateSchedule(accountId, name, rule, zone, dose._1, dose._2, instructions, ReminderPolicy.Default,
          tzFollowsUser)
      )
      .fold(errors => throw new IllegalArgumentException(errors.mkString("; ")), outcome => outcome)

  /** One extra occurrence of the schedule's current revision, `daysBack` days before today in the schedule zone. */
  def seedOccurrence(
      scheduleId: UUID,
      daysBack: Int,
      at: LocalTime,
      slotKey: String,
      transform: Occurrence => Occurrence = occ => occ
  ): UUID =
    uow.transaction { tx =>
      val schedule = tx.schedules.get(scheduleId).get
      val revision = tx.revisions.get(scheduleId, schedule.currentRevision).get
      val zone = schedule.tz
      val date = clock.now().atZone(zone).toLocalDate.minusDays(daysBack.toLong)
      val instant = date.atTime(at).atZone(zone).toInstant
      val candidate = OccurrenceCandidate(date, HhMm.unsafe(f"${at.getHour}%02d:${at.getMinute}%02d"), slotKey,
        instant, DstKind.None)
      val row = NewOccurrence.materialized(
        UUID.randomUUID(),
        schedule.accountId,
        schedule.medicationId,
        scheduleId,
        schedule.currentRevision,
        candidate,
        zone,
        revision.policy,
        revision.doseSnapshot
      )
      require(tx.occurrences.insertAll(List(row.copy(state = transform(row.state)))) == 1,
        s"fixture occurrence at $instant conflicted")
      row.id
    }

  /** The choice_map of the latest send whose chunks contain `containing`. */
  def lastChoiceMap(containing: String): List[ChoiceMapEntry] =
    ops.reverse
      .collectFirst { case VendorOp.Send(_, m, _, _) if m.chunks.exists(_.contains(containing)) => m.choiceMap }
      .getOrElse(throw new NoSuchElementException(s"no message containing '$containing'"))

  private[application] def push(event: InboundEvent): Unit =
    mediator.push(event)
    barrier.drain(mediator, event)

  // ---------- Inspection ----------

  def ops: List[VendorOp] = fake.synchronized(fake.ops.toList)

  def lastOps: List[VendorOp] = ops

  def lastChunks: List[String] =
    ops.collect { case VendorOp.Send(_, m, _, _) => m.chunks }.lastOption.getOrElse(Nil)

  def lastForm: Option[RenderedForm] =
    ops.collect { case VendorOp.Send(_, m, _, _) => m.form }.flatten.lastOption

  /** The reminder text the outbox dispatcher would send for the schedule's next occurrence, as non-empty lines. */
  def renderReminderForNextOccurrence(): List[String] =
    val schedule = uow.schedules.all.headOption.getOrElse(throw new NoSuchElementException("no schedule"))
    val occ = uow.occurrences.listBySchedule(schedule.id).head
    val row = OutboxMessage(
      id = UUID.randomUUID(),
      sendKey = "reminder:wizard-test",
      op = OutboxOp.Send,
      kind = "reminder",
      vendor = "fake",
      accountId = Some(occ.accountId),
      occurrenceId = Some(occ.id),
      channelId = None,
      epoch = Some(0),
      payload = LoopDispatch.toJson(LoopDispatch.Reminder(occ.id, Some("dm:user-1"), "initial", 1, silent = false)),
      target = None,
      importance = "reminder",
      status = OutboxStatus.Queued,
      attempts = 0,
      nextAttemptAt = t0,
      leaseUntil = None,
      attemptedAt = None,
      previousAttemptedAt = None,
      opsDone = 0,
      possibleDuplicate = false,
      platformMessageId = None,
      lastError = None,
      createdAt = t0,
      sentAt = None
    )
    val (_, rendered) = CatalogueOutboxRenderer(uow, codec, clock).render(row, CapabilityProfiles.Telegram)
    rendered.chunks.flatMap(_.split("\n").toList.filter(_.nonEmpty))

  // ---------- Transcript formatting ----------

  def lineT(input: String): List[String] = s"> $input" +: line(input)

  def commandT(name: String): List[String] = s"> /$name" +: command(name)

  /** A typed line with every resulting op, controls included (`lineT` shows the text only). */
  def typeT(input: String): List[String] =
    val before = ops.size
    line(input)
    s"> $input" +: ops.drop(before).map(formatOp)

  def tapT(containing: String, label: String): List[String] =
    val before = ops.size
    val wire = wireOf(containing, label)
    tapWithHandle(wire)
    s"> tap \"$label\"" +: ops.drop(before).map(formatOp)

  def drive(script: List[MedicationWizardRig => List[String]]): String =
    script.flatMap(_.apply(this)).mkString("\n") + "\n"

  // ---------- Internals ----------

  private def pushAndCollect(body: Inbound, interaction: Option[InteractionHandle]): List[VendorOp] =
    seq += 1
    val event = InboundEvent(
      EventId(UUID.randomUUID()),
      "fake",
      s"fake:msg:wiz:$seq",
      t0,
      actor = actor,
      chat = ChatRef("fake", "dm:user-1"),
      body = body,
      interaction = interaction
    )
    val before = ops.size
    push(event)
    ops.drop(before)

  private[application] def wireOf(containing: String, label: String): String =
    ops.reverse
      .collect { case VendorOp.Send(_, m, _, _) if m.chunks.exists(_.contains(containing)) => m }
      .flatMap(_.choiceMap.find(_.label == label).map(_.callback))
      .headOption
      .getOrElse(throw new NoSuchElementException(s"no message containing '$containing' with choice '$label'"))

  private def refOf(wire: String): CallbackRef =
    val payload = codec.decode(wire).toOption.get
    CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, wire)

  private def formatOp(op: VendorOp): String = op match
    case VendorOp.Send(_, message, _, _) =>
      val controls = message.controls.map {
        case RenderedControls.Buttons(rows) =>
          rows.map(_.map(c => s"[${c.label}]").mkString(" ")).mkString(" | ")
        case RenderedControls.Numbered(_, choices) =>
          s"numbered(${choices.map(_.label).mkString("|")})"
        case RenderedControls.NoControls => ""
      }.filter(_.nonEmpty).mkString(" ")
      s"send: ${message.chunks.mkString(" / ")}" + (if controls.nonEmpty then s" | $controls" else "")
    case VendorOp.Edit(_, message) => s"edit: ${message.chunks.mkString(" / ")}"
    case other                     => other.productPrefix

private object MedicationWizardRig:
  val t0: Instant = Instant.parse("2026-09-21T00:00:00Z")
  val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  val actor: PlatformIdentity = PlatformIdentity("fake", "user-1")

  enum Profile(val capabilities: CapabilityProfile):
    case Console extends Profile(CapabilityProfiles.Console)
    case Telegram extends Profile(CapabilityProfiles.Telegram)

  val ConsoleScript: List[MedicationWizardRig => List[String]] = List(
    _.lineT("/start"),
    _.lineT("1"),  // Medication
    _.lineT("1"),  // I agree
    _.lineT("1"),  // Confirm
    _.lineT("13"), // UTC+03:00 — Europe/Kyiv
    _.lineT("1"),  // Yes
    _.lineT("/menu"),
    _.lineT("2"), // Medications
    _.lineT("2"), // Add medication
    _.lineT("Vitamin D"),
    _.lineT("1000 IU"),
    _.lineT("09:00"),
    _.lineT("with breakfast"),
    _.lineT("1"), // Every day
    _.lineT("1")  // Create
  )

  val TelegramScript: List[MedicationWizardRig => List[String]] = List(
    _.commandT("start"),
    _.tapT("Welcome to Dosegram", "Medication"),
    _.tapT("I agree that", ConsentCopy.Agree),
    _.tapT("Please confirm", ConsentCopy.Confirm),
    _.tapT("Pick your timezone", "UTC+03:00 — Europe/Kyiv"),
    _.tapT("It is", Labels.Yes),
    _.commandT("menu"),
    _.tapT(MenuCopy.mainPrompt, "Medications"),
    _.tapT("Medications", "Add medication"),
    _.lineT("Vitamin D"),
    _.lineT("1000 IU"),
    _.lineT("09:00"),
    _.lineT("with breakfast"),
    _.tapT(WizardCopy.daysPrompt, WizardCopy.everyDay),
    _.tapT("every day 09:00", Labels.Create)
  )

  def goldenPath(profile: Profile): String =
    s"core/test/resources/goldens/transcripts/medication-wizard.${profile.toString.toLowerCase}.golden"

  def expectedGolden(profile: Profile): String =
    val path = s"/goldens/transcripts/medication-wizard.${profile.toString.toLowerCase}.golden"
    val stream = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw IllegalStateException(s"$path is not on the test classpath"))
    Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).get

  /** A recording interaction handle. */
  final class CapturingInteractionHandle extends InteractionHandle:
    val calls = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    @volatile private var ackedFlag = false
    override def answer(toast: Option[String]): Unit =
      calls.add(s"answer(${toast.getOrElse("")})")
      ackedFlag = true
    override def editSource(rendered: RenderedMessage): Unit =
      calls.add(s"editSource(${rendered.chunks.mkString(" ")})")
    override def acked: Boolean = ackedFlag

  /** A fixture-only FIFO fence. The repository recognizes its private event as an already-seen event with no stored
    * reply, so it reaches the replay lookup only after earlier delivery has finished. It never inserts a row, resolves
    * an identity, calls the application handler, or emits/records any rendered message.
    */
  private final class DeliveryBarrier(inner: UnitOfWork):
    private val pending = new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.CountDownLatch]()

    val uow: UnitOfWork = new UnitOfWork:
      override def transaction[A](f: Tx => A): A = inner.transaction: tx =>
        val inbound = tx.inboundEvents
        val fenced = new Tx:
          export tx.{inboundEvents as _, *}
          override val inboundEvents: InboundEventRepository = new InboundEventRepository:
            export inbound.complete
            override def insert(event: NewInboundEvent): Boolean =
              if pending.containsKey(event.vendorEventId) then false else tx.inboundEvents.insert(event)
            override def find(vendor: String, vendorEventId: String): Option[StoredInboundEvent] =
              Option(pending.get(vendorEventId)) match
                case Some(done) =>
                  done.countDown()
                  None
                case None => tx.inboundEvents.find(vendor, vendorEventId)
        f(fenced)

    def drain(mediator: ChatMediator, previous: InboundEvent): Unit =
      val id = s"fixture-fence:${UUID.randomUUID()}"
      val done = new java.util.concurrent.CountDownLatch(1)
      pending.put(id, done)
      try
        mediator.push(
          previous.copy(
            eventId = EventId(UUID.randomUUID()),
            vendorEventId = id,
            body = Inbound.ConversationStarted(None),
            interaction = None
          )
        )
        if !done.await(15, java.util.concurrent.TimeUnit.SECONDS) then
          throw new java.util.concurrent.TimeoutException("wizard event delivery did not reach its FIFO fence")
      finally pending.remove(id)

  /** Synchronized window over FakeAdapter's unsynchronized recording buffers (same pattern as FirstFlowsSuite). */
  final class SyncAdapter(val inner: FakeAdapter, beforeSend: RenderedMessage => Unit = _ => ()) extends ChatAdapter:
    private def around[A](f: => A): A = inner.synchronized(f)
    override def vendor: String = inner.vendor
    override def capabilities: CapabilityProfile = inner.capabilities
    override def start(sink: InboundSink, resumeFrom: Option[String]): Unit = around(inner.start(sink, resumeFrom))
    override def stop(): Unit = around(inner.stop())
    override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle =
      beforeSend(rendered)
      around(inner.send(chat, rendered, sendKey))
    override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle = around(
      inner.edit(handle, rendered)
    )
    override def delete(handle: MessageHandle): Unit = around(inner.delete(handle))
    override def registerCommands(specs: List[CommandSpec]): Unit = around(inner.registerCommands(specs))
    override def resolveChat(identity: PlatformIdentity): ChatRef = around(inner.resolveChat(identity))
    override def renderText(text: RichText): List[String] = around(inner.renderText(text))
end MedicationWizardRig