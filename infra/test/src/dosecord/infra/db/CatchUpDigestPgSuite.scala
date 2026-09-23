package dosecord.infra.db

import dosecord.contracts.DigestItem
import dosecord.contracts.HhMm
import dosecord.contracts.LoopDispatch
import dosecord.contracts.Weekday
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.CallbackKey
import dosecord.core.chat.CallbackKeys
import dosecord.core.chat.CapabilityProfiles
import dosecord.core.chat.FakeAdapter
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.ports.NewOutboxMessage
import dosecord.core.ports.OutboxOp
import dosecord.core.ports.Wake
import dosecord.core.scheduling.CatalogueOutboxRenderer
import dosecord.core.scheduling.Materialiser
import dosecord.core.scheduling.OutboxDispatcher
import dosecord.core.scheduling.ReminderLoop
import dosecord.infra.Metrics
import dosecord.infra.MicrometerLoopMetrics

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** ROADMAP M1.8 on Testcontainers Postgres 18: the 6 h clock-jump acceptance (unknown rows, one digest, no stale
  * reminders), the crash + 30 min jump acceptance (exactly one sent message carrying the new epoch), the digest's
  * re-read-before-rendering drop, the materialiser-first startup gap fill, and `dosecord_unknown_total{reason}`.
  */
class CatchUpDigestPgSuite extends PgSuite:

  private val monday = Instant.parse("2026-09-21T08:00:00Z") // a Monday
  private val utc = ZoneId.of("UTC")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE dose_actions, dose_occurrences, schedule_revisions, medication_schedules, medications, " +
            "domain_events, outbox_messages, rendered_messages, delivery_channels, platform_identities, " +
            "worker_heartbeat, users CASCADE"
        )
      finally st.close()
    }

  private lazy val fixtures = Fixtures(dataSource)

  private def dailyAt(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))

  private def loop(clock: MutableClock): ReminderLoop =
    ReminderLoop(
      PgUnitOfWork(dataSource, clock),
      Materialiser(PgUnitOfWork(dataSource, clock), clock, MicrometerLoopMetrics()),
      Wake.polling,
      clock,
      instance = "catchup-test",
      metrics = MicrometerLoopMetrics()
    )

  private def newAdapter(): FakeAdapter = FakeAdapter(CapabilityProfiles.Discord, vendor = "fake")

  private def dispatcher(clock: MutableClock, adapter: FakeAdapter): OutboxDispatcher =
    val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.fill[Byte](32)(7)), None))
    OutboxDispatcher(
      PgUnitOfWork(dataSource, clock),
      Map("fake" -> adapter),
      CatalogueOutboxRenderer(PgUnitOfWork(dataSource, clock), codec, clock),
      clock
    )

  private final case class OccRow(scheduledFor: Instant, status: String, unknownReason: Option[String])

  private def occurrenceRows(scheduleId: UUID): List[OccRow] = withConnection { conn =>
    given Connection = conn
    given RowMapper[OccRow] = rs =>
      OccRow(rs.instant("scheduled_for"), rs.getString("status"), rs.optString("unknown_reason"))
    sql"""SELECT scheduled_for, status::text AS status, unknown_reason FROM dose_occurrences
          WHERE schedule_id = $scheduleId ORDER BY scheduled_for"""
      .query[OccRow]()
  }

  private final case class OutboxRow(sendKey: String, kind: String, status: String, payload: String)

  private def outboxRows(): List[OutboxRow] = withConnection { conn =>
    given Connection = conn
    given RowMapper[OutboxRow] = rs =>
      OutboxRow(rs.getString("send_key"), rs.getString("kind"), rs.getString("status"), rs.getString("payload"))
    sql"SELECT send_key, kind, status::text AS status, payload FROM outbox_messages ORDER BY created_at, send_key"
      .query[OutboxRow]()
  }

  private def digestOf(rows: List[OutboxRow]): (String, LoopDispatch.Digest) =
    val digests = rows.filter(_.kind == "digest")
    assertEquals(digests.size, 1, s"exactly one digest row, got: ${digests.map(_.sendKey)}")
    LoopDispatch.fromJson(digests.head.payload) match
      case d: LoopDispatch.Digest => (digests.head.sendKey, d)
      case other                  => fail(s"digest row carries ${other.getClass.getSimpleName}")

  private def unknownCount(reason: String): Double =
    Metrics.scrape().linesIterator
      .find(_.startsWith(s"""dosecord_unknown_total{reason="$reason"}"""))
      .map(_.split(" +")(1).toDouble)
      .getOrElse(0.0)

  private def occurrenceIdsAt(scheduleId: UUID, scheduledFor: List[Instant]): Set[UUID] =
    fixtures.uow
      .transaction(_.occurrences.listBySchedule(scheduleId))
      .filter(o => scheduledFor.contains(o.scheduledFor))
      .map(_.id)
      .toSet

  // Acceptance 1: a 6 h clock jump yields unknown rows, one digest, no stale reminders.
  test("a 6 h clock jump yields unknown rows, one digest, and no stale reminders"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00", "12:00"), utc)
    fixtures.deliveryChannel(accountId, "fake", "dm:owner", monday)
    val outageBefore = unknownCount("outage")

    // The worker is down from 08:00; at 14:00 it restarts: the materialiser runs first, then the loop ticks.
    clock.advance(Duration.ofHours(6))
    assertEquals(loop(clock).tickOnce(clock.now()), 2)

    val today = occurrenceRows(created.scheduleId).filter(_.scheduledFor.isBefore(monday.plus(Duration.ofHours(6))))
    assertEquals(today.map(_.status), List("unknown", "unknown"), "both of today's doses are unknown")
    assertEquals(today.map(_.unknownReason), List(Some("outage"), Some("outage")))
    assertEquals(unknownCount("outage"), outageBefore + 2, "dosecord_unknown_total{reason=\"outage\"} counts both")

    val rows = outboxRows()
    assertEquals(rows.filter(r => r.kind == "reminder" || r.kind == "missed_notice"), Nil, "no stale reminders")
    val (sendKey, digest) = digestOf(rows)
    val bucket = monday.plus(Duration.ofHours(1)).getEpochSecond / ReminderLoop.DigestBucketSeconds
    assertEquals(sendKey, s"digest:$accountId:$bucket")
    assertEquals(
      digest.items.map(_.occurrenceId).toSet,
      occurrenceIdsAt(created.scheduleId, today.map(_.scheduledFor)),
      "the digest covers exactly today's two occurrences"
    )
    assert(digest.items.forall(_.epoch == 1), "per-item epochs: the unknown transition's epoch")
    assertEquals(
      withConnection { conn =>
        given Connection = conn
        sql"SELECT count(*) FROM dose_actions WHERE action = 'marked_unknown'".queryOne[Int]().getOrElse(0)
      },
      2
    )

    // The digest dispatches as exactly one message carrying the catalogue header.
    val adapter = newAdapter()
    assertEquals(dispatcher(clock, adapter).dispatchOnce(), 1)
    assertEquals(adapter.sent.size, 1, "one digest message")
    assert(
      adapter.sent.head._2.chunks.mkString("\n").contains("While I was away, 2 doses passed without a reminder."),
      s"digest body was: ${adapter.sent.head._2.chunks}"
    )

  // Acceptance 2: crash with a queued s1 row plus a 30 min jump yields exactly one sent message carrying the new epoch.
  test("crash with a queued s1 row plus a 30 min jump yields exactly one sent message carrying the new epoch"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc)
    fixtures.deliveryChannel(accountId, "fake", "dm:owner", monday)
    val l = loop(clock)

    clock.advance(Duration.ofMinutes(65)) // 09:05
    assertEquals(l.tickOnce(clock.now()), 1) // pending -> due, the s1 row is queued at epoch 1
    // Crash: the queued row is never dispatched. Thirty minutes later the worker restarts.
    clock.advance(Duration.ofMinutes(30)) // 09:35
    assertEquals(l.tickOnce(clock.now()), 1)

    val occ = fixtures.uow
      .transaction(_.occurrences.listBySchedule(created.scheduleId))
      .find(_.scheduledFor == monday.plus(Duration.ofHours(1)))
      .get
    assertEquals(occ.status, OccurrenceStatus.Due)
    assertEquals(occ.state.reminderSeq, 3, "the 25-min-stale cadence collapsed to the elapsed seq")
    assertEquals(occ.state.epoch, 2)

    val rows = outboxRows()
    val stale = rows.filter(_.sendKey.startsWith(s"occ:${occ.id}:e1:"))
    assertEquals(stale.map(_.status), List("cancelled"), "the queued s1 row was retired without a send")
    val (_, digest) = digestOf(rows)
    assertEquals(digest.items, List(DigestItem(occ.id, 2)), "exactly one digest item, carrying the new epoch")

    val adapter = newAdapter()
    assertEquals(dispatcher(clock, adapter).dispatchOnce(), 1)
    assertEquals(adapter.sent.size, 1, "exactly one sent message")
    assert(
      adapter.sent.head._2.chunks.mkString("\n").contains("While I was away, 1 dose passed"),
      s"the digest was the send: ${adapter.sent.head._2.chunks}"
    )

  // DESIGN.md section 7.5: the dispatcher re-reads the items before rendering and skips the send when none remain.
  test("a digest whose items all resolved before the send is cancelled without a vendor call"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc)
    val channelId = fixtures.deliveryChannel(accountId, "fake", "dm:owner", monday)
    val occurrenceId = fixtures.occurrenceAt(
      created.scheduleId,
      monday.plus(Duration.ofHours(1)),
      "t0900x",
      OccurrenceStatus.Due
    )
    val bucket = monday.plus(Duration.ofHours(1)).getEpochSecond / ReminderLoop.DigestBucketSeconds
    PgUnitOfWork(dataSource, clock).transaction(
      _.outbox.enqueue(
        NewOutboxMessage(
          id = UUID.randomUUID(),
          sendKey = s"digest:$accountId:$bucket",
          op = OutboxOp.Send,
          kind = "digest",
          vendor = "fake",
          accountId = Some(accountId),
          channelId = Some(channelId),
          payload = LoopDispatch.toJson(
            LoopDispatch.Digest(accountId, bucket, List(DigestItem(occurrenceId, 0)))
          ),
          importance = "reminder",
          nextAttemptAt = clock.now()
        )
      )
    )
    // The user resolves the dose from Today before the dispatcher runs: the item is stale at render time.
    withConnection { conn =>
      given Connection = conn
      val now = clock.now()
      sql"""UPDATE dose_occurrences
            SET status = 'taken', taken_at = $now, effective_at = $now, next_action_at = NULL,
                epoch = epoch + 1, version = version + 1, updated_at = $now
            WHERE id = $occurrenceId""".execute()
    }

    val adapter = newAdapter()
    assertEquals(dispatcher(clock, adapter).dispatchOnce(), 1, "the row is claimed, then skipped")
    assertEquals(adapter.sent, Nil, "no vendor call")
    assertEquals(
      outboxRows().map(r => (r.sendKey, r.status)),
      List((s"digest:$accountId:$bucket", "cancelled"))
    )

  // M1.8: materialiser-first startup — the outage gap is materialised with past-deadline rows as unknown(outage).
  test("materialiser-first startup fills the 48 h+ outage gap with unknown(outage) rows and replays nothing"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc)
    fixtures.deliveryChannel(accountId, "fake", "dm:owner", monday)
    val outageBefore = unknownCount("outage")

    // Down for 72 h: the materialised window (48 h) lags a full day behind the restart.
    clock.advance(Duration.ofHours(72))
    assertEquals(loop(clock).tickOnce(clock.now()), 2, "Monday's and Tuesday's rows are claimed after the gap fill")

    val rows = occurrenceRows(created.scheduleId)
    val thursday = monday.plus(Duration.ofHours(73)) // Thursday 09:00
    assertEquals(
      rows.takeWhile(_.scheduledFor.isBefore(thursday)).map(r => (r.status, r.unknownReason)),
      List(("unknown", Some("outage")), ("unknown", Some("outage")), ("unknown", Some("outage"))),
      "Monday and Tuesday collapsed in the loop; Wednesday's gap row was inserted unknown(outage)"
    )
    assertEquals(
      rows.filter(_.scheduledFor == thursday).map(_.status),
      List("pending"),
      "the next live slot stays pending, not replayed"
    )
    assertEquals(unknownCount("outage"), outageBefore + 3, "loop and materialiser markings both count")
    assertEquals(outboxRows().count(r => r.kind == "reminder" || r.kind == "missed_notice"), 0, "no stale reminders")
    val (_, digest) = digestOf(outboxRows())
    assertEquals(
      digest.items.size,
      2,
      "the digest covers the two loop-marked rows; the materialiser's gap insert produces no dispatch"
    )

  // The metric distinguishes the two unknown reasons (ADR-012).
  test("dosecord_unknown_total{reason} counts undelivered when workers were healthy"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc)
    fixtures.deliveryChannel(accountId, "fake", "dm:owner", monday)
    val undeliveredBefore = unknownCount("undelivered")

    // A peer was healthy inside the due window, but no reminder was ever delivered.
    fixtures.uow.transaction(_.heartbeat.touch("healthy-peer", "worker", monday.plus(Duration.ofMinutes(65))))
    clock.advance(Duration.ofMinutes(185)) // 11:05, past the 11:00 miss deadline
    assertEquals(loop(clock).tickOnce(clock.now()), 1)

    assertEquals(occurrenceRows(created.scheduleId).head.unknownReason, Some("undelivered"))
    assertEquals(unknownCount("undelivered"), undeliveredBefore + 1)
end CatchUpDigestPgSuite
