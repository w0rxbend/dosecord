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
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.copy.CheckInCopy
import dosegram.core.domain.copy.CompareCopy
import dosegram.core.domain.copy.RhythmCopy
import dosegram.core.domain.copy.FoodCopy
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.ExportCopy
import dosegram.core.domain.copy.PrivacyCopy
import dosegram.core.domain.copy.HelpCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.SleepCopy
import dosegram.core.domain.copy.MenuCopy
import dosegram.core.domain.copy.StatsCardCopy
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.copy.PromptCopy
import dosegram.core.domain.copy.WaterCopy
import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptPresets
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.InsightRule
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.insights.WeekdayMood
import dosegram.core.domain.insights.YearFigures
import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.mood.CheckIn
import dosegram.core.application.mood.MoodService
import dosegram.core.domain.EntryDay
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.copy.InsightCopy
import dosegram.core.domain.copy.MonthCopy
import dosegram.core.domain.copy.ReportCopy
import dosegram.core.domain.copy.YearCopy
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.CompareCardCell
import dosegram.core.ports.CompareCardView
import dosegram.core.ports.RhythmCardView
import dosegram.core.ports.CardLook
import dosegram.core.ports.CardPage
import dosegram.core.ports.Clock
import dosegram.core.ports.SleepCardView
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps
import dosegram.core.ports.DayCardView
import dosegram.core.ports.DoseMark
import dosegram.core.ports.FoodCardView
import dosegram.core.ports.FoodLineView
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.MedicationStripView
import dosegram.core.ports.MonthCardView
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.MoodGalleryView
import dosegram.core.ports.StatusCards
import dosegram.core.ports.TrendsCardView
import dosegram.core.ports.UnitOfWork
import dosegram.core.ports.WaterCardView
import dosegram.core.ports.WeekCardView

import java.time.Duration
import java.time.LocalTime
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

  /** Records every view it was asked to lay out, and draws each card as a reference named after it, recording the chat
    * it was drawn for (ROADMAP G4.3: the report worker draws for the chat of the event).
    */
  private final class RecordingCards(poster: Boolean = false) extends StatusCards, CardDrawing:
    val moods: mutable.ListBuffer[MoodCardView] = mutable.ListBuffer.empty
    val waters: mutable.ListBuffer[WaterCardView] = mutable.ListBuffer.empty
    val days: mutable.ListBuffer[DayCardView] = mutable.ListBuffer.empty
    val sleeps: mutable.ListBuffer[SleepRegularityFigures] = mutable.ListBuffer.empty
    val foods: mutable.ListBuffer[FoodCardView] = mutable.ListBuffer.empty
    val looks: mutable.ListBuffer[CardLook] = mutable.ListBuffer.empty
    val chats: mutable.ListBuffer[Option[ChatRef]] = mutable.ListBuffer.empty

    private def image(key: String): Option[CardPage] =
      Some(CardPage(key, s"<svg id='$key'/>", s"alt of $key", s"$key.png", hasSpoiler = false))

    override def draw(page: CardPage, chat: Option[ChatRef]): Either[String, AttachmentRef] =
      chats += chat
      Right(AttachmentRef(MediaKind.Photo, page.templateId, "image/png", page.altText, hasSpoiler = page.hasSpoiler))

    override def mood(view: MoodCardView, look: CardLook): Option[CardPage] =
      moods += view
      looks += look
      image("mood")

    override def water(view: WaterCardView, look: CardLook): Option[CardPage] =
      waters += view
      looks += look
      image("water")

    override def day(view: DayCardView, look: CardLook): Option[CardPage] =
      days += view
      looks += look
      image("day")

    override def sleep(view: SleepRegularityFigures, look: CardLook): Option[CardPage] =
      sleeps += view
      looks += look
      image("sleep")

    val months: mutable.ListBuffer[MonthCardView] = mutable.ListBuffer.empty
    val insights: mutable.ListBuffer[Comparison.Shown] = mutable.ListBuffer.empty

    override def month(view: MonthCardView, look: CardLook): List[CardPage] =
      months += view
      List(image("month-1"), image("month-2")).flatten

    override def monthPoster(view: MonthCardView, look: CardLook): Option[CardPage] =
      if poster then image("month-poster") else None

    val years: mutable.ListBuffer[YearFigures] = mutable.ListBuffer.empty
    val compares: mutable.ListBuffer[CompareCardView] = mutable.ListBuffer.empty
    val rhythms: mutable.ListBuffer[RhythmCardView] = mutable.ListBuffer.empty

    override def rhythm(view: RhythmCardView, look: CardLook): Option[CardPage] =
      rhythms += view
      Some(CardPage("rhythm", "<svg id='rhythm'/>", view.text.take(1024), "rhythm.png", hasSpoiler = false))

    override def compare(view: CompareCardView, look: CardLook): Option[CardPage] =
      compares += view
      Some(CardPage("compare", "<svg id='compare'/>", view.text.take(1024), "compare.png", hasSpoiler = false))

    override def year(view: YearFigures, look: CardLook): Option[CardPage] =
      years += view
      image("year")

    override def insight(view: Comparison.Shown, look: CardLook): Option[CardPage] =
      insights += view
      image("insight")
    override def food(view: FoodCardView, look: CardLook): Option[CardPage] =
      foods += view
      looks += look
      image("food")

    val weeks: mutable.ListBuffer[WeekCardView] = mutable.ListBuffer.empty

    override def weekGlance(view: WeekCardView, look: CardLook): Option[CardPage] =
      weeks += view
      looks += look
      image("week-glance")

    val waterWeeks: mutable.ListBuffer[WeekCardView] = mutable.ListBuffer.empty
    val sleepWeeks: mutable.ListBuffer[WeekCardView] = mutable.ListBuffer.empty
    val moodWeekdays: mutable.ListBuffer[List[WeekdayMood]] = mutable.ListBuffer.empty

    override def waterWeek(view: WeekCardView, look: CardLook): Option[CardPage] =
      waterWeeks += view
      view.water.map(_ => personal("water-week", look))

    override def sleepWeek(view: WeekCardView, look: CardLook): Option[CardPage] =
      sleepWeeks += view
      view.sleep.map(_ => personal("sleep-week", look))

    override def moodWeekday(window: DateRange, weekdays: List[WeekdayMood], look: CardLook): Option[CardPage] =
      moodWeekdays += weekdays
      Option.when(window.days == 7 && weekdays.size == 7 && weekdays.exists(_.days > 0))(
        personal("mood-weekday", look)
      )

    val trends: mutable.ListBuffer[TrendsCardView] = mutable.ListBuffer.empty

    override def trends(view: TrendsCardView, look: CardLook): Option[CardPage] =
      trends += view
      Some(personal("trends", look))

    val strips: mutable.ListBuffer[MedicationStripView] = mutable.ListBuffer.empty

    override def medicationStrip(view: MedicationStripView, look: CardLook): List[CardPage] =
      strips += view
      List(personal("strip", look))

    val galleries: mutable.ListBuffer[MoodGalleryView] = mutable.ListBuffer.empty

    override def moodGallery(view: MoodGalleryView, look: CardLook): Option[CardPage] =
      galleries += view
      Some(personal("mood-gallery", look))

    /** A personal week card. The spoiler follows discreet mode. The look list stays the one the theme test counts. */
    private def personal(key: String, look: CardLook): CardPage =
      CardPage(key, s"<svg id='$key'/>", s"alt of $key", s"$key.png", hasSpoiler = look.isDiscreet)

  /** The tables behind the rig's transaction, as the tests inspect them. */
  private final class World(val tracking: InMemoryTracking, val consents: InMemoryConsentLedger)

  private def parts(uow: UnitOfWork, cards: StatusCards, appOpens: Boolean = true)(clock: Clock): CaptureParts =
    CaptureParts.over(uow, clock, SequentialIds(), cards).copy(appOpens = appOpens)

  private final class Chat(val rig: MedicationWizardRig, val world: World, val account: UUID):
    def grant(purposes: ConsentPurpose*): Unit =
      purposes.foreach(world.consents.grant(account, _, ConsentNotice(1, "test statement", "en"), ConsentTaps(rig.clock.now(), rig.clock.now())))

    def liveWater: List[Int] = world.tracking.entries.values.filter(_.isLive).map(_.amount.millilitres).toList

  private def withChat(profile: Profile, cards: StatusCards = RecordingCards(), appOpens: Boolean = true)(
      test: Chat => Unit
  ): Unit =
    val drawing = cards match
      case recording: RecordingCards => recording
      case _                         => CardDrawing.unavailable
    ox.supervised:
      val rig = MedicationWizardRig(profile, captureParts = Some(uow => parts(uow, cards, appOpens)), drawing = drawing)
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

  private def photos(ops: List[VendorOp]): List[RenderedMessage] =
    ops.collect { case VendorOp.Send(_, message, _, _) if message.attachment.isDefined => message }

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
        List(
          ConsentCopy.receipt(ConsentPurpose.Hydration, "21 Sep 2026"),
          // The typed amount waits for the goal choice, and the card says so (walk-through of 2026-11-27).
          s"${WaterCopy.goalAsk}\n\n${WaterCopy.goalPending(250)}"
        )
      )
      // The goal card alone: the offer of water prompts waits for the goal choice (finding 12 of 2026-11-27).
      assertEquals(labels(confirmed), List("1.5 l", "2.0 l", "2.5 l", WaterCopy.NoGoal))
      assertEquals(chat.liveWater, Nil, "the amount waits for the goal choice")

      val recorded = chat.rig.tapOn(WaterCopy.goalAsk, "2.0 l")
      // Then the offer of water prompts (ADR-023): a forced choice with nothing preselected.
      assertEquals(texts(recorded), List("Water +250 ml. Today: 0.25 of 2.0 l (12%).", PromptCopy.offerWater))
      assert(
        labels(recorded).endsWith(
          PromptPresets.forKind(PromptKind.Water).map(p => PromptCopy.presetName(p.id)) :+ PromptCopy.NotNow
        ),
        labels(recorded).toString
      )
      assertEquals(chat.liveWater, List(250))
      val goalAgain = chat.rig.tapOn("Water +250 ml", WaterCopy.Goal)
      val changed = chat.rig.tapOn(WaterCopy.goalAsk, "2.5 l")
      assert(!texts(changed).contains(PromptCopy.offerWater), s"a later goal change offers nothing: ${texts(changed)}")
      assert(texts(goalAgain).exists(_.startsWith(WaterCopy.goalAsk)), texts(goalAgain).toString)

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

  test("a Confirm on a card from before the notice version changed records nothing when the purpose is already on"):
    withChat(Profile.Telegram): chat =>
      chat.rig.line("250 ml")
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      chat.grant(ConsentPurpose.Hydration) // granted meanwhile under notice version 1 (finding 4, 2026-11-27)
      val (_, confirmWire) = chat.rig.controlOn("Please confirm", ConsentCopy.Confirm)
      assertEquals(chat.rig.tap(confirmWire), List(ReminderCopy.staleControlToast))
      assertEquals(chat.world.consents.events.toList, List("hydration:granted"))
      assertEquals(chat.liveWater, Nil, "the typed amount is not run a second time")

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

  // The tap itself runs the export on the production path: NewUserWalkSuite.
  test("/privacy offers the export of every record, with or without a consent (finding 19)"):
    withChat(Profile.Telegram): chat =>
      chat.rig.command("privacy")
      assertEquals(
        chat.rig.lastChoiceMap(ConsentCopy.privacyNone).map(_.label),
        List(ExportCopy.ExportMyData, PrivacyCopy.DeleteData)
      )
      chat.grant(ConsentPurpose.Hydration)
      chat.rig.command("privacy")
      assertEquals(
        chat.rig.lastChoiceMap(ConsentCopy.privacyTitle).map(_.label),
        List(ConsentCopy.withdrawLabel(ConsentPurpose.Hydration), ExportCopy.ExportMyData, PrivacyCopy.DeleteData)
      )

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
      // The card is drawn for the chat of the tap, so its adapter can show the upload status while it is drawn.
      assertEquals(cards.chats.toList.map(_.exists(_.chatId.nonEmpty)), List(true))

  test("a second image tap before the images consent replaces the open consent card instead of adding one (finding 11)"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Hydration)
      chat.rig.command("water")
      chat.rig.tapOn("Water today", "Goal")
      chat.rig.tapOn(WaterCopy.goalAsk, "2.0 l")
      chat.rig.line("500 ml")
      chat.rig.tapOn("Water +500 ml", WaterCopy.ShowAsImage)
      val (firstCard, _) = chat.rig.controlOn("I agree that", ConsentCopy.Agree)
      val again = chat.rig.tapOn("Water +500 ml", WaterCopy.ShowAsImage)
      assertEquals(texts(again), List(ConsentCopy.statement(ConsentPurpose.ImagesInChat)), "the card is asked again below")
      chat.rig.dispatchOnce()
      assertEquals(
        chat.rig.ops.collect { case VendorOp.Delete(handle) => handle.messageId },
        List(firstCard.messageId),
        "the earlier card is deleted"
      )
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val sent = chat.rig.tapOn("Please confirm", ConsentCopy.Confirm)
      assertEquals(sent.count { case VendorOp.Send(_, m, _, _) => m.attachment.isDefined; case _ => false }, 1)

  // ---------- Mood ----------

  test("/mood asks; a tap records and is answered with the mood image, captioned with the label and the time"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
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

  // Owner decision 2026-11-13: the mood image follows the images consent like every other personal image (BUX-MOOD-03).
  test("without the images consent the check-in is answered as text in place of the prompt, and nothing is drawn"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Mood)
      chat.rig.command("mood")
      val answer = chat.rig.tapOn(CheckInCopy.prompt, "4")
      assertEquals(answer.collect { case VendorOp.Edit(_, message) => message.chunks }.flatten, List("Pleasant · 03:00"))
      assertEquals(answer.collect { case VendorOp.Send(_, message, _, _) => message.attachment }.flatten, Nil)
      assertEquals(labels(answer), List(CheckInCopy.AddEnergy, "Undo"))
      assertEquals(chat.rig.line("mood 2"), List("Unpleasant · 03:00"), "a typed check-in is text as well")
      assertEquals(cards.moods.toList, Nil, "no mood card is drawn without the consent")
      assertEquals(chat.world.tracking.checkins.values.map(_.valence.step).toList.sorted, List(2, 4))

  // Image round 5 (supervisor decision, 2026-11-28): /mood history and its blob gallery (VISUALS IMG-26).
  test("/mood history lists the latest check-ins, oldest first, and Show as image sends their gallery after the consent"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Mood)
      chat.rig.line("mood 2")
      chat.rig.line("mood 4")
      val shown = chat.rig.line("/mood history")
      assertEquals(
        shown,
        List(s"${CheckInCopy.historyTitle(2)}\n\nMon 21 Sep 03:00 · Unpleasant\n\nMon 21 Sep 03:00 · Pleasant")
      )
      val asked = chat.rig.tapOn(CheckInCopy.historyTitle(2), StatsCardCopy.ShowAsImage)
      assertEquals(texts(asked), List(ConsentCopy.statement(ConsentPurpose.ImagesInChat)))
      assertEquals(cards.galleries.toList, Nil, "nothing is drawn before the images consent")
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val sent = photos(chat.rig.tapOn("Please confirm", ConsentCopy.Confirm))
      assertEquals(sent.map(_.attachment.map(_.assetKey)), List(Some("mood-gallery")))
      assertEquals(sent.flatMap(_.chunks), List("alt of mood-gallery"))
      assertEquals(cards.galleries.toList.flatMap(_.entries.map(entry => (entry.valence.step, entry.energy))), List(2 -> None, 4 -> None))

  test("/mood history keeps the latest fourteen; without check-ins it says how to record one and offers no image"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
      assertEquals(chat.rig.line("/mood history"), List(CheckInCopy.historyEmpty))
      (1 to 16).foreach(step => chat.rig.line(s"mood ${1 + step % 5}"))
      val shown = chat.rig.line("/mood history").mkString
      assert(shown.startsWith(CheckInCopy.historyTitle(14)), shown)
      assertEquals(shown.split("\n\n").size, 15)
      chat.rig.tapOn(CheckInCopy.historyTitle(14), StatsCardCopy.ShowAsImage)
      assertEquals(cards.galleries.toList.map(_.entries.size), List(14))
      assertEquals(cards.galleries.head.entries.last.valence.step, 1 + 16 % 5, "the newest is the last")

  test("an old /mood history button asks for the mood consent once it is withdrawn, and draws nothing"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
      chat.rig.line("mood 3")
      chat.rig.line("/mood history")
      val (_, wire) = chat.rig.controlOn(CheckInCopy.historyTitle(1), StatsCardCopy.ShowAsImage)
      chat.world.consents.withdraw(chat.account, ConsentPurpose.Mood, chat.rig.clock.now())
      val asked = chat.rig.tap(wire)
      assertEquals(asked, List(ConsentCopy.statement(ConsentPurpose.Mood)))
      assertEquals(cards.galleries.toList, Nil)

  test("a Ukrainian account types its check-in and water in Ukrainian and is answered in Ukrainian (ROADMAP G2.22)"):
    withChat(Profile.Telegram, StatusCards.textOnly): chat =>
      chat.grant(ConsentPurpose.Mood, ConsentPurpose.Hydration)
      chat.rig.uow.accounts.saveSettings(
        chat.account,
        AccountSettings.Default.copy(locale = Some("uk")),
        chat.rig.clock.now()
      )
      assertEquals(chat.rig.line("настрій 4"), List("Приємно · 03:00"))
      assertEquals(chat.world.tracking.checkins.values.map(_.valence.step).toList, List(4))
      chat.rig.line("250 мл")
      assertEquals(chat.liveWater, List(250))

  test("a Ukrainian account confirms the water consent after a typed amount and gets the goal card and the offer"):
    // Walk-through of 2026-11-27: the confirming tap failed and the update was quarantined; the user saw nothing.
    withChat(Profile.Telegram, StatusCards.textOnly): chat =>
      chat.rig.uow.accounts.saveSettings(chat.account, AccountSettings.Default.copy(locale = Some("uk")), chat.rig.clock.now())
      chat.rig.line("250 мл")
      Lang.within(Lang.Uk):
        chat.rig.tapOn(ConsentCopy.statement(ConsentPurpose.Hydration), ConsentCopy.Agree)
        val confirmed = chat.rig.tapOn(ConsentCopy.confirmation(ConsentPurpose.Hydration), ConsentCopy.Confirm)
        assertEquals(chat.world.consents.events.toList, List("hydration:granted"))
        assert(texts(confirmed).exists(_.contains(WaterCopy.goalAsk)), texts(confirmed).toString)
        assert(!texts(confirmed).contains(PromptCopy.offerWater), texts(confirmed).toString)
        val recorded = chat.rig.tapOn(WaterCopy.goalAsk, WaterCopy.goalChoice(2000))
        assertEquals(chat.liveWater, List(250), texts(recorded).toString)
        assert(texts(recorded).contains(PromptCopy.offerWater), texts(recorded).toString)

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
        chat.grant(ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
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

  test("after Undo the prompt no longer shows the level, and a later tap on it says the check-in was undone"):
    for cards <- List(RecordingCards(), StatusCards.textOnly) do
      withChat(Profile.Telegram, cards): chat =>
        chat.grant(ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
        chat.rig.command("mood")
        val (prompt, four) = chat.rig.controlOn(CheckInCopy.prompt, "4")
        chat.rig.tapFrom(prompt, four)
        chat.rig.tapOn("Pleasant · 03:00", "Undo")
        assertEquals(chat.rig.messageAt(prompt).chunks, List(CheckInCopy.undone), "the level is gone from the prompt")
        val (_, stale) = chat.rig.tapFrom(prompt, four)
        assertEquals(texts(stale), List(CheckInCopy.promptUndone))
        assertEquals(stale.count(_.isInstanceOf[VendorOp.Edit]), 0)
        assertEquals(chat.world.tracking.checkins.values.count(_.isLive), 0, "the stale tap records nothing")

  test("Undo after Add energy clears the prompt too, and a later tap on it says the check-in was undone"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
      chat.rig.command("mood")
      val (prompt, four) = chat.rig.controlOn(CheckInCopy.prompt, "4")
      chat.rig.tapFrom(prompt, four)
      chat.rig.tapOn("Pleasant · 03:00", CheckInCopy.AddEnergy)
      chat.rig.tapOn(CheckInCopy.energyAsk, "2")
      val (_, kept) = chat.rig.tapFrom(prompt, four)
      assertEquals(texts(kept), List("Already recorded at 03:00."), "the check-in with its energy still stands")
      chat.rig.tapOn("Pleasant. Energy: low. · 03:00", "Undo")
      assertEquals(chat.rig.messageAt(prompt).chunks, List(CheckInCopy.undone))
      val (_, stale) = chat.rig.tapFrom(prompt, four)
      assertEquals(texts(stale), List(CheckInCopy.promptUndone))
      assertEquals(chat.world.tracking.checkins.values.count(_.isLive), 0)

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
      // The seven days offer their picture (IMG-19) and the three week cards (IMG-20 to IMG-22).
      assertEquals(
        labels(shown),
        List(
          "Today",
          "7 days",
          "30 days",
          StatsCardCopy.ShowAsImage,
          StatsCardCopy.WaterWeek,
          StatsCardCopy.SleepWeek,
          StatsCardCopy.MoodWeekday,
          "Month",
          "Patterns",
          StatsCardCopy.ByMedication
        )
      )

  test("/stats meds lists each medication over 7 days with its own counts, 14 days on a tap, the strip on request"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication)
      val everyDay = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
      val created = chat.rig.seedSchedule(chat.account, "Vitamin D", everyDay, kyiv)
      def taken(daysBack: Int): Unit =
        chat.rig.seedOccurrence(created.scheduleId, daysBack, LocalTime.of(9, 0), s"t$daysBack", occ =>
          occ.copy(status = OccurrenceStatus.Taken, nextActionAt = None, takenAt = Some(occ.scheduledFor),
            effectiveAt = Some(occ.scheduledFor)))
        ()
      taken(1)
      taken(10)
      chat.rig.seedOccurrence(created.scheduleId, 2, LocalTime.of(9, 0), "s2", occ =>
        occ.copy(status = OccurrenceStatus.Skipped, nextActionAt = None, skippedAt = Some(occ.scheduledFor)))
      val today = chat.rig.clock.now().atZone(kyiv).toLocalDate
      // Today's 09:00 dose is still open: it is named, and not counted.
      assertEquals(
        chat.rig.line("/stats meds"),
        List(
          List(
            StatsCardCopy.medsTitle(7, today),
            "1. Vitamin D: 1 of 2 taken · skipped 1 · 1 still to answer, not counted yet",
            StatsCardCopy.medsNote
          ).mkString("\n\n")
        )
      )
      assertEquals(
        chat.rig.lastChoiceMap("By medication").map(_.label),
        List("7 days", "14 days", StatsCardCopy.ShowAsImage)
      )
      val fortnight = texts(chat.rig.tapOn("By medication", "14 days"))
      assert(fortnight.exists(_.contains("1. Vitamin D: 2 of 3 taken · skipped 1")), fortnight)
      val asked = chat.rig.tapOn("By medication · last 14 days", StatsCardCopy.ShowAsImage)
      assertEquals(texts(asked), List(ConsentCopy.statement(ConsentPurpose.ImagesInChat)))
      assertEquals(cards.strips.toList, Nil, "the strip is not drawn before the images consent")
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val sent = photos(chat.rig.tapOn("Please confirm", ConsentCopy.Confirm))
      assertEquals(sent.map(_.attachment.map(_.assetKey)), List(Some("strip")))
      assertEquals(sent.flatMap(_.chunks), List("alt of strip"))
      val view = cards.strips.toList.last
      assertEquals((view.from, view.dayCount), (today.minusDays(13), 14))
      assertEquals(view.rows.map(row => (row.name, row.asNeeded)), List("Vitamin D" -> false))
      val marks = view.rows.head.days
      assertEquals(marks(13), List(DoseMark.Open))
      assertEquals(marks(12), List(DoseMark.TakenOnTime))
      assertEquals(marks(11), List(DoseMark.Skipped))
      assertEquals(marks(3), List(DoseMark.TakenOnTime))

  test("after the medication consent is withdrawn, the buttons of an old /stats meds card show no medication"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.ImagesInChat)
      val everyDay = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
      chat.rig.seedSchedule(chat.account, "Vitamin D", everyDay, kyiv)
      chat.rig.line("/stats meds")
      chat.world.consents.withdraw(chat.account, ConsentPurpose.Medication, chat.rig.clock.now())
      val statement = ConsentCopy.statement(ConsentPurpose.Medication)
      assertEquals(texts(chat.rig.tapOn("By medication", "14 days")), List(statement))
      assertEquals(texts(chat.rig.tapOn("By medication", StatsCardCopy.ShowAsImage)), List(statement))
      assertEquals(cards.strips.toList, Nil, "no strip is drawn without the medication consent")

  test("/stats meds with no medication says how to add one and offers no image"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Medication)
      val today = chat.rig.clock.now().atZone(kyiv).toLocalDate
      assertEquals(chat.rig.line("/stats meds"), List(s"${StatsCardCopy.medsTitle(7, today)}\n\n${StatsCardCopy.medsNone}"))
      assertEquals(chat.rig.lastChoiceMap("By medication").map(_.label), List("7 days", "14 days"))

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
      assertEquals(texts(started), List(s"${SleepCopy.started("03:00")}\n\n${SleepCopy.onHold}"))
      assertEquals(labels(started), List(SleepCopy.ImUp, "Undo"))
      chat.rig.clock.at = chat.rig.clock.at.plus(Duration.ofHours(7))
      val woke = chat.rig.tapOn(SleepCopy.started("03:00"), SleepCopy.ImUp)
      val night = "Slept 03:00 to 10:00, 7 h 00 min (your check-ins)."
      assertEquals(texts(woke), List(s"$night\n\n${SleepCopy.qualityAsk}"))
      val rated = chat.rig.tapOn(SleepCopy.qualityAsk, "4")
      assertEquals(texts(rated), List(s"$night\n\n${SleepCopy.rated(4)}"))
      assertEquals(chat.world.tracking.sleep.live.map(_.quality.map(_.step)), List(Some(4)))
      assertEquals(chat.rig.command("sleep"), List(night))

  test("a late Undo on a night points to the app, or to Adjust times when no app opens (MINI_APP_URL unset)"):
    for (appOpens, expected) <- List(true -> SleepCopy.undoWindowPassed, false -> SleepCopy.undoWindowPassedInChat) do
      withChat(Profile.Telegram, appOpens = appOpens): chat =>
        chat.grant(ConsentPurpose.Sleep)
        chat.rig.line("slept 23:30 7:10")
        val (_, undo) = chat.rig.controlOn("Slept 23:30 to 07:10", "Undo")
        chat.rig.clock.at = chat.rig.clock.at.plus(Duration.ofMinutes(16))
        assertEquals(chat.rig.tap(undo), List(expected))
        assertEquals(chat.world.tracking.sleep.live.size, 1)

  test("with no app to open, a night that exists or overlaps is refused with Adjust times for the stored one"):
    withChat(Profile.Telegram, appOpens = false): chat =>
      chat.grant(ConsentPurpose.Sleep)
      chat.rig.line("slept 23:30 7:10")
      assertEquals(chat.rig.line("/sleep 22:00 6:00"), List(SleepCopy.nightExistsInChat))
      val opened = chat.rig.tapOn(SleepCopy.nightExistsInChat, SleepCopy.AdjustTimes)
      assertEquals(texts(opened), List(SleepCopy.adjusting("23:30", "07:10")))
      assertEquals(chat.rig.line("nap 6:00 7:00"), List(SleepCopy.overlapsInChat))
      val again = chat.rig.tapOn(SleepCopy.overlapsInChat, SleepCopy.AdjustTimes)
      assertEquals(texts(again), List(SleepCopy.adjusting("23:30", "07:10")))
      assertEquals(chat.world.tracking.sleep.live.map(_.duration.toMinutes), List(460L))

  test("Adjust times moves each edge by 15 minutes, stores nothing until Save, and keeps the rating (BUX-SLEEP-04)"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Sleep)
      chat.rig.line("slept 23:30 7:10")
      chat.rig.tapOn(SleepCopy.qualityAsk, "4")
      val opened = chat.rig.tapOn(SleepCopy.rated(4), SleepCopy.AdjustTimes)
      assertEquals(texts(opened), List(SleepCopy.adjusting("23:30", "07:10")))
      assertEquals(
        labels(opened),
        List(SleepCopy.BedtimeEarlier, SleepCopy.BedtimeLater, SleepCopy.WakeEarlier, SleepCopy.WakeLater, SleepCopy.Save, "Cancel")
      )
      chat.rig.tapOn(SleepCopy.adjusting("23:30", "07:10"), SleepCopy.BedtimeEarlier)
      chat.rig.tapOn(SleepCopy.adjusting("23:15", "07:10"), SleepCopy.WakeLater)
      assertEquals(chat.world.tracking.sleep.live.map(_.duration.toMinutes), List(460L), "nothing stored before Save")
      val saved = chat.rig.tapOn(SleepCopy.adjusting("23:15", "07:25"), SleepCopy.Save)
      assertEquals(texts(saved), List(s"Slept 23:15 to 07:25, 8 h 10 min (your entry).\n\n${SleepCopy.rated(4)}"))
      assertEquals(chat.world.tracking.sleep.live.map(e => (e.duration.toMinutes, e.quality.map(_.step))), List((490L, Some(4))))
      chat.rig.tapOn(SleepCopy.rated(4), SleepCopy.AdjustTimes)
      val cancelled = chat.rig.tapOn(SleepCopy.adjusting("23:15", "07:25"), "Cancel")
      assertEquals(texts(cancelled), List(s"Slept 23:15 to 07:25, 8 h 10 min (your entry).\n\n${SleepCopy.rated(4)}"))

  test("I'm up without an open night asks for the bedtime with chips around the user's bedtime; a chip records it"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Sleep)
      chat.rig.command("sleep")
      chat.rig.tapOn(SleepCopy.noNight, SleepCopy.GoingToSleep)
      // 07:00 the next day: the tap of 03:00 is older than an open night may be, so no night is open.
      chat.rig.clock.at = chat.rig.clock.at.plus(Duration.ofHours(28))
      val asked = chat.rig.tapOn(SleepCopy.started("03:00"), SleepCopy.ImUp)
      assertEquals(texts(asked), List(SleepCopy.bedtimeAsk))
      assertEquals(labels(asked), List("22:00", "22:30", "23:00", "23:30", "00:00"))
      val night = chat.rig.tapOn(SleepCopy.bedtimeAsk, "23:00")
      assertEquals(texts(night), List(s"Slept 23:00 to 07:00, 8 h 00 min (your entry).\n\n${SleepCopy.qualityAsk}"))
      assertEquals(chat.world.tracking.sleep.live.map(_.duration.toMinutes), List(480L))

  test("Nap, then I'm up, is a nap from the check-ins without a rating; /sleep offers Nap beside Going to sleep"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Sleep)
      chat.rig.command("sleep")
      val started = chat.rig.tapOn(SleepCopy.noNight, SleepCopy.Nap)
      assertEquals(texts(started), List(SleepCopy.napStarted("03:00")))
      assertEquals(chat.rig.command("sleep"), List(SleepCopy.napStarted("03:00")))
      chat.rig.clock.at = chat.rig.clock.at.plus(Duration.ofMinutes(20))
      val woke = chat.rig.tapOn(SleepCopy.napStarted("03:00"), SleepCopy.ImUp)
      assertEquals(texts(woke), List("Napped 03:00 to 03:20, 20 min (your check-ins)."))
      assertEquals(labels(woke), List(SleepCopy.AdjustTimes, "Undo"))
      assertEquals(chat.world.tracking.sleep.live.map(_.kind), List(SleepKind.Nap))

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
        List("Sleep: 1 night logged · 9 more nights for an average · your records · 1 of 14 days have data")
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
      assert(today.exists(_.contains("Sleep last night: 5 h 40 min (your records)")), s"got: $today")
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

  // ---------- The month and the patterns ----------

  /** Water on each day of `days` before the rig's day, at noon in Kyiv, through the use case. */
  private def waterOn(chat: Chat, daysBack: Range, millilitres: Int => Int): Unit =
    val water = HydrationService(chat.rig.clock, SequentialIds())
    val start = chat.rig.clock.at
    daysBack.foreach { back =>
      chat.rig.clock.at = start.minus(Duration.ofDays(back.toLong)).plus(Duration.ofHours(9))
      val amount = WaterAmount.ofMillilitres(millilitres(back)).toOption.get
      chat.rig.uow.transaction(tx =>
        assert(water.log(tx, LogWater(chat.account, amount, EntryDay.Today, EntrySource.Bot, s"w$back")).isRight)
      )
    }
    chat.rig.clock.at = start

  test("/stats month shows the month so far with its gate; Previous month moves back to a month with figures"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      // 21 September is the rig's day: the 14 days before 21 August are all in August.
      waterOn(chat, 22 to 40, _ => 1500)
      val september = chat.rig.line("/stats month")
      assertEquals(september.head.split("\n\n").take(2).toList, List("September 2026 · Europe/Kyiv", "0 days with records"))
      assert(september.head.contains(MonthCopy.gateTitle), september)
      assertEquals(chat.rig.lastChoiceMap("September 2026").map(_.label), List(ReportCopy.PreviousMonth))
      val august = chat.rig.tapOn("September 2026", ReportCopy.PreviousMonth)
      assert(texts(august).head.startsWith("August 2026 · Europe/Kyiv\n\n19 days with records"), texts(august))
      assert(texts(august).head.contains("Water: "), texts(august))
      assertEquals(labels(august), List(ReportCopy.PreviousMonth, ReportCopy.NextMonth, "Show as image"))

  test("Show as image on a month asks the image consent, then sends its pages, each with its own caption"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      waterOn(chat, 22 to 40, _ => 1500)
      chat.rig.line("/stats month")
      chat.rig.tapOn("September 2026", ReportCopy.PreviousMonth)
      assertEquals(texts(chat.rig.tapOn("August 2026", "Show as image")), List(ConsentCopy.statement(ConsentPurpose.ImagesInChat)))
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val sent = chat.rig.tapOn("Please confirm", ConsentCopy.Confirm)
      val photos = sent.collect { case VendorOp.Send(_, message, _, _) if message.attachment.isDefined => message }
      assertEquals(photos.map(_.attachment.map(_.assetKey)), List(Some("month-1"), Some("month-2")))
      assertEquals(photos.flatMap(_.chunks), List("alt of month-1", "alt of month-2"))
      assertEquals(cards.months.map(view => (view.figures.month.toString, view.sleep)).toList, List(("2026-08", None)))

  test("Show as image on a month sends the month in one image after its pages (IMG-24)"):
    val cards = RecordingCards(poster = true)
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.ImagesInChat)
      waterOn(chat, 22 to 40, _ => 1500)
      chat.rig.line("/stats month")
      chat.rig.tapOn("September 2026", ReportCopy.PreviousMonth)
      val sent = chat.rig.tapOn("August 2026", "Show as image")
      val photos = sent.collect { case VendorOp.Send(_, message, _, _) if message.attachment.isDefined => message }
      assertEquals(photos.map(_.attachment.map(_.assetKey)), List(Some("month-1"), Some("month-2"), Some("month-poster")))
      assertEquals(photos.flatMap(_.chunks), List("alt of month-1", "alt of month-2", "alt of month-poster"))

  // ---------- The year (analytics round 6, supervisor decision, 2026-11-28) ----------

  test("/stats year shows the months so far behind the 14-day gate and the year before; a tap opens that year"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      // 21 September 2026 is the rig's day: 19 days of August, and 21 days of October and November 2025.
      waterOn(chat, 22 to 40, _ => 1500)
      waterOn(chat, 315 to 335, _ => 2000)
      val year = chat.rig.line("/stats year").head.split("\n\n").toList
      assertEquals(
        year,
        List(
          "2026 · Europe/Kyiv",
          "Days with records: 19 of 264",
          YearCopy.gateNote,
          "Water, a day with records: Aug 1.5 l · Sep —",
          "2026: 1.5 l (19 days) · 2025: 2.0 l (21 days)"
        )
      )
      assertEquals(chat.rig.lastChoiceMap("2026 · Europe/Kyiv").map(_.label), List("◀ 2025", "Show as image"))
      val tapped = chat.rig.tapOn("2026 · Europe/Kyiv", "◀ 2025")
      val before = texts(tapped).head.split("\n\n").toList
      assertEquals(before.take(2), List("2025 · Europe/Kyiv", "Days with records: 21 of 365"))
      assertEquals(before.last, "2025: 2.0 l (21 days)")
      assertEquals(labels(tapped), List("2026 ▶", "Show as image"))

  test("/stats year without a record says so and offers neither image nor another year"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      val year = chat.rig.line("/stats year").head.split("\n\n").toList
      assertEquals(year, List("2026 · Europe/Kyiv", "Days with records: 0 of 264", "Nothing was recorded in 2026."))
      assertEquals(chat.rig.lastChoiceMap("2026 · Europe/Kyiv"), Nil)

  test("Show as image on the year asks the image consent, then sends the one-year card captioned with its text"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      waterOn(chat, 22 to 40, _ => 1500)
      chat.rig.line("/stats year")
      assertEquals(texts(chat.rig.tapOn("2026 · Europe/Kyiv", "Show as image")), List(ConsentCopy.statement(ConsentPurpose.ImagesInChat)))
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val sent = chat.rig.tapOn("Please confirm", ConsentCopy.Confirm)
      val photos = sent.collect { case VendorOp.Send(_, message, _, _) if message.attachment.isDefined => message }
      assertEquals(photos.map(_.attachment.map(_.assetKey)), List(Some("year")))
      assertEquals(photos.flatMap(_.chunks), List("alt of year"))
      assertEquals(cards.years.map(view => (view.year, view.recorded.keySet)).toList, List(2026 -> Set(TrendDomain.Medication, TrendDomain.Hydration)))

  // ---------- Two periods compared (supervisor decision, 2026-11-28) ----------

  test("/stats compare puts last week beside this week, with the gate and the difference in words"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      // 21 September 2026, the rig's day, is a Monday: last week is 14 to 20 September, this week the one day.
      waterOn(chat, 1 to 7, _ => 1500)
      val compared = chat.rig.line("/stats compare").head.split("\n\n").toList
      assertEquals(
        compared,
        List(
          "Compare two periods · Europe/Kyiv",
          "Last week, 14 Sep to 20 Sep: days with records 7 of 7",
          "This week, 21 Sep: days with records 0 of 1",
          CompareCopy.gateNote(dosegram.core.domain.insights.MinimumSample.WeeklyMeanDays),
          CompareCopy.differenceNote,
          "Doses taken of those answered: Last week — · This week —",
          "Water, a day with records: Last week 1.5 l (7 days) · This week —",
          StatsCopy.NotACause
        )
      )
      assertEquals(
        chat.rig.lastChoiceMap("Compare two periods").map(_.label),
        List("This week", "This month", "Pick two months", "Show as image")
      )

  test("This month compares the month so far with the whole month before; the picker compares two months"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      // August has 14 days of water from the 1st, so its month passes the gate; July has 20 days.
      waterOn(chat, 37 to 51, _ => 2000)
      waterOn(chat, 60 to 79, _ => 1500)
      chat.rig.line("/stats compare")
      val months = texts(chat.rig.tapOn("Compare two periods", "This month")).head.split("\n\n").toList
      assert(months.contains("Last month, 1 Aug to 31 Aug: days with records 15 of 31"), months)
      assert(months.contains("Water, a day with records: Last month 2.0 l (15 days) · This month —"), months)
      val first = chat.rig.tapOn("Compare two periods", "Pick two months")
      assertEquals(texts(first), List(CompareCopy.pickFirst))
      assertEquals(labels(first).take(4), List("Sep 2026", "Aug 2026", "Jul 2026", "Jun 2026"))
      val second = chat.rig.tapOn(CompareCopy.pickFirst, "Jul 2026")
      assertEquals(texts(second), List("First month: Jul 2026. Pick the second month."))
      assert(!labels(second).contains("Jul 2026"), labels(second))
      val pair = texts(chat.rig.tapOn("First month: Jul 2026", "Aug 2026")).head.split("\n\n").toList
      assert(pair.contains("Jul 2026, 1 Jul to 31 Jul: days with records 20 of 31"), pair)
      assert(
        pair.contains("Water, a day with records: Jul 2026 1.5 l (20 days) · Aug 2026 2.0 l (15 days) · 0.5 l higher"),
        pair
      )

  test("Show as image on the comparison asks the images consent, then sends IMG-27 captioned with its text"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      waterOn(chat, 1 to 7, _ => 1500)
      chat.rig.line("/stats compare")
      assertEquals(
        texts(chat.rig.tapOn("Compare two periods", "Show as image")),
        List(ConsentCopy.statement(ConsentPurpose.ImagesInChat))
      )
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val sent = chat.rig.tapOn("Please confirm", ConsentCopy.Confirm)
      val photos = sent.collect { case VendorOp.Send(_, message, _, _) if message.attachment.isDefined => message }
      assertEquals(photos.map(_.attachment.map(_.assetKey)), List(Some("compare")))
      val view = cards.compares.head
      assertEquals((view.earlier.label, view.later.label), ("Last week", "This week"))
      assertEquals(view.rows.map(_.domain), List(TrendDomain.Medication, TrendDomain.Hydration))
      assertEquals(view.rows(1).earlier, CompareCardCell(Some("1.5 l"), Some(1500.0), "7 days"))
      assertEquals(view.rows(1).later, CompareCardCell(None, None, "0 days"))
      assertEquals(photos.flatMap(_.chunks), List(view.text))

  test("/stats compare with nothing recorded says so and offers no image"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      val compared = chat.rig.line("/stats compare").head.split("\n\n").toList
      assertEquals(compared.last, CompareCopy.none)
      assertEquals(
        chat.rig.lastChoiceMap("Compare two periods").map(_.label),
        List("This week", "This month", "Pick two months")
      )

  // ---------- The rhythm of the day (supervisor decision, 2026-11-28) ----------

  test("/stats rhythm shows when in the day each area's records fall, behind the 14-day gate"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      // Water at noon in Kyiv on the 14 days before the rig's day, 21 September 2026: the window is 23 Aug to 21 Sep.
      waterOn(chat, 1 to 14, _ => 500)
      val rhythm = chat.rig.line("/stats rhythm").head.split("\n\n").toList
      assertEquals(
        rhythm,
        List(
          "Rhythm of the day · last 30 days · Europe/Kyiv",
          "23 Aug to 21 Sep: days with records 14 of 30",
          RhythmCopy.gateNote,
          RhythmCopy.clockNote,
          "Doses: —",
          "Water (14 days): 14 entries, most often 12:00 to 13:00 · morning 0, afternoon 14, evening 0, night 0",
          RhythmCopy.closing
        )
      )
      assertEquals(chat.rig.lastChoiceMap("Rhythm of the day").map(_.label), List(StatsCardCopy.ShowAsImage))

  test("an area below the gate shows its recorded days, and nothing to draw offers no image"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      waterOn(chat, 1 to 9, _ => 500)
      val rhythm = chat.rig.line("/stats rhythm").head.split("\n\n").toList
      assert(rhythm.contains("Water: records on 9 days"), rhythm)
      assertEquals(chat.rig.lastChoiceMap("Rhythm of the day").map(_.label), Nil)

  test("Rhythm of the day on the 30-day /stats opens the rhythm; Show as image asks the images consent, then IMG-28"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      waterOn(chat, 1 to 14, _ => 500)
      chat.rig.line("/stats 30")
      val opened = texts(chat.rig.tapOn("Last 30 days", RhythmCopy.Rhythm))
      assert(opened.head.startsWith("Rhythm of the day · last 30 days"), opened)
      assertEquals(
        texts(chat.rig.tapOn("Rhythm of the day", StatsCardCopy.ShowAsImage)),
        List(ConsentCopy.statement(ConsentPurpose.ImagesInChat))
      )
      assertEquals(cards.rhythms.toList, Nil, "the card is not drawn before the images consent")
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val sent = chat.rig.tapOn("Please confirm", ConsentCopy.Confirm)
      val photos = sent.collect { case VendorOp.Send(_, message, _, _) if message.attachment.isDefined => message }
      assertEquals(photos.map(_.attachment.map(_.assetKey)), List(Some("rhythm")))
      val view = cards.rhythms.head
      assertEquals(view.rows.map(_.domain), List(TrendDomain.Medication, TrendDomain.Hydration))
      assertEquals(view.rows.head.hours, None)
      assertEquals(view.rows(1).hours.map(_(12)), Some(14))
      assertEquals(view.rows(1).summary, "most often 12:00 to 13:00")
      assertEquals(view.rows(1).days, "14 days")
      assertEquals(photos.flatMap(_.chunks), List(view.text))

  test("/stats rhythm with nothing recorded says so; with counts off it leaves out the gate and the days (R-GAM-5)"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      assertEquals(chat.rig.line("/stats rhythm").head.split("\n\n").toList.last, RhythmCopy.none)
      waterOn(chat, 1 to 14, _ => 500)
      chat.rig.uow.accounts.setGamification(chat.account, false, chat.rig.clock.now())
      val rhythm = chat.rig.line("/stats rhythm").head.split("\n\n").toList
      assert(!rhythm.contains(RhythmCopy.gateNote), rhythm)
      assert(rhythm.exists(_.startsWith("Water: 14 entries, most often 12:00 to 13:00")), rhythm)

  test("/insights without the cross-domain consent runs no comparison and offers it; the consent turns it on"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Hydration, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
      val now = chat.rig.clock.at
      chat.rig.clock.at = now.minus(Duration.ofDays(30))
      chat.rig.uow.transaction(tx => HydrationService(chat.rig.clock, SequentialIds()).setGoal(tx, chat.account, Some(2000)))
      chat.rig.clock.at = now
      waterOn(chat, 1 to 20, back => if back % 2 == 0 then 2200 else 800)
      val moods = MoodService(chat.rig.clock, SequentialIds())
      val start = chat.rig.clock.at
      (1 to 20).foreach { back =>
        chat.rig.clock.at = start.minus(Duration.ofDays(back.toLong)).plus(Duration.ofHours(10))
        val valence = Valence.of(if back % 2 == 0 then 5 else 3).get
        chat.rig.uow.transaction(tx =>
          moods.checkIn(tx, CheckIn(chat.account, valence, None, Nil, EntryDay.Today, EntrySource.Bot, s"m$back"))
        )
      }
      chat.rig.clock.at = start
      val off = chat.rig.line("/insights")
      assert(off.head.contains(InsightCopy.crossDomainOff), off)
      assert(!off.head.contains("water goal, your average mood"), off)
      assertEquals(chat.rig.lastChoiceMap(InsightCopy.title).map(_.label), List(InsightCopy.CompareAcrossAreas))
      assertEquals(
        texts(chat.rig.tapOn(InsightCopy.title, InsightCopy.CompareAcrossAreas)),
        List(ConsentCopy.statement(ConsentPurpose.CrossDomainPatterns))
      )
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val on = texts(chat.rig.tapOn("Please confirm", ConsentCopy.Confirm))
      assert(on.exists(_.contains("days you reached your water goal, your average mood was")), on)
      val image = chat.rig.tapOn(InsightCopy.title, "Show as image")
      val photo = image.collect { case VendorOp.Send(_, message, _, _) if message.attachment.isDefined => message }
      assertEquals(photo.map(_.attachment.map(_.assetKey)), List(Some("insight")))
      assertEquals(cards.insights.map(_.rule).toList, List(InsightRule.WaterGoalAndMood))

  test("an unknown period is answered with the usage"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Medication)
      assertEquals(
        chat.rig.line("/stats decade"),
        List(
          "Send /stats, /stats today, /stats 7, /stats 30, /stats trends, /stats month, /stats year, /stats compare, " +
            "/stats rhythm or /stats meds."
        )
      )

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
      chat.grant(ConsentPurpose.ImagesInChat)
      val asked = chat.rig.line("/mood 3")
      assert(asked.exists(text => text.contains("1) I agree") && text.contains("2) Not now")), s"got: $asked")
      chat.rig.line("1")
      val confirmed = chat.rig.line("1")
      assert(confirmed.exists(_.contains("Consent recorded on 21 Sep 2026: mood records.")), s"got: $confirmed")
      assert(confirmed.exists(_.contains("[image: alt of mood]")), s"got: $confirmed")
      assert(confirmed.exists(_.contains("Neutral · 03:00")), s"got: $confirmed")

  // ---------- Meals ----------

  private def liveMeals(chat: Chat): List[(MealType, Option[String])] =
    chat.world.tracking.meals.live.map(entry => entry.mealType -> entry.text.map(_.value))

  test("/food before the consent shows stage 1 and records nothing; the receipt is followed by the meal buttons"):
    withChat(Profile.Telegram): chat =>
      assertEquals(chat.rig.line("/food"), List(ConsentCopy.statement(ConsentPurpose.Nutrition)))
      assertEquals(liveMeals(chat), Nil)
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val confirmed = texts(chat.rig.tapOn("Please confirm", ConsentCopy.Confirm))
      assert(confirmed.exists(_.contains("meal records")), s"got: $confirmed")
      assert(confirmed.contains(FoodCopy.which), s"got: $confirmed")
      assertEquals(chat.world.consents.events.toList, List("nutrition:granted"))

  test("/food shows one button per meal type; one tap records that meal now, with Undo (AC-NUT-1)"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Nutrition)
      assertEquals(chat.rig.line("/food"), List(FoodCopy.which))
      assertEquals(
        chat.rig.lastChoiceMap(FoodCopy.which).map(_.label),
        MealType.values.toList.map(FoodCopy.label)
      )
      val saved = chat.rig.tapOn(FoodCopy.which, "Lunch")
      assertEquals(texts(saved), List(FoodCopy.saved(MealType.Lunch, "03:00", withNote = false)))
      // "Edit in app" opens the Mini App; the rig's adapter has no Mini App address, so it leaves that button out.
      assertEquals(labels(saved), List("Undo"))
      assertEquals(liveMeals(chat), List(MealType.Lunch -> None))

  test("\"lunch: soup and bread\" records a lunch with its words; the echo does not repeat them (AC-NUT-2)"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Nutrition)
      assertEquals(chat.rig.line("lunch: Soup and bread"), List(FoodCopy.saved(MealType.Lunch, "03:00", withNote = true)))
      assertEquals(liveMeals(chat), List(MealType.Lunch -> Some("Soup and bread")))
      assertEquals(chat.rig.line("/food dinner: Pasta"), List(FoodCopy.saved(MealType.Dinner, "03:00", withNote = true)))
      assertEquals(liveMeals(chat).last, MealType.Dinner -> Some("Pasta"))

  test("\"soup and bread\" records nothing; a meal word without the colon asks which meal"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Nutrition)
      assert(!chat.rig.line("soup and bread").contains(FoodCopy.which))
      assertEquals(chat.rig.line("lunch was late today"), List(s"${FoodCopy.which}\n\n${FoodCopy.ambiguousHint}"))
      assertEquals(liveMeals(chat), Nil)

  test("Undo on the outcome removes the meal"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Nutrition)
      chat.rig.line("snack: apple")
      val undone = chat.rig.tapOn(FoodCopy.saved(MealType.Snack, "03:00", withNote = true), "Undo")
      assertEquals(texts(undone), List(FoodCopy.undone))
      assertEquals(liveMeals(chat), Nil)

  test("a late Undo on a meal points to the app, or to /privacy when no app opens (MINI_APP_URL unset)"):
    for (appOpens, expected) <- List(true -> FoodCopy.undoWindowPassed, false -> FoodCopy.undoWindowPassedInChat) do
      withChat(Profile.Telegram, appOpens = appOpens): chat =>
        chat.grant(ConsentPurpose.Nutrition)
        chat.rig.line("snack: apple")
        val (_, undo) = chat.rig.controlOn(FoodCopy.saved(MealType.Snack, "03:00", withNote = true), "Undo")
        chat.rig.clock.at = chat.rig.clock.at.plus(Duration.ofMinutes(16))
        assertEquals(chat.rig.tap(undo), List(expected))
        assertEquals(liveMeals(chat).size, 1)

  test("Change a meal on /food edits the type of a recent meal, with Undo, and keeps its time and words"):
    withChat(Profile.Telegram, appOpens = false): chat =>
      chat.grant(ConsentPurpose.Nutrition)
      chat.rig.line("lunch: soup")
      chat.rig.clock.at = chat.rig.clock.at.plus(Duration.ofMinutes(20))
      chat.rig.line("/food")
      val recent = chat.rig.tapOn(FoodCopy.which, FoodCopy.ChangeMeal)
      assertEquals(texts(recent), List(FoodCopy.recentTitle))
      val lunch = FoodCopy.recentLabel(MealType.Lunch, "Mon", "03:00")
      assertEquals(labels(recent), List(lunch, Labels.Cancel))
      val picked = chat.rig.tapOn(FoodCopy.recentTitle, lunch)
      assertEquals(texts(picked), List(FoodCopy.picked(MealType.Lunch, "Mon", "03:00", withNote = true)))
      assertEquals(labels(picked), List(FoodCopy.Edit, FoodCopy.Delete, Labels.Back))
      val asked = chat.rig.tapOn(FoodCopy.picked(MealType.Lunch, "Mon", "03:00", withNote = true), FoodCopy.Edit)
      assertEquals(texts(asked), List(FoodCopy.editAsk))
      val changed = chat.rig.tapOn(FoodCopy.editAsk, FoodCopy.label(MealType.Dinner))
      assertEquals(texts(changed), List(FoodCopy.changed(MealType.Dinner, "Mon", "03:00")))
      assertEquals(liveMeals(chat), List(MealType.Dinner -> Some("soup")))
      val undone = chat.rig.tapOn(FoodCopy.changed(MealType.Dinner, "Mon", "03:00"), Labels.Undo)
      assertEquals(texts(undone), List(FoodCopy.changed(MealType.Lunch, "Mon", "03:00")))
      assertEquals(liveMeals(chat), List(MealType.Lunch -> Some("soup")))

  test("Delete on a recent meal removes it, Undo brings it back with its time and words"):
    withChat(Profile.Telegram, appOpens = false): chat =>
      chat.grant(ConsentPurpose.Nutrition)
      chat.rig.line("breakfast: oats")
      chat.rig.line("snack: apple")
      chat.rig.clock.at = chat.rig.clock.at.plus(Duration.ofMinutes(30))
      chat.rig.line("/food")
      chat.rig.tapOn(FoodCopy.which, FoodCopy.ChangeMeal)
      val snack = FoodCopy.recentLabel(MealType.Snack, "Mon", "03:00")
      chat.rig.tapOn(FoodCopy.recentTitle, snack)
      val deleted = chat.rig.tapOn(FoodCopy.picked(MealType.Snack, "Mon", "03:00", withNote = true), FoodCopy.Delete)
      assertEquals(texts(deleted), List(FoodCopy.deleted(MealType.Snack, "Mon", "03:00")))
      assertEquals(liveMeals(chat), List(MealType.Breakfast -> Some("oats")))
      val restored = chat.rig.tapOn(FoodCopy.deleted(MealType.Snack, "Mon", "03:00"), Labels.Undo)
      assertEquals(texts(restored), List(FoodCopy.restored(MealType.Snack, "Mon", "03:00")))
      assertEquals(liveMeals(chat).toSet, Set[(MealType, Option[String])](MealType.Breakfast -> Some("oats"), MealType.Snack -> Some("apple")))
      assertEquals(chat.world.tracking.meals.live.map(_.occurredAt).distinct.size, 1, "the time is kept")

  test("the late Undo on a meal names Change a meal on /food when no app opens"):
    assert(FoodCopy.undoWindowPassedInChat.contains(FoodCopy.ChangeMeal), FoodCopy.undoWindowPassedInChat)
    assert(Lang.within(Lang.Uk)(FoodCopy.undoWindowPassedInChat).contains(Lang.within(Lang.Uk)(FoodCopy.ChangeMeal)))

  test("/food lists the meals logged today above the buttons"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Nutrition)
      chat.rig.line("breakfast: oats")
      val today = FoodCopy.today(List(MealType.Breakfast -> "03:00"))
      assertEquals(chat.rig.line("/meal"), List(s"$today\n\n${FoodCopy.which}"))

  test("the food day image needs the images consent and draws today's plate with the day in words (G8.3)"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Nutrition)
      chat.rig.line("breakfast: oats")
      chat.rig.line("snack: apple")
      chat.rig.line("/food")
      val asked = chat.rig.tapOn(FoodCopy.which, FoodCopy.ShowAsImage)
      assertEquals(texts(asked), List(ConsentCopy.statement(ConsentPurpose.ImagesInChat)))
      assertEquals(cards.foods.toList, Nil)
      chat.grant(ConsentPurpose.ImagesInChat)
      val sent = chat.rig.tapOn(FoodCopy.which, FoodCopy.ShowAsImage)
      val view = cards.foods.toList.last
      assertEquals(view.mains.keySet, Set(MealType.Breakfast))
      assertEquals((view.snacks, view.line), (1, FoodLineView(1, 3)))
      val caption = texts(sent).mkString("\n")
      assert(caption.contains("Today: breakfast 03:00, snack 03:00."), caption)
      assert(caption.contains("1 of 3 planned meals logged, 1 snack"), caption)

  test("food is in /stats and on the daily summary card when the domain is on (G8.3)"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Nutrition, ConsentPurpose.ImagesInChat)
      chat.rig.line("lunch: soup")
      val today = chat.rig.line("/stats today").mkString("\n")
      assert(today.contains("Food: 1 of 3 planned meals logged"), today)
      val week = chat.rig.line("/stats").mkString("\n")
      assert(week.contains("Food: 1 meal logged · Breakfast 0, Lunch 1, Dinner 0 of 7 days"), week)
      chat.rig.tapOn("Food: 1 of 3 planned meals logged", StatsCardCopy.ShowAsImage)
      assertEquals(cards.days.toList.last.food, Some(FoodLineView(1, 3)))

  test("Show as image on the seven days sends the week at a glance, captioned with the seven days' text (IMG-19)"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Nutrition, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
      chat.rig.line("lunch: soup")
      chat.rig.line("mood 4")
      val week = chat.rig.line("/stats").mkString("\n")
      assert(chat.rig.lastChoiceMap("Food: 1 meal logged").map(_.label).contains(StatsCardCopy.ShowAsImage))
      val sent = chat.rig.tapOn("Food: 1 meal logged", StatsCardCopy.ShowAsImage)
      val photo = sent.collect { case VendorOp.Send(_, message, _, _) if message.attachment.isDefined => message }
      assertEquals(photo.map(_.attachment.map(_.assetKey)), List(Some("week-glance")))
      assert(week.contains("Food: 1 meal logged"), week)
      val view = cards.weeks.toList.last
      assertEquals(view.food.map(_.flatten.map(_.mainMeals).sum), Some(1))
      assertEquals(view.mood.map(_.flatten.size), Some(1))
      assertEquals(view.water, None, "water is drawn only behind its consent")
      assert(photo.flatMap(_.chunks).mkString("\n").contains("Food: 1 meal logged"), "the caption is the seven days' text")

  test(
    "the seven-day /stats offers water, sleep and mood cards and sends each with its text equivalent (IMG-20 to IMG-22)"
  ):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.Sleep, ConsentPurpose.Mood)
      chat.rig.command("water")
      chat.rig.tapOn("Water today", "Goal")
      chat.rig.tapOn(WaterCopy.goalAsk, "2.0 l")
      chat.rig.line("500 ml")
      chat.rig.line("mood 4")

      chat.rig.line("/stats")
      val weekCards = List(StatsCardCopy.WaterWeek, StatsCardCopy.SleepWeek, StatsCardCopy.MoodWeekday)
      assertEquals(
        chat.rig.lastChoiceMap("Last 7 days").map(_.label),
        List("Today", "7 days", "30 days", StatsCardCopy.ShowAsImage) ++ weekCards ++
          List("Month", "Patterns", StatsCardCopy.ByMedication)
      )
      chat.rig.line("/stats today")
      assertEquals(
        chat.rig.lastChoiceMap("Today · Europe/Kyiv").map(_.label),
        List("Today", "7 days", "30 days", StatsCardCopy.ShowAsImage, "Month", "Patterns", StatsCardCopy.ByMedication)
      )
      chat.rig.line("/stats 30")
      assertEquals(
        chat.rig.lastChoiceMap("Last 30 days").map(_.label),
        List(
          "Today",
          "7 days",
          "30 days",
          StatsCardCopy.Trends,
          RhythmCopy.Rhythm,
          "Month",
          "Patterns",
          StatsCardCopy.ByMedication
        )
      )

      val asked = chat.rig.tapOn("Last 7 days", StatsCardCopy.WaterWeek)
      assertEquals(texts(asked), List(ConsentCopy.statement(ConsentPurpose.ImagesInChat)))
      assertEquals(cards.waterWeeks.toList, Nil, "the card is not drawn before the images consent")
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val water = chat.rig.tapOn("Please confirm", ConsentCopy.Confirm)
      val waterPhoto = photos(water)
      assertEquals(waterPhoto.map(_.attachment.map(_.assetKey)), List(Some("water-week")))
      assertEquals(waterPhoto.flatMap(_.chunks), List("alt of water-week"))
      assertEquals(waterPhoto.flatMap(_.attachment.toList).map(_.altText), List("alt of water-week"))
      assertEquals(waterPhoto.flatMap(_.attachment.toList).map(_.hasSpoiler), List(false))
      assert(cards.waterWeeks.toList.last.water.exists(_.flatten.exists(_.totalMl == 500)))

      val sleep = chat.rig.tapOn("Last 7 days", StatsCardCopy.SleepWeek)
      assertEquals(photos(sleep).map(_.attachment.map(_.assetKey)), List(Some("sleep-week")))
      assertEquals(photos(sleep).flatMap(_.chunks), List("alt of sleep-week"))
      assert(cards.sleepWeeks.toList.last.sleep.isDefined)

      val mood = chat.rig.tapOn("Last 7 days", StatsCardCopy.MoodWeekday)
      assertEquals(photos(mood).map(_.attachment.map(_.assetKey)), List(Some("mood-weekday")))
      assertEquals(photos(mood).flatMap(_.chunks), List("alt of mood-weekday"))
      assert(cards.moodWeekdays.toList.last.exists(_.days > 0))

  test("/stats trends shows each domain with its previous 30 days; 90 days switch to weeks (ROADMAP G9.4, G9.5)"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.Mood)
      chat.rig.line("500 ml")
      chat.rig.line("mood 4")

      val month = chat.rig.line("/stats trends").mkString("\n")
      assert(month.contains("Trends · last 30 days · Europe/Kyiv"), month)
      // The counts switch is on: a figure below its minimum says what it still needs (R-GAM-3).
      assert(month.contains("Water: 9 more recorded days for an average · 1 of 30 days have data"), month)
      assert(month.contains("Previous 30 days: No data"), month)
      assert(month.contains("Mood: 1 check-in · 9 more days with a check-in for an average"), month)
      assert(month.contains("Days with records: water 1 of 7, mood 1 of 7."), month)
      assert(!month.contains("Medication:"), "a domain without a record in the period is left out")
      assertEquals(
        chat.rig.lastChoiceMap("Trends · last 30 days").map(_.label),
        List("30 days", "90 days", StatsCardCopy.ShowAsImage, CompareCopy.ComparePeriods, "Month", "Patterns", StatsCardCopy.ByMedication)
      )

      val quarter = texts(chat.rig.tapOn("Trends · last 30 days", "90 days")).mkString("\n")
      assert(quarter.contains("Trends · last 90 days"), quarter)
      assert(quarter.contains("Previous 90 days: No data"), quarter)

  test("Show as image on the trends asks the images consent, then sends IMG-23 with its text equivalent"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration)
      chat.rig.line("500 ml")
      chat.rig.line("/stats trends")
      val asked = chat.rig.tapOn("Trends · last 30 days", StatsCardCopy.ShowAsImage)
      assertEquals(texts(asked), List(ConsentCopy.statement(ConsentPurpose.ImagesInChat)))
      assertEquals(cards.trends.toList, Nil, "the card is not drawn before the images consent")
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val sent = photos(chat.rig.tapOn("Please confirm", ConsentCopy.Confirm))
      assertEquals(sent.map(_.attachment.map(_.assetKey)), List(Some("trends")))
      assertEquals(sent.flatMap(_.chunks), List("alt of trends"))
      val view = cards.trends.toList.last
      assertEquals(view.window.days, 30)
      assertEquals(view.byWeek, false)
      assertEquals(view.rows.map(_.domain), List(TrendDomain.Hydration))
      assertEquals(view.rows.head.figure, "9 more recorded days for an average")
      assertEquals(view.rows.head.buckets.flatten, List(500.0))
      assert(view.text.contains("Water: 9 more recorded days for an average. Previous 30 days: No data."), view.text)

  test("a discreet Ukrainian account gets the week cards in Ukrainian behind a spoiler (IMG-20)"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.ImagesInChat)
      val chosen = AccountSettings.Default.copy(isDiscreet = true, locale = Some("uk"))
      chat.rig.uow.accounts.saveSettings(chat.account, chosen, chat.rig.clock.now())
      chat.rig.command("stats")
      assertEquals(
        chat.rig.lastChoiceMap("Останні 7 днів").map(_.label),
        List(
          "Сьогодні",
          "7 днів",
          "30 днів",
          "Показати як зображення",
          "Поділитися карткою",
          "Вода за 7 днів",
          "Сон за 7 ночей",
          "Настрій за днями тижня",
          "Місяць",
          "Закономірності",
          "За ліками"
        )
      )
      val sent = chat.rig.tapOn("Останні 7 днів", "Вода за 7 днів")
      val photo = photos(sent)
      assertEquals(photo.map(_.attachment.map(_.assetKey)), List(Some("water-week")))
      assertEquals(photo.flatMap(_.chunks), List("alt of water-week"))
      assertEquals(
        photo.flatMap(_.attachment.toList).map(image => (image.altText, image.hasSpoiler)),
        List(("alt of water-week", true))
      )

  test("/today offers the daily summary card, which the images consent then sends (IMG-03)"):
    val cards = RecordingCards()
    withChat(Profile.Telegram, cards): chat =>
      chat.grant(ConsentPurpose.Medication)
      chat.rig.command("today")
      assert(chat.rig.lastChoiceMap(MenuCopy.medicationsTodaysDoses).map(_.label).contains(StatsCardCopy.ShowAsImage))
      val asked = chat.rig.tapOn(MenuCopy.medicationsTodaysDoses, StatsCardCopy.ShowAsImage)
      assertEquals(texts(asked), List(ConsentCopy.statement(ConsentPurpose.ImagesInChat)))
      chat.rig.tapOn("I agree that", ConsentCopy.Agree)
      val sent = chat.rig.tapOn("Please confirm", ConsentCopy.Confirm)
      val photo = sent.collect { case VendorOp.Send(_, message, _, _) if message.attachment.isDefined => message }
      assertEquals(photo.map(_.attachment.map(_.assetKey)), List(Some("day")))
      assertEquals(cards.days.toList.map(_.doses.isDefined), List(true))

  test("a photo is answered with a neutral notice and nothing is fetched or stored (G8.1, BUX-FOOD-05)"):
    withChat(Profile.Telegram): chat =>
      chat.grant(ConsentPurpose.Nutrition)
      assertEquals(chat.rig.photo(Some("lunch: pasta")), List(FoodCopy.photoLater))
      assertEquals(liveMeals(chat), Nil)
end CaptureFlowsSuite
