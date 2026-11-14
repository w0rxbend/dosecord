package dosegram.core.application.exports

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.copy.Lang

/** A schedule in words, as the add-medication confirm card and the record summary show it: in the account's language,
  * weekday names and "as needed" included.
  */
class RuleInWordsSuite extends munit.FunSuite:

  private val nine = List(HhMm.unsafe("09:00"))
  private val monWedFri = Rule.FixedTimes(List(SlotGroup(List(Weekday.Mon, Weekday.Wed, Weekday.Fri), nine)))

  test("certain days and as needed are in English for an English account"):
    assert(RecordSummaries.ruleInWords(monWedFri).contains("Mon, Wed"), RecordSummaries.ruleInWords(monWedFri))
    assertEquals(RecordSummaries.ruleInWords(Rule.AsNeeded(3, 240)), "as needed")

  test("certain days and as needed are in Ukrainian for a Ukrainian account"):
    val days = Lang.within(Lang.Uk)(RecordSummaries.ruleInWords(monWedFri))
    assert(days.contains("Пн, Ср"), days)
    assert(days.endsWith("09:00"), days)
    assertEquals(Lang.within(Lang.Uk)(RecordSummaries.ruleInWords(Rule.AsNeeded(3, 240))), "за потреби")
end RuleInWordsSuite
