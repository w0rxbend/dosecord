package dosecord.core.application.mood

import dosecord.core.domain.copy.MoodCopy

/** `/mood` parsing (ROADMAP M0.12d). The keyword and range fixtures below are transcribed verbatim from the retired
  * prototype's `_parse_mood` (git history, `services/discord-bot/.../command_handler.py`) per ROADMAP section 2 —
  * "salvage the parsing RULES as test fixtures, write fresh Scala".
  */
class MoodParsingSuite extends munit.FunSuite:

  // Salvaged fixture: `_parse_mood` first tried `int(mood)` with `1 <= level <= 10`.
  test("salvaged rule: integers inside 1-10 parse, anything outside does not"):
    val accepted = (1 to 10).toList
    val rejected = List(0, 11, -1, 100)
    accepted.foreach: level =>
      assertEquals(MoodParsing.levelOf(level.toString), Some(level), s"$level parses")
    rejected.foreach: level =>
      assertEquals(MoodParsing.levelOf(level.toString), None, s"$level rejected")

  // Salvaged fixture: `_parse_mood`'s keyword map, applied to `mood.lower()`.
  private val salvagedKeywordMap: Map[String, Int] = Map(
    "terrible" -> 1,
    "bad" -> 2,
    "awful" -> 1,
    "okay" -> 5,
    "fine" -> 5,
    "good" -> 8,
    "great" -> 10,
    "amazing" -> 10
  )

  test("salvaged rule: the keyword map maps words to levels, case-insensitively"):
    salvagedKeywordMap.foreach: (word, level) =>
      assertEquals(MoodParsing.levelOf(word), Some(level), word)
      assertEquals(MoodParsing.levelOf(word.toUpperCase), Some(level), s"$word uppercased")

  test("salvaged rule: unknown words do not parse"):
    assertEquals(MoodParsing.levelOf("banana"), None)
    assertEquals(MoodParsing.levelOf("ok"), None, "the salvaged map has 'okay', not 'ok'")
    assertEquals(MoodParsing.levelOf(""), None)

  test("note is stored only when supplied; trailing #tags go to tags (R37)"):
    assertEquals(
      MoodParsing.parse("8 slept well #sleep").map(p => (p.level.value, p.note, p.tags)),
      Right((8, Some("slept well"), List("sleep")))
    )
    assertEquals(
      MoodParsing.parse("8").map(p => (p.level.value, p.note, p.tags)),
      Right((8, None, Nil))
    )
    assertEquals(
      MoodParsing.parse("good rough morning #work #health").map(p => (p.level.value, p.note, p.tags)),
      Right((8, Some("rough morning"), List("work", "health")))
    )

  test("a #tag in the middle is note text, not a tag"):
    assertEquals(
      MoodParsing.parse("5 #notatag in the middle").map(p => (p.note, p.tags)),
      Right((Some("#notatag in the middle"), Nil))
    )

  test("junk input is rejected with the catalogue usage error and parses nothing (C18)"):
    assertEquals(MoodParsing.parse(""), Left(MoodCopy.invalidMood))
    assertEquals(MoodParsing.parse("   "), Left(MoodCopy.invalidMood))
    assertEquals(MoodParsing.parse("banana split"), Left(MoodCopy.invalidMood))
    assertEquals(MoodParsing.parse("0"), Left(MoodCopy.invalidMood))
    assertEquals(MoodParsing.parse("11"), Left(MoodCopy.invalidMood))
end MoodParsingSuite
