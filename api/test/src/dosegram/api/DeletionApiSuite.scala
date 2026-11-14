package dosegram.api

import dosegram.core.ports.ConsentPurpose
import dosegram.infra.db.RowMapper.given
import dosegram.infra.db.sql

import java.sql.Connection
import java.util.UUID
import scala.language.implicitConversions

/** ROADMAP G2.17 and G5.7, the Mini App half: a deletion route never deletes. It sends the confirmation into the chat
  * (MINI_APP 8.8, MA-STEP-1), answers 202 with the expiry of the confirmation, and records the step-up request; the
  * session routes write their audit rows (ROADMAP G2.16).
  */
final class DeletionApiSuite extends ApiSuite:

  /** An account with a chat to confirm in. */
  private def accountWithChat(): ApiSuite.TelegramAccount =
    val account = telegramAccount()
    fixtures.deliveryChannel(account.accountId, "fake", s"chat-${account.accountId}", clock.now())
    account

  private def queued(account: UUID): List[String] =
    val conn = dataSource.getConnection
    try
      given Connection = conn
      sql"SELECT payload::text FROM outbox_messages WHERE account_id = $account ORDER BY created_at".query[String]()
    finally conn.close()

  private def events(account: UUID): List[String] =
    val conn = dataSource.getConnection
    try
      given Connection = conn
      sql"SELECT event_type FROM audit_log WHERE subject_ref = $account ORDER BY occurred_at, id".query[String]()
    finally conn.close()

  test("POST /account/deletion sends the confirmation into the chat and deletes nothing"):
    val account = accountWithChat()
    val token = login(account)
    val reply = post("/api/v1/account/deletion", ujson.Obj(), token)
    assertEquals(reply.status, 202, reply.body)
    assertEquals(reply.json("status").str, "confirmation_sent")
    assertEquals(reply.json("expiresAt").str, clock.now().plusSeconds(600).toString)
    assertEquals(queued(account.accountId).size, 1)
    assert(queued(account.accountId).head.contains("The app asked to delete your account"))
    assertEquals(get("/api/v1/settings", token).status, 200, "the account and its session are still there")
    // The fixture's consent and the sign-in have their own rows.
    assertEquals(
      events(account.accountId).toSet -- Set("consent.granted", "session.created"),
      Set("account.delete.requested", "stepup.requested")
    )

  test("DELETE /domains/{domain}/data sends the confirmation of that domain; an unknown domain is 404"):
    val account = accountWithChat()
    fixtures.grant(account.accountId, ConsentPurpose.Mood)
    val token = login(account)
    val reply = delete("/api/v1/domains/mood/data", token)
    assertEquals(reply.status, 202, reply.body)
    assert(queued(account.accountId).head.contains("Delete your mood records?"))
    assertProblem(delete("/api/v1/domains/vitals/data", token), 404, "not_found")
    assertEquals(queued(account.accountId).size, 1)

  test("without a chat to confirm in, a deletion request answers 403 account_required"):
    val account = telegramAccount()
    assertProblem(post("/api/v1/account/deletion", ujson.Obj(), login(account)), 403, "account_required")

  test("a session exchange and a sign-out write session.created and session.revoked"):
    val account = telegramAccount()
    val token = login(account)
    assertEquals(delete("/api/v1/session", token).status, 204)
    assertEquals(events(account.accountId).toSet - "consent.granted", Set("session.created", "session.revoked"))
