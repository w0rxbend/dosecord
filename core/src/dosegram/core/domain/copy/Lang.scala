package dosegram.core.domain.copy

import ox.ForkLocal

import java.time.format.DateTimeFormatter

/** A shipped locale of the message catalogue (PRODUCT_SPEC section 8.1; ROADMAP G2.22). `code` is what `users.locale`,
  * the consent ledger and the settings API store.
  */
enum Lang(val code: String):
  case En extends Lang("en")
  case Uk extends Lang("uk")

/** The language of the copy being produced. One inbound event, one prompt or one report is produced for one account, so
  * the language is bound once around that work ([[within]]) and every catalogue entry reads it ([[current]]); code
  * outside any binding gets English.
  */
object Lang:

  // Ox `ForkLocal`, as `LogContext` binds its keys: forks created inside a binding inherit it.
  private val Bound: ForkLocal[Lang] = ForkLocal(En)

  def current: Lang = Bound.get()

  /** Runs `body` with `lang` as the language of every catalogue entry it produces. */
  def within[A](lang: Lang)(body: => A): A = Bound.unsupervisedWhere(lang)(body)

  /** The shipped locale a stored code or a client's language tag names: `uk`, `uk-UA` and `uk_UA` are Ukrainian. */
  def ofCode(code: String): Option[Lang] =
    val primary = code.trim.takeWhile(c => c != '-' && c != '_').toLowerCase(java.util.Locale.ROOT)
    values.find(_.code == primary)

  /** PRODUCT_SPEC R-LOC-1: the account's own choice, otherwise the client's language tag if it names a shipped locale,
    * otherwise English.
    */
  def resolve(stored: Option[String], hint: Option[String]): Lang =
    stored.flatMap(ofCode).orElse(hint.flatMap(ofCode)).getOrElse(En)
end Lang

/** The text of the current language. Every catalogue entry is written as one pair, so no key exists in one locale only
  * (PRODUCT_SPEC R-LOC-2).
  */
def tr[A](en: A, uk: A): A =
  Lang.current match
    case Lang.En => en
    case Lang.Uk => uk

/** The CLDR plural categories of the shipped locales (PRODUCT_SPEC R-LOC-3). */
enum PluralCategory:
  case One, Few, Many, Other

object Plural:

  /** English has one and other; Ukrainian has one, few and many for whole numbers and other for fractions. */
  def category(lang: Lang, n: Long): PluralCategory =
    lang match
      case Lang.En => if n == 1 then PluralCategory.One else PluralCategory.Other
      case Lang.Uk =>
        val abs = math.abs(n)
        val (last, lastTwo) = (abs % 10, abs % 100)
        if last == 1 && lastTwo != 11 then PluralCategory.One
        else if last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) then PluralCategory.Few
        else PluralCategory.Many

  /** A fractional count takes the "other" form in both locales ("1.5 litres", "1,5 літра"). */
  def categoryOf(lang: Lang, value: BigDecimal): PluralCategory =
    if value.isWhole then category(lang, value.toLongExact) else PluralCategory.Other

/** The forms of a Ukrainian noun after a number: "1 день", "2 дні", "5 днів", "1,5 дня". */
final case class UkForms(one: String, few: String, many: String, other: String):
  def of(category: PluralCategory): String =
    category match
      case PluralCategory.One   => one
      case PluralCategory.Few   => few
      case PluralCategory.Many  => many
      case PluralCategory.Other => other

  /** The form after the whole number `n`: `Days(5)` is "днів". */
  def apply(n: Long): String = of(Plural.category(Lang.Uk, n))

  /** The number with its form: `Days.count(5)` is "5 днів". */
  def count(n: Long): String = s"$n ${apply(n)}"

object UkForms:
  /** For most nouns the fraction form is the genitive singular, which the few form shares often enough to default. */
  def apply(one: String, few: String, many: String): UkForms = UkForms(one, few, many, few)

/** The noun of a count in the current language, without the number: `noun(2, "night", "nights", ...)`. */
def noun(n: Long, enOne: String, enOther: String, uk: UkForms): String =
  Lang.current match
    case Lang.En => if n == 1 then enOne else enOther
    case Lang.Uk => uk.of(Plural.category(Lang.Uk, n))

/** A count with its noun: "2 nights", "2 ночі". */
def counted(n: Long, enOne: String, enOther: String, uk: UkForms): String = s"$n ${noun(n, enOne, enOther, uk)}"

/** A decimal number written with the separator of the current language (PRODUCT_SPEC R-LOC-4): "1.25", "1,25". */
def decimal(plain: String): String =
  Lang.current match
    case Lang.En => plain
    case Lang.Uk => plain.replace('.', ',')

/** Date formats of the current language: "13 Nov 2026" and "13 листопада 2026" (BOT_UX BUX-MSG-04). */
object Formats:
  private val Ukrainian = java.util.Locale.forLanguageTag("uk")
  private val EnglishDay = DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
  private val UkrainianDay = DateTimeFormatter.ofPattern("d MMMM yyyy", Ukrainian)
  private val EnglishDayMonth = DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)
  private val UkrainianDayMonth = DateTimeFormatter.ofPattern("d MMMM", Ukrainian)

  def day: DateTimeFormatter = pick(EnglishDay, UkrainianDay)
  def dayMonth: DateTimeFormatter = pick(EnglishDayMonth, UkrainianDayMonth)

  /** The Java locale of the current language, for formats a template builds itself. */
  def locale: java.util.Locale = pick(java.util.Locale.ENGLISH, Ukrainian)

  private def pick[A](en: A, uk: A): A =
    Lang.current match
      case Lang.En => en
      case Lang.Uk => uk
end Formats
