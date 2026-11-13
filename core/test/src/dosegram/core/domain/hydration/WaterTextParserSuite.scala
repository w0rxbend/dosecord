package dosegram.core.domain.hydration

/** ROADMAP G5.3b: the deterministic grammar of BOT_UX section 11.2 in millilitres, litres and glasses, with the entry
  * bounds of PRODUCT_SPEC R-HYD-1 (AC-HYD-4).
  */
class WaterTextParserSuite extends munit.FunSuite:

  private val parser = WaterTextParser(glassMl = 250)

  private def recorded(text: String): (Int, EnteredAmount) =
    parser.free(text) match
      case WaterText.Amount(amount) => (amount.millilitres, amount.entered)
      case other                    => fail(s"'$text' parsed as $other")

  test("an amount with a unit is recorded in millilitres with the value and unit as entered"):
    assertEquals(recorded("250 ml"), (250, EnteredAmount(BigDecimal(250), HydrationUnit.Millilitre)))
    assertEquals(recorded("+250 ml"), (250, EnteredAmount(BigDecimal(250), HydrationUnit.Millilitre)))
    assertEquals(recorded("drank 300ml"), (300, EnteredAmount(BigDecimal(300), HydrationUnit.Millilitre)))
    assertEquals(recorded("water 0.5 l"), (500, EnteredAmount(BigDecimal("0.5"), HydrationUnit.Litre)))
    assertEquals(recorded("1 litre"), (1000, EnteredAmount(BigDecimal(1), HydrationUnit.Litre)))
    assertEquals(recorded("2 liters water"), (2000, EnteredAmount(BigDecimal(2), HydrationUnit.Litre)))

  test("a decimal comma and a decimal point give the same amount"):
    assertEquals(recorded("0,5 l")._1, 500)
    assertEquals(recorded("0.5 l")._1, 500)

  test("case and surrounding spaces do not matter"):
    assertEquals(recorded("  300 ML ")._1, 300)
    assertEquals(recorded("Water 1 L")._1, 1000)

  test("glasses are recorded as a multiple of the glass size"):
    assertEquals(recorded("2 glasses"), (500, EnteredAmount(BigDecimal(2), HydrationUnit.Glass)))
    assertEquals(recorded("glass"), (250, EnteredAmount(BigDecimal(1), HydrationUnit.Glass)))
    assertEquals(recorded("drank 1 glass")._1, 250)

  test("a number that names water is recorded in millilitres"):
    assertEquals(recorded("water 300")._1, 300)
    assertEquals(recorded("300 water")._1, 300)

  test("a bare number records nothing and needs a unit"):
    assertEquals(parser.free("500"), WaterText.MissingUnit)
    assertEquals(parser.free("+500"), WaterText.MissingUnit)

  test("a bare number is millilitres where water was already asked for"):
    assertEquals(parser.inWaterPrompt("250"), WaterText.Amount(waterAmount(250)))
    assertEquals(parser.inWaterPrompt("0,5 l"), parser.free("0,5 l"))

  test("an amount outside 1 to 5000 ml is refused and records nothing"):
    assertEquals(parser.free("8000 ml"), WaterText.OutOfBounds)
    assertEquals(parser.free("9 l"), WaterText.OutOfBounds)
    assertEquals(parser.free("0 ml"), WaterText.OutOfBounds)
    assertEquals(parser.free("21 glasses"), WaterText.OutOfBounds)
    assertEquals(recorded("5000 ml")._1, 5000)
    assertEquals(recorded("1 ml")._1, 1)

  test("text that is not an amount of water is not matched"):
    List("", "hello", "ml", "l", "water", "two glasses", "300 mg", "1.5 glasses", "250 ml of tea", "12 oz", "-5 ml")
      .foreach(text => assertEquals(parser.free(text), WaterText.NotWater, clue(text)))

  test("12 fluid ounces are 355 ml and stay 12 fl oz as entered (AC-HYD-3)"):
    val entered = EnteredAmount(BigDecimal(12), HydrationUnit.FluidOunce)
    assertEquals(WaterAmount.of(entered, glassMl = 250).map(a => (a.millilitres, a.entered)), Right((355, entered)))

  private def waterAmount(millilitres: Int): WaterAmount =
    WaterAmount(millilitres, EnteredAmount(BigDecimal(millilitres), HydrationUnit.Millilitre))
end WaterTextParserSuite
