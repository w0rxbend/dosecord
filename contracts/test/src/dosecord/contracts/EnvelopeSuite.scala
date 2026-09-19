package dosecord.contracts

class EnvelopeSuite extends munit.FunSuite:
  test("placeholder"):
    assertEquals(Envelope("a").id, "a")
