package dosegram.infra.db

import dosegram.contracts.*
import dosegram.core.application.capture.CaptureParts
import dosegram.core.application.medication.MedicationDepthChat
import dosegram.core.chat.*
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.inventory.RefillThreshold
import dosegram.core.ports.IdSource
import dosegram.core.ports.InventorySettings
import dosegram.core.ports.ScheduleStatus
import dosegram.core.ports.StatusCards
import dosegram.core.scheduling.MedicationDepthLoop
import dosegram.core.scheduling.ScheduleLifecycle

import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.language.implicitConversions

/** The refill notice, its buttons, "Pause for..." and the resume at its date against Postgres 18 (PRODUCT_SPEC
  * R-REG-5, R-REG-14, R-REG-15, AC-REG-5, AC-REG-9; BOT_UX section 10.5; ROADMAP G9.10, G9.12).
  */
class MedicationDepthPgSuite extends PgSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val ids = IdSource.uuidV7(SecureRandom())
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  /** A local time in Kyiv on Monday 2026-09-21 (UTC+3). */
  private def local(day: Int, hhmm: String): Instant = Instant.parse(f"2026-09-$day%02dT$hhmm:00+03:00")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE outbox_messages, inventory_events, medication_inventory, dose_actions, dose_occurrences, " +
            "schedule_revisions, medication_schedules, medications, delivery_channels, platform_identities, " +
            "consent_events, consent_state, domain_events, users CASCADE"
        )
      finally st.close()
    }

  private lazy val fixtures = Fixtures(dataSource)

  private final class Rig(onHand: BigDecimal):
    val clock = MutableClock(local(21, "05:00"))
    val errors = ListBuffer.empty[Throwable]
    val account: UUID = fixtures.medicationAccount("Europe/Kyiv")
    fixtures.deliveryChannel(account, "fake", "chat-1", clock.now())
    private val daily = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
    val schedule: UUID = fixtures.schedule(clock, account, "Iron", daily, kyiv, doseUnit = Some("tablet")).scheduleId
    val medication: UUID = fixtures.uow.transaction(_.schedules.get(schedule).get.medicationId)
    fixtures.uow.transaction(
      _.medicationDepth.inventory
        .save(InventorySettings(medication, account, onHand, 1, Some(RefillThreshold.Days(7)), "count-1"), None, clock.now())
    )
    val lifecycle = ScheduleLifecycle(fixtures.uow, clock)
    val loop = MedicationDepthLoop(fixtures.uow, clock, ids, codec, lifecycle, (_, e) => errors += e)
    val chat = MedicationDepthChat(
      codec,
      clock,
      lifecycle,
      CaptureParts.over(fixtures.uow, clock, ids, StatusCards.textOnly),
      inner = (_, _, _) => Reply.empty
    )

    def notices: List[String] =
      withConnection: conn =>
        given Connection = conn
        sql"""SELECT payload FROM outbox_messages WHERE account_id = $account AND kind = 'prompt'
              ORDER BY created_at, id""".query[String]()

    def tap(action: String, value: Long): Reply =
      val entry = ActionRegistry.byName(action).get
      val wire = codec.encode(CallbackMode.Direct, entry, medication, value).wire
      val event = InboundEvent(
        EventId(UUID.randomUUID()),
        "fake",
        UUID.randomUUID().toString,
        clock.now(),
        actor = PlatformIdentity("fake", "owner", None),
        chat = ChatRef("fake", "chat-1"),
        principal = None,
        body = Inbound.InteractionSubmitted(CallbackRef(entry.id, medication, value, slot = false, raw = wire), None)
      )
      val principal = Principal(IdentityId(UUID.randomUUID()), Some(AccountId(account)), linked = true)
      fixtures.uow.transaction(chat.handle(event, principal, _))

    def text(reply: Reply): String = (reply.replace.toList ++ reply.followUps).flatMap(_.body).mkString

  test("AC-REG-9: one notice at the first touchpoint once the count is below the threshold, none again for 3 days"):
    val rig = Rig(onHand = 3)
    rig.loop.tick() // 05:00: before the wake time of the day profile
    assertEquals(rig.notices, Nil)
    rig.clock.at = local(21, "07:30")
    rig.loop.tick()
    rig.clock.at = local(21, "08:00")
    rig.loop.tick()
    assertEquals(rig.notices.size, 1)
    val notice = rig.notices.head
    assert(notice.contains("About 3 tablets of Iron left by your count."), notice)
    assert(notice.contains("covers until about Wed 23 Sep"), notice)
    assert(notice.contains("\"silent\": true"), notice)
    assert(notice.contains("Show card") && notice.contains("Stop for this pack"), notice)
    rig.clock.at = local(23, "23:00") // inside quiet hours, and less than 3 days on
    rig.loop.tick()
    assertEquals(rig.notices.size, 1)
    rig.clock.at = local(24, "07:31")
    rig.loop.tick()
    assertEquals(rig.notices.size, 2, "again after 3 days")
    assertEquals(rig.errors.toList, Nil)

  test("the refill notice is written in the account's language (ROADMAP G2.22)"):
    val rig = Rig(onHand = 3)
    fixtures.uow.transaction: tx =>
      val settings = tx.accounts.settingsOf(rig.account).get
      tx.accounts.saveSettings(rig.account, settings.copy(locale = Some("uk")), rig.clock.now())
    rig.clock.at = local(21, "07:30")
    rig.loop.tick()
    val notice = rig.notices.head
    assert(notice.contains("Iron: за вашим підрахунком лишилося близько 3 tablets."), notice)
    assert(notice.contains("Показати картку") && notice.contains("Досить для цієї пачки"), notice)
    assertEquals(rig.errors.toList, Nil)

  test("Stop for this pack ends the notices; a refill with the offered quantity starts them again later"):
    val rig = Rig(onHand = 3)
    fixtures.uow.transaction(_.medicationDepth.inventory.refill(rig.account, rig.medication, 1, "first", rig.clock.now()))
    rig.clock.at = local(21, "07:30")
    assert(rig.text(rig.tap("refill.stop", 0)).contains("No more refill notices for this pack"))
    rig.loop.tick()
    assertEquals(rig.notices, Nil)
    val refilled = rig.text(rig.tap("refill.add", 30000))
    assert(refilled.contains("Iron: 34 tablets by your count."), refilled)
    rig.loop.tick()
    assertEquals(rig.notices, Nil, "34 days is above the 7-day threshold")

  test("Show card answers with the card's text equivalent when no renderer is wired"):
    val rig = Rig(onHand = 3)
    rig.clock.at = local(21, "07:30")
    val card = rig.text(rig.tap("refill.card", 0))
    assert(card.contains("Iron: 3 tablets left by your count, at or below your threshold of 7 days."), card)

  test("AC-REG-5: Pause for 3 days pauses now and resumes at local midnight of the third day with one silent notice"):
    val rig = Rig(onHand = 50)
    rig.clock.at = local(21, "10:00")
    val paused = rig.text(rig.tap("med.pause_for", 3))
    assert(paused.contains("Paused Iron until Thu 24 Sep."), paused)
    assertEquals(fixtures.uow.transaction(_.schedules.get(rig.schedule).get.status), ScheduleStatus.Paused)
    rig.clock.at = local(23, "23:59")
    rig.loop.tick()
    assertEquals(fixtures.uow.transaction(_.schedules.get(rig.schedule).get.status), ScheduleStatus.Paused)
    rig.clock.at = local(24, "00:00")
    rig.loop.tick()
    rig.loop.tick()
    assertEquals(fixtures.uow.transaction(_.schedules.get(rig.schedule).get.status), ScheduleStatus.Active)
    val resumed = rig.notices.filter(_.contains("are back on"))
    assertEquals(resumed.size, 1)
    assert(resumed.head.contains("\"silent\": true"))
    // Nothing is backfilled: the next reminder is today's 09:00, after the resume.
    val next = fixtures.uow.transaction(_.occurrences.nextScheduledAfter(rig.schedule, rig.clock.now()))
    assertEquals(next, Some(local(24, "09:00")))
    assertEquals(rig.errors.toList, Nil)
end MedicationDepthPgSuite
