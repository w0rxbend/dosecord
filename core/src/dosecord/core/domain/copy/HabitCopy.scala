package dosecord.core.domain.copy

/** The habit taxonomy (ROADMAP M1.4a recorded decision): boolean / count / duration / measurement plus avoid. The
  * `dbValue` strings match the `habits.habit_type` CHECK constraint of the V1 schema; habit creation lands in M7.4.
  */
object HabitCopy:

  enum HabitKind(val dbValue: String, val label: String, val description: String):
    case Boolean extends HabitKind("boolean", "Yes or no", "Whether it happened or not.")
    case Count extends HabitKind("count", "Count", "How many times it happened.")
    case Duration extends HabitKind("duration", "Duration", "How long it lasted.")
    case Measurement extends HabitKind("measurement", "Measurement", "A measured value.")
    case Avoid extends HabitKind("avoid", "Avoid", "Something you are trying not to do.")

  val entries: List[CopyEntry] =
    HabitKind.values.toList.flatMap(k =>
      List(
        CopyEntry(s"habit.kind.${k.dbValue}.label", k.label),
        CopyEntry(s"habit.kind.${k.dbValue}.description", k.description)
      )
    )
end HabitCopy
