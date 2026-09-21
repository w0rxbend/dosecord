package dosecord.infra.db

import dosecord.contracts.ChoiceMapEntry
import dosecord.contracts.MessageHandle
import dosecord.contracts.PlatformIdentity
import dosecord.core.ports.AuditEntry
import dosecord.core.ports.Clock
import dosecord.core.ports.NewDomainEvent
import dosecord.core.ports.NewInboundEvent

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** M0.12a repository half: the mediator's Postgres repositories against Testcontainers Postgres 18. */
class MediatorRepositorySuite extends PgSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE inbound_events, domain_events, auth_audit_log, rendered_messages, outbox_messages, " +
            "platform_identities, users CASCADE"
        )
      finally st.close()
    }

  private def newUow = PgUnitOfWork(dataSource, Clock.system)

  test("inbound_events: insert, duplicate rejected, complete stores reply and account id"):
    val uow = newUow
    val eventId = UUID.randomUUID()
    val accountId = UUID.randomUUID()
    assert(uow.transaction(_.inboundEvents.insert(NewInboundEvent("fake", "evt-1", eventId, t0))))
    assert(
      !uow.transaction(_.inboundEvents.insert(NewInboundEvent("fake", "evt-1", UUID.randomUUID(), t0))),
      "duplicate (vendor, vendor_event_id) rejected"
    )
    uow.transaction(_.inboundEvents.complete("fake", "evt-1", Some(accountId), """{"toast":"ok"}""", t0))

    val stored = uow.transaction(_.inboundEvents.find("fake", "evt-1")).get
    assertEquals(stored.eventId, eventId)
    assertEquals(stored.accountId, Some(accountId))
    // jsonb normalizes whitespace; compare semantically.
    assertEquals(stored.reply.map(_.filterNot(_.isWhitespace)), Some("""{"toast":"ok"}"""))
    assertEquals(stored.processedAt, Some(t0))

    withConnection { conn =>
      given Connection = conn
      assertEquals(
        sql"SELECT count(*) FROM inbound_events WHERE vendor = 'fake' AND vendor_event_id = 'evt-1'"
          .queryOne[Long]()
          .get,
        1L,
        "exactly one row"
      )
    }

  test("domain_events: append carries type, source and account id"):
    val uow = newUow
    val accountId = UUID.randomUUID()
    uow.transaction(
      _.domainEvents.append(
        NewDomainEvent(
          id = UUID.randomUUID(),
          eventType = "dosecord.mood.checkin_recorded.v1",
          source = "dosecord.mood",
          subject = Some(s"account:$accountId"),
          accountId = Some(accountId),
          correlationId = Some("fake:evt-1"),
          causationId = Some(UUID.randomUUID().toString),
          actorJson = s"""{"vendor":"fake","vendorUserId":"user-1","accountId":"$accountId"}""",
          occurredAt = t0,
          dataJson = """{"type":"dosecord.mood.checkin_recorded.v1"}"""
        )
      )
    )
    withConnection { conn =>
      given Connection = conn
      val row =
        sql"""SELECT type, source, account_id, correlation_id FROM domain_events""".queryOne[(String, String, UUID, String)]()
      assert(row.isDefined)
      assertEquals(row.get, ("dosecord.mood.checkin_recorded.v1", "dosecord.mood", accountId, "fake:evt-1"))
    }

  private given RowMapper[(String, String, UUID, String)] = rs =>
    (rs.getString("type"), rs.getString("source"), rs.uuid("account_id"), rs.getString("correlation_id"))

  test("identities: unknown actor gets a stable unlinked principal; a linked identity resolves its account"):
    val uow = newUow
    val actor = PlatformIdentity("fake", "user-1")
    val first = uow.transaction(_.identities.resolve(actor, t0))
    assert(!first.linked, "unknown identities start unlinked")
    assertEquals(first.accountId, None)
    val second = uow.transaction(_.identities.resolve(actor, t0.plusSeconds(60)))
    assertEquals(second, first, "resolution is stable")
    assertEquals(uow.transaction(_.identities.find(actor)), Some(first))

    val userId = UUID.randomUUID()
    val identityId = UUID.randomUUID()
    withConnection { conn =>
      given Connection = conn
      sql"INSERT INTO users (id, status) VALUES ($userId, 'active')".execute()
      sql"""INSERT INTO platform_identities (id, user_id, vendor, vendor_user_id)
            VALUES ($identityId, $userId, 'fake', 'user-2')""".execute()
    }
    val linked = uow.transaction(_.identities.resolve(PlatformIdentity("fake", "user-2"), t0))
    assert(linked.linked)
    assertEquals(linked.accountId.map(_.uuid), Some(userId))

  test("audit: append writes an auth_audit_log row under the append-only trigger"):
    val uow = newUow
    uow.transaction(
      _.audit.append(
        AuditEntry(
          kind = "callback_tampered",
          outcome = "rejected",
          vendor = "fake",
          vendorEventId = Some("evt-9"),
          chatId = Some("chat-1")
        )
      )
    )
    withConnection { conn =>
      given Connection = conn
      assertEquals(
        sql"SELECT count(*) FROM auth_audit_log WHERE kind = 'callback_tampered' AND outcome = 'rejected'"
          .queryOne[Long]()
          .get,
        1L
      )
    }

  test("rendered_messages: choice_map by target; latest pending prompt skips finalized and mapless messages"):
    val uow = newUow
    val chat = "chat-1"
    val entriesA = List(ChoiceMapEntry("cs", 1, "one", None, "dc:a1"), ChoiceMapEntry("cs", 2, "two", None, "dc:a2"))
    val handleA = MessageHandle("fake", chat, "mA")
    val handleB = MessageHandle("fake", chat, "mB")
    val handleNoMap = MessageHandle("fake", chat, "mC")
    uow.transaction { tx =>
      tx.renderedMessages.record(handleA, None, "prompt", None, None, None, entriesA, t0)
      tx.renderedMessages.record(handleNoMap, None, "notice", None, None, None, Nil, t0.plusSeconds(30))
      tx.renderedMessages.record(handleB, None, "prompt", None, None, None, entriesA, t0.plusSeconds(60))
    }

    val byTarget = uow.transaction(_.renderedMessages.choiceMapFor(handleA)).get
    assertEquals(byTarget.choiceMap, entriesA)
    assert(!byTarget.controlsRemoved)

    val latest = uow.transaction(_.renderedMessages.latestPendingPrompt("fake", chat)).get
    assertEquals(latest.handle.messageId, "mB", "the newest message with a choice_map wins")

    withConnection { conn =>
      given Connection = conn
      sql"UPDATE rendered_messages SET controls_removed_at = ${t0.plusSeconds(90)} WHERE message_id = 'mB'".execute()
    }
    val afterFinalize = uow.transaction(_.renderedMessages.latestPendingPrompt("fake", chat)).get
    assertEquals(afterFinalize.handle.messageId, "mA", "a finalized prompt is no longer pending")
    assert(
      uow.transaction(_.renderedMessages.choiceMapFor(handleB)).get.controlsRemoved,
      "the target lookup reports removed controls"
    )
