package dosegram.core.domain.copy

import dosegram.core.domain.mood.MoodLevel

/** The shareable card in chat (VISUALS section 6.18, IMG-17): one fact of the last seven days, chosen by its exact
  * sentence, with the warning of VA-IMG-17b before anything is sent. No line names a medication, a date or a time.
  */
object ShareCopy:

  def Share: String = tr("Share a card", "Поділитися карткою")

  def pick: String = tr(
    "Pick one fact for a card you can forward. The card shows no name, date, time or medication. Anyone you send this to can keep it.",
    "Оберіть один факт для картки, яку можна переслати. На картці немає імені, дат, часу чи ліків. Кожен, кому ви її надішлете, зможе її зберегти."
  )

  def nothing: String = tr(
    "Nothing to share from the last 7 days yet.",
    "За останні 7 днів поки немає чим поділитися."
  )

  def discreet: String = tr(
    "Discreet mode is on. This card can be forwarded, and anyone you send it to can keep it. Send it anyway?",
    "Увімкнено стриманий режим. Цю картку можна переслати, і кожен, кому ви її надішлете, зможе її зберегти. Усе одно надіслати?"
  )

  def SendAnyway: String = tr("Send the card", "Надіслати картку")

  def waterGoal(hits: Int): String = tr(s"Water goal reached on $hits of 7 days", s"Ціль із води: $hits з 7 днів")
  def sleepLogged(hits: Int): String = tr(s"Sleep logged on $hits of 7 days", s"Сон записано: $hits з 7 днів")
  def moodLogged(hits: Int): String = tr(s"Mood logged on $hits of 7 days", s"Настрій записано: $hits з 7 днів")
  def weekMood(level: MoodLevel): String =
    tr(
      s"This week's mood: ${MoodLabels.level(level).toLowerCase}",
      s"Настрій тижня: ${MoodLabels.level(level).toLowerCase}"
    )

  def entries: List[CopyEntry] =
    List(
      CopyEntry("share.button", Share),
      CopyEntry("share.pick", pick),
      CopyEntry("share.nothing", nothing),
      CopyEntry("share.discreet", discreet),
      CopyEntry("share.send_anyway", SendAnyway),
      CopyEntry("share.water", waterGoal(5)),
      CopyEntry("share.sleep", sleepLogged(6)),
      CopyEntry("share.mood", moodLogged(4)),
      CopyEntry("share.week_mood", weekMood(MoodLevel.V6))
    )
end ShareCopy
