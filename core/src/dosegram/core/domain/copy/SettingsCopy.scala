package dosegram.core.domain.copy

import dosegram.core.domain.reminders.Anchor
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.ports.ImageTheme

import java.time.DayOfWeek
import java.time.LocalTime

/** The settings hub of `/settings` (supervisor decision, 2026-11-28; BOT_UX section 17): one card with every value
  * beside its name, in sections, and a switch for each value the chat sets in place. A body line names the setting in
  * full, as the Mini App does; a button names it short, so it fits BUX-KB-01, and shows the state it is in.
  */
object SettingsCopy:

  def title: String = tr("Settings", "Налаштування")

  def regionSection: String = tr("Language and region", "Мова й регіон")
  def daySection: String = tr("Your day", "Ваш день")
  def imagesSection: String = tr("Images and messages", "Зображення й повідомлення")
  def reportsSection: String = tr("Reports", "Звіти")

  def on: String = tr("on", "увімк.")
  def off: String = tr("off", "вимк.")
  def state(isOn: Boolean): String = if isOn then on else off

  def languageName: String = tr("Language", "Мова")
  def zoneName: String = tr("Time zone", "Часовий пояс")
  def themeName: String = tr("Image theme", "Тема зображень")
  def discreetName: String = tr("Discreet reminders", "Стримані нагадування")
  def animatedName: String = tr("Animated mood", "Анімований настрій")
  def countsName: String = tr("Counts in reports", "Підрахунки у звітах")
  def notesName: String = tr("Notes after updates", "Примітки після оновлень")
  def workName: String = tr("Work", "Робота")
  def quietName: String = tr("Quiet hours", "Тихі години")

  /** "Discreet reminders: on": a setting and its value, on a line of the card. */
  def line(name: String, value: String): String = s"$name: $value"

  def themeValue(theme: ImageTheme): String = theme match
    case ImageTheme.Auto  => tr("Auto", "Авто")
    case ImageTheme.Light => tr("Light", "Світла")
    case ImageTheme.Dark  => tr("Dark", "Темна")

  def zoneValue(zone: String, now: String): String = tr(s"$zone, now $now", s"$zone, зараз $now")

  /** The animated mood stays still while reminders are discreet (MINI_APP MA-SET-12); the card says so. */
  def animatedValue(isOn: Boolean, isDiscreet: Boolean): String =
    if isOn && isDiscreet then
      tr("on, still while reminders are discreet", "увімк., нерухомий, поки нагадування стримані")
    else state(isOn)

  def anchorName(anchor: Anchor): String = anchor match
    case Anchor.Wake      => tr("Wake", "Підйом")
    case Anchor.Breakfast => tr("Breakfast", "Сніданок")
    case Anchor.Lunch     => tr("Lunch", "Обід")
    case Anchor.Dinner    => tr("Dinner", "Вечеря")
    case Anchor.Bedtime   => tr("Bedtime", "Відхід до сну")
    case Anchor.WorkStart => tr("Work starts", "Початок роботи")
    case Anchor.WorkEnd   => tr("Work ends", "Кінець роботи")

  /** "Wake 07:00": an anchor and its time, on a line of the card and on its button. */
  def anchorAt(anchor: Anchor, at: LocalTime): String = s"${anchorName(anchor)} ${PromptCopy.clock(at)}"

  def hours(start: LocalTime, end: LocalTime): String = s"${PromptCopy.clock(start)}–${PromptCopy.clock(end)}"

  def workValue(days: Set[DayOfWeek], start: LocalTime, end: LocalTime): String =
    s"${PromptCopy.days(days)} ${hours(start, end)}"

  /** "Morning summary: off · Weekly summary: on": each report and whether it is on. */
  def reportsLine(reports: List[(PromptKind, Boolean)]): String =
    reports.map((kind, isOn) => line(PromptCopy.kindName(kind), state(isOn))).mkString(" · ")

  def links: String = tr(
    "Reminders and reports are set in /reminders, consents and your data in /privacy.",
    "Нагадування й звіти налаштовуються в /reminders, згоди й ваші дані — у /privacy."
  )

  /** The first line of the card after a change: the new value and the one it replaced (BUX-SET-02). */
  def changed(name: String, before: String, after: String): String =
    tr(s"Saved. $name: $after (was $before).", s"Збережено. $name: $after (було $before).")

  def dayHelp: String = tr(
    "The reminders set in /reminders follow these times. Pick one to change it, or type, for example, /reminders wake 06:30.",
    "Нагадування з /reminders зважають на ці години. Виберіть, що змінити, або напишіть, наприклад, /reminders wake 06:30."
  )

  def anchorAsk(anchor: Anchor, at: LocalTime): String = tr(
    s"${line(anchorName(anchor), PromptCopy.clock(at))}. Pick a new time, or type /reminders ${anchor.id} 06:30.",
    s"${line(anchorName(anchor), PromptCopy.clock(at))}. Виберіть новий час або напишіть /reminders ${anchor.id} 06:30."
  )

  def workAsk(current: String): String = tr(
    s"${line(workName, current)}. Pick new work hours, or type /reminders work mon-fri 09:00-18:00.",
    s"${line(workName, current)}. Виберіть нові робочі години або напишіть /reminders work mon-fri 09:00-18:00."
  )

  def quietAsk(current: String): String = tr(
    s"${line(quietName, current)}. Pick new quiet hours, or type /reminders quiet 22:00-07:00.",
    s"${line(quietName, current)}. Виберіть нові тихі години або напишіть /reminders quiet 22:00-07:00."
  )

  /** The morning summary's time, or "off", as its line and its button show it. */
  def summaryValue(at: Option[LocalTime]): String = at.fold(off)(PromptCopy.clock)

  def summaryAsk(current: String): String = tr(
    s"${line(PromptCopy.kindName(PromptKind.DailyReport), current)}. Yesterday in one message each morning. Pick a " +
      "time, or type any other, for example /reminders summary 07:45. It is never sent in your quiet hours.",
    s"${line(PromptCopy.kindName(PromptKind.DailyReport), current)}. Учорашній день одним повідомленням щоранку. " +
      "Виберіть час або напишіть інший, наприклад /reminders summary 07:45. У тихі години він не надходить."
  )

  // Buttons: at most 24 characters in both languages (BUX-KB-01).
  def SummaryButton(at: Option[LocalTime]): String =
    tr(s"Morning summary: ${summaryValue(at)}", s"Підсумок: ${summaryValue(at)}")
  def ZoneButton: String = tr("Time zone", "Часовий пояс")
  def DayButton: String = tr("Day and hours", "День і години")
  def ThemeButton(theme: ImageTheme): String = tr(s"Theme: ${themeValue(theme)}", s"Тема: ${themeValue(theme)}")
  def DiscreetButton(isOn: Boolean): String = tr(s"Discreet: ${state(isOn)}", s"Стримано: ${state(isOn)}")
  def AnimatedButton(isOn: Boolean): String = tr(s"Animated mood: ${state(isOn)}", s"Анімація: ${state(isOn)}")
  def CountsButton(isOn: Boolean): String = tr(s"Counts: ${state(isOn)}", s"Підрахунки: ${state(isOn)}")
  def NotesButton(isOn: Boolean): String = tr(s"Update notes: ${state(isOn)}", s"Примітки: ${state(isOn)}")
  def RemindersButton: String = tr("Reminders and reports", "Нагадування й звіти")
  def PrivacyButton: String = tr("Privacy", "Приватність")
  def WorkButton: String = tr("Work hours", "Робочі години")
  def QuietButton: String = tr("Quiet hours", "Тихі години")

  /** Every button label of the hub, for the test of BUX-KB-01. */
  def buttons: List[String] =
    List(ZoneButton, DayButton, RemindersButton, PrivacyButton, WorkButton, QuietButton) ++
      ImageTheme.values.toList.map(ThemeButton) ++
      List(true, false).flatMap(isOn => List(DiscreetButton(isOn), AnimatedButton(isOn), CountsButton(isOn))) ++
      List(true, false).map(NotesButton) ++
      List(None, Some(LocalTime.of(10, 30))).map(SummaryButton)

  private val sampleAt = LocalTime.of(7, 0)

  def entries: List[CopyEntry] =
    List(
      CopyEntry("settings.title", title),
      CopyEntry("settings.section.region", regionSection),
      CopyEntry("settings.section.day", daySection),
      CopyEntry("settings.section.images", imagesSection),
      CopyEntry("settings.section.reports", reportsSection),
      CopyEntry("settings.language", line(languageName, LanguageCopy.name(Lang.En))),
      CopyEntry("settings.zone", line(zoneName, zoneValue("Europe/Kyiv", "21:14"))),
      CopyEntry("settings.theme", line(themeName, themeValue(ImageTheme.Auto))),
      CopyEntry("settings.discreet", line(discreetName, state(true))),
      CopyEntry("settings.animated", line(animatedName, animatedValue(isOn = true, isDiscreet = true))),
      CopyEntry("settings.counts", line(countsName, state(false))),
      CopyEntry("settings.notes", line(notesName, state(true))),
      CopyEntry("settings.anchor", anchorAt(Anchor.Wake, sampleAt)),
      CopyEntry("settings.work", line(workName, workValue(Set(DayOfWeek.MONDAY), sampleAt, LocalTime.NOON))),
      CopyEntry("settings.quiet", line(quietName, hours(LocalTime.of(22, 0), sampleAt))),
      CopyEntry("settings.reports", reportsLine(List(PromptKind.DailyReport -> false))),
      CopyEntry("settings.links", links),
      CopyEntry("settings.changed", changed(discreetName, off, on)),
      CopyEntry("settings.day_help", dayHelp),
      CopyEntry("settings.anchor_ask", anchorAsk(Anchor.Wake, sampleAt)),
      CopyEntry("settings.work_ask", workAsk("09:00–18:00")),
      CopyEntry("settings.quiet_ask", quietAsk("22:00–07:00")),
      CopyEntry("settings.summary_ask", summaryAsk(summaryValue(Some(LocalTime.of(7, 45))))),
      CopyEntry("settings.summary_button", SummaryButton(None))
    ) ++ buttons.zipWithIndex.map((label, i) => CopyEntry(s"settings.button.$i", label))
end SettingsCopy
