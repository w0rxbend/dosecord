package dosegram.core.domain.copy

/** Control labels (ROADMAP M1.4a recorded decisions). The shapes that use them — the two-row reminder layout, the
  * missed notice, the post-Taken follow-up — are pinned by `core/chat/Controls.scala` (M0.9).
  */
object Labels:

  def Taken = tr("Taken", "Прийнято")
  def Skip = tr("Skip", "Пропустити")
  def Undo = tr("Undo", "Скасувати")
  def Correct = tr("Correct", "Виправити")
  def ITookIt = tr("I took it", "Уже прийнято")
  def KeepMissed = tr("Keep missed", "Лишити пропущеним")
  def Cancel = tr("Cancel", "Скасувати")
  def Back = tr("Back", "Назад")
  def Create = tr("Create", "Створити")
  def Done = tr("Done", "Готово")
  def Yes = tr("Yes", "Так")
  def No = tr("No", "Ні")
  def Change = tr("Change", "Змінити")
  def Continue = tr("Continue", "Продовжити")
  def NoNote = tr("No note", "Без нотатки")
  def Now = tr("Now", "Зараз")
  def AtScheduledTime = tr("At scheduled time", "У запланований час")
  def Update = tr("Update", "Оновити")
  def Archive = tr("Archive", "В архів")
  def Timezone = tr("Timezone", "Часовий пояс")
  def Language = tr("Language", "Мова")

  /** Snooze option labels: `Snooze 10m`, `Snooze 1h`. */
  def snooze(minutes: Int): String =
    if minutes % 60 == 0 then tr(s"Snooze ${minutes / 60}h", s"Відкласти на ${minutes / 60} год")
    else tr(s"Snooze ${minutes}m", s"Відкласти на $minutes хв")

  /** Missed-notice row (recorded decision): [I took it][Skip][Keep missed]. */
  def missedNotice: List[String] = List(ITookIt, Skip, KeepMissed)

  /** Post-Taken follow-up (recorded decision): [Undo][Correct] (M3.1 appends [Add note]). */
  def postTaken: List[String] = List(Undo, Correct)

  def entries: List[CopyEntry] = List(
    CopyEntry("label.taken", Taken),
    CopyEntry("label.skip", Skip),
    CopyEntry("label.undo", Undo),
    CopyEntry("label.correct", Correct),
    CopyEntry("label.i_took_it", ITookIt),
    CopyEntry("label.keep_missed", KeepMissed),
    CopyEntry("label.cancel", Cancel),
    CopyEntry("label.back", Back),
    CopyEntry("label.create", Create),
    CopyEntry("label.done", Done),
    CopyEntry("label.yes", Yes),
    CopyEntry("label.no", No),
    CopyEntry("label.change", Change),
    CopyEntry("label.continue", Continue),
    CopyEntry("label.no_note", NoNote),
    CopyEntry("label.now", Now),
    CopyEntry("label.at_scheduled_time", AtScheduledTime),
    CopyEntry("label.update", Update),
    CopyEntry("label.archive", Archive),
    CopyEntry("label.timezone", Timezone),
    CopyEntry("label.language", Language),
    CopyEntry("label.snooze.10", snooze(10)),
    CopyEntry("label.snooze.30", snooze(30)),
    CopyEntry("label.snooze.60", snooze(60))
  )
end Labels
