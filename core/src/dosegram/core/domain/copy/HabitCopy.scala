package dosegram.core.domain.copy

/** The habit taxonomy (ROADMAP M1.4a recorded decision): boolean / count / duration / measurement plus avoid. The
  * `dbValue` strings match the `habits.habit_type` CHECK constraint of the V1 schema; habit creation lands in M7.4.
  */
object HabitCopy:

  enum HabitKind(val dbValue: String):
    case Boolean extends HabitKind("boolean")
    case Count extends HabitKind("count")
    case Duration extends HabitKind("duration")
    case Measurement extends HabitKind("measurement")
    case Avoid extends HabitKind("avoid")

    def label: String =
      this match
        case Boolean     => tr("Yes or no", "Так чи ні")
        case Count       => tr("Count", "Кількість")
        case Duration    => tr("Duration", "Тривалість")
        case Measurement => tr("Measurement", "Вимірювання")
        case Avoid       => tr("Avoid", "Уникати")

    def description: String =
      this match
        case Boolean     => tr("Whether it happened or not.", "Чи це сталося.")
        case Count       => tr("How many times it happened.", "Скільки разів це сталося.")
        case Duration    => tr("How long it lasted.", "Скільки це тривало.")
        case Measurement => tr("A measured value.", "Виміряне значення.")
        case Avoid       => tr("Something you are trying not to do.", "Те, чого ви намагаєтеся не робити.")
  end HabitKind

  def entries: List[CopyEntry] =
    HabitKind.values.toList.flatMap(k =>
      List(
        CopyEntry(s"habit.kind.${k.dbValue}.label", k.label),
        CopyEntry(s"habit.kind.${k.dbValue}.description", k.description)
      )
    )
end HabitCopy
