package dosegram.core.domain.input

import scala.math.BigDecimal.RoundingMode
import scala.util.matching.Regex

/** A typed dose (BOT_UX section 15.6, BUX-MED-03): a leading amount and the rest as the unit, shown as typed. The
  * amount takes a decimal point or comma ("0,5"), a leading point (".5"), a fraction ("1/2", "½") or a whole number
  * with a fraction ("1 1/2", "1½"); a third is kept to 3 fraction digits. Text without a leading amount is kept whole
  * as the unit, so a dose is never refused. The bot never converts or judges a dose.
  */
object DoseText:

  private val MaxFractionDigits = 3
  private val Mixed: Regex = "^(\\d+) (\\d+)/(\\d+)\\s*(.*)$".r
  private val Fraction: Regex = "^(\\d+)/(\\d+)\\s*(.*)$".r
  private val Decimal: Regex = "^(\\d+(?:[.,]\\d+)?|[.,]\\d+)\\s*(.*)$".r

  def parse(text: String): (Option[BigDecimal], Option[String]) =
    val plain = InputText.plainForms(text).replaceAll("\\s+", " ").trim
    val read = plain match
      case ""                                            => None
      case Mixed(whole, n, d, unit) if BigDecimal(d) > 0 => Some((BigDecimal(whole) + ratio(n, d), unitOf(unit)))
      case Fraction(n, d, unit) if BigDecimal(d) > 0     => Some((ratio(n, d), unitOf(unit)))
      case Decimal(number, unit)                         => Some((BigDecimal(number.replace(',', '.')), unitOf(unit)))
      case _                                             => None
    read match
      // Only a positive amount is an amount (BUX-MED-03); anything else stays the user's own words.
      case Some((amount, unit)) if amount > 0 => (Some(amount), unit)
      case _                                  => (None, Option(plain).filter(_.nonEmpty))

  private def ratio(n: String, d: String): BigDecimal =
    (BigDecimal(n) / BigDecimal(d)).setScale(MaxFractionDigits, RoundingMode.HALF_UP).underlying.stripTrailingZeros

  private def unitOf(rest: String): Option[String] =
    Option(rest.trim).filter(_.nonEmpty)
