package dosegram.core.scheduling

import dosegram.contracts.Block
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.ChoiceStyle
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.application.capture.MedicationConsentGate
import dosegram.core.application.medication.InventoryService
import dosegram.core.application.medication.InventoryView
import dosegram.core.application.medication.MedicationDepthChat
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.MedicationDepthCopy
import dosegram.core.domain.inventory.Inventory
import dosegram.core.domain.reminders.DayAnchors
import dosegram.core.domain.reminders.DayProfile
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.Clock
import dosegram.core.ports.IdSource
import dosegram.core.ports.MedicationEdit
import dosegram.core.ports.MedicationStatus
import dosegram.core.ports.NewOutboxMessage
import dosegram.core.ports.OutboxOp
import dosegram.core.ports.PausedSchedule
import dosegram.core.ports.StoredMedication
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.util.control.NonFatal

/** The two timed duties of the medication-depth track, at the prompt loop's poll, one transaction per row:
  *
  *   - a pause with a resume date ends at local midnight of that date in the schedule's zone, with one silent notice
  *     (PRODUCT_SPEC R-REG-5, AC-REG-5);
  *   - the refill notice (R-REG-14, BOT_UX section 10.5, VISUALS section 10.1): once a count is at or below the user's
  *     threshold, one silent text at the account's first touchpoint of the day (the wake time of the day profile) and
  *     outside quiet hours, again every 3 days until a refill, a new count or "Stop for this pack". It states a count
  *     and a date from the user's own entries and gives no advice; in discreet mode it names nothing.
  *
  * No vendor call happens here: the outbox dispatcher sends, with the prompt message class.
  */
final class MedicationDepthLoop(
    uow: UnitOfWork,
    clock: Clock,
    ids: IdSource,
    codec: CallbackCodec,
    lifecycle: ScheduleLifecycle,
    onError: (UUID, Throwable) => Unit,
    batch: Int = MedicationDepthLoop.Batch
):
  import MedicationDepthLoop.*

  /** One pass: the due resumes, then the refill notices; returns the number of messages queued. */
  def tick(): Int =
    val now = clock.now()
    val resumes = uow.transaction(_.medicationDepth.pauses.dueResumes(now, batch))
    val resumed =
      resumes.count(due =>
        guarded(due.scheduleId)(uow.transaction(tx => inLanguage(tx, due.accountId)(resume(tx, due, now))))
      )
    resumed + notices(now, after = None)

  /** Every page of candidates: a page of counts that owe nothing must not hold back the counts after it. */
  @scala.annotation.tailrec
  private def notices(now: Instant, after: Option[UUID], sent: Int = 0): Int =
    val page = uow.transaction(_.medicationDepth.inventory.noticeCandidates(now.minus(Repeat), after, batch))
    val total = sent + page.count(inventory =>
      guarded(inventory.medicationId)(
        uow.transaction(tx => inLanguage(tx, inventory.accountId)(notice(tx, inventory, now)))
      )
    )
    if page.size < batch then total else notices(now, page.lastOption.map(_.medicationId), total)

  /** Each notice is written in its account's language (ROADMAP G2.22), as the prompt loop writes its plans. */
  private def inLanguage[A](tx: Tx, accountId: UUID)(body: => A): A =
    Lang.within(tx.accounts.languageOf(accountId))(body)

  private def guarded(id: UUID)(work: => Boolean): Boolean =
    try work
    catch
      case NonFatal(e) =>
        onError(id, e)
        false

  private def resume(tx: Tx, due: PausedSchedule, now: Instant): Boolean =
    tx.schedules.getForUpdate(due.scheduleId).filter(_.status.dbValue == "paused") match
      case None           => false
      case Some(schedule) =>
        lifecycle.resumeInTx(tx, schedule.id, now)
        val medication = tx.medications.get(schedule.medicationId)
        medication.filter(_.status == MedicationStatus.Paused).foreach(markActive(tx, _, now))
        val name = medication.map(_.name).getOrElse("")
        enqueue(
          tx,
          due.accountId,
          s"med.resumed:${schedule.id}:${due.resumeOn}",
          List(MedicationDepthCopy.resumedOn(name)),
          Nil,
          now
        )

  /** A medication paused with its schedules (the Mini App's pause) is active again with them (R-REG-5). */
  private def markActive(tx: Tx, medication: StoredMedication, now: Instant): Unit =
    tx.medications.replace(
      MedicationEdit(
        medication.id,
        medication.accountId,
        medication.updatedAt,
        medication.name,
        medication.doseAmount,
        medication.doseUnit,
        medication.instructions,
        MedicationStatus.Active
      ),
      now
    ): Unit

  private def notice(tx: Tx, candidate: Inventory, now: Instant): Boolean =
    val medication = tx.medications.get(candidate.medicationId).filter(_.status != MedicationStatus.Archived)
    val view = InventoryService.view(tx, candidate.accountId, candidate.medicationId, now)
    (medication, view) match
      case (Some(med), Some(found))
          if found.belowThreshold && MedicationConsentGate.isOpen(tx, candidate.accountId) &&
            atTouchpoint(tx, candidate.accountId, now) =>
        val settings = tx.accounts.settingsOf(candidate.accountId).getOrElse(AccountSettings.Default)
        val lines =
          if settings.isDiscreet then List(MedicationDepthCopy.refillLowDiscreet)
          else
            List(
              MedicationDepthCopy.refillLow(
                med.name,
                found.inventory.onHand,
                MedicationDepthChat.countableUnit(med),
                found.supply.lastCovered
              )
            )
        val countOff = Option.when(found.inventory.countOff)(MedicationDepthCopy.countOff).toList
        val sent = enqueue(
          tx,
          candidate.accountId,
          s"refill.low:${candidate.medicationId}:${now.getEpochSecond / 60}",
          lines ++ countOff,
          buttons(found),
          now
        )
        if sent then tx.medicationDepth.inventory.markNotice(candidate.medicationId, now)
        sent
      case _ => false

  /** "Refilled +N" with the last refill when there is one, "Show card" and "Stop for this pack". */
  private def buttons(view: InventoryView): List[Choice] =
    val medicationId = view.inventory.medicationId
    val refilled = view.inventory.lastRefill.map(quantity =>
      Choice.callback(
        MedicationDepthCopy.refilledLabel(quantity),
        token(MedicationDepthChat.RefillAdd, medicationId, (quantity * 1000).toLong),
        ChoiceStyle.Primary
      )
    )
    refilled.toList ++ List(
      Choice.callback(MedicationDepthCopy.refillShowCard, token(MedicationDepthChat.RefillCard, medicationId, 0)),
      Choice.callback(MedicationDepthCopy.refillStop, token(MedicationDepthChat.RefillStop, medicationId, 0))
    )

  /** At or after the wake time of the account's day, and outside its quiet hours (R-SCH-23). */
  private def atTouchpoint(tx: Tx, accountId: UUID, now: Instant): Boolean =
    val zone = ZoneId.of(tx.accounts.timezoneOf(accountId).getOrElse("UTC"))
    val settings = tx.accounts.settingsOf(accountId).getOrElse(AccountSettings.Default)
    val wake = tx.reminders.dayProfiles.anchors(accountId).getOrElse(DayAnchors.of(DayProfile.Default)).wake
    val local = now.atZone(zone).toLocalTime
    val quiet = settings.quietHours.getOrElse(DayProfile.DefaultQuiet)
    !local.isBefore(wake) && !quiet.contains(local)

  private def enqueue(
      tx: Tx,
      accountId: UUID,
      sendKey: String,
      lines: List[String],
      choices: List[Choice],
      now: Instant
  ): Boolean =
    tx.channels.activePrimaryChannels(accountId).headOption match
      case None         => false
      case Some(target) =>
        val message = OutboundMessage(
          body = lines.map(line => Node.Paragraph(List(Inline.Text(line)))),
          blocks = Option.when(choices.nonEmpty)(Block.Choices(ChoiceSet(sendKey, choices = choices))).toList,
          silent = true,
          dedupeKey = sendKey,
          correlationId = sendKey
        )
        tx.outbox.enqueue(
          NewOutboxMessage(
            id = ids.next(now),
            sendKey = sendKey,
            op = OutboxOp.Send,
            kind = PromptLoop.OutboxKind,
            vendor = target.vendor,
            accountId = Some(accountId),
            channelId = Some(target.channelId),
            payload = OutboundMessage.toJson(message),
            importance = "info",
            nextAttemptAt = now
          )
        )

  private def token(action: ActionEntry, subject: UUID, value: Long): String =
    codec.encode(CallbackMode.Direct, action, subject, value).wire
end MedicationDepthLoop

object MedicationDepthLoop:
  /** R-REG-14 (PROPOSED): the notice repeats every 3 days. */
  val Repeat: Duration = Duration.ofDays(3)
  val Batch = 100
