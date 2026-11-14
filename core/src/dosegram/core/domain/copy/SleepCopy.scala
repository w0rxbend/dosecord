package dosegram.core.domain.copy

import dosegram.core.domain.sleep.EdgeSource
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepKind

/** The sleep copy of the chat (BOT_UX sections 11.5 and 21.9). It states what the user entered and where each figure
  * comes from; it rates nothing and names no reference duration (PRODUCT_SPEC R-SLP-19).
  */
object SleepCopy:
  def GoingToSleep = tr("Going to sleep", "Лягаю спати")
  def ShowAsImage = tr("Show as image", "Показати зображенням")
  def imageUnavailable = tr(
    "The image is not available right now. The figures above are complete.",
    "Зображення зараз недоступне. Цифри вище повні."
  )
  def imageEmpty = tr(
    "No night logged in the last 14 days, so there is nothing to draw yet.",
    "За останні 14 днів не записано жодної ночі, тож зображувати поки нічого."
  )
  def ImUp = tr("I'm up", "Встаю")
  def noNight = tr("No night logged yet.", "Ще не записано жодної ночі.")
  def ask = tr(
    "When did you sleep? Send bedtime and wake time, for example: slept 23:30 7:10.",
    "Коли ви спали? Надішліть час, коли лягли і коли встали, наприклад: сон 23:30 7:10."
  )
  def usage = tr(
    "Send /sleep, or bedtime and wake time, for example: /sleep 23:30 7:10.",
    "Надішліть /sleep або час, коли лягли і коли встали, наприклад: /sleep 23:30 7:10."
  )
  def qualityAsk = tr("How did you sleep? 1 very poorly · 5 very well", "Як вам спалося? 1 дуже погано · 5 дуже добре")
  def undone = tr("Sleep entry removed.", "Запис сну видалено.")
  def startUndone = tr("Sleep check-in removed.", "Позначку сну видалено.")
  def unknown = tr("That sleep entry is no longer there.", "Цього запису сну вже немає.")
  def undoWindowPassed = tr(
    "This can no longer be undone here. Change it in the app.",
    "Тут це вже не скасувати. Змініть запис у застосунку."
  )
  def overlaps = tr(
    "This overlaps a night or nap you already logged. Change that one in the app.",
    "Цей час перетинається з уже записаною ніччю чи денним сном. Змініть той запис у застосунку."
  )
  def nightExists = tr(
    "That day already has a night. Change it in the app.",
    "Для цього дня ніч уже записано. Змініть її в застосунку."
  )
  def invalid = tr(
    "Those times are not a night: the wake time must be after the bedtime and in the past.",
    "Цей час не схожий на ніч: пробудження має бути пізніше, ніж відхід до сну, і вже в минулому."
  )
  def tooShort = tr(
    "That is shorter than an hour, so it is not saved as a night. The check-in stays open.",
    "Це менше години, тож як ніч не збережено. Позначка лишається відкритою."
  )

  def started(time: String): String = tr(s"Sleep check-in started at $time.", s"Позначку сну почато о $time.")

  /** "Slept 23:32 to 07:10, 7 h 38 min (your check-ins)." */
  def slept(episode: SleepEpisode, bedtime: String, wakeTime: String): String =
    val noun = if episode.kind == SleepKind.Nap then tr("Napped", "Денний сон") else tr("Slept", "Сон")
    tr(
      s"$noun $bedtime to $wakeTime, ${StatsCopy.duration(episode.duration.toMinutes.toInt)} (${sourceOf(episode)}).",
      s"$noun з $bedtime до $wakeTime, ${StatsCopy.duration(episode.duration.toMinutes.toInt)} (${sourceOf(episode)})."
    )

  def rated(step: Int): String = tr(s"Your rating: $step of 5.", s"Ваша оцінка: $step з 5.")

  /** Where an episode comes from: both edges tapped are check-ins, anything typed is the user's entry. */
  def sourceOf(episode: SleepEpisode): String =
    val edges = List(episode.interval.onset.source, episode.interval.wake.source)
    if edges.forall(_ == EdgeSource.CheckIn) then StatsCopy.SleepSource else tr("your entry", "ваш запис")

  def entries: List[CopyEntry] =
    List(
      CopyEntry("sleep.button.night", GoingToSleep),
      CopyEntry("sleep.button.up", ImUp),
      CopyEntry("sleep.none", noNight),
      CopyEntry("sleep.night", started("23:32")),
      CopyEntry("sleep.ask", ask),
      CopyEntry("sleep.usage", usage),
      CopyEntry("sleep.quality.ask", qualityAsk),
      CopyEntry("sleep.quality.rated", rated(4)),
      CopyEntry("sleep.undone", undone),
      CopyEntry("sleep.night.undone", startUndone),
      CopyEntry("sleep.unknown", unknown),
      CopyEntry("sleep.undo_window_passed", undoWindowPassed),
      CopyEntry("sleep.overlap", overlaps),
      CopyEntry("sleep.night_exists", nightExists),
      CopyEntry("sleep.invalid", invalid),
      CopyEntry("sleep.too_short", tooShort),
      CopyEntry("sleep.image.button", ShowAsImage),
      CopyEntry("sleep.image.unavailable", imageUnavailable),
      CopyEntry("sleep.image.empty", imageEmpty)
    )
