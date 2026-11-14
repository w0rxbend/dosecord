package dosegram.core.application.menu

import dosegram.contracts.*
import dosegram.core.application.LanguageChat
import dosegram.core.application.capture.MedicationConsentGate
import dosegram.core.application.capture.MedicationScreen
import dosegram.core.application.dose.LogDosePicker
import dosegram.core.application.exports.RecordSummaries
import dosegram.core.application.identity.AccountTimezoneFlow
import dosegram.core.application.medication.AddMedicationFlow
import dosegram.core.application.medication.MedicationDepthChat
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.chat.CallbackToken
import dosegram.core.chat.ChatHandler
import dosegram.core.chat.WizardEngine
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.copy.DoseUnits
import dosegram.core.domain.copy.Formats
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.MedicationDepthCopy
import dosegram.core.domain.copy.MenuCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.ports.CallbackSlot
import dosegram.core.ports.Clock
import dosegram.core.ports.ScheduleStatus
import dosegram.core.ports.StoredMedication
import dosegram.core.ports.StoredOccurrence
import dosegram.core.ports.StoredSchedule
import dosegram.core.ports.StoredScheduleRevision
import dosegram.core.ports.Tx
import dosegram.core.scheduling.ScheduleLifecycle

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Which menu entries are shipped (ROADMAP M1.9: unshipped entries are hidden by flag, never shown as "under
  * development"). Habits (M7.4), Reminders (M7.6), Stats (M3.4) and Edit (M3.2) stay hidden until their slices land;
  * Log dose shipped in M1.10 and is always shown.
  */
final case class MenuFlags(
    habits: Boolean = false,
    reminders: Boolean = false,
    stats: Boolean = false,
    edit: Boolean = false
)

/** The menu, Today, History, Settings and pause/resume/archive chat side of ROADMAP M1.9 (menu tree per M1.4a and
  * docs/MEDICATION_REMINDER_UX.md), with the M1.10 Log dose entry unflagged. Sits between the M1.10
  * [[dosegram.core.application.dose.DoseIntakeHandler]] (which owns every dose-control tap) and the plain command
  * handlers: menu taps arrive as slot tokens minted here; the `/today` `[Log <name>]` rows are direct `dose.log`
  * tokens.
  *
  * Lifecycle ops are revisions through the M1.5 [[ScheduleLifecycle]] transaction halves, so they commit inside the
  * mediator's per-event transaction.
  */
final class MenuHandler(
    codec: CallbackCodec,
    clock: Clock,
    lifecycle: ScheduleLifecycle,
    flags: MenuFlags = MenuFlags(),
    inner: ChatHandler
) extends ChatHandler:

  import MenuHandler.*

  private val gate = MedicationConsentGate(codec, clock)

  private var flowStarter: Option[(InboundEvent, Principal, String, Tx) => Reply] = None

  /** Wired by the composition root: starting a wizard from a menu entry goes through the engine (sessions, tokens). */
  def wireEngine(engine: WizardEngine): Unit =
    flowStarter = Some((event, principal, flowId, tx) => engine.startFlow(event, principal, flowId, tx))

  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    event.body match
      case Inbound.CommandInvoked(CommandMenu, _, _) =>
        guardLinked(event, principal)(mainMenu(event, principal, tx, source = None))
      case Inbound.CommandInvoked(CommandToday, _, _) =>
        guardLinked(event, principal):
          gate.guarded(event, principal, tx)(MedicationScreen.Today)(todayView(event, principal, tx, source = None))
      case Inbound.CommandInvoked(CommandHistory, _, _) =>
        guardLinked(event, principal):
          gate.guarded(event, principal, tx)(MedicationScreen.History):
            historyView(event, principal, tx, source = None)
      case Inbound.CommandInvoked(CommandMeds, _, _) =>
        guardLinked(event, principal):
          gate.guarded(event, principal, tx)(MedicationScreen.Medications):
            medicationsMenu(event, principal, tx, source = None, notice = None)
      case Inbound.CommandInvoked(CommandApp, _, _) =>
        guardLinked(event, principal)(appCard(event))
      case Inbound.InteractionSubmitted(ref, source) => onTap(event, principal, ref, source, tx)
      case _                                         => inner.handle(event, principal, tx)

  private def guardLinked(event: InboundEvent, principal: Principal)(reply: => Reply): Reply =
    if principal.accountId.isEmpty then
      Reply(followUps =
        List(
          outbound(
            IdentityCopy.createFirst,
            s"menu:unlinked:${event.vendor}:${event.vendorEventId}",
            event
          )
        )
      )
    else reply

  // ---------- Taps ----------

  private def onTap(
      event: InboundEvent,
      principal: Principal,
      ref: CallbackRef,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    ActionRegistry.byId(ref.actionId) match
      case Some(entry) if entry.name == MenuOpenAction.name || entry.name == MenuOpenFormAction.name =>
        tx.slots.loadForUpdate(ref.subject) match
          case Some(slot) if slot.accountId == principal.accountId.map(_.uuid) =>
            dispatch(WizardDocument.slotPayloadKey(slot.payload), event, principal, source, tx)
          case _ => Reply(toast = Some(ReminderCopy.staleControlToast))
      // Dose-control taps (the /today [Taken][Skip] rows, reminder rows, the log picker) are direct-mode tokens
      // handled by the M1.10 DoseIntakeHandler in front of this handler.
      case _ => inner.handle(event, principal, tx)

  /** A menu entry of the medication domain is behind its consent (PRODUCT_SPEC R-IDC-9); the main menu, the account
    * menu and the timezone are not.
    */
  private def dispatch(
      key: String,
      event: InboundEvent,
      principal: Principal,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    medicationScreenOf(key) match
      case Some(screen) => gate.guarded(event, principal, tx)(screen)(open(key, event, principal, source, tx))
      case None         => open(key, event, principal, source, tx)

  private def open(
      key: String,
      event: InboundEvent,
      principal: Principal,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    key match
      case KeyRoot        => mainMenu(event, principal, tx, source)
      case KeyMedications => medicationsMenu(event, principal, tx, source, notice = None)
      case KeyAccount     => accountMenu(event, principal, tx, source)
      case KeyToday       => todayView(event, principal, tx, source)
      case KeyHistory     => historyView(event, principal, tx, source)
      case KeySettings    => settingsView(event, principal, tx, source)
      case KeyAdd         => guardLinked(event, principal)(startWizard(event, principal, AddMedicationFlow.Id, tx))
      case KeyTimezone    => guardLinked(event, principal)(startWizard(event, principal, AccountTimezoneFlow.Id, tx))
      case key if key.startsWith(KeyPausePrefix) =>
        withSchedule(key.drop(KeyPausePrefix.length), principal, tx): (medication, schedule) =>
          lifecycle.pauseInTx(tx, schedule.id, clock.now())
          medicationsMenu(event, principal, tx, source, notice = Some(MenuCopy.pausedConfirm(medication.name)))
      case key if key.startsWith(KeyResumePrefix) =>
        withSchedule(key.drop(KeyResumePrefix.length), principal, tx): (medication, schedule) =>
          lifecycle.resumeInTx(tx, schedule.id, clock.now())
          val next = tx.occurrences
            .nextScheduledAfter(schedule.id, clock.now())
            .map(at => formatDayTime(at, schedule.tz))
          val notice = next
            .map(when => MenuCopy.resumedConfirm(medication.name, when))
            .getOrElse(MenuCopy.resumedConfirmNoNext(medication.name))
          medicationsMenu(event, principal, tx, source, notice = Some(notice))
      case key if key.startsWith(KeyArchiveConfirmPrefix) =>
        withSchedule(key.drop(KeyArchiveConfirmPrefix.length), principal, tx): (medication, schedule) =>
          lifecycle.archiveInTx(tx, schedule.id, clock.now())
          medicationsMenu(event, principal, tx, source, notice = Some(MenuCopy.archivedConfirm(medication.name)))
      case key if key.startsWith(KeyArchivePrefix) =>
        withSchedule(key.drop(KeyArchivePrefix.length), principal, tx): (medication, _) =>
          Reply(replace =
            Some(
              OutboundMessage(
                body = List(paragraph(MenuCopy.archiveConfirmPrompt(medication.name))),
                blocks =
                  List(
                    Block.Choices(
                      ChoiceSet(
                        id = "menu.archive.confirm",
                        choices = List(
                          Choice.callback(
                            Labels.Archive,
                            mint(
                              event.chat,
                              principal.accountId.map(_.uuid),
                              MenuOpenAction,
                              KeyArchiveConfirmPrefix + medication.id,
                              tx
                            ).wire,
                            ChoiceStyle.Danger
                          ),
                          Choice.callback(
                            Labels.Cancel,
                            mint(event.chat, principal.accountId.map(_.uuid), MenuOpenAction, KeyMedications, tx).wire
                          )
                        )
                      )
                    )
                  ),
                dedupeKey = s"menu:archive:${medication.id}:${event.vendorEventId}",
                correlationId = s"${event.vendor}:${event.vendorEventId}",
                replaces = source
              )
            )
          )
      case KeyLogDose => guardLinked(event, principal)(LogDosePicker.reply(codec, event, principal, tx))
      case _          => Reply(toast = Some(ReminderCopy.staleControlToast))

  private def startWizard(
      event: InboundEvent,
      principal: Principal,
      flowId: String,
      tx: Tx
  ): Reply =
    flowStarter
      .map(_.apply(event, principal, flowId, tx))
      .getOrElse(Reply(toast = Some(ReminderCopy.failureToast)))

  /** The medication + schedule behind a `menu:<op>:<medicationId>` slot key; a stale key gets the stale toast. */
  private def withSchedule(
      medicationId: String,
      principal: Principal,
      tx: Tx
  )(f: (StoredMedication, StoredSchedule) => Reply): Reply =
    (for
      id <- uuidOf(medicationId)
      medication <- tx.medications.get(id) if principal.accountId.map(_.uuid).contains(medication.accountId)
      schedule <- tx.schedules.listForMedication(id).headOption
    yield f(medication, schedule))
      .getOrElse(Reply(toast = Some(ReminderCopy.staleControlToast)))

  private def uuidOf(text: String): Option[UUID] =
    try Some(UUID.fromString(text))
    catch case _: IllegalArgumentException => None

  // ---------- Menus ----------

  private def mainMenu(event: InboundEvent, principal: Principal, tx: Tx, source: Option[MessageHandle]): Reply =
    val chat = event.chat
    val accountId = principal.accountId.map(_.uuid)
    val entries =
      List(KeyToday -> MenuCopy.topLevel.head, KeyMedications -> MenuCopy.topLevel(1)) ++
        (if flags.habits then List("menu:habits" -> MenuCopy.topLevel(2)) else Nil) ++
        (if flags.reminders then List("menu:reminders" -> MenuCopy.topLevel(3)) else Nil) ++
        (if flags.stats then List("menu:stats" -> MenuCopy.topLevel(4)) else Nil) ++
        List(KeyAccount -> MenuCopy.topLevel(5))
    Reply(replace =
      Some(
        OutboundMessage(
          body = List(paragraph(MenuCopy.mainPrompt)),
          blocks = List(
            Block.Choices(
              ChoiceSet(
                id = "menu.main",
                choices = entries.map((key, label) =>
                  Choice.callback(label, mint(chat, accountId, MenuOpenAction, key, tx).wire)
                )
              )
            )
          ),
          dedupeKey = s"menu:main:${event.vendor}:${event.vendorEventId}",
          correlationId = s"${event.vendor}:${event.vendorEventId}",
          replaces = source
        )
      )
    )

  /** `/app`: a link choice, so no token is minted and nothing comes back from the tap (ADR-015). */
  private def appCard(event: InboundEvent): Reply =
    Reply(followUps =
      List(
        OutboundMessage(
          body = List(paragraph(MenuCopy.appPrompt)),
          blocks = List(Block.Choices(ChoiceSet(id = "menu.app", choices = List(openAppChoice)))),
          dedupeKey = s"menu:app:${event.vendor}:${event.vendorEventId}",
          correlationId = s"${event.vendor}:${event.vendorEventId}"
        )
      )
    )

  private def accountMenu(event: InboundEvent, principal: Principal, tx: Tx, source: Option[MessageHandle]): Reply =
    val chat = event.chat
    val accountId = principal.accountId.map(_.uuid)
    Reply(replace =
      Some(
        OutboundMessage(
          body = List(paragraph(MenuCopy.accountPrompt)),
          blocks = List(
            Block.Choices(
              ChoiceSet(
                id = "menu.account",
                choices = List(
                  Choice.callback(Labels.Timezone, mint(chat, accountId, MenuOpenAction, KeyTimezone, tx).wire)
                ) ++ accountId.map(id => Choice.callback(Labels.Language, LanguageChat.openToken(codec, id))) ++ List(
                  Choice.callback(Labels.Back, mint(chat, accountId, MenuOpenAction, KeyRoot, tx).wire)
                )
              )
            )
          ),
          dedupeKey = s"menu:account:${event.vendor}:${event.vendorEventId}",
          correlationId = s"${event.vendor}:${event.vendorEventId}",
          replaces = source
        )
      )
    )

  private def medicationsMenu(
      event: InboundEvent,
      principal: Principal,
      tx: Tx,
      source: Option[MessageHandle],
      notice: Option[String]
  ): Reply =
    val chat = event.chat
    val account = principal.accountId.get.uuid
    val accountId = principal.accountId.map(_.uuid)
    val medications = tx.medications.listForAccount(account)
    val rows = medications.flatMap: medication =>
      tx.schedules
        .listForMedication(medication.id)
        .headOption
        .filterNot(_.status == ScheduleStatus.Archived)
        .map(medication -> _)
    val body = notice.map(paragraph).toList ++
      List(paragraph(MenuCopy.medicationsPrompt)) ++
      (if rows.isEmpty then List(paragraph(MenuCopy.medicationsEmpty))
       else rows.map((medication, schedule) => paragraph(medicationLine(tx, medication, schedule))))
    val toggles = rows.map: (medication, schedule) =>
      val paused = schedule.status == ScheduleStatus.Paused
      val toggle =
        if paused then
          Choice.callback(
            MenuCopy.medicationsResume,
            mint(chat, accountId, MenuOpenAction, KeyResumePrefix + medication.id, tx).wire,
            ChoiceStyle.Primary
          )
        else
          Choice.callback(
            MenuCopy.medicationsPause,
            mint(chat, accountId, MenuOpenAction, KeyPausePrefix + medication.id, tx).wire
          )
      // R-REG-5: a pause that ends on its own, offered next to the open-ended one (ROADMAP G9.12).
      val pauseFor = Option.unless(paused):
        Choice.callback(
          MedicationDepthCopy.pauseFor,
          codec.encode(CallbackMode.Direct, MedicationDepthChat.PauseFor, medication.id, 0).wire
        )
      Block.Choices(
        ChoiceSet(
          id = s"menu.medications.toggle.${medication.id}",
          choices = List(toggle) ++ pauseFor.toList ++ List(
            Choice.callback(
              Labels.Archive,
              mint(chat, accountId, MenuOpenAction, KeyArchivePrefix + medication.id, tx).wire
            )
          )
        )
      )
    val actions = List(
      Choice.callback(MenuCopy.medicationsTodaysDoses, mint(chat, accountId, MenuOpenAction, KeyToday, tx).wire),
      Choice.callback(
        MenuCopy.medicationsAdd,
        mint(chat, accountId, MenuOpenFormAction, KeyAdd, tx).wire,
        ChoiceStyle.Primary
      ),
      Choice.callback(MenuCopy.medicationsLogDose, mint(chat, accountId, MenuOpenAction, KeyLogDose, tx).wire),
      Choice.callback(MenuCopy.medicationsSettings, mint(chat, accountId, MenuOpenAction, KeySettings, tx).wire),
      Choice.callback(MenuCopy.medicationsHistory, mint(chat, accountId, MenuOpenAction, KeyHistory, tx).wire)
    )
    val blocks =
      toggles ++ List(
        Block.Choices(ChoiceSet(id = "menu.medications.actions", choices = actions)),
        Block.Choices(
          ChoiceSet(
            id = "menu.nav",
            choices = List(Choice.callback(Labels.Back, mint(chat, accountId, MenuOpenAction, KeyRoot, tx).wire))
          )
        )
      )
    Reply(replace =
      Some(
        OutboundMessage(
          body = body,
          blocks = blocks,
          dedupeKey = s"menu:medications:${event.vendor}:${event.vendorEventId}",
          correlationId = s"${event.vendor}:${event.vendorEventId}",
          replaces = source
        )
      )
    )

  /** `Vitamin D 1000 IU — every day 09:00`; a paused schedule shows the toggle line `Paused since Tue`. */
  private def medicationLine(tx: Tx, medication: StoredMedication, schedule: StoredSchedule): String =
    val title = doseTitle(medication.name, medication.doseAmount, medication.doseUnit)
    if schedule.status.dbValue == "paused" then
      val pausedAt = tx.revisions
        .list(schedule.id)
        .filter(_.reason.contains("pause"))
        .lastOption
        .map(_.effectiveFrom)
      val day = pausedAt.map(at => formatDay(at, schedule.tz)).getOrElse("")
      s"$title — ${MenuCopy.pausedSince(day)}"
    else
      tx.revisions.latest(schedule.id) match
        case Some(revision) => s"$title — ${ruleSummary(revision)}"
        case None           => title

  // ---------- Today ----------

  private def todayView(event: InboundEvent, principal: Principal, tx: Tx, source: Option[MessageHandle]): Reply =
    val account = principal.accountId.get.uuid
    val today = clock.now().atZone(userZone(tx, account)).toLocalDate
    val rows = tx.occurrences
      .listForAccountBetween(account, today, today)
      .filterNot(_.status == OccurrenceStatus.Cancelled)
    val (open, resolved) = rows.partition(_.status.isOpen)
    val asNeeded = asNeededMedications(tx, account)
    val doseRows = open.sortBy(_.scheduledFor).take(MaxTodayRows)
    val logRows = asNeeded.take(math.max(0, MaxTodayRows - doseRows.size))
    val hidden = open.size - doseRows.size + asNeeded.size - logRows.size
    val body = List(paragraph(MenuCopy.medicationsTodaysDoses)) ++
      (if doseRows.isEmpty && logRows.isEmpty && resolved.isEmpty then List(paragraph(MenuCopy.todayEmpty))
       else Nil) ++
      doseRows.map(doseLine) ++
      logRows.map(medication => paragraph(MenuCopy.asNeededRow(medication.name))) ++
      resolved.sortBy(_.scheduledFor).map(doseLine) ++
      (if hidden > 0 then List(paragraph(MenuCopy.todayMore(hidden))) else Nil)
    val doseBlocks = doseRows.map: occ =>
      Block.Choices(
        ChoiceSet(
          id = s"today.dose.${occ.id}",
          choices = List(
            Choice.callback(Labels.Taken, directToken("dose.taken", occ.id), ChoiceStyle.Success),
            Choice.callback(Labels.Skip, directToken("dose.skip", occ.id))
          )
        )
      )
    val logBlocks = logRows.map: medication =>
      Block.Choices(
        ChoiceSet(
          id = s"today.log.${medication.id}",
          choices = List(
            Choice.callback(
              ControlLimits.fitLabel(MenuCopy.logDoseLabel(medication.name)),
              directToken("dose.log", medication.id)
            )
          )
        )
      )
    Reply(replace =
      Some(
        OutboundMessage(
          body = body,
          blocks = doseBlocks ++ logBlocks :+ navBlock(event, principal, tx, List(openAppChoice)),
          dedupeKey = s"menu:today:${event.vendor}:${event.vendorEventId}",
          correlationId = s"${event.vendor}:${event.vendorEventId}",
          replaces = source
        )
      )
    )

  /** `09:00 Vitamin D, 1000 IU — scheduled`; the status phrase comes from the M1.4a catalogue. */
  private def doseLine(occ: StoredOccurrence): Node =
    val snapshot = occ.doseSnapshot
    val title = snapshot
      .map(s => doseTitle(s.medicationName, s.doseAmount, s.doseUnit))
      .getOrElse(MenuCopy.yourDose)
    val time = occ.localTime.map(t => f"${t.getHour}%02d:${t.getMinute}%02d").getOrElse("")
    paragraph(s"$time $title — ${statusText(occ)}")

  private def statusText(occ: StoredOccurrence): String =
    val tz = occ.tz
    occ.status match
      case OccurrenceStatus.Pending => MenuCopy.statusScheduled
      case OccurrenceStatus.Due     => MenuCopy.statusDue
      case OccurrenceStatus.Snoozed =>
        MenuCopy.statusSnoozedUntil(formatTime(occ.state.snoozedUntil.getOrElse(occ.scheduledFor), tz))
      case OccurrenceStatus.Taken =>
        val at = occ.state.effectiveAt.orElse(occ.state.takenAt).getOrElse(occ.scheduledFor)
        val late =
          if occ.state.effectiveAt.exists(_.isAfter(occ.state.dueWindowEnd)) then s" (${MenuCopy.takenLateSuffix})"
          else ""
        MenuCopy.statusTakenAt(formatTime(at, tz)) + late
      case OccurrenceStatus.Skipped =>
        MenuCopy.statusSkippedAt(formatTime(occ.state.skippedAt.getOrElse(occ.scheduledFor), tz))
      case OccurrenceStatus.Missed    => MenuCopy.statusMissed
      case OccurrenceStatus.Unknown   => MenuCopy.statusUnknown
      case OccurrenceStatus.Cancelled => MenuCopy.statusUnknown

  // ---------- History ----------

  private def historyView(event: InboundEvent, principal: Principal, tx: Tx, source: Option[MessageHandle]): Reply =
    val account = principal.accountId.get.uuid
    val zone = userZone(tx, account)
    val today = clock.now().atZone(zone).toLocalDate
    val rows = tx.occurrences
      .listForAccountBetween(account, today.minusDays(6), today)
      .filterNot(_.status == OccurrenceStatus.Cancelled)
    val body =
      if rows.isEmpty then List(paragraph(MenuCopy.historyTitle), paragraph(MenuCopy.historyEmpty))
      else
        List(paragraph(MenuCopy.historyTitle)) ++
          rows
            .groupBy(_.localDate)
            .toList
            .sortBy(_._1)
            .flatMap((day, dayRows) => paragraph(formatDayHeader(day)) +: dayRows.sortBy(_.scheduledFor).map(doseLine))
    Reply(replace =
      Some(
        OutboundMessage(
          body = body,
          blocks = List(navBlock(event, principal, tx, Nil)),
          dedupeKey = s"menu:history:${event.vendor}:${event.vendorEventId}",
          correlationId = s"${event.vendor}:${event.vendorEventId}",
          replaces = source
        )
      )
    )

  // ---------- Settings ----------

  private def settingsView(event: InboundEvent, principal: Principal, tx: Tx, source: Option[MessageHandle]): Reply =
    val account = principal.accountId.get.uuid
    val lines = tx.medications
      .listForAccount(account)
      .flatMap: medication =>
        tx.schedules
          .listForMedication(medication.id)
          .headOption
          .flatMap(schedule => tx.revisions.latest(schedule.id))
          .map: revision =>
            val offset = revision.policy.initialOffsetMinutes match
              case 0          => MenuCopy.policyOffsetAtDoseTime
              case n if n < 0 => MenuCopy.policyOffsetBefore(-n)
              case n          => MenuCopy.policyOffsetAfter(n)
            paragraph(
              MenuCopy.settingsPolicyLine(
                medication.name,
                offset,
                MenuCopy.snoozeList(revision.policy.snoozeOptionsMinutes),
                revision.policy.missAfterMinutes
              )
            )
    Reply(replace =
      Some(
        OutboundMessage(
          body = List(paragraph(MenuCopy.settingsTitle), paragraph(MenuCopy.settingsIntro)) ++ lines,
          blocks = List(navBlock(event, principal, tx, Nil)),
          dedupeKey = s"menu:settings:${event.vendor}:${event.vendorEventId}",
          correlationId = s"${event.vendor}:${event.vendorEventId}",
          replaces = source
        )
      )
    )

  // ---------- Helpers ----------

  /** The row under a card: Menu, then the card's own links (`extra`). */
  private def navBlock(event: InboundEvent, principal: Principal, tx: Tx, extra: List[Choice]): Block =
    Block.Choices(
      ChoiceSet(
        id = "menu.nav",
        choices = Choice.callback(
          MenuCopy.menuLabel,
          mint(event.chat, principal.accountId.map(_.uuid), MenuOpenAction, KeyRoot, tx).wire
        ) :: extra
      )
    )

  private def asNeededMedications(tx: Tx, account: UUID): List[StoredMedication] =
    tx.medications
      .listForAccount(account)
      .filter: medication =>
        tx.schedules
          .listForMedication(medication.id)
          .headOption
          .exists(schedule => schedule.kind == "as_needed" && schedule.status.dbValue == "active")

  private def userZone(tx: Tx, account: UUID): ZoneId =
    ZoneId.of(tx.accounts.timezoneOf(account).getOrElse("UTC"))

  /** The schedule in the words of the wizard and the record summary, in the account's language. */
  private def ruleSummary(revision: StoredScheduleRevision): String = RecordSummaries.ruleInWords(revision.rule)

  private def doseTitle(name: String, amount: Option[BigDecimal], unit: Option[String]): String =
    amount.flatMap(value => DoseUnits.doseText(Some(value), unit)).fold(name)(dose => s"$name $dose")

  private def mint(chat: ChatRef, accountId: Option[UUID], action: ActionEntry, key: String, tx: Tx): CallbackToken =
    val slotId = UUID.randomUUID()
    tx.slots.insert(
      CallbackSlot(
        id = slotId,
        accountId = accountId,
        chat = WizardDocument.chatToJson(chat),
        payload = WizardDocument.slotPayloadToJson(key),
        sessionId = None,
        stepSeq = None,
        expiresAt = None
      )
    )
    codec.encode(CallbackMode.Slot, action, slotId, 0)

  private def directToken(actionName: String, subject: UUID): String =
    codec
      .encode(CallbackMode.Direct, ActionRegistry.byName(actionName).get, subject, 0)
      .wire

  private def outbound(text: String, dedupeKey: String, event: InboundEvent): OutboundMessage =
    OutboundMessage(
      body = List(paragraph(text)),
      dedupeKey = dedupeKey,
      correlationId = s"${event.vendor}:${event.vendorEventId}"
    )

  private def paragraph(text: String): Node = Node.Paragraph(List(Inline.Text(text)))

  private def formatTime(at: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(at)

  private def formatDay(at: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("EEE", Formats.locale).withZone(zone).format(at)

  private def formatDayTime(at: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("EEE HH:mm", Formats.locale).withZone(zone).format(at)

  private def formatDayHeader(day: LocalDate): String =
    day.format(DateTimeFormatter.ofPattern("EEE d MMM", Formats.locale))

private object MenuHandler:
  val CommandMenu = "menu"
  val CommandToday = "today"
  val CommandHistory = "history"
  val CommandMeds = "meds"
  val CommandApp = "app"

  /** The Mini App route of its Today screen (MINI_APP section 3.4). */
  val AppHome: AppRoute = AppRoute("today")

  /** Opens the Mini App on Today; an adapter without a Mini App leaves it out (BOT_UX BUX-KB-05). */
  private val openAppChoice: Choice = Choice.openApp(MenuCopy.openApp, AppHome)

  val MenuOpenAction: ActionEntry = ActionRegistry.byName("menu.open").get
  val MenuOpenFormAction: ActionEntry = ActionRegistry.byName("menu.open_form").get

  // Slot payload keys.
  val KeyRoot = "menu:root"
  val KeyMedications = "menu:medications"
  val KeyAccount = "menu:account"
  val KeyToday = "menu:today"
  val KeyHistory = "menu:history"
  val KeySettings = "menu:settings"
  val KeyAdd = "menu:add"
  val KeyTimezone = "menu:tz"
  val KeyPausePrefix = "menu:pause:"
  val KeyResumePrefix = "menu:resume:"
  val KeyArchivePrefix = "menu:archive:"
  val KeyArchiveConfirmPrefix = "menu:archive:confirm:"
  val KeyLogDose: String = LogDosePicker.MenuKey

  /** The 8 x 3 digest cap of DESIGN.md section 4.3 applied to the interactive Today. */
  val MaxTodayRows = 8

  /** The screen a medication entry belongs to, which is the one its consent prompt leads back to; `None` for an entry
    * outside the medication domain.
    */
  def medicationScreenOf(key: String): Option[MedicationScreen] =
    key match
      case KeyRoot | KeyAccount | KeyTimezone => None
      case KeyToday | KeyLogDose              => Some(MedicationScreen.Today)
      case KeyHistory                         => Some(MedicationScreen.History)
      case _                                  => Some(MedicationScreen.Medications)
end MenuHandler
