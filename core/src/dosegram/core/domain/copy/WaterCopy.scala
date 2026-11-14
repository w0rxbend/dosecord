package dosegram.core.domain.copy

import dosegram.core.domain.hydration.HydrationBounds
import dosegram.core.domain.insights.GoalProgress
import dosegram.core.domain.insights.HydrationDay

/** Water copy for the chat (BOT_UX section 11.2 and the transcript of section 21.7). Every line states what was logged
  * against the user's own goal: no line suggests an amount or comments on a total (PRODUCT_SPEC section 8.8).
  */
object WaterCopy:

  def goalAsk =
    tr(
      "Set a daily amount to count towards? Pick any amount that suits you. Dosegram does not recommend an amount.",
      "Задати денну кількість, до якої рахувати? Виберіть будь-яку зручну для вас. Dosegram не рекомендує кількість."
    )
  def NoGoal = tr("No goal", "Без цілі")
  def Goal = tr("Goal", "Ціль")
  def ShowAsImage = tr("Show as image", "Показати зображенням")
  def nothingLogged = tr("Water today: nothing logged yet.", "Вода сьогодні: поки нічого не записано.")
  def usage = tr("Send an amount, for example /water 300.", "Надішліть кількість, наприклад /water 300.")
  def undoWindowPassed = tr("The undo window has passed.", "Час на скасування минув.")
  def unknownEntry = tr("This entry is no longer there.", "Цього запису вже немає.")
  def imageUnavailable = tr(
    "The image is not available right now. The figures above are complete.",
    "Зображення зараз недоступне. Цифри вище повні."
  )
  def imageNeedsGoal = tr(
    "The image shows progress to your own goal. Set one with Goal first.",
    "Зображення показує поступ до вашої власної цілі. Спершу задайте її кнопкою «Ціль»."
  )

  /** `water.too_much`: the bound is an input-error guard, not guidance. */
  def tooMuch = tr(
    s"One entry can be at most ${HydrationBounds.MaxEntryMl} ml. Send a smaller amount.",
    s"Один запис може мати щонайбільше ${HydrationBounds.MaxEntryMl} мл. Надішліть меншу кількість."
  )

  def preset(millilitres: Int): String = tr(s"+ $millilitres ml", s"+ $millilitres мл")
  def goalChoice(millilitres: Int): String = volume(millilitres)

  /** A volume in litres with its unit: "1.25 l", "2.0 l". */
  def volume(millilitres: Int): String =
    tr(s"${StatsCopy.dayLitres(millilitres)} l", s"${StatsCopy.dayLitres(millilitres)} л")

  /** The accessible name of the vessel: the value, the goal and the percent in words (MINI_APP MA-HYD-2). */
  def vesselName(progress: GoalProgress): String =
    tr(
      s"Water: ${StatsCopy.dayLitres(progress.totalMl)} of ${StatsCopy.dayLitres(progress.goalMl)} litres, " +
        s"${progress.percent} percent",
      s"Вода: ${StatsCopy.dayLitres(progress.totalMl)} л з ${StatsCopy.dayLitres(progress.goalMl)} л, " +
        UkNouns.Percent.count(progress.percent.toLong)
    )
  def dailyLimit(limit: Int): String =
    tr(s"A day holds at most $limit water entries.", s"За день можна зробити щонайбільше $limit записів води.")

  /** The water card of `/water`. */
  def card(day: HydrationDay): String =
    if day.entries == 0 then nothingLogged else tr(s"Water today: ${figures(day)}", s"Вода сьогодні: ${figures(day)}")

  /** `water.added`: the echo names the amount and the new daily total; "Daily goal reached." is said once. */
  def added(millilitres: Int, day: HydrationDay, goalReachedNow: Boolean): String =
    val reached = if goalReachedNow then tr(" Daily goal reached.", " Денну ціль досягнуто.") else ""
    tr(
      s"Water +$millilitres ml. Today: ${figures(day)}.$reached",
      s"Вода +$millilitres мл. Сьогодні: ${figures(day)}.$reached"
    )

  def undone(day: HydrationDay): String =
    tr(s"Undone. Water today: ${figures(day)}.", s"Скасовано. Вода сьогодні: ${figures(day)}.")

  def goalSet(millilitres: Option[Int]): String =
    millilitres
      .map(goal => tr(s"Daily goal: ${goalChoice(goal)}.", s"Денна ціль: ${goalChoice(goal)}."))
      .getOrElse(tr("No daily goal.", "Без денної цілі."))

  /** The figures of a day without the domain name in front, for a surface that shows the domain as a heading. */
  def figures(day: HydrationDay): String =
    day.progress match
      case Some(progress) =>
        tr(
          s"${StatsCopy.dayLitres(progress.totalMl)} of ${StatsCopy.dayLitres(progress.goalMl)} l (${progress.percent}%)",
          s"${StatsCopy.dayLitres(progress.totalMl)} з ${StatsCopy.dayLitres(progress.goalMl)} л (${progress.percent}%)"
        )
      case None => tr(s"${StatsCopy.dayLitres(day.totalMl)} l", s"${StatsCopy.dayLitres(day.totalMl)} л")

  private def sampleDay = HydrationDay(totalMl = 1250, entries = 4, goalMl = Some(2000))

  def entries: List[CopyEntry] = List(
    CopyEntry("water.goal.ask", goalAsk),
    CopyEntry("water.goal.none", NoGoal),
    CopyEntry("water.goal.button", Goal),
    CopyEntry("water.goal.choice", goalChoice(2000)),
    CopyEntry("water.vessel.name", vesselName(GoalProgress(1250, 2000))),
    CopyEntry("water.goal.set", goalSet(Some(2000))),
    CopyEntry("water.goal.unset", goalSet(None)),
    CopyEntry("water.show_as_image", ShowAsImage),
    CopyEntry("water.nothing_logged", nothingLogged),
    CopyEntry("water.usage", usage),
    CopyEntry("water.undo_window_passed", undoWindowPassed),
    CopyEntry("water.unknown_entry", unknownEntry),
    CopyEntry("water.image_unavailable", imageUnavailable),
    CopyEntry("water.image_needs_goal", imageNeedsGoal),
    CopyEntry("water.too_much", tooMuch),
    CopyEntry("water.preset", preset(250)),
    CopyEntry("water.daily_limit", dailyLimit(HydrationBounds.MaxEntriesPerDay)),
    CopyEntry("water.card", card(sampleDay)),
    CopyEntry("water.card.no_goal", card(sampleDay.copy(goalMl = None))),
    CopyEntry("water.added", added(250, sampleDay, goalReachedNow = false)),
    CopyEntry("water.added.goal_reached", added(500, sampleDay.copy(totalMl = 2000), goalReachedNow = true)),
    CopyEntry("water.undone", undone(sampleDay))
  )
end WaterCopy
