package dosegram.core.domain.hydration

import java.util.Locale
import scala.util.matching.Regex

/** What a line of text says about water (BOT_UX section 11.2). */
enum WaterText:
  /** An unambiguous amount inside the bounds: recorded at once. */
  case Amount(amount: WaterAmount)

  /** An amount outside 1 to 5000 ml: refused with the bounds, nothing recorded. */
  case OutOfBounds

  /** A number without a unit: nothing recorded, the bot asks. */
  case MissingUnit

  /** Not an amount of water. */
  case NotWater

/** The deterministic water grammar of BOT_UX section 11.2, in millilitres, litres and glasses (ROADMAP G5.3b; fluid
  * ounces and other containers arrive with G9.13). No model is involved (PRODUCT_SPEC R-HYD-8).
  *
  * {{{
  * water  = [ "water" / "drank" / "+" ] amount  /  amount [ "water" ]
  * amount = number unit  /  [ count ] "glass(es)"
  * number = digits [ ( "." / "," ) digits ]
  * }}}
  *
  * `glassMl` is the size of one glass for this user: the first quick-add preset.
  */
final class WaterTextParser(glassMl: Int):

  /** Free text with no prompt active: a bare number is not enough to record. */
  def free(text: String): WaterText =
    parse(text): number =>
      if number.namesWater then millilitres(number.value) else WaterText.MissingUnit

  /** The answer to "Other amount" or the argument of `/water`: a bare number is millilitres. */
  def inWaterPrompt(text: String): WaterText =
    parse(text)(number => millilitres(number.value))

  private def parse(text: String)(bareNumber: WaterTextParser.BareNumber => WaterText): WaterText =
    text.trim.toLowerCase(Locale.ROOT) match
      case WaterTextParser.Line(lead, number, unit, trail) =>
        val namesWater = Option(lead).exists(_ != "+") || Option(trail).isDefined
        (Option(number).map(WaterTextParser.decimal), Option(unit)) match
          case (None, None)                                       => WaterText.NotWater
          case (count, Some(unit)) if WaterTextParser.Glass(unit) => glasses(count)
          case (Some(value), Some("ml" | "мл"))                   => amount(value, HydrationUnit.Millilitre)
          case (Some(value), Some(_))                             => amount(value, HydrationUnit.Litre)
          case (Some(value), None) => bareNumber(WaterTextParser.BareNumber(value, namesWater))
          case (None, Some(_))     => WaterText.NotWater
      case _ => WaterText.NotWater

  private def glasses(count: Option[BigDecimal]): WaterText =
    val whole = count.getOrElse(BigDecimal(1))
    if whole.isWhole then amount(whole, HydrationUnit.Glass) else WaterText.NotWater

  private def millilitres(value: BigDecimal): WaterText = amount(value, HydrationUnit.Millilitre)

  private def amount(value: BigDecimal, unit: HydrationUnit): WaterText =
    WaterAmount.of(EnteredAmount(value, unit), glassMl) match
      case Right(water)            => WaterText.Amount(water)
      case Left(AmountOutOfBounds) => WaterText.OutOfBounds

object WaterTextParser:

  private final case class BareNumber(value: BigDecimal, namesWater: Boolean)

  /** The words of a glass, English and Ukrainian (ROADMAP G2.22). */
  private val Glass: Set[String] = Set("glass", "glasses", "склянка", "склянки", "склянок")

  /** Digits are capped so that no input can build an arbitrarily large number. The Ukrainian words follow the English
    * ones of each part (ROADMAP G2.22).
    */
  private val Line: Regex =
    ("""(water|drank|вода|випив|випила|\+)?\s*(\d{1,6}(?:[.,]\d{1,3})?)?\s*""" +
      """(ml|litres?|liters?|l|glass(?:es)?|мл|літрів|літри|літра|літр|л|склянка|склянки|склянок)?\s*(water|води)?""").r

  private def decimal(digits: String): BigDecimal = BigDecimal(digits.replace(',', '.'))
end WaterTextParser
