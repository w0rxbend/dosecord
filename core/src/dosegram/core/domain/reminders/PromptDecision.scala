package dosegram.core.domain.reminders

import dosegram.core.domain.QuietHours

import java.time.Duration
import java.time.Instant

/** The terminal states of a prompt occurrence (ARCHITECTURE section 8.4). A prompt is decided once, at its due time,
  * and is recorded in the state the decision gives it: `sent`, or `suppressed` with its reason. A sent prompt ends as
  * `answered` or `expired`. The `db` strings match the `prompt_occurrences.status` CHECK.
  */
enum PromptStatus(val db: String):
  case Sent extends PromptStatus("sent")
  case Answered extends PromptStatus("answered")
  case Expired extends PromptStatus("expired")
  case Suppressed extends PromptStatus("suppressed")
  case Cancelled extends PromptStatus("cancelled")

  /** A prompt that reached the chat: it counts toward caps and budgets. */
  def wasSent: Boolean = this == Sent || this == Answered || this == Expired

object PromptStatus:
  def fromDb(db: String): PromptStatus =
    values.find(_.db == db).getOrElse(throw IllegalArgumentException(s"unknown prompt status '$db'"))

/** Why a prompt sent nothing. The `db` strings match the `prompt_occurrences.reason` CHECK. */
enum SuppressReason(val db: String):
  case NoConsent extends SuppressReason("no_consent")
  case Unreachable extends SuppressReason("unreachable")
  case QuietHours extends SuppressReason("quiet_hours")
  case Paused extends SuppressReason("paused")
  case Budget extends SuppressReason("budget")
  case GoalReached extends SuppressReason("goal_reached")
  case RecentEntry extends SuppressReason("recent_entry")
  case NoGoal extends SuppressReason("no_goal")
  case OnTrack extends SuppressReason("on_track")
  case Ignored extends SuppressReason("ignored")
  case Backoff extends SuppressReason("backoff")
  case Crowded extends SuppressReason("crowded")
  case CatchUp extends SuppressReason("catch_up")
  case Stale extends SuppressReason("stale")

object SuppressReason:
  def fromDb(db: String): SuppressReason =
    values.find(_.db == db).getOrElse(throw IllegalArgumentException(s"unknown suppress reason '$db'"))

/** A line a prompt carries once: the interval widened after unanswered prompts, or the kind paused for the day. */
enum PromptNotice(val db: String):
  case Widened extends PromptNotice("widened")
  case PausedForToday extends PromptNotice("paused_today")

object PromptNotice:
  def fromDb(db: String): PromptNotice =
    values.find(_.db == db).getOrElse(throw IllegalArgumentException(s"unknown prompt notice '$db'"))

enum PromptOutcome:
  case Send(notice: Option[PromptNotice])

  /** `announce` asks the loop to turn the open prompt of the kind into the one-line pause notice. */
  case Suppress(reason: SuppressReason, announce: Boolean = false)

/** A prompt already decided on the plan day of the slot being decided, as the history rules read it. */
final case class DecidedPrompt(
    dueAt: Instant,
    status: PromptStatus,
    reason: Option[SuppressReason],
    notice: Option[PromptNotice]
)

/** What the user recorded in the prompt's domain: the latest entry, and for water the day's total and own goal. */
final case class DomainProgress(lastEntryAt: Option[Instant], totalMl: Int, goalMl: Option[Int])

object DomainProgress:
  val Empty: DomainProgress = DomainProgress(None, 0, None)

/** Everything [[PromptDecision.decide]] reads. `recentSince` is the instant after which an entry makes the prompt
  * unnecessary (the previous slot of an interval plan, three hours before the slot otherwise); `window` is the open and
  * close of an interval plan's window on the slot's day.
  */
final case class PromptInput(
    slot: NudgeSlot,
    kind: PromptKind,
    plan: NudgePlan,
    ownCap: Boolean,
    slotsToday: Int,
    recentSince: Instant,
    window: Option[(Instant, Instant)],
    quiet: QuietHours,
    pausedUntil: Option[Instant],
    progress: DomainProgress,
    today: List[DecidedPrompt],
    sharedSentToday: Int,
    budget: Int,
    lastOtherPromptAt: Option[Instant],
    consented: Boolean,
    reachable: Boolean
)

/** The pure decision of one due prompt (ARCHITECTURE REM-13, REM-14; PRODUCT_SPEC R-SCH-22 to R-SCH-25, R-HYD-10, as
  * amended by ADR-023). Every rule is a check on data read at due time; nothing here sends or stores.
  */
object PromptDecision:

  /** The default daily budget of R-SCH-22 for plans without their own cap. */
  val DefaultBudget = 4

  /** At least this long between two prompts of different kinds (PRODUCT_SPEC section 3.2). */
  val MinSpacing: Duration = Duration.ofMinutes(20)

  /** Unanswered prompts in a row after which the interval widens to every second slot, said once. */
  val WidenAfter = 3

  /** Unanswered prompts in a row after which the kind pauses for the rest of the day, said once. */
  val PauseAfter = 6

  def decide(in: PromptInput): PromptOutcome =
    val streak = ignoredStreak(in.today, in.progress.lastEntryAt)
    val previous = in.today.filter(_.dueAt.isBefore(in.slot.at)).maxByOption(_.dueAt)
    val checks: List[() => Option[PromptOutcome]] = List(
      () => Option.unless(in.consented)(suppress(SuppressReason.NoConsent)),
      () => Option.unless(in.reachable)(suppress(SuppressReason.Unreachable)),
      () => Option.when(in.quiet.contains(in.slot.local.toLocalTime))(suppress(SuppressReason.QuietHours)),
      () => Option.when(in.pausedUntil.exists(_.isAfter(in.slot.at)))(suppress(SuppressReason.Paused)),
      () => Option.when(overBudget(in))(suppress(SuppressReason.Budget)),
      () => Option.when(goalReached(in))(suppress(SuppressReason.GoalReached)),
      () =>
        Option.when(in.progress.lastEntryAt.exists(_.isAfter(in.recentSince)))(suppress(SuppressReason.RecentEntry)),
      () => behindGoal(in),
      () =>
        Option.when(streak >= PauseAfter):
          val announced = in.today.exists(_.reason.contains(SuppressReason.Ignored))
          PromptOutcome.Suppress(SuppressReason.Ignored, announce = !announced)
      ,
      () =>
        Option.when(streak >= WidenAfter && !previous.exists(_.reason.contains(SuppressReason.Backoff)))(
          suppress(SuppressReason.Backoff)
        ),
      () =>
        Option.when(in.lastOtherPromptAt.exists(at => Duration.between(at, in.slot.at).compareTo(MinSpacing) < 0))(
          suppress(SuppressReason.Crowded)
        )
    )
    checks.iterator
      .map(_())
      .collectFirst { case Some(outcome) => outcome }
      .getOrElse:
        val widenedBefore = in.today.exists(_.notice.contains(PromptNotice.Widened))
        PromptOutcome.Send(Option.when(streak >= WidenAfter && !widenedBefore)(PromptNotice.Widened))

  /** Sent prompts in a row, newest first, that no entry followed: a prompt is answered by a tap or by any entry of its
    * domain after it was due. Suppressed prompts neither count nor break the run.
    */
  def ignoredStreak(today: List[DecidedPrompt], lastEntryAt: Option[Instant]): Int =
    today
      .filter(_.status.wasSent)
      .sortBy(_.dueAt)
      .reverse
      .takeWhile(p => p.status != PromptStatus.Answered && !lastEntryAt.exists(_.isAfter(p.dueAt)))
      .size

  private def suppress(reason: SuppressReason): PromptOutcome = PromptOutcome.Suppress(reason)

  private def overBudget(in: PromptInput): Boolean =
    if in.ownCap then in.today.count(_.status.wasSent) >= in.slotsToday
    else in.sharedSentToday >= in.budget

  private def goalReached(in: PromptInput): Boolean =
    in.kind == PromptKind.Water && in.progress.goalMl.exists(in.progress.totalMl >= _)

  /** "Only when I am behind my goal": the goal spread evenly over the window; at or above that line nothing is sent.
    * Without a goal there is no line, and nothing is sent.
    */
  private def behindGoal(in: PromptInput): Option[PromptOutcome] =
    in.plan match
      case NudgePlan.Interval(_, _, _, _, true) =>
        in.progress.goalMl match
          case None       => Some(suppress(SuppressReason.NoGoal))
          case Some(goal) =>
            in.window.flatMap: (open, close) =>
              val span = Duration.between(open, close).toSeconds.toDouble
              val elapsed = Duration.between(open, in.slot.at).toSeconds.toDouble
              val expected = goal * (elapsed / span)
              Option.when(in.progress.totalMl >= expected)(suppress(SuppressReason.OnTrack))
      case _ => None

/** How long a due prompt may wait for its decision before it is stale: a prompt the service could not decide in time,
  * after downtime, is recorded as `suppressed` and never sent late (REM-16).
  */
object PromptExpiry:
  def of(plan: NudgePlan): Duration = plan match
    case NudgePlan.Interval(_, _, every, _, _) => Duration.ofMinutes(math.min(every, 60).toLong)
    case _                                     => Duration.ofMinutes(60)

  /** The instant after which an entry makes the prompt of `slot` unnecessary. */
  def recentSince(plan: NudgePlan, slot: NudgeSlot): Instant = plan match
    case NudgePlan.Interval(_, _, every, _, _) => slot.at.minus(Duration.ofMinutes(every.toLong))
    case _                                     => slot.at.minus(Duration.ofHours(3))
