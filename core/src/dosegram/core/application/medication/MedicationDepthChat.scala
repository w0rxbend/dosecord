package dosegram.core.application.medication

import dosegram.contracts.Block
import dosegram.contracts.CallbackRef
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.ChoiceStyle
import dosegram.contracts.Inbound
import dosegram.contracts.InboundEvent
import dosegram.contracts.Inline
import dosegram.contracts.MessageHandle
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.application.capture.CaptureParts
import dosegram.core.application.capture.MedicationConsentGate
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.chat.ChatHandler
import dosegram.core.domain.copy.DoseUnit
import dosegram.core.domain.copy.DoseUnits
import dosegram.core.domain.copy.MedicationDepthCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.inventory.RefillThreshold
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.CardLook
import dosegram.core.ports.Clock
import dosegram.core.ports.RefillCardView
import dosegram.core.ports.ReportPage
import dosegram.core.ports.ScheduleStatus
import dosegram.core.ports.StoredMedication
import dosegram.core.ports.Tx
import dosegram.core.scheduling.ScheduleLifecycle

import java.time.ZoneId
import java.util.UUID

/** The medication-depth taps of the chat (ROADMAP G9.10, G9.12): the refill notice's buttons, "Pause for..." with its
  * days, and the question before an as-needed log above the user's own limit. Every button names the medication; one of
  * another account, or of a medication without the consent, changes nothing. Anything else goes to `inner`.
  */
final class MedicationDepthChat(
    codec: CallbackCodec,
    clock: Clock,
    lifecycle: ScheduleLifecycle,
    capture: CaptureParts,
    inner: ChatHandler
) extends ChatHandler:

  import MedicationDepthChat.*

  private final case class Tap(event: InboundEvent, accountId: UUID, source: Option[MessageHandle], tx: Tx):
    def key: String = s"${event.vendor}:${event.vendorEventId}"
    def zone: ZoneId = ZoneId.of(tx.accounts.timezoneOf(accountId).getOrElse("UTC"))

    def show(kind: String, lines: List[String], choices: List[Choice] = Nil): Reply =
      val message = OutboundMessage(
        body = lines.map(line => Node.Paragraph(List(Inline.Text(line)))),
        blocks = Option.when(choices.nonEmpty)(Block.Choices(ChoiceSet(kind, choices = choices))).toList,
        dedupeKey = s"$kind:$key",
        correlationId = key
      )
      source match
        case Some(handle) => Reply(replace = Some(message.copy(replaces = Some(handle))))
        case None         => Reply(followUps = List(message))

  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    val answer = event.body match
      case Inbound.InteractionSubmitted(ref, source) =>
        principal.accountId.flatMap(account => tap(Tap(event, account.uuid, source, tx), ref, principal))
      case _ => None
    answer.getOrElse(inner.handle(event, principal, tx))

  private def tap(tap: Tap, ref: CallbackRef, principal: Principal): Option[Reply] =
    ActionRegistry
      .byId(ref.actionId)
      .flatMap:
        case DoseLog                             => limitCheck(tap, ref)
        case action if Own.contains(action.name) =>
          Some(
            ownMedication(tap, ref.subject) match
              case None             => Reply(toast = Some(ReminderCopy.staleControlToast))
              case Some(medication) => onOwn(tap, ref, action, medication, principal)
          )
        case _ => None

  private def onOwn(
      tap: Tap,
      ref: CallbackRef,
      action: ActionEntry,
      medication: StoredMedication,
      principal: Principal
  ): Reply =
    action.name match
      case RefillAdd.name  => refill(tap, medication, BigDecimal(ref.value) / 1000)
      case RefillCard.name => card(tap, medication)
      case RefillStop.name =>
        if tap.tx.medicationDepth.inventory.stopNotices(tap.accountId, medication.id, clock.now()) then
          tap.show("refill.stopped", List(MedicationDepthCopy.refillStopped))
        else Reply(toast = Some(MedicationDepthCopy.refillUnavailable))
      case PauseFor.name if ref.value == 0 => pauseChoices(tap, medication)
      case PauseFor.name                   => pause(tap, medication, ref.value.toInt)
      case LimitConfirm.name               =>
        // The confirmed log is the picker's own log: the same message, so the picker's one-intent guard still holds.
        val logRef = CallbackRef(DoseLog.id, medication.id, 0, slot = false, raw = token(DoseLog, medication.id, 0))
        inner.handle(
          tap.event.copy(body = Inbound.InteractionSubmitted(logRef, tap.source)),
          principal,
          tap.tx
        )
      case _ => tap.show("take.limits.cancelled", List(MedicationDepthCopy.prnCancelled))

  /** A medication of this account, with the medication consent open (PRODUCT_SPEC R-IDC-9). */
  private def ownMedication(tap: Tap, medicationId: UUID): Option[StoredMedication] =
    Option
      .when(MedicationConsentGate.isOpen(tap.tx, tap.accountId))(())
      .flatMap(_ => tap.tx.medications.get(medicationId))
      .filter(_.accountId == tap.accountId)

  // ---------- as needed ----------

  /** A log from the picker that would pass the user's own limit asks first; any other log goes on at once. */
  private def limitCheck(tap: Tap, ref: CallbackRef): Option[Reply] =
    for
      medication <- ownMedication(tap, ref.subject)
      facts <- AsNeededLimits.aboveLimit(tap.tx, tap.accountId, medication.id, tap.zone, clock.now())
    yield tap.show(
      "take.limits.confirm",
      List(MedicationDepthCopy.prnConfirm(medication.name, facts)),
      List(
        Choice.callback(MedicationDepthCopy.prnLogIt, token(LimitConfirm, medication.id, 0), ChoiceStyle.Primary),
        Choice.callback(MedicationDepthCopy.prnCancel, token(LimitCancel, medication.id, 0))
      )
    )

  // ---------- inventory ----------

  private def refill(tap: Tap, medication: StoredMedication, quantity: BigDecimal): Reply =
    tap.tx.medicationDepth.inventory.refill(tap.accountId, medication.id, quantity, tap.key, clock.now()) match
      case Some(inventory) =>
        tap.show(
          "refill.refilled",
          List(MedicationDepthCopy.refilled(medication.name, inventory.onHand, countableUnit(medication)))
        )
      case None => Reply(toast = Some(MedicationDepthCopy.refillUnavailable))

  /** IMG-15 as an Outcome of the notice (VISUALS section 10.1), with its text equivalent as the caption. */
  private def card(tap: Tap, medication: StoredMedication): Reply =
    InventoryService.view(tap.tx, tap.accountId, medication.id, clock.now()) match
      case None       => Reply(toast = Some(MedicationDepthCopy.refillUnavailable))
      case Some(view) =>
        val look = CardLook.of(tap.tx.accounts.settingsOf(tap.accountId).getOrElse(AccountSettings.Default))
        val cardView = cardViewOf(medication, view)
        val caption = if look.isDiscreet then MedicationDepthCopy.refillLowDiscreet else cardView.text
        val message = OutboundMessage(
          body = List(Node.Paragraph(List(Inline.Text(caption)))),
          dedupeKey = s"refill.card:${tap.key}",
          correlationId = tap.key
        )
        capture.cards.refill(cardView, look) match
          case Some(page) =>
            Reply(followUps =
              capture.delivery.answer(tap.tx, tap.accountId, tap.event.chat, List(ReportPage(page, message)))
            )
          case None => Reply(followUps = List(message))

  // ---------- pause for a number of days ----------

  private def pauseChoices(tap: Tap, medication: StoredMedication): Reply =
    tap.show(
      "med.pause_for",
      List(MedicationDepthCopy.pauseForPrompt(medication.name)),
      MedicationDepthCopy.pauseDays.map(days =>
        Choice.callback(MedicationDepthCopy.pauseDaysLabel(days), token(PauseFor, medication.id, days.toLong))
      )
    )

  /** Pauses the medication's active schedule and sets the date it resumes on, in the schedule's zone (R-REG-5). */
  private def pause(tap: Tap, medication: StoredMedication, days: Int): Reply =
    if !MedicationDepthCopy.pauseDays.contains(days) then Reply(toast = Some(ReminderCopy.staleControlToast))
    else
      tap.tx.schedules.listForMedication(medication.id).find(_.status == ScheduleStatus.Active) match
        case None           => Reply(toast = Some(ReminderCopy.staleControlToast))
        case Some(schedule) =>
          val now = clock.now()
          val resumeOn = now.atZone(schedule.tz).toLocalDate.plusDays(days.toLong)
          lifecycle.pauseInTx(tap.tx, schedule.id, now)
          tap.tx.medicationDepth.pauses.setResumeOn(schedule.id, Some(resumeOn), now)
          tap.show("med.paused_until", List(MedicationDepthCopy.pausedUntil(medication.name, resumeOn)))

  private def token(action: ActionEntry, subject: UUID, value: Long): String =
    codec.encode(CallbackMode.Direct, action, subject, value).wire
end MedicationDepthChat

object MedicationDepthChat:
  private def named(name: String): ActionEntry =
    ActionRegistry.byName(name).getOrElse(throw IllegalStateException(s"action '$name' is missing from the registry"))

  val DoseLog: ActionEntry = named("dose.log")
  val RefillAdd: ActionEntry = named("refill.add")
  val RefillCard: ActionEntry = named("refill.card")
  val RefillStop: ActionEntry = named("refill.stop")
  val PauseFor: ActionEntry = named("med.pause_for")
  val LimitConfirm: ActionEntry = named("take.limit_confirm")
  val LimitCancel: ActionEntry = named("take.limit_cancel")

  private val Own: Set[String] =
    Set(RefillAdd, RefillCard, RefillStop, PauseFor, LimitConfirm, LimitCancel).map(_.name)

  /** The unit a medication's count is kept in: its dose unit when countable, otherwise "units" (R-REG-14). */
  def countableUnit(medication: StoredMedication): DoseUnit = DoseUnits.countable(medication.doseUnit)

  /** The card of a count, its text equivalent written from the same values. */
  def cardViewOf(medication: StoredMedication, view: InventoryView): RefillCardView =
    val unit = countableUnit(medication)
    val inventory = view.inventory
    val threshold = inventory.threshold match
      case Some(RefillThreshold.Units(n))   => MedicationDepthCopy.thresholdUnits(n, unit)
      case Some(RefillThreshold.Days(days)) => MedicationDepthCopy.thresholdDays(days)
      case None                             => MedicationDepthCopy.thresholdDays(7)
    val thresholdUnits = inventory.threshold.collect { case RefillThreshold.Units(n) => n }
    val label = medication.name
    RefillCardView(
      label,
      inventory.onHand,
      unit.noun(inventory.onHand),
      thresholdUnits,
      threshold,
      view.supply.lastCovered,
      MedicationDepthCopy.refillCardText(label, inventory.onHand, unit, threshold, view.supply.lastCovered)
    )
end MedicationDepthChat
