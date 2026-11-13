package dosegram.contracts

import org.scalacheck.Arbitrary

import Gens.given

/** The product limits on tappable controls (ADR-015): our own bounds, enforced where the values are constructed. */
class ControlLimitsSuite extends munit.FunSuite:

  private def choices(count: Int): List[Choice] = (1 to count).toList.map(i => Choice(s"c$i", s"cb:$i"))

  test("a choice label holds 1 to 32 characters"):
    assertEquals(Choice("x" * 32, "cb").label.length, 32)
    intercept[IllegalArgumentException](Choice("x" * 33, "cb"))
    intercept[IllegalArgumentException](Choice("", "cb"))

  test("a choice set holds 1 to 24 choices"):
    assertEquals(ChoiceSet("set", choices = choices(24)).choices.size, 24)
    intercept[IllegalArgumentException](ChoiceSet("set", choices = choices(25)))
    intercept[IllegalArgumentException](ChoiceSet("set", choices = Nil))

  test("fitLabel keeps a short label and shortens a long one to the limit with an ellipsis"):
    assertEquals(ControlLimits.fitLabel("Log Vitamin D"), "Log Vitamin D")
    assertEquals(ControlLimits.fitLabel("x" * 32), "x" * 32)
    assertEquals(ControlLimits.fitLabel("x" * 40), "x" * 31 + "…")

  test("a profile row holds at most 8 buttons"):
    val profile = Iterator.continually(summon[Arbitrary[CapabilityProfile]].arbitrary.sample).flatten.next()
    assertEquals(profile.copy(maxChoicesPerRow = 8).maxChoicesPerRow, 8)
    intercept[IllegalArgumentException](profile.copy(maxChoicesPerRow = 9))

  test("a form needs a title and at least one field, with no platform cap on either"):
    val fields = (1 to 9).toList.map(i => Field(s"k$i", s"Field $i", FieldType.Text))
    assertEquals(Form("f", "t" * 60, fields, "cb").fields.size, 9)
    intercept[IllegalArgumentException](Form("f", "", fields, "cb"))
    intercept[IllegalArgumentException](Form("f", "Title", Nil, "cb"))
