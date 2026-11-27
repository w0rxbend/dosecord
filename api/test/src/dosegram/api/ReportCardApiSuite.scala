package dosegram.api

import dosegram.core.ports.ConsentPurpose
import dosegram.infra.db.sql

import java.sql.Connection
import java.util.UUID
import scala.language.implicitConversions

/** The share button of the Insights screen (MINI_APP 7.11, VISUALS IMG-17): the chat's facts and sentences, behind the
  * `images_in_chat` consent, sent through the report queue once per idempotency key (ROADMAP G4.3).
  */
final class ReportCardApiSuite extends ApiSuite:

  /** A session of an account with a mood check-in this week and a private chat to receive cards; `images` grants the
    * images consent.
    */
  private def account(images: Boolean = true): (String, UUID) =
    val account = telegramAccount()
    fixtures.grant(account.accountId, ConsentPurpose.Mood)
    if images then fixtures.grant(account.accountId, ConsentPurpose.ImagesInChat)
    val conn = dataSource.getConnection
    try
      given Connection = conn
      sql"""UPDATE platform_identities SET dm_channel_id = ${account.telegramUserId.toString}
            WHERE user_id = ${account.accountId}""".execute()
      sql"""INSERT INTO delivery_channels (id, account_id, platform_identity_id, role, priority, state, updated_at)
            SELECT ${UUID.randomUUID()}, user_id, id, 'primary', 0, 'healthy', ${clock.now()}
            FROM platform_identities WHERE user_id = ${account.accountId}""".execute()
    finally conn.close()
    val token = login(account)
    assertEquals(post("/api/v1/mood/checkins", ujson.Obj("valence" -> 4), token, newKey()).status, 201)
    (token, account.accountId)

  private def queued(accountId: UUID): Int = uow.transaction(_.reports.requestedSince(accountId, clock.now().minusSeconds(3600))).size

  test("the cards on offer are named by the sentence each card states"):
    val (token, _) = account()
    val cards = get("/api/v1/report-cards", token)
    assertEquals(cards.status, 200, cards.body)
    assert(cards.json("available").bool)
    assert(cards.json("warning").str.contains("Anyone you send this to can keep it"))
    val offers = cards.json("cards").arr.map(card => card("kind").str -> card("sentence").str).toList
    assert(offers.contains("share-week-mood" -> "This week's mood: pleasant"), offers.toString)

  test("a delivery is queued for the chat once per key"):
    val (token, accountId) = account()
    val key = newKey()
    val sent = post("/api/v1/report-cards/share-week-mood/deliveries", ujson.Obj(), token, key)
    assertEquals(sent.status, 202, sent.body)
    assertEquals(sent.json("status").str, "queued")
    assertEquals(post("/api/v1/report-cards/share-week-mood/deliveries", ujson.Obj(), token, key).body, sent.body)
    assertEquals(queued(accountId), 1)

  test("without the images consent nothing is offered and nothing is queued"):
    val (token, accountId) = account(images = false)
    val cards = get("/api/v1/report-cards", token)
    assert(!cards.json("available").bool)
    assertEquals(cards.json("cards").arr.toList, Nil)
    assertProblem(post("/api/v1/report-cards/share-week-mood/deliveries", ujson.Obj(), token, newKey()), 403, "consent_required")
    assertEquals(queued(accountId), 0)

  test("a card that is not on offer is not found"):
    val (token, accountId) = account()
    assertProblem(post("/api/v1/report-cards/share-water-goal/deliveries", ujson.Obj(), token, newKey()), 404, "not_found")
    assertEquals(queued(accountId), 0)

  test("in discreet mode a delivery needs the person's confirmation"):
    val (token, accountId) = account()
    uow.transaction: tx =>
      val settings = tx.accounts.settingsOf(accountId).get
      tx.accounts.saveSettings(accountId, settings.copy(isDiscreet = true), clock.now())
    assert(get("/api/v1/report-cards", token).json("discreet").bool)
    val refused = post("/api/v1/report-cards/share-week-mood/deliveries", ujson.Obj(), token, newKey())
    assertEquals(refused.fieldErrors, List("confirmed" -> "required"))
    val sent = post("/api/v1/report-cards/share-week-mood/deliveries", ujson.Obj("confirmed" -> true), token, newKey())
    assertEquals(sent.status, 202, sent.body)

  test("the share builder offers the week at a glance and the trends of 30 and 90 days, each by its kind"):
    val (token, _) = account()
    val read = get("/api/v1/report-cards", token).json
    val choices = read("builder").arr.map(choice => choice("card").str -> choice("periods").arr.map(_("kind").str).toList)
    assertEquals(choices.toList, List("week-glance" -> List("week-glance-7"), "trends" -> List("trends-30", "trends-90")))
    assertEquals(read("builderTitle").str, "Send a card to your chat")
    assert(read("builderWarning").str.contains("your own figures and the dates"), read.toString)

  test("a preview is the drawn card the chat would receive, with the trends' own text equivalent, and queues nothing"):
    val (token, accountId) = account()
    val preview = get("/api/v1/report-cards/trends-90/preview", token)
    assertEquals(preview.status, 200, preview.body)
    val png = java.util.Base64.getDecoder.decode(preview.json("png").str)
    assertEquals(new String(png, java.nio.charset.StandardCharsets.UTF_8), "<svg id=\"trends-90\"/>")
    assert(preview.json.obj.get("svg").isEmpty, "no SVG string is sent (ARCHITECTURE section 9.5.1)")
    val alt = preview.json("altText").str
    assert(alt.startsWith("Trends · last 90 days"), alt)
    assert(alt.contains("Mood: 1 check-in"), alt)
    assertEquals(queued(accountId), 0)

  test("a builder card is queued for the chat with its text equivalent as the caption"):
    val (token, accountId) = account()
    val sent = post("/api/v1/report-cards/trends-30/deliveries", ujson.Obj(), token, newKey())
    assertEquals(sent.status, 202, sent.body)
    assertEquals(sent.json("kind").str, "trends-30")
    assertEquals(post("/api/v1/report-cards/week-glance-7/deliveries", ujson.Obj(), token, newKey()).status, 429)
    assertEquals(queued(accountId), 1)

  test("without the images consent the builder is empty and a preview is refused; an unknown kind is not found"):
    val (blocked, _) = account(images = false)
    val read = get("/api/v1/report-cards", blocked).json
    assertEquals(read.obj.get("builder"), None, "an empty builder is left out of the document")
    assertProblem(get("/api/v1/report-cards/trends-30/preview", blocked), 403, "consent_required")
    val (token, _) = account()
    assertProblem(get("/api/v1/report-cards/trends-7/preview", token), 404, "not_found")
