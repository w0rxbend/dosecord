package dosecord.core.application

import dosecord.contracts.*
import dosecord.core.chat.*
import dosecord.core.chat.MediatorFakes.*
import dosecord.core.domain.DstKind
import dosecord.core.domain.Occurrence
import dosecord.core.domain.OccurrenceCandidate
import dosecord.core.domain.ReminderPolicy
import dosecord.core.domain.Rule
import dosecord.core.domain.copy.Labels
import dosecord.core.domain.copy.MenuCopy
import dosecord.core.domain.copy.WizardCopy
import dosecord.core.ports.*
import dosecord.core.scheduling.CatalogueOutboxRenderer
import dosecord.core.scheduling.CreateSchedule
import dosecord.core.scheduling.Materialiser
import dosecord.core.scheduling.OutboxDispatcher
import dosecord.core.scheduling.ReminderLoop
import dosecord.core.scheduling.ScheduleLifecycle
import ox.Ox

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import scala.io.Source
import scala.util.Using

/** The transcript rig of [[MedicationWizardSuite]]: the real mediator, the M1.9 [[Application]] composition and the
  * real flows over the in-memory ports and a `FakeAdapter` on the console or discord profile. Drives console-grammar
  * lines, raw taps (with a recording interaction handle) and modal submits, and collects the adapter's ops.
  */
private final class MedicationWizardRig(profile: MedicationWizardRig.Profile)(using Ox):
  import MedicationWizardRig.*

  val uow = InMemoryUnitOfWork()
  val clock = FixedClock(t0)
  private val fake = FakeAdapter(profile.capabilities, vendor = "fake")
  private val adapter = SyncAdapter(fake)
  private val adapters: Map[String, ChatAdapter] = Map("fake" -> adapter)
  private val mediator =
    ChatMediator(uow, adapters, codec, Application.handler(uow, adapters, codec, clock), clock, CommandRegistry.byName)

  private var seq = 0
  private val handles = scala.collection.mutable.ListBuffer.empty[CapturingInteractionHandle]

  private def newHandle(): CapturingInteractionHandle =
    val handle = CapturingInteractionHandle()
    handles += handle
    handle

  /** Every interaction handle created by this rig (modal captures for the transcript and assertions). */
  def openedForms: List[RenderedForm] = handles.flatMap(_.openedForm).toList

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
    val chunks = pushAndCollect(Inbound.InteractionSubmitted(refOf(wire), Nil, None), Some(handle))
      .collect { case VendorOp.Send(_, m, _, _) => m.chunks }.flatten
    (handle, chunks)

  /** Submits the wizard's step 1 modal (the last opened form) with these field answers. */
  def submitWizardForm(name: String, dose: String, times: String, instructions: String): List[String] =
    val form = handles.reverseIterator.flatMap(_.openedForm).nextOption()
      .getOrElse(throw new NoSuchElementException("no open modal to submit"))
    val body = Inbound.FormSubmitted(
      form.id,
      refOf(form.submit),
      Map("name" -> name, "dose" -> dose, "times" -> times, "instructions" -> instructions)
    )
    pushAndCollect(body, Some(newHandle()))
      .collect { case VendorOp.Send(_, m, _, _) => m.chunks }.flatten

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

  // ---------- Discord-profile helpers ----------

  def bootstrapDiscord(): Unit =
    command("start")
    tapLabel("Pick your timezone", "UTC+03:00 — Europe/Kyiv")
    tapLabel("It is", Labels.Yes)

  def startWizardDiscord(times: String = "09:00", instructions: String = "with breakfast"): Unit =
    command("menu")
    tapLabel(MenuCopy.mainPrompt, "Medications")
    tapLabel("Medications", "Add medication")
    submitWizardForm("Vitamin D", "1000 IU", times, instructions)

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
  def seedAccount(): UUID =
    val principal = uow.identities.resolve(actor, t0)
    uow.accounts.createAccount(principal.identityId, "Europe/Kyiv", t0).uuid

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

  private[application] def push(event: InboundEvent): Unit = mediator.push(event)

  private[application] def awaitOps(before: Int): Unit = await(ops.size > before, "the event produced a reply")

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
    val (_, rendered) = CatalogueOutboxRenderer(uow, codec, clock).render(row, CapabilityProfiles.Discord)
    rendered.chunks.flatMap(_.split("\n").toList.filter(_.nonEmpty))

  // ---------- Transcript formatting ----------

  def lineT(input: String): List[String] = s"> $input" +: line(input)

  def commandT(name: String): List[String] = s"> /$name" +: command(name)

  def tapT(containing: String, label: String): List[String] =
    val before = ops.size
    val wire = wireOf(containing, label)
    val (handle, _) = tapWithHandle(wire)
    val modal = handle.openedForm.map: form =>
      s"modal: ${form.id} (${form.fields.map(_.key).mkString(", ")})"
    s"> tap \"$label\"" +: (modal.toList ++ ops.drop(before).map(formatOp))

  def submitT(name: String, dose: String, times: String, instructions: String): List[String] =
    val before = ops.size
    submitWizardForm(name, dose, times, instructions)
    s"> submit name=\"$name\" dose=\"$dose\" times=\"$times\" instructions=\"$instructions\"" +:
      ops.drop(before).map(formatOp)

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
    mediator.push(event)
    await(
      ops.size > before || interaction.exists { case h: CapturingInteractionHandle => h.replied; case _ => false },
      s"$body produced a reply"
    )
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
        case RenderedControls.SelectMenu(_, choices, min, max) =>
          s"select(${choices.map(_.label).mkString("|")} min=$min max=$max)"
        case RenderedControls.Numbered(_, choices, _) =>
          s"numbered(${choices.map(_.label).mkString("|")})"
        case RenderedControls.NoControls => ""
      }.filter(_.nonEmpty).mkString(" ")
      s"send: ${message.chunks.mkString(" / ")}" + (if controls.nonEmpty then s" | $controls" else "")
    case VendorOp.OpenForm(form) => s"modal: ${form.id} (${form.fields.map(_.key).mkString(", ")})"
    case VendorOp.Edit(_, message) => s"edit: ${message.chunks.mkString(" / ")}"
    case other => other.productPrefix

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(10)
      ok = cond
    assert(ok, clue)

private object MedicationWizardRig:
  val t0: Instant = Instant.parse("2026-09-21T00:00:00Z")
  val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  val actor: PlatformIdentity = PlatformIdentity("fake", "user-1")

  enum Profile(val capabilities: CapabilityProfile):
    case Console extends Profile(CapabilityProfiles.Console)
    case Discord extends Profile(CapabilityProfiles.Discord)

  val ConsoleScript: List[MedicationWizardRig => List[String]] = List(
    _.lineT("/start"),
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

  val DiscordScript: List[MedicationWizardRig => List[String]] = List(
    _.commandT("start"),
    _.tapT("Pick your timezone", "UTC+03:00 — Europe/Kyiv"),
    _.tapT("It is", Labels.Yes),
    _.commandT("menu"),
    _.tapT(MenuCopy.mainPrompt, "Medications"),
    _.tapT("Medications", "Add medication"),
    _.submitT("Vitamin D", "1000 IU", "09:00", "with breakfast"),
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

  /** A recording interaction handle that also captures the opened modal (the mediator delivers VendorOp.OpenForm
    * through the live handle, never through the adapter).
    */
  final class CapturingInteractionHandle extends InteractionHandle:
    val calls = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    @volatile var openedForm: Option[RenderedForm] = None
    @volatile private var ackedFlag = false
    override def deferUpdate(): Unit =
      calls.add("deferUpdate")
      ackedFlag = true
    override def deferReply(ephemeral: Boolean): Unit =
      calls.add(s"deferReply($ephemeral)")
      ackedFlag = true
    override def answer(toast: Option[String]): Unit =
      calls.add(s"answer(${toast.getOrElse("")})")
      ackedFlag = true
    override def respond(rendered: RenderedMessage): MessageHandle =
      calls.add(s"respond(${rendered.chunks.mkString(" ")})")
      MessageHandle("fake", "dm:user-1", "r1")
    override def editSource(rendered: RenderedMessage): Unit =
      calls.add(s"editSource(${rendered.chunks.mkString(" ")})")
    override def openForm(form: RenderedForm): Unit =
      calls.add(s"openForm(${form.id})")
      openedForm = Some(form)
      ackedFlag = true
    override def acked: Boolean = ackedFlag

    /** True once a post-ack delivery call landed (the ack itself — deferUpdate/deferReply — happens before the
      * handler runs and must not satisfy the rig's await).
      */
    def replied: Boolean =
      import scala.jdk.CollectionConverters.*
      calls.asScala.exists: call =>
        call.startsWith("answer(") || call.startsWith("openForm(") || call.startsWith("editSource(") ||
          call.startsWith("respond(")

  /** Synchronized window over FakeAdapter's unsynchronized recording buffers (same pattern as FirstFlowsSuite). */
  final class SyncAdapter(val inner: FakeAdapter) extends ChatAdapter:
    private def around[A](f: => A): A = inner.synchronized(f)
    override def vendor: String = inner.vendor
    override def capabilities: CapabilityProfile = inner.capabilities
    override def start(sink: InboundSink, resumeFrom: Option[String]): Unit = around(inner.start(sink, resumeFrom))
    override def stop(): Unit = around(inner.stop())
    override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle = around(
      inner.send(chat, rendered, sendKey)
    )
    override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle = around(
      inner.edit(handle, rendered)
    )
    override def delete(handle: MessageHandle): Unit = around(inner.delete(handle))
    override def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit = around(
      inner.react(handle, emoji, on, txnKey)
    )
    override def registerCommands(specs: List[CommandSpec]): Unit = around(inner.registerCommands(specs))
    override def resolveChat(identity: PlatformIdentity): ChatRef = around(inner.resolveChat(identity))
    override def renderText(text: RichText): List[String] = around(inner.renderText(text))
end MedicationWizardRig
