package dosegram.core.domain.copy

/** The Ukrainian nouns that follow a number in more than one catalogue file, in the case the sentence needs: the
  * nominative after a bare count ("5 днів"), the genitive after "з" ("з 7 днів").
  */
private[copy] object UkNouns:
  val Days = UkForms("день", "дні", "днів", "дня")
  val DaysOf = UkForms("дня", "днів", "днів", "дня")
  val Nights = UkForms("ніч", "ночі", "ночей", "ночі")
  val Meals = UkForms("прийом їжі", "прийоми їжі", "прийомів їжі", "прийому їжі")
  val PlannedMeals = UkForms("запланованого прийому їжі", "запланованих прийомів їжі", "запланованих прийомів їжі")
  val Snacks = UkForms("перекус", "перекуси", "перекусів", "перекусу")
  val CheckIns = UkForms("позначка", "позначки", "позначок", "позначки")
  val Doses = UkForms("доза", "дози", "доз", "дози")
  val Times = UkForms("раз", "рази", "разів", "раза")
  val Levels = UkForms("рівень", "рівні", "рівнів", "рівня")
  val Weeks = UkForms("тиждень", "тижні", "тижнів", "тижня")
  val Months = UkForms("місяць", "місяці", "місяців", "місяця")
  val Entries = UkForms("запис", "записи", "записів", "запису")
  val Percent = UkForms("відсоток", "відсотки", "відсотків", "відсотка")
  val Schedules = UkForms("розклад", "розклади", "розкладів", "розкладу")
  val AnsweredDoses = UkForms("доза з відповіддю", "дози з відповіддю", "доз із відповіддю")

  /** After "з вашими попередніми": a baseline always has at least two earlier weeks. */
  val WeeksWith = UkForms("тижнем", "тижнями", "тижнями")
