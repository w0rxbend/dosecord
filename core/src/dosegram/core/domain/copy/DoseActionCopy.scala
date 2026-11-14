package dosegram.core.domain.copy

/** The `dose_action` kinds of the V1 schema enum (`infra/src/main/resources/db/migration/V1__baseline.sql`), mirrored
  * here so the catalogue can attach one display sentence to every kind and a test can pin the mirror.
  */
enum DoseActionKind(val dbValue: String):
  case ReminderSent extends DoseActionKind("reminder_sent")
  case ReminderDeferred extends DoseActionKind("reminder_deferred")
  case Taken extends DoseActionKind("taken")
  case Skipped extends DoseActionKind("skipped")
  case Snoozed extends DoseActionKind("snoozed")
  case AutoMarkedMissed extends DoseActionKind("auto_marked_missed")
  case MarkedUnknown extends DoseActionKind("marked_unknown")
  case ManuallyCorrected extends DoseActionKind("manually_corrected")
  case Undone extends DoseActionKind("undone")
  case NoteAdded extends DoseActionKind("note_added")
  case Cancelled extends DoseActionKind("cancelled")
  case CatchUpCollapsed extends DoseActionKind("catch_up_collapsed")
  case ChainChildCreated extends DoseActionKind("chain_child_created")
end DoseActionKind

object DoseActionKind:
  def fromDbValue(value: String): DoseActionKind =
    values.find(_.dbValue == value).getOrElse(throw new IllegalArgumentException(s"unknown dose_action '$value'"))

/** One neutral, non-shaming sentence per `dose_action` kind (ROADMAP M1.4a acceptance), used wherever history renders
  * an action row. The `match` is exhaustive, so a new enum kind fails compilation here.
  */
object DoseActionCopy:

  def sentence(kind: DoseActionKind): String =
    kind match
      case DoseActionKind.ReminderSent     => tr("Reminder sent.", "Нагадування надіслано.")
      case DoseActionKind.ReminderDeferred =>
        tr("Reminder deferred until the end of quiet hours.", "Нагадування відкладено до кінця тихих годин.")
      case DoseActionKind.Taken            => tr("Taken.", "Прийнято.")
      case DoseActionKind.Skipped          => tr("Skipped.", "Пропущено.")
      case DoseActionKind.Snoozed          => tr("Snoozed.", "Відкладено.")
      case DoseActionKind.AutoMarkedMissed =>
        tr("Marked missed after no response.", "Позначено як пропущене: відповіді не було.")
      case DoseActionKind.MarkedUnknown =>
        tr(
          "Marked unknown — no reminder delivery was confirmed.",
          "Позначено як невідоме — доставку нагадування не підтверджено."
        )
      case DoseActionKind.ManuallyCorrected => tr("Corrected manually.", "Виправлено вручну.")
      case DoseActionKind.Undone            => tr("Undone.", "Скасовано.")
      case DoseActionKind.NoteAdded         => tr("Note added.", "Нотатку додано.")
      case DoseActionKind.Cancelled         => tr("Cancelled.", "Відмінено.")
      case DoseActionKind.CatchUpCollapsed  =>
        tr("Collapsed into catch-up after downtime.", "Об’єднано в підсумок після перерви в роботі.")
      case DoseActionKind.ChainChildCreated =>
        tr("Follow-up dose of the chain created.", "Створено наступну дозу ланцюжка.")

  def entries: List[CopyEntry] =
    DoseActionKind.values.toList.map(k => CopyEntry(s"dose_action.${k.dbValue}", sentence(k)))
end DoseActionCopy
