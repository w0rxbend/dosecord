package dosegram.core.domain.nutrition

import dosegram.core.domain.input.InputText

import java.util.Locale

/** What a line of text says about a meal (PRODUCT_SPEC R-NUT-4, BOT_UX section 11.3). */
enum MealLine:
  /** A meal word, a colon and optional words: recorded at once. */
  case Logged(mealType: MealType, text: Option[MealText])

  /** A meal line whose words are longer than R-NUT-1 allows: refused, nothing recorded. */
  case TooLong(limit: Int)

  /** A meal word without the colon, or "ate" or "food" without a meal word: the bot asks which meal; nothing is
    * recorded until a tap.
    */
  case Ambiguous

  /** Anything else is not a meal. */
  case NotMeal

/** The deterministic meal grammar of BOT_UX section 11.3, in the shipped locales (English, and the Ukrainian meal words
  * of AC-NUT-2). The colon is required, so that a sentence such as "lunch was late today" is never recorded. The words
  * after the colon are kept as typed and never interpreted (R-NUT-5).
  *
  * {{{
  * meal     = mealtype ":" [ text ]
  * mealtype = breakfast / lunch / dinner / snack     and locale equivalents
  * }}}
  */
object MealTextParser:

  private val words: Map[String, MealType] = Map(
    "breakfast" -> MealType.Breakfast,
    "lunch" -> MealType.Lunch,
    "dinner" -> MealType.Dinner,
    "snack" -> MealType.Snack,
    "сніданок" -> MealType.Breakfast,
    "обід" -> MealType.Lunch,
    "вечеря" -> MealType.Dinner,
    "перекус" -> MealType.Snack
  )

  private val eatingWords: Set[String] = Set("ate", "food")

  /** A leading word, optional space and a colon (a full-width colon too), then the rest as typed. */
  private val Headed = """(?s)^\s*(\p{L}+)\s*[:：]\s*(.*)$""".r

  private val Leading = """^\s*(\p{L}+).*""".r

  /** Free text with no prompt active. */
  def free(text: String): MealLine =
    text match
      case Headed(word, rest) =>
        mealOf(word) match
          case Some(mealType) => logged(mealType, rest)
          case None           => withoutColon(text)
      case _ => withoutColon(text)

  /** The argument of `/food`: a meal word alone records that meal without words. */
  def argument(text: String): MealLine =
    mealOf(text.trim) match
      case Some(mealType) => MealLine.Logged(mealType, None)
      case None           => free(text)

  /** The meal type a word names in a shipped locale, if any. */
  def mealOf(word: String): Option[MealType] = words.get(InputText.normalise(word).toLowerCase(Locale.ROOT))

  private def withoutColon(text: String): MealLine =
    text.replace('\n', ' ') match
      case Leading(first) =>
        val word = InputText.normalise(first)
        if mealOf(word).isDefined || eatingWords(word) then MealLine.Ambiguous else MealLine.NotMeal
      case _ => MealLine.NotMeal

  private def logged(mealType: MealType, rest: String): MealLine =
    MealText.of(rest) match
      case Right(text)                  => MealLine.Logged(mealType, text)
      case Left(MealTextTooLong(limit)) => MealLine.TooLong(limit)
end MealTextParser
