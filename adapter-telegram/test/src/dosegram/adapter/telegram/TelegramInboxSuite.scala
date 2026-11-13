package dosegram.adapter.telegram

import dosegram.core.domain.copy.AdapterCopy
import dosegram.core.ports.InboxEntry
import dosegram.core.ports.InboundInbox
import dosegram.core.ports.InboxPurge
import dosegram.core.ports.PendingInboxEntry

import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import scala.collection.mutable

/** ROADMAP G1.7, ADR-017: the adapter with a durable inbox on the fake wire. An update is stored at receipt and reaches
  * the mediator only when the processor offers it; the processor's loop is not run here, a test calls one pass.
  */
class TelegramInboxSuite extends munit.FunSuite:

  /** The inbox without the database; `down` stands for a database that refuses the write. */
  private final class MemoryInbox extends InboundInbox:
    val rows: mutable.LinkedHashMap[String, (InboxEntry, Instant, String)] = mutable.LinkedHashMap.empty
    @volatile var down = false
    @volatile var afterStore: () => Unit = () => ()

    def statuses: List[String] = synchronized(rows.values.map(_._3).toList)

    override def store(entry: InboxEntry, receivedAt: Instant): Unit =
      try
        synchronized:
          if down then throw SQLException("injected: database unavailable")
          if !rows.contains(entry.vendorEventId) then rows(entry.vendorEventId) = (entry, receivedAt, "pending")
      finally afterStore()

    override def pending(perChat: Int, limit: Int): List[PendingInboxEntry] = synchronized:
      rows.values.collect { case (entry, at, "pending") => PendingInboxEntry(entry, at) }.toList.take(limit)

    override def markProcessed(entry: InboxEntry, at: Instant): Unit = synchronized:
      rows(entry.vendorEventId) = rows(entry.vendorEventId).copy(_3 = "processed")

    override def recordFailure(entry: InboxEntry, maxAttempts: Int, at: Instant): Boolean = false

    override def purge(now: Instant): InboxPurge = InboxPurge(0, 0, 0)

  private def withInbox(config: TelegramConfig = TelegramRig.webhook): (TelegramRig, MemoryInbox) =
    val inbox = MemoryInbox()
    (TelegramRig(config, Some(inbox)), inbox)

  private def receive(rig: TelegramRig, fixture: String): Boolean =
    rig.adapter.receive(TelegramRig.decoded(BotApiFixtures.update(fixture)))

  private def pass(rig: TelegramRig): Int = rig.adapter.inboxProcessor.getOrElse(fail("no inbox processor")).processOnce()

  test("an update is stored before receipt answers true, and reaches the mediator only when processed"):
    val (rig, inbox) = withInbox()
    rig.started()
    assert(receive(rig, "message-text"))
    assertEquals(inbox.rows.keys.toList, List("telegram:update:900000001"))
    assertEquals(rig.events, Nil, "nothing is processed at receipt")
    assertEquals(pass(rig), 1)
    assertEquals(rig.events.map(_.vendorEventId), List("telegram:update:900000001"))
    assertEquals(inbox.statuses, List("processed"))

  test("the same update received twice is one entry and one event"):
    val (rig, inbox) = withInbox()
    rig.started()
    receive(rig, "message-text")
    receive(rig, "message-text")
    while pass(rig) > 0 do ()
    assertEquals(inbox.rows.size, 1)
    assertEquals(rig.events.size, 1)

  test("an update that could not be stored is refused, so Telegram or the next poll brings it again"):
    val (rig, inbox) = withInbox()
    rig.started()
    inbox.down = true
    assert(!receive(rig, "message-text"))
    assertEquals(rig.logs, List("telegram update not stored: database unavailable update_id=900000001"))

  test("before start nothing is stored"):
    val (rig, inbox) = withInbox()
    assert(!receive(rig, "message-text"))
    assertEquals(inbox.rows.size, 0)

  test("a sender outside the allowlist gets the fixed reply at receipt and nothing is stored"):
    val (rig, inbox) = withInbox(TelegramRig.webhook.copy(access = AccessPolicy.Allowlist(Set(1L))))
    rig.started()
    assert(receive(rig, "message-text"))
    assertEquals(inbox.rows.size, 0)
    assertEquals(rig.messageCalls.map(_.text("text")), List(Some(AdapterCopy.notAvailable)))

  test("an update from a group or of an unknown kind is dropped at receipt and not stored"):
    val (rig, inbox) = withInbox()
    rig.started()
    assert(receive(rig, "group-message"))
    assert(receive(rig, "unknown-kind"))
    assertEquals(inbox.rows.size, 0)

  test("the stored entry is processed with its receipt time, so a late callback is not answered (TG-11)"):
    val (rig, _) = withInbox()
    rig.started()
    assert(receive(rig, "callback-query"))
    rig.timer.advance(Duration.ofSeconds(31))
    pass(rig)
    val event = rig.events.headOption.getOrElse(fail("the callback became an event"))
    assertEquals(event.receivedAt, TelegramRig.T0)
    event.interaction.foreach(_.answer(None))
    rig.timer.advance(Duration.ofSeconds(5))
    assertEquals(rig.answers, Nil, "an answer older than the answer age would be refused")

  test("an entry processed after a restart reaches the mediator once more, under the same key (TG-08)"):
    val inbox = MemoryInbox()
    val before = TelegramRig(TelegramRig.webhook, Some(inbox)).started()
    before.adapter.receive(TelegramRig.decoded(BotApiFixtures.update("message-text")))
    val after = TelegramRig(TelegramRig.webhook, Some(inbox)).started()
    assertEquals(pass(after), 1)
    assertEquals(after.events.map(_.vendorEventId), List("telegram:update:900000001"))

  test("webhook mode registers the webhook: the URL, the secret, the four update kinds and one connection"):
    val registration = WebhookRegistration("https://bot.example/tg/webhook/path-secret", "header-secret")
    val rig = TelegramRig(TelegramRig.webhook.copy(webhook = Some(registration))).started()
    val calls = rig.wire.calls("setWebhook")
    assertEquals(
      calls.map(_.request.params),
      List(
        ujson.Obj(
          "url" -> "https://bot.example/tg/webhook/path-secret",
          "secret_token" -> "header-secret",
          "allowed_updates" -> ujson.Arr("message", "edited_message", "callback_query", "my_chat_member"),
          "max_connections" -> 1
        )
      )
    )
    assertEquals(rig.wire.calls("deleteWebhook"), Nil)
    assert(!registration.toString.contains("secret"), registration.toString)

  test("a webhook that cannot be registered is a failed start"):
    val registration = WebhookRegistration("https://bot.example/tg/webhook/path-secret", "header-secret")
    val rig = TelegramRig(TelegramRig.webhook.copy(webhook = Some(registration)))
    val unauthorized = BotApiFixtures.error("unauthorized-401")
    rig.wire.replyNext("setWebhook", unauthorized.status, unauthorized.body)
    assert(intercept[IllegalStateException](rig.started()).getMessage.contains("setWebhook"))

  test("long polling stores each update in the inbox, and only then confirms it by the next offset"):
    val (rig, inbox) = withInbox(TelegramRig.webhook.copy(updates = UpdatesMode.Poll))
    rig.wire.enqueueUpdates(BotApiFixtures.update("message-text"))
    // The reader is the timer's first task; stopping the adapter at the first store ends its loop after one batch.
    inbox.afterStore = () => rig.adapter.stop()
    rig.started()
    rig.timer.advance(Duration.ZERO)
    assertEquals(inbox.rows.keys.toList, List("telegram:update:900000001"))
    assertEquals(rig.events, Nil, "nothing is processed by the reader")

  test("long polling keeps the offset when the store fails, so the update is polled again"):
    val (rig, inbox) = withInbox(TelegramRig.webhook.copy(updates = UpdatesMode.Poll))
    rig.wire.enqueueUpdates(BotApiFixtures.update("message-text"))
    inbox.down = true
    inbox.afterStore = () => rig.adapter.stop()
    rig.started()
    rig.timer.advance(Duration.ZERO)
    assertEquals(inbox.rows.size, 0)
    assertEquals(rig.wire.calls("getUpdates").map(_.number("offset")), List(None))
