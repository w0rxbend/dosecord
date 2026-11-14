package dosegram.core.application.capture

import dosegram.contracts.Block
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.InboundEvent
import dosegram.contracts.Inline
import dosegram.contracts.MessageHandle
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.CardLook
import dosegram.core.ports.Tx

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One inbound event of a linked account as the capture flows see it: who, in which zone, the message a tap came from,
  * and the transaction of the event, on which every flow reads and records. It builds the messages of the answer, so
  * every flow keys and correlates them the same way.
  */
private[capture] final case class Turn(
    event: InboundEvent,
    principal: Principal,
    accountId: UUID,
    zone: ZoneId,
    source: Option[MessageHandle],
    tx: Tx
):

  /** The idempotency key of whatever this event records: one update records at most once. */
  def clientKey: String = s"${event.vendor}:${event.vendorEventId}"

  /** A text message with one row of buttons per inner list. `kind` tells the messages of one answer apart. */
  def message(kind: String, lines: List[String], rows: List[List[Choice]]): OutboundMessage =
    OutboundMessage(
      body = lines.map(line => Node.Paragraph(List(Inline.Text(line)))),
      blocks = rows
        .filter(_.nonEmpty)
        .zipWithIndex
        .map((row, index) => Block.Choices(ChoiceSet(s"$kind.$index", choices = row))),
      dedupeKey = s"$kind:${event.vendor}:${event.vendorEventId}",
      correlationId = s"${event.vendor}:${event.vendorEventId}"
    )

  /** The answer takes the place of the message the tap came from; typed input gets a new message. */
  def show(message: OutboundMessage): Reply =
    source match
      case Some(handle) => Reply(replace = Some(message.copy(replaces = Some(handle))))
      case None         => Reply(followUps = List(message))

  def toast(text: String): Reply = Reply(toast = Some(text))

  /** The theme and discreet mode the account set for its images, read on this event's transaction. */
  def look: CardLook = CardLook.of(tx.accounts.settingsOf(accountId).getOrElse(AccountSettings.Default))

  def localTime(at: Instant): String = Turn.Clock.withZone(zone).format(at)

  def localDate(at: Instant): String = ConsentCopy.dateOf(at, zone)

private[capture] object Turn:
  private val Clock = DateTimeFormatter.ofPattern("HH:mm")

/** The direct-mode tokens of the capture buttons (ADR-006): the subject is the account, or the entry for an Undo. */
private[capture] final class Tokens(codec: CallbackCodec):
  def direct(action: ActionEntry, subject: UUID, value: Long): String =
    codec.encode(CallbackMode.Direct, action, subject, value).wire

private[capture] object CaptureActions:
  val ConsentStep: ActionEntry = named("consent.step")
  val ConsentWithdraw: ActionEntry = named("consent.withdraw")
  val WaterAdd: ActionEntry = named("water.add")
  val WaterUndo: ActionEntry = named("water.undo")
  val WaterGoal: ActionEntry = named("water.goal")
  val WaterGoalAsk: ActionEntry = named("water.goal_ask")
  val WaterImage: ActionEntry = named("water.image")
  val MoodCheckIn: ActionEntry = named("mood.checkin")
  val MoodEnergy: ActionEntry = named("mood.energy")
  val MoodUndo: ActionEntry = named("mood.undo")
  val StatsShow: ActionEntry = named("stats.show")
  val StatsImage: ActionEntry = named("stats.image")
  val SleepStart: ActionEntry = named("sleep.start")
  val SleepUp: ActionEntry = named("sleep.up")
  val SleepRate: ActionEntry = named("sleep.rate")
  val SleepUndo: ActionEntry = named("sleep.undo")
  val SleepStartUndo: ActionEntry = named("sleep.start_undo")
  val SleepImage: ActionEntry = named("sleep.image")
  val StatsMonth: ActionEntry = named("stats.month")
  val StatsMonthImage: ActionEntry = named("stats.month_image")
  val InsightsShow: ActionEntry = named("insights.show")
  val InsightsImage: ActionEntry = named("insights.image")
  val InsightsCompare: ActionEntry = named("insights.compare")

  val all: Set[ActionEntry] = Set(
    ConsentStep,
    ConsentWithdraw,
    WaterAdd,
    WaterUndo,
    WaterGoal,
    WaterGoalAsk,
    WaterImage,
    MoodCheckIn,
    MoodEnergy,
    MoodUndo,
    StatsShow,
    StatsImage,
    SleepStart,
    SleepUp,
    SleepRate,
    SleepUndo,
    SleepStartUndo,
    SleepImage,
    StatsMonth,
    StatsMonthImage,
    InsightsShow,
    InsightsImage,
    InsightsCompare
  )

  private def named(name: String): ActionEntry =
    ActionRegistry
      .byName(name)
      .getOrElse(throw IllegalStateException(s"action '$name' is missing from the registry"))
