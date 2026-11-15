package dosegram.api

import dosegram.core.application.dose.DoseAttribution
import dosegram.core.application.dose.DoseCommands
import dosegram.core.domain.OccurrenceEvent
import dosegram.core.ports.ConsentPurpose
import dosegram.infra.crypto.DataKeys
import dosegram.infra.db.SealedColumns
import dosegram.infra.db.openText

import java.util.UUID

/** `POST /occurrences/{id}/actions` (ROADMAP G3.7, MINI_APP MA-TODAY-2 and MA-TODAY-3): the chat's use case behind
  * the medication consent, once per idempotency key, with the state the server holds as the answer.
  */
final class DoseActionApiSuite extends ApiSuite:

  private val dailyAtNine = ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("09:00"))

  /** A session of a new account with one dose of today at 09:00, and that dose's id. */
  private def doseOfToday(): (String, String) =
    startNewDay()
    val token = login(telegramAccount())
    val medication = post("/api/v1/medications", ujson.Obj("name" -> "Iron", "doseAmount" -> "1", "doseUnit" -> "tablet"), token, newKey())
    assertEquals(medication.status, 201, medication.body)
    val schedule = post(s"/api/v1/medications/${medication.json("id").str}/schedules", dailyAtNine, token, newKey())
    assertEquals(schedule.status, 201, schedule.body)
    val today = get("/api/v1/today", token).json("occurrences").arr
    assertEquals(today.size, 1, today.toString)
    (token, today(0)("id").str)

  private def act(token: String, id: String, body: ujson.Obj, key: Map[String, String] = newKey()): Reply =
    post(s"/api/v1/occurrences/$id/actions", body, token, key)

  private def actions(id: String) = uow.transaction(_.doseActions.listForOccurrence(UUID.fromString(id)))

  /** The intake events recorded for the dose. The envelope is sealed (V19), so each one is opened before it is read. */
  private def intakeEvents(id: String): Int =
    val conn = dataSource.getConnection
    try
      val opener = DataKeys.development.on(conn)
      val rows = conn
        .prepareStatement("SELECT account_id, id, data_enc FROM domain_events WHERE type LIKE '%intake_taken%'")
        .executeQuery()
      val envelopes = Iterator
        .continually(rows.next())
        .takeWhile(identity)
        .map: _ =>
          val field = SealedColumns.EventData.forRow(Option(rows.getObject(1, classOf[UUID])), rows.getObject(2, classOf[UUID]))
          opener.openText(field, rows.getBytes(3))
        .toList
      envelopes.count(_.contains(id))
    finally conn.close()

  test("taken records the dose once per key, with the chat's action row and one intake event"):
    val (token, id) = doseOfToday()
    val key = newKey()
    val first = act(token, id, ujson.Obj("kind" -> "taken"), key)
    assertEquals(first.status, 200, first.body)
    assertEquals(first.json("outcome").str, "recorded")
    assertEquals(first.json("occurrence")("state").str, "taken")
    val repeat = act(token, id, ujson.Obj("kind" -> "taken"), key)
    assertEquals(repeat.body, first.body)
    assertEquals(actions(id).map(_.vendor), List(Some("miniapp")))
    assertEquals(intakeEvents(id), 1)
    assertEquals(get("/api/v1/today", token).json("occurrences")(0)("state").str, "taken")

  test("a dose answered in the chat first is already resolved, not an error, and records nothing"):
    val (token, id) = doseOfToday()
    uow.transaction: tx =>
      val occ = tx.occurrences.lockById(UUID.fromString(id)).get
      DoseCommands(clock).answer(tx, occ, OccurrenceEvent.Taken, DoseAttribution("telegram:42", "telegram"))
    val reply = act(token, id, ujson.Obj("kind" -> "skipped"))
    assertEquals(reply.status, 200, reply.body)
    assertEquals(reply.json("outcome").str, "already_resolved")
    assertEquals(reply.json("resolvedIn").str, "chat")
    assertEquals(reply.json("occurrence")("state").str, "taken")
    assertEquals(actions(id).size, 1)

  test("undo takes the last answer back; a second undo is refused by name"):
    val (token, id) = doseOfToday()
    assertEquals(act(token, id, ujson.Obj("kind" -> "taken")).json("outcome").str, "recorded")
    val undone = act(token, id, ujson.Obj("kind" -> "undo"))
    assertEquals(undone.json("outcome").str, "recorded", undone.body)
    assertNotEquals(undone.json("occurrence")("state").str, "taken")
    val again = act(token, id, ujson.Obj("kind" -> "undo"))
    assertEquals(again.json("outcome").str, "refused")
    assertEquals(again.json("refusal").str, "nothing_to_undo")

  test("skip and snooze go through the same rules; a snooze needs its length"):
    val (token, id) = doseOfToday()
    assertEquals(act(token, id, ujson.Obj("kind" -> "snoozed")).fieldErrors, List("minutes" -> "invalid"))
    assertEquals(act(token, id, ujson.Obj("kind" -> "later")).fieldErrors, List("kind" -> "invalid"))
    val skipped = act(token, id, ujson.Obj("kind" -> "skipped"))
    assertEquals(skipped.json("occurrence")("state").str, "skipped", skipped.body)
    val snoozed = act(token, id, ujson.Obj("kind" -> "snoozed", "minutes" -> 10))
    assertEquals(snoozed.json("outcome").str, "already_resolved")

  test("an open dose lists the snooze lengths its policy allows, and one of them snoozes it"):
    val (token, id) = doseOfToday()
    val choices = get("/api/v1/today", token).json("occurrences")(0)("snoozeChoicesMinutes").arr.map(_.num.toInt).toList
    assert(choices.nonEmpty, choices.toString)
    val snoozed = act(token, id, ujson.Obj("kind" -> "snoozed", "minutes" -> choices.head))
    assertEquals(snoozed.json("outcome").str, "recorded", snoozed.body)
    assertEquals(snoozed.json("occurrence")("state").str, "snoozed")

  test("an answer needs an idempotency key"):
    val (token, id) = doseOfToday()
    assertEquals(act(token, id, ujson.Obj("kind" -> "taken"), Map.empty).fieldErrors, List("Idempotency-Key" -> "required"))
    assertEquals(actions(id), Nil)

  test("without the medication consent nothing is recorded"):
    val (token, id) = doseOfToday()
    val accountId = uow.transaction(_.occurrences.lockById(UUID.fromString(id))).get.accountId
    assert(tracking.consents.withdraw(accountId, ConsentPurpose.Medication, clock.now()))
    assertProblem(act(token, id, ujson.Obj("kind" -> "taken")), 403, "consent_required")
    assertEquals(actions(id), Nil)

  test("an unknown occurrence is not found"):
    val (token, _) = doseOfToday()
    assertProblem(act(token, UUID.randomUUID().toString, ujson.Obj("kind" -> "taken")), 404, "not_found")
