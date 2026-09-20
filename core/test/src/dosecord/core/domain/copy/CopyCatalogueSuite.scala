package dosecord.core.domain.copy

/** ROADMAP M1.4a acceptance: the catalogue is forbidden-phrase clean, every `dose_action` kind of the V1 schema has a
  * sentence, and the recorded copy decisions hold.
  */
class CopyCatalogueSuite extends munit.FunSuite:

  test("catalogue entry ids are unique"):
    val ids = Copy.all.map(_.id)
    assertEquals(ids.distinct, ids)

  test("no catalogue entry contains a forbidden phrase"):
    val violations = Copy.all.flatMap(e => ForbiddenPhrases.violations(e.text).map(p => s"${e.id}: '$p'"))
    assertEquals(violations, Nil)

  test("no catalogue entry is blank"):
    assert(Copy.all.nonEmpty)
    Copy.all.foreach(e => assert(e.text.trim.nonEmpty, s"${e.id} is blank"))

  test("the forbidden-phrase detector catches clinical advice and shaming"):
    assert(ForbiddenPhrases.violations("You should take it now.").nonEmpty)
    assert(ForbiddenPhrases.violations("Double your next dose.").nonEmpty)
    assert(ForbiddenPhrases.violations("You failed.").nonEmpty)
    assertEquals(ForbiddenPhrases.violations("Recorded at 09:03."), Nil)

  test("every dose_action kind of the V1 schema enum has a catalogue sentence"):
    // Mirrors `CREATE TYPE dose_action` in infra/src/main/resources/db/migration/V1__baseline.sql; a schema change
    // that adds a kind fails here until the catalogue gains its sentence.
    val v1Kinds = List(
      "reminder_sent",
      "reminder_deferred",
      "taken",
      "skipped",
      "snoozed",
      "auto_marked_missed",
      "marked_unknown",
      "manually_corrected",
      "undone",
      "note_added",
      "cancelled",
      "catch_up_collapsed",
      "chain_child_created"
    )
    assertEquals(DoseActionKind.values.toList.map(_.dbValue), v1Kinds)
    DoseActionKind.values.foreach { kind =>
      val sentence = DoseActionCopy.sentence(kind)
      assert(sentence.trim.nonEmpty, s"$kind has a blank sentence")
      assertEquals(ForbiddenPhrases.violations(sentence), Nil, clue(kind))
    }

  test("every ops alert is prefixed 'Ops:'"):
    OpsCopy.entries.foreach(e => assert(e.text.startsWith(OpsCopy.Prefix + " "), e.id))

  test("recorded decision: reminder body with instructions on a second line when present"):
    assertEquals(
      ReminderCopy.reminderBody("Vitamin D", Some("1000 IU"), None),
      List("Time for Vitamin D, 1000 IU.")
    )
    assertEquals(
      ReminderCopy.reminderBody("Vitamin D", Some("1000 IU"), Some("with breakfast")),
      List("Time for Vitamin D, 1000 IU.", "with breakfast")
    )
    assertEquals(ReminderCopy.reminderBody("Vitamin D", None, None), List("Time for Vitamin D."))

  test("recorded decision: top menu is [Today][Medications][Habits][Reminders][Stats][Account]"):
    assertEquals(MenuCopy.topLevel, List("Today", "Medications", "Habits", "Reminders", "Stats", "Account"))

  test("recorded decision: missed notice is [I took it][Skip][Keep missed]"):
    assertEquals(Labels.missedNotice, List("I took it", "Skip", "Keep missed"))

  test("recorded decision: post-Taken is [Undo][Correct]"):
    assertEquals(Labels.postTaken, List("Undo", "Correct"))

  test("recorded decision: habit taxonomy is boolean/count/duration/measurement plus avoid"):
    assertEquals(
      HabitCopy.HabitKind.values.toList.map(_.dbValue),
      List("boolean", "count", "duration", "measurement", "avoid")
    )
end CopyCatalogueSuite
