package dosegram.core.domain.copy

import java.util.Locale

/** One dose unit of the catalogue: the code a medication stores and its words after a number in each shipped language.
  * `isCountable` marks a unit that names a thing the user can count in a pack, which an inventory counts in.
  */
final case class DoseUnit(code: String, enOne: String, enOther: String, uk: UkForms, isCountable: Boolean):

  /** The word after `count` in the current language: "tablets", "таблеток". */
  def noun(count: BigDecimal): String =
    Lang.current match
      case Lang.En => if Plural.categoryOf(Lang.En, count) == PluralCategory.One then enOne else enOther
      case Lang.Uk => uk.of(Plural.categoryOf(Lang.Uk, count))

  /** The number with its word: "21 tablets", "21 таблетка". */
  def count(n: BigDecimal): String = s"${DoseUnits.number(n)} ${noun(n)}"

  /** The unit's name in a picker: "tablet", "таблетка". */
  def label: String = tr(enOne, uk.one)

  private[copy] def forms: Set[String] = Set(code, enOne, enOther, uk.one, uk.few, uk.many, uk.other)

/** The dose unit catalogue (supervisor decision, 2026-11-14). A medication stores a unit code, or the word a user typed
  * in the chat; everything that shows a dose or a count reads it through here, so no stored English code reaches a
  * Ukrainian sentence. A word the catalogue does not know is shown as the user wrote it.
  */
object DoseUnits:
  private def same(word: String): UkForms = UkForms(word, word, word, word)
  private def measure(code: String, uk: String): DoseUnit = DoseUnit(code, code, code, same(uk), isCountable = false)

  val PlainUnit: DoseUnit =
    DoseUnit("unit", "unit", "units", UkForms("одиниця", "одиниці", "одиниць"), isCountable = true)

  val all: List[DoseUnit] = List(
    measure("mg", "мг"),
    measure("mcg", "мкг"),
    measure("g", "г"),
    measure("ml", "мл"),
    measure("IU", "МО"),
    DoseUnit("tablet", "tablet", "tablets", UkForms("таблетка", "таблетки", "таблеток"), isCountable = true),
    DoseUnit("capsule", "capsule", "capsules", UkForms("капсула", "капсули", "капсул"), isCountable = true),
    DoseUnit("drop", "drop", "drops", UkForms("крапля", "краплі", "крапель"), isCountable = true),
    DoseUnit("puff", "puff", "puffs", UkForms("вдих", "вдихи", "вдихів", "вдиху"), isCountable = true),
    PlainUnit,
    DoseUnit("pill", "pill", "pills", UkForms("пігулка", "пігулки", "пігулок"), isCountable = true),
    DoseUnit("sachet", "sachet", "sachets", same("саше"), isCountable = true),
    DoseUnit(
      "application",
      "application",
      "applications",
      UkForms("нанесення", "нанесення", "нанесень"),
      isCountable = true
    ),
    DoseUnit("spray", "spray", "sprays", UkForms("впорскування", "впорскування", "впорскувань"), isCountable = true),
    DoseUnit("patch", "patch", "patches", UkForms("пластир", "пластирі", "пластирів", "пластиря"), isCountable = true)
  )

  /** The units the Mini App offers, in its order; the codes are what `doseUnit` stores. */
  val picker: List[DoseUnit] =
    List("mg", "mcg", "g", "ml", "IU", "tablet", "capsule", "drop", "puff", "unit").flatMap(code =>
      all.find(_.code == code)
    )

  private val byWord: Map[String, DoseUnit] =
    all.flatMap(unit => unit.forms.map(word => key(word) -> unit)).toMap

  private def key(word: String): String = word.trim.toLowerCase(Locale.ROOT)

  /** The catalogue unit a stored word names, in either language and either number. */
  def of(stored: String): Option[DoseUnit] = byWord.get(key(stored))

  /** A number written with the separator of the current language and without trailing zeros: "1,5", "6". */
  def number(n: BigDecimal): String = decimal(n.bigDecimal.stripTrailingZeros.toPlainString)

  /** The count with its unit word: "6 таблеток"; an unknown word stays as the user wrote it. */
  def count(n: BigDecimal, stored: String): String =
    of(stored).fold(s"${number(n)} ${stored.trim}")(_.count(n))

  /** The picker name of a stored unit, or the stored word itself. */
  def label(stored: String): String = of(stored).fold(stored.trim)(_.label)

  /** The dose of a medication: the amount with its unit, the amount alone, the unit's name alone, or nothing. */
  def doseText(amount: Option[BigDecimal], unit: Option[String]): Option[String] =
    (amount, unit) match
      case (Some(n), Some(word)) => Some(count(n, word))
      case (Some(n), None)       => Some(number(n))
      case (None, Some(word))    => Some(label(word))
      case (None, None)          => None

  /** The unit an inventory counts in: the dose unit when it is countable, otherwise "units" (R-REG-14). */
  def countable(stored: Option[String]): DoseUnit =
    stored.flatMap(of).filter(_.isCountable).getOrElse(PlainUnit)
end DoseUnits
