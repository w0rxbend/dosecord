package dosegram.core.application

import dosegram.contracts.*
import dosegram.core.application.MedicationWizardRig.Profile
import dosegram.core.application.capture.CaptureParts
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackMode
import dosegram.core.application.sleep.EnterSleep
import dosegram.core.application.sleep.SleepInput
import dosegram.core.application.sleep.SleepService
import dosegram.core.domain.EntrySource
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.copy.CheckInCopy
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.HelpCopy
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.SleepCopy
import dosegram.core.domain.copy.WaterCopy
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.CardLook
import dosegram.core.ports.Clock
import dosegram.core.ports.SleepCardView
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps
import dosegram.core.ports.DayCardView
import dosegram.core.ports.DoseMark
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.StatusCards
import dosegram.core.ports.UnitOfWork
import dosegram.core.ports.WaterCardView

import java.time.Duration
import java.time.ZoneId
import java.util.UUID
import scala.collection.mutable

/** The capture surface of the chat on the in-memory ports, through the real mediator and the product composition
  * ([[Application.withCapture]]): the two-stage consent before a domain records anything (BOT_UX section 5.5), water
  * by text and by button with the user's own goal (11.2), the mood check-in answered with its image (11.4), the
  * statistics card with the daily summary image (12.5), and `/privacy` (20.1). Times are 03:00 in Kyiv.
  */
class CaptureFlowsSuite extends munit.FunSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")

  /** Records every view it was asked to draw and answers with a reference named after the card. */
  private final class RecordingCards extends StatusCards:
    val moods: mutable.ListBuffer[MoodCardView] = mutable.ListBuffer.empty
    val waters: mutable.ListBuffer[WaterCardView] = mutable.ListBuffer.empty
    val days: mutable.ListBuffer[DayCardView] = mutable.ListBuffer.empty
    val sleeps: mutable.ListBuffer[SleepRegularityFigures] = mutable.ListBuffer.empty
    val looks: mutable.ListBuffer[CardLook] = mutable.ListBuffer.empty

    private def image(key: String): Option[AttachmentRef] =
      Some(AttachmentRef(MediaKind.Photo, key, "image/png", s"alt of $key"))

    override def mood(view: MoodCardView, look: CardLook): Option[AttachmentRef] =
      moods += view
      looks += look
      image("mood")

    override def water(view: WaterCardView, look: CardLook): Option[AttachmentRef] =
      waters += view
      looks += look
      image("water")

    override def day(view: DayCardView, look: CardLook): Option[AttachmentRef] =
      days += view
      looks += look
      image("day")

    override def sleep(view: SleepRegularityFigures, look: CardLook): Option[AttachmentRef] =
      sleeps += view
      looks += look
      image("sleep")

  /** The tables behind the rig's transaction, as the tests inspect them. */
  private final class World(val tracking: InMemoryTracking, val consents: InMemoryConsentLedger)

  private def parts(uow: UnitOfWork, cards: StatusCards)(clock: Clock): CaptureParts =
    CaptureParts.over(uow, clock, SequentialIds(), cards)

  private final class Chat(val rig: MedicationWizardRig, val world: World, val account: UUID):
    def grant(purposes: ConsentPurpose*): Unit =
      purposes.foreach(world.consents.grant(account, _, ConsentNotice(1, "test statement", "en"), ConsentTaps(rig.clock.now(), rig.clock.now())))

    def liveWater: List[Int] = world.tracking.entries.values.filter(_.isLive).map(_.amount.millilitres).toList

  private def withChat(profile: Profile, cards: StatusCards = RecordingCards())(test: Chat => Unit): Unit =
    ox.supervised:
      val rig = MedicationWizardRig(profile, captureParts = Some(uow => parts(uow, cards)))
      val account = rig.seedAccount(consents = Nil)
      test(Chat(rig, World(rig.uow.tracking, rig.uow.consents), account))

  private def texts(ops: List[VendorOp]): List[String] =
    ops.flatMap:
      case VendorOp.Send(_, message, _, _) => message.chunks
      case VendorOp.Edit(_, message)       => message.chunks
      case _                               => Nil

  private def labels(ops: List[VendorOp]): List[String] =
    ops.flatMap:
      case VendorOp.Send(_, message, _, _) => message.choiceMap.map(_.label)
      case VendorOp.Edit(_, message)       => message.choiceMap.map(_.label)
      case _                               => Nil

  // ---------- Consent ----------

  test("a typed amount before the consent shows stage 1 and records nothing"):
    withChat(Profile.Telegram): chat =>
      val shown = chat.rig.line("250 ml")
      assertEquals(shown, List(ConsentCopy.statement(ConsentPurpose.Hydration)))
      assertEquals(chat.rig.lastChoiceMap("I agree that").map(_.label), List(ConsentCopy.Agree, ConsentCopy.NotNow))
      assertEquals(chat.liveWater, Nil)
      assertEquals(chat.world.consents.events.toList, Nil)

  test("consent is recorded on the Confirm tap only; the card becomes the receipt and the typed amount is kept"):
    withChat(Profile.Telegram): chat =>
      chat.rig.line("250 ml")
      val stageTwo = chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      assertEquals(texts(stageTwo), List(ConsentCopy.confirmation(ConsentPurpose.Hydration)))
      assert(stageTwo.forall(_.isInstanceOf[VendorOp.Edit]), s"stage 2 edits the card: $stageTwo")
      assertEquals(chat.world.consents.events.toList, Nil, "stage 1 records nothing")

      val confirmed = chat.rig.tapOn("Please confirm", ConsentCopy.Confirm)
      assertEquals(chat.world.consents.events.toList, List("hydration:granted"))
      assertEquals(
        texts(confirmed),
        List(ConsentCopy.receipt(ConsentPurpose.Hydration, "21 Sep 2026"), WaterCopy.goalAsk)
      )
      assertEquals(labels(confirmed), List("1.5 l", "2.0 l", "2.5 l", WaterCopy.NoGoal))
      assertEquals(chat.liveWater, Nil, "the amount waits for the goal choice")

      val recorded = chat.rig.tapOn(WaterCopy.goalAsk, "2.0 l")
      assertEquals(texts(recorded), List("Water +250 ml. Today: 0.25 of 2.0 l (12%)."))
      assertEquals(chat.liveWater, List(250))

  test("a second Confirm on an old card records nothing and does not run the input again"):
    withChat(Profile.Telegram): chat =>
      chat.rig.command("mood")
      val stageTwo = chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val confirmWire = stageTwo
        .collect { case VendorOp.Edit(_, message) => message.choiceMap }
        .flatten
        .find(_.label == ConsentCopy.Confirm)
        .get
        .callback
      chat.rig.tap(confirmWire)
      val again = chat.rig.tap(confirmWire)
      assertEquals(again, List(ReminderCopy.staleControlToast))
      assertEquals(chat.world.consents.events.toList, List("mood:granted"))

  test("Not now leaves the domain off and says how to turn it on later"):
    withChat(Profile.Telegram): chat =>
      chat.rig.command("water")
      val declined = chat.rig.tapOn("I agree that", ConsentCopy.NotNow)
      assertEquals(texts(declined), List(ConsentCopy.declined(ConsentPurpose.Hydration)))
      assertEquals(labels(declined), Nil)
      assertEquals(chat.world.consents.events.toList, Nil)

  test("Go back returns from stage 2 to stage 1"):
    withChat(Profile.Telegram): chat =>
      chat.rig.command("water")
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val back = chat.rig.tapOn("Please confirm", ConsentCopy.GoBack)
      assertEquals(texts(back), List(ConsentCopy.statement(ConsentPurpose.Hydration)))

  test("the mood consent text carries the urgent-help sentence (BUX-MOOD-07)"):
    withChat(Profile.Telegram): chat =>
      val shown = chat.rig.command("mood")
      assert(shown.exists(_.contains(ConsentCopy.SafetyUrgent)), s"got: $shown")

  test("/privacy lists a consent with its date, and a withdrawal sends the command back to the consent"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Hydration)
      val card = chat.rig.command("privacy")
      assertEquals(card, List(s"${ConsentCopy.privacyTitle}\n\nWater records: since 21 Sep 2026"))
      val withdrawn = chat.rig.tapOn("Water records", ConsentCopy.withdrawLabel(ConsentPurpose.Hydration))
      assertEquals(texts(withdrawn), List(ConsentCopy.withdrawn(ConsentPurpose.Hydration)))
      assertEquals(chat.rig.command("water"), List(ConsentCopy.statement(ConsentPurpose.Hydration)))

  test("/privacy without a consent says so"):
    withChat(Profile.Telegram): chat =>
      assertEquals(chat.rig.command("privacy"), List(ConsentCopy.privacyNone))

  // ---------- Water ----------

  test("/water shows the card with the default presets; each tap is one entry and edits the card"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Hydration)
      assertEquals(chat.rig.command("water"), List(WaterCopy.nothingLogged))
      assertEquals(chat.rig.lastChoiceMap("Water today").map(_.label), List("+ 200 ml", "+ 300 ml", "+ 500 ml", "Goal"))

      val first = chat.rig.tapOn("Water today", "+ 300 ml")
      assertEquals(texts(first), List("Water +300 ml. Today: 0.3 l."))
      assert(first.forall(_.isInstanceOf[VendorOp.Edit]), s"the echo edits the card: $first")
      assertEquals(labels(first), List("Undo", "+ 200 ml", "+ 300 ml", "+ 500 ml", "Goal"))

      chat.rig.tapOn("Water +300 ml", "+ 300 ml")
      assertEquals(chat.liveWater, List(300, 300))

  test("the goal is the user's own: the echo counts towards it and says the goal is reached once"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Hydration)
      chat.rig.command("water")
      chat.rig.tapOn("Water today", "Goal")
      val afterGoal = chat.rig.tapOn(WaterCopy.goalAsk, "1.5 l")
      assertEquals(texts(afterGoal), List(WaterCopy.nothingLogged))
      assert(labels(afterGoal).contains(WaterCopy.ShowAsImage), "the image is offered once a goal exists")

      assertEquals(chat.rig.line("1.5 l"), List("Water +1500 ml. Today: 1.5 of 1.5 l (100%). Daily goal reached."))
      assertEquals(chat.rig.line("200 ml"), List("Water +200 ml. Today: 1.7 of 1.5 l (113%)."))

  test("Undo voids the entry it belongs to and shows the day"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Hydration)
      chat.rig.line("drank 300ml")
      chat.rig.line("water 0,5 l")
      val undone = chat.rig.tapOn("Water +500 ml", "Undo")
      assertEquals(texts(undone), List("Undone. Water today: 0.3 l."))
      assertEquals(chat.liveWater, List(300))

  test("glasses are sized by the first preset, and the echo shows millilitres"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Hydration)
      assertEquals(chat.rig.line("2 glasses"), List("Water +400 ml. Today: 0.4 l."))

  test("an amount over the bound is refused and nothing is recorded"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Hydration)
      assertEquals(chat.rig.line("8000 ml"), List(WaterCopy.tooMuch))
      assertEquals(chat.rig.line("/water lots"), List(WaterCopy.usage))
      assertEquals(chat.liveWater, Nil)

  test("a bare number is not an amount of water"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Hydration)
      assertEquals(chat.rig.line("250"), List(HelpCopy.unknownMessage))
      assertEquals(chat.liveWater, Nil)

  test("Show as image asks the images consent first, then sends the vessel card with the figures as its caption"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Hydration)
      chat.rig.command("water")
      chat.rig.tapOn("Water today", "Goal")
      chat.rig.tapOn(WaterCopy.goalAsk, "2.0 l")
      chat.rig.line("500 ml")

      val asked = chat.rig.tapOn("Water +500 ml", WaterCopy.ShowAsImage)
      assertEquals(texts(asked), List(ConsentCopy.statement(ConsentPurpose.ImagesInChat)))
      assert(asked.forall(_.isInstanceOf[VendorOp.Send]), s"the consent is a message of its own, the card stays: $asked")
      assertEquals(cards.waters.toList, Nil, "nothing is rendered before the consent")

      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val sent = chat.rig.tapOn("Please confirm", ConsentCopy.Confirm)
      val photo = sent.collect { case VendorOp.Send(_, message, _, _) if message.attachment.isDefined => message }
      assertEquals(photo.map(_.attachment.map(_.assetKey)), List(Some("water")))
      assertEquals(photo.flatMap(_.chunks), List("Water today: 0.5 of 2.0 l (25%)"))
      assertEquals(cards.waters.toList.map(view => (view.totalMl, view.goalMl, view.entries)), List((500, 2000, 1)))

  // ---------- Mood ----------

  test("/mood asks; a tap records and is answered with the mood image, captioned with the label and the time"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Mood)
      assertEquals(chat.rig.command("mood"), List(s"${CheckInCopy.prompt}\n\n${CheckInCopy.scale}"))
      val answer = chat.rig.tapOn(CheckInCopy.prompt, "4")
      val edits = answer.collect { case VendorOp.Edit(_, message) => message }
      assertEquals(edits.map(_.chunks), List(List("Pleasant · 03:00")), "the prompt shows the recorded level")
      assertEquals(edits.flatMap(_.choiceMap), Nil, "and its 1 to 5 buttons are gone")
      val photo = answer.collect { case VendorOp.Send(_, message, _, _) => message }
      assertEquals(photo.map(_.attachment.map(_.assetKey)), List(Some("mood")))
      assertEquals(photo.flatMap(_.chunks), List("Pleasant · 03:00"))
      assertEquals(labels(answer), List(CheckInCopy.AddEnergy, "Undo"))
      assertEquals(cards.moods.toList.map(view => (view.valence.step, view.energy)), List((4, None)))
      assertEquals(chat.world.tracking.checkins.values.map(_.valence.step).toList, List(4))

  test("Add energy asks on the same message and the answer names the energy label"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Mood)
      chat.rig.line("mood 4")
      val ask = chat.rig.tapOn("Pleasant · 03:00", CheckInCopy.AddEnergy)
      assertEquals(texts(ask), List(s"${CheckInCopy.energyAsk}\n\n${CheckInCopy.energyScale}"))
      val answered = chat.rig.tapOn(CheckInCopy.energyAsk, "2")
      assertEquals(texts(answered), List("Pleasant. Energy: low. · 03:00"))
      assertEquals(labels(answered), List("Undo"))
      val live = chat.world.tracking.checkins.values.filter(_.isLive).toList
      assertEquals(live.map(checkin => (checkin.valence.step, checkin.energy.map(_.step))), List((4, Some(2))))

  test("Undo removes the check-in"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Mood)
      chat.rig.line("/mood 2")
      val undone = chat.rig.tapOn("Unpleasant · 03:00", "Undo")
      assertEquals(texts(undone), List(CheckInCopy.undone))
      assertEquals(chat.world.tracking.checkins.values.count(_.isLive), 0)

  test("without a renderer the confirmation is the text alone, in place of the prompt"):
    withChat(Profile.Telegram, StatusCards.textOnly): chat =>
      chat.grant(ConsentPurpose.Mood)
      chat.rig.command("mood")
      val answer = chat.rig.tapOn(CheckInCopy.prompt, "5")
      assertEquals(answer.collect { case VendorOp.Edit(_, message) => message.chunks }.flatten, List("Very pleasant · 03:00"))
      assertEquals(answer.collect { case VendorOp.Send(_, message, _, _) => message.attachment }.flatten, Nil)

  test("one prompt is one check-in: later taps on it record nothing and say when it was recorded"):
    for cards <- List(RecordingCards(), StatusCards.textOnly) do
      withChat(Profile.Telegram, cards): chat =>
        chat.grant(ConsentPurpose.Mood)
        chat.rig.command("mood")
        val (prompt, four) = chat.rig.controlOn(CheckInCopy.prompt, "4")
        val two = chat.rig.controlOn(CheckInCopy.prompt, "2")._2
        val (_, first) = chat.rig.tapFrom(prompt, four)
        chat.rig.clock.at = chat.rig.clock.now().plusSeconds(60)
        val (_, second) = chat.rig.tapFrom(prompt, four) // the old keyboard, before or after the edit lands
        val (_, racing) = chat.rig.tapFrom(prompt, two) // another step of the same prompt
        chat.rig.restartMediator()
        val (_, afterRestart) = chat.rig.tapFrom(prompt, four)

        assertEquals(chat.world.tracking.checkins.values.map(_.valence.step).toList, List(4))
        assertEquals(first.count(_.isInstanceOf[VendorOp.Edit]), 1, "the prompt is edited once")
        assertEquals(chat.rig.messageAt(prompt).choiceMap.map(_.label).filter(_.length == 1), Nil, "no step left")
        for stale <- List(second, racing, afterRestart) do
          assertEquals(texts(stale), List("Already recorded at 03:00."), "a short self-deleting answer only")
          assertEquals(stale.count(_.isInstanceOf[VendorOp.Edit]), 0)

  test("a new prompt records another check-in the same day"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Mood)
      chat.rig.command("mood")
      chat.rig.tapOn(CheckInCopy.prompt, "4")
      chat.rig.command("mood")
      chat.rig.tapOn(CheckInCopy.prompt, "2")
      assertEquals(chat.world.tracking.checkins.values.map(_.valence.step).toList.sorted, List(2, 4))

  test("an invalid step records nothing"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Mood)
      assertEquals(chat.rig.line("/mood 8"), List(CheckInCopy.usage))
      assertEquals(chat.rig.line("mood 9"), List(HelpCopy.unknownMessage))
      assertEquals(chat.world.tracking.checkins.size, 0)

  // ---------- Statistics ----------

  test("/stats asks the medication consent first, then shows the period with one button per period"):
    withChat(Profile.Telegram): chat =>
      assertEquals(chat.rig.command("stats"), List(ConsentCopy.statement(ConsentPurpose.Medication)))
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val shown = chat.rig.tapOn("Please confirm", ConsentCopy.Confirm)
      assert(texts(shown).exists(_.startsWith("Last 7 days · Europe/Kyiv")), s"got: ${texts(shown)}")
      assertEquals(labels(shown), List("Today", "7 days", "30 days"))

  test("the daily summary image is drawn from today's doses, water and mood, with today's text as its caption"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
      val everyDay = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
      chat.rig.seedSchedule(chat.account, "Vitamin D", everyDay, kyiv)
      chat.rig.command("water")
      chat.rig.tapOn("Water today", "Goal")
      chat.rig.tapOn(WaterCopy.goalAsk, "2.0 l")
      chat.rig.line("500 ml")
      chat.rig.line("mood 4")

      val today = chat.rig.line("/stats today")
      assert(today.exists(_.contains("Water today: 0.5 of 2.0 l (25%)")), s"got: $today")
      val sent = chat.rig.tapOn("Today · Europe/Kyiv", "Show as image")
      val photo = sent.collect { case VendorOp.Send(_, message, _, _) if message.attachment.isDefined => message }
      assertEquals(photo.map(_.attachment.map(_.assetKey)), List(Some("day")))
      assertEquals(photo.flatMap(_.chunks), today, "the caption is today's text")
      val view = cards.days.head
      assertEquals(view.doses, Some(List(DoseMark.Open)))
      assertEquals(view.water.map(water => (water.totalMl, water.goalMl)), Some((500, 2000)))
      assertEquals(view.mood.map(_.valence.step), Some(4))

  // ---------- Sleep ----------

  test("/sleep before the consent shows stage 1 and records nothing"):
    withChat(Profile.Telegram): chat =>
      assertEquals(chat.rig.command("sleep"), List(ConsentCopy.statement(ConsentPurpose.Sleep)))
      assertEquals(chat.world.tracking.sleep.live, Nil)

  test("Going to sleep, then I'm up seven hours later, is one night from the check-ins, rated with one tap"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Sleep)
      assertEquals(chat.rig.command("sleep"), List(SleepCopy.noNight))
      val started = chat.rig.tapOn(SleepCopy.noNight, SleepCopy.GoingToSleep)
      assertEquals(texts(started), List(SleepCopy.started("03:00")))
      assertEquals(labels(started), List(SleepCopy.ImUp, "Undo"))
      chat.rig.clock.at = chat.rig.clock.at.plus(Duration.ofHours(7))
      val woke = chat.rig.tapOn(SleepCopy.started("03:00"), SleepCopy.ImUp)
      val night = "Slept 03:00 to 10:00, 7 h 00 min (your check-ins)."
      assertEquals(texts(woke), List(s"$night\n\n${SleepCopy.qualityAsk}"))
      val rated = chat.rig.tapOn(SleepCopy.qualityAsk, "4")
      assertEquals(texts(rated), List(s"$night\n\n${SleepCopy.rated(4)}"))
      assertEquals(chat.world.tracking.sleep.live.map(_.quality.map(_.step)), List(Some(4)))
      assertEquals(chat.rig.command("sleep"), List(night))

  test("I'm up without an open night records no night and asks for the times"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Sleep)
      chat.rig.command("sleep")
      chat.rig.tapOn(SleepCopy.noNight, SleepCopy.GoingToSleep)
      chat.rig.tapOn(SleepCopy.started("03:00"), "Undo")
      assertEquals(chat.rig.command("sleep"), List(SleepCopy.noNight))
      chat.world.tracking.sleep.live.foreach(night => fail(s"no night: $night"))

  test("Show as image sends the regularity chart of 14 days, after the image consent, with the figures as caption"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Sleep, ConsentPurpose.ImagesInChat)
      assertEquals(chat.rig.command("sleep"), List(SleepCopy.noNight), "no image before a night exists")
      chat.rig.line("slept 23:30 7:10")
      val card = "Slept 23:30 to 07:10, 7 h 40 min (your entry)."
      assertEquals(chat.rig.command("sleep"), List(card))
      val sent = chat.rig.tapOn(card, SleepCopy.ShowAsImage)
      val figures = cards.sleeps.toList
      assertEquals(figures.map(view => (view.window.days, view.nights, view.bars.size)), List((14, 1, 1)))
      assertEquals(
        texts(sent),
        List("Sleep: 1 night logged · 9 more nights for an average · your check-ins · 1 of 14 days have data")
      )

  test("a typed night is the latest night with those clock times; a typed length is not guessed"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Sleep)
      val typed = "Slept 23:30 to 07:10, 7 h 40 min (your entry)."
      assertEquals(chat.rig.line("slept 23:30 7:10"), List(s"$typed\n\n${SleepCopy.qualityAsk}"))
      assertEquals(chat.rig.line("slept 7h"), List(SleepCopy.ask))
      assertEquals(chat.rig.line("/sleep 22:00 6:00"), List(SleepCopy.nightExists))
      assertEquals(chat.rig.line("/sleep soon"), List(SleepCopy.usage))
      assertEquals(chat.world.tracking.sleep.live.map(_.duration.toMinutes), List(460L))

  test("with the sleep consent and a sleep goal the daily summary carries last night, in the text and on the card"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Sleep, ConsentPurpose.ImagesInChat)
      val sleep = SleepService(chat.rig.clock, SequentialIds())
      val now = chat.rig.clock.now()
      val night = EnterSleep(
        SleepInput(chat.account, EntrySource.Bot, "night"),
        SleepKind.Main,
        now.minus(Duration.ofMinutes(6 * 60 + 10)),
        now.minus(Duration.ofMinutes(30))
      )
      assert(chat.rig.uow.transaction(tx => sleep.enter(tx, night)).isRight)
      chat.rig.uow.transaction(tx => sleep.setGoal(tx, chat.account, Some(7 * 60)))

      val today = chat.rig.line("/stats today")
      assert(today.exists(_.contains("Sleep last night: 5 h 40 min (your check-ins)")), s"got: $today")
      chat.rig.tapOn("Today · Europe/Kyiv", "Show as image")
      assertEquals(cards.days.head.sleep, Some(SleepCardView(Some(340), 420)))

  test("without a sleep goal the daily summary card has no sleep line, as water has none without its goal"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Sleep, ConsentPurpose.ImagesInChat)
      chat.rig.line("/stats today")
      chat.rig.tapOn("Today · Europe/Kyiv", "Show as image")
      assertEquals(cards.days.head.sleep, None)

  test("every image is drawn in the image theme and the discreet mode the account set, the default before any change"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
      chat.rig.line("mood 4")
      val chosen = AccountSettings.Default.copy(isDiscreet = true, imageTheme = ImageTheme.Light)
      chat.rig.uow.accounts.saveSettings(chat.account, chosen, chat.rig.clock.now())
      chat.rig.line("mood 2")
      chat.rig.line("/stats today")
      chat.rig.tapOn("Today · Europe/Kyiv", "Show as image")
      assertEquals(
        cards.looks.toList,
        List(CardLook(ImageTheme.Auto, isDiscreet = false), CardLook.of(chosen), CardLook.of(chosen))
      )

  test("an unknown period is answered with the usage"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Medication)
      assertEquals(chat.rig.line("/stats year"), List("Send /stats, /stats today, /stats 7 or /stats 30."))

  // ---------- Ownership, help, console ----------

  test("a button minted for another account is a stale button and records nothing"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Hydration)
      val foreign = MedicationWizardRig.codec
        .encode(CallbackMode.Direct, ActionRegistry.byName("water.add").get, UUID.randomUUID(), 300)
        .wire
      assertEquals(chat.rig.tap(foreign), List(ReminderCopy.staleControlToast))
      assertEquals(chat.liveWater, Nil)

  test("without an account a capture command points at /start"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console, captureParts = Some(uow => parts(uow, StatusCards.textOnly)))
      assertEquals(rig.line("/water 300"), List(IdentityCopy.createFirst))

  test("/help lists the capture commands in the composition that has them"):
    withChat(Profile.Telegram): chat =>
      val help = chat.rig.command("help").mkString("\n")
      CommandRegistry.withCapture.foreach(spec => assert(help.contains(s"/${spec.name}"), s"/help lists /${spec.name}"))

  test("on a text-only profile the consent is answered by number and the image is its text equivalent"):
    withChat(Profile.Console): chat =>
      val asked = chat.rig.line("/mood 3")
      assert(asked.exists(text => text.contains("1) I agree") && text.contains("2) Not now")), s"got: $asked")
      chat.rig.line("1")
      val confirmed = chat.rig.line("1")
      assert(confirmed.exists(_.contains("Consent recorded on 21 Sep 2026: mood records.")), s"got: $confirmed")
      assert(confirmed.exists(_.contains("[image: alt of mood]")), s"got: $confirmed")
      assert(confirmed.exists(_.contains("Neutral · 03:00")), s"got: $confirmed")
end CaptureFlowsSuite
