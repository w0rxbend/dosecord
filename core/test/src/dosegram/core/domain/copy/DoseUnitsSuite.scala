package dosegram.core.domain.copy

/** The dose unit catalogue (supervisor decision, 2026-11-14): every stored unit code is shown in the account's language,
  * with the plural form of its count, in the chat, on cards, in API sentences and in the Mini App picker.
  */
class DoseUnitsSuite extends munit.FunSuite:

  private val Counts = List(BigDecimal(1), BigDecimal(2), BigDecimal(5), BigDecimal(21), BigDecimal("1.5"))

  /** Each code with its words after 1, 2, 5, 21 and 1.5, in English and in Ukrainian. */
  private val Expected: Map[String, (List[String], List[String])] = Map(
    "mg" -> (List("1 mg", "2 mg", "5 mg", "21 mg", "1.5 mg"), List("1 мг", "2 мг", "5 мг", "21 мг", "1,5 мг")),
    "mcg" -> (List("1 mcg", "2 mcg", "5 mcg", "21 mcg", "1.5 mcg"), List("1 мкг", "2 мкг", "5 мкг", "21 мкг", "1,5 мкг")),
    "g" -> (List("1 g", "2 g", "5 g", "21 g", "1.5 g"), List("1 г", "2 г", "5 г", "21 г", "1,5 г")),
    "ml" -> (List("1 ml", "2 ml", "5 ml", "21 ml", "1.5 ml"), List("1 мл", "2 мл", "5 мл", "21 мл", "1,5 мл")),
    "IU" -> (List("1 IU", "2 IU", "5 IU", "21 IU", "1.5 IU"), List("1 МО", "2 МО", "5 МО", "21 МО", "1,5 МО")),
    "tablet" -> (
      List("1 tablet", "2 tablets", "5 tablets", "21 tablets", "1.5 tablets"),
      List("1 таблетка", "2 таблетки", "5 таблеток", "21 таблетка", "1,5 таблетки")
    ),
    "capsule" -> (
      List("1 capsule", "2 capsules", "5 capsules", "21 capsules", "1.5 capsules"),
      List("1 капсула", "2 капсули", "5 капсул", "21 капсула", "1,5 капсули")
    ),
    "drop" -> (
      List("1 drop", "2 drops", "5 drops", "21 drops", "1.5 drops"),
      List("1 крапля", "2 краплі", "5 крапель", "21 крапля", "1,5 краплі")
    ),
    "puff" -> (
      List("1 puff", "2 puffs", "5 puffs", "21 puffs", "1.5 puffs"),
      List("1 вдих", "2 вдихи", "5 вдихів", "21 вдих", "1,5 вдиху")
    ),
    "unit" -> (
      List("1 unit", "2 units", "5 units", "21 units", "1.5 units"),
      List("1 одиниця", "2 одиниці", "5 одиниць", "21 одиниця", "1,5 одиниці")
    ),
    "pill" -> (
      List("1 pill", "2 pills", "5 pills", "21 pills", "1.5 pills"),
      List("1 пігулка", "2 пігулки", "5 пігулок", "21 пігулка", "1,5 пігулки")
    ),
    "sachet" -> (
      List("1 sachet", "2 sachets", "5 sachets", "21 sachets", "1.5 sachets"),
      List("1 саше", "2 саше", "5 саше", "21 саше", "1,5 саше")
    ),
    "application" -> (
      List("1 application", "2 applications", "5 applications", "21 applications", "1.5 applications"),
      List("1 нанесення", "2 нанесення", "5 нанесень", "21 нанесення", "1,5 нанесення")
    ),
    "spray" -> (
      List("1 spray", "2 sprays", "5 sprays", "21 sprays", "1.5 sprays"),
      List("1 впорскування", "2 впорскування", "5 впорскувань", "21 впорскування", "1,5 впорскування")
    ),
    "patch" -> (
      List("1 patch", "2 patches", "5 patches", "21 patches", "1.5 patches"),
      List("1 пластир", "2 пластирі", "5 пластирів", "21 пластир", "1,5 пластиря")
    )
  )

  test("every unit of the catalogue has its words after 1, 2, 5, 21 and a fraction, in English and Ukrainian"):
    assertEquals(DoseUnits.all.map(_.code).toSet, Expected.keySet)
    for (code, (english, ukrainian)) <- Expected do
      assertEquals(Lang.within(Lang.En)(Counts.map(DoseUnits.count(_, code))), english, code)
      assertEquals(Lang.within(Lang.Uk)(Counts.map(DoseUnits.count(_, code))), ukrainian, code)

  test("a half and a count of zero take the fraction and the many forms"):
    assertEquals(DoseUnits.count(BigDecimal("0.5"), "tablet"), "0.5 tablets")
    assertEquals(Lang.within(Lang.Uk)(DoseUnits.count(BigDecimal("0.5"), "tablet")), "0,5 таблетки")
    assertEquals(Lang.within(Lang.Uk)(DoseUnits.count(BigDecimal(0), "tablet")), "0 таблеток")
    assertEquals(Lang.within(Lang.Uk)(DoseUnits.count(BigDecimal(11), "capsule")), "11 капсул")
    assertEquals(DoseUnits.count(BigDecimal("6.000"), "tablet"), "6 tablets")

  test("a unit stored as typed in the chat is read in either language and either number"):
    for stored <- List("tablet", "Tablets", " TABLET ", "таблетки", "таблеток") do
      assertEquals(Lang.within(Lang.Uk)(DoseUnits.count(BigDecimal(6), stored)), "6 таблеток", stored)
      assertEquals(DoseUnits.count(BigDecimal(6), stored), "6 tablets", stored)
    assertEquals(DoseUnits.count(BigDecimal(1), "iu"), "1 IU")
    assertEquals(DoseUnits.count(BigDecimal(2), "patches"), "2 patches")

  test("a unit the catalogue does not know is shown as the user wrote it"):
    assertEquals(Lang.within(Lang.Uk)(DoseUnits.count(BigDecimal(2), "scoops")), "2 scoops")
    assertEquals(DoseUnits.label("scoops"), "scoops")

  test("the dose text is the amount with its unit, the unit alone, or nothing"):
    assertEquals(Lang.within(Lang.Uk)(DoseUnits.doseText(Some(BigDecimal(2)), Some("tablet"))), Some("2 таблетки"))
    assertEquals(DoseUnits.doseText(Some(BigDecimal("1000")), Some("IU")), Some("1000 IU"))
    assertEquals(DoseUnits.doseText(Some(BigDecimal("2.5")), None), Some("2.5"))
    assertEquals(Lang.within(Lang.Uk)(DoseUnits.doseText(Some(BigDecimal("2.5")), None)), Some("2,5"))
    assertEquals(Lang.within(Lang.Uk)(DoseUnits.doseText(None, Some("capsule"))), Some("капсула"))
    assertEquals(DoseUnits.doseText(None, None), None)

  test("the picker offers every stored code with its name in the account's language"):
    assertEquals(DoseUnits.picker.map(_.code), List("mg", "mcg", "g", "ml", "IU", "tablet", "capsule", "drop", "puff", "unit"))
    assertEquals(Lang.within(Lang.Uk)(DoseUnits.picker.map(_.label)).take(6), List("мг", "мкг", "г", "мл", "МО", "таблетка"))
    assertEquals(DoseUnits.picker.map(_.label).drop(5), List("tablet", "capsule", "drop", "puff", "unit"))

  test("a count of an inventory takes the countable unit, else units in the account's language"):
    assertEquals(DoseUnits.countable(Some("tablets")).code, "tablet")
    assertEquals(DoseUnits.countable(Some("mg")).code, "unit")
    assertEquals(DoseUnits.countable(None).code, "unit")
    assertEquals(Lang.within(Lang.Uk)(DoseUnits.countable(Some("ml")).count(BigDecimal(5))), "5 одиниць")
    assertEquals(DoseUnits.countable(Some("ml")).count(BigDecimal(1)), "1 unit")
end DoseUnitsSuite
