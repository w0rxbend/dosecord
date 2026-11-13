package dosegram.core.domain.reminders

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One test per rule of [[PromptDecision.decide]], in the order the rules apply. */
class PromptDecisionSuite extends munit.FunSuite:

  private val zone = ZoneId.of("UTC")
  private val day = LocalDate.of(2026, 11, 16) // a Monday
  private val preset = PromptPresets.WaterWorkHours
  private val slots = NudgeSlots.on(preset.plan, DayProfile.Default, day, zone, 1L)
  private val tenThirty = slots.find(_.slotKey == "1030").get

  private def at(hhmm: String): Instant = Instant.parse(s"${day}T$hhmm:00Z")

  private def input(slot: NudgeSlot = tenThirty): PromptInput =
    PromptInput(
      slot = slot,
      kind = PromptKind.Water,
      plan = preset.plan,
      ownCap = true,
      slotsToday = slots.size,
      recentSince = PromptExpiry.recentSince(preset.plan, slot),
      window = Some(at("09:00") -> at("17:30")),
      quiet = DayProfile.DefaultQuiet,
      pausedUntil = None,
      progress = DomainProgress(None, 0, Some(2000)),
      today = Nil,
      sharedSentToday = 0,
      budget = PromptDecision.DefaultBudget,
      lastOtherPromptAt = None,
      consented = true,
      reachable = true
    )

  private def sent(hhmm: String, status: PromptStatus = PromptStatus.Sent): DecidedPrompt =
    DecidedPrompt(at(hhmm), status, None, None)

  private def suppressed(hhmm: String, reason: SuppressReason): DecidedPrompt =
    DecidedPrompt(at(hhmm), PromptStatus.Suppressed, Some(reason), None)

  private def send = PromptOutcome.Send(None)
  private def suppress(reason: SuppressReason) = PromptOutcome.Suppress(reason)

  test("a due prompt with nothing against it is sent"):
    assertEquals(PromptDecision.decide(input()), send)

  test("no consent"):
    assertEquals(PromptDecision.decide(input().copy(consented = false)), suppress(SuppressReason.NoConsent))

  test("no chat to send to"):
    assertEquals(PromptDecision.decide(input().copy(reachable = false)), suppress(SuppressReason.Unreachable))

  test("inside quiet hours the prompt is dropped, not moved"):
    val early = DayProfile.Default.quiet.copy(start = dosegram.contracts.HhMm.unsafe("10:00"))
    assertEquals(PromptDecision.decide(input().copy(quiet = early)), suppress(SuppressReason.QuietHours))

  test("paused until after the slot"):
    assertEquals(
      PromptDecision.decide(input().copy(pausedUntil = Some(at("11:00")))),
      suppress(SuppressReason.Paused)
    )
    assertEquals(PromptDecision.decide(input().copy(pausedUntil = Some(at("10:00")))), send)

  test("a plan with its own cap stops at its slot count; a budgeted plan at the shared budget"):
    val full = (1 to slots.size).toList.map(i => DecidedPrompt(at("09:00").minusSeconds(i.toLong), PromptStatus.Answered, None, None))
    assertEquals(PromptDecision.decide(input().copy(today = full)), suppress(SuppressReason.Budget))
    val budgeted = input().copy(ownCap = false, sharedSentToday = 4)
    assertEquals(PromptDecision.decide(budgeted), suppress(SuppressReason.Budget))
    assertEquals(PromptDecision.decide(input().copy(sharedSentToday = 4)), send)

  test("the day's goal is reached"):
    assertEquals(
      PromptDecision.decide(input().copy(progress = DomainProgress(None, 2000, Some(2000)))),
      suppress(SuppressReason.GoalReached)
    )

  test("water logged within the interval skips the slot"):
    assertEquals(
      PromptDecision.decide(input().copy(progress = DomainProgress(Some(at("10:05")), 250, Some(2000)))),
      suppress(SuppressReason.RecentEntry)
    )
    assertEquals(
      PromptDecision.decide(input().copy(progress = DomainProgress(Some(at("09:55")), 250, Some(2000)))),
      send
    )

  test("only when behind: without a goal nothing is sent; on the line nothing is sent; behind it is sent"):
    val behind = PromptPresets.WaterBehindGoal
    val slot = NudgeSlots.on(behind.plan, DayProfile.Default, day, zone, 1L).find(_.slotKey == "1400").get
    val base = input(slot).copy(plan = behind.plan, window = Some(at("08:00") -> at("21:00")))
    assertEquals(PromptDecision.decide(base.copy(progress = DomainProgress.Empty)), suppress(SuppressReason.NoGoal))
    // 6 of 13 hours passed: the line is 2600 * 6 / 13 = 1200 ml.
    val goal = Some(2600)
    assertEquals(
      PromptDecision.decide(base.copy(progress = DomainProgress(Some(at("08:30")), 1200, goal))),
      suppress(SuppressReason.OnTrack)
    )
    assertEquals(PromptDecision.decide(base.copy(progress = DomainProgress(Some(at("08:30")), 1199, goal))), send)

  test("three unanswered prompts widen the interval to every second slot, said once"):
    val three = List(sent("09:30", PromptStatus.Expired), sent("10:00", PromptStatus.Expired))
    assertEquals(PromptDecision.decide(input().copy(today = three)), send)
    val ignoredThree = three :+ sent("10:30")
    val eleven = slots.find(_.slotKey == "1100").get
    assertEquals(PromptDecision.decide(input(eleven).copy(today = ignoredThree)), suppress(SuppressReason.Backoff))
    val afterBackoff = ignoredThree :+ suppressed("11:00", SuppressReason.Backoff)
    val elevenThirty = slots.find(_.slotKey == "1130").get
    assertEquals(
      PromptDecision.decide(input(elevenThirty).copy(today = afterBackoff)),
      PromptOutcome.Send(Some(PromptNotice.Widened))
    )
    val noticed = afterBackoff :+ DecidedPrompt(at("11:30"), PromptStatus.Sent, None, Some(PromptNotice.Widened))
    val noon = slots.find(_.slotKey == "1200").get
    assertEquals(PromptDecision.decide(input(noon).copy(today = noticed)), suppress(SuppressReason.Backoff))

  test("six unanswered prompts pause the kind for the day, announced once"):
    val six = List("09:30", "10:00", "10:30", "11:30", "12:30", "13:30").map(sent(_, PromptStatus.Expired))
    val slot = slots.find(_.slotKey == "1400").get
    assertEquals(
      PromptDecision.decide(input(slot).copy(today = six)),
      PromptOutcome.Suppress(SuppressReason.Ignored, announce = true)
    )
    val later = slots.find(_.slotKey == "1430").get
    assertEquals(
      PromptDecision.decide(input(later).copy(today = six :+ suppressed("14:00", SuppressReason.Ignored))),
      PromptOutcome.Suppress(SuppressReason.Ignored, announce = false)
    )

  test("an entry or a tap ends the unanswered run"):
    val ignored = List("09:30", "10:00", "10:30").map(sent(_, PromptStatus.Expired))
    assertEquals(PromptDecision.ignoredStreak(ignored, None), 3)
    assertEquals(PromptDecision.ignoredStreak(ignored, Some(at("10:10"))), 1)
    assertEquals(PromptDecision.ignoredStreak(ignored :+ sent("11:00", PromptStatus.Answered), None), 0)
    assertEquals(PromptDecision.ignoredStreak(ignored :+ suppressed("11:00", SuppressReason.Crowded), None), 3)

  test("another kind's prompt less than 20 minutes earlier crowds this one out"):
    assertEquals(
      PromptDecision.decide(input().copy(lastOtherPromptAt = Some(at("10:15")))),
      suppress(SuppressReason.Crowded)
    )
    assertEquals(PromptDecision.decide(input().copy(lastOtherPromptAt = Some(at("10:10")))), send)

  test("catch-up: of the slots a downtime passed over only the newest fresh one is decided"):
    val due = slots.filter(_.at.isBefore(at("12:01")))
    val split = PromptPlanner.split(due, Set.empty, at("08:00"), at("12:05"), PromptExpiry.of(preset.plan))
    assertEquals(split.current.map(_.slotKey), Some("1200"))
    assertEquals(split.passed.size, due.size - 1)
    assertEquals(split.stale, None)
    val late = PromptPlanner.split(due, Set.empty, at("08:00"), at("12:45"), PromptExpiry.of(preset.plan))
    assertEquals(late.current, None)
    assertEquals(late.stale.map(_.slotKey), Some("1200"))

  test("catch-up ignores slots before the plan was chosen and slots already recorded"):
    val due = slots.filter(_.at.isBefore(at("12:01")))
    val recorded = due.take(2).map(s => (s.planDate, s.slotKey)).toSet
    val split = PromptPlanner.split(due, recorded, at("10:15"), at("12:00"), Duration.ofMinutes(30))
    assertEquals(split.passed.map(_.slotKey), List("1030", "1100", "1130"))
    assertEquals(split.current.map(_.slotKey), Some("1200"))
