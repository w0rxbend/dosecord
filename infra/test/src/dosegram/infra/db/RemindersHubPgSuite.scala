package dosegram.infra.db

import dosegram.contracts.*
import dosegram.core.application.capture.CaptureParts
import dosegram.core.application.reminders.ReminderSettings
import dosegram.core.application.reminders.RemindersHandler
import dosegram.core.chat.*
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptPresets
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.IdSource
import dosegram.core.ports.StatusCards

import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import java.util.random.RandomGenerator

/** `/reminders` on Testcontainers Postgres 18 (ADR-023): the hub, the preset catalogue with its preview, the day
  * profile edited by command, and the pause of every prompt.
  */
class RemindersHubPgSuite extends PgSuite:

  // Monday 16 November 2026, 08:00 in Kyiv.
  private val start = Instant.parse("2026-11-16T08:00:00+02:00")
  private lazy val fixtures = Fixtures(dataSource)
  private val ids = IdSource.uuidV7(RandomGenerator.getDefault)
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  private final class Rig(consents: ConsentPurpose*):
    val clock = MutableClock(start)
    val account: UUID = fixtures.account("Europe/Kyiv")
    if consents.nonEmpty then fixtures.grant(account, consents*)
    val uow = PgUnitOfWork(dataSource, clock)
    val settings = ReminderSettings(clock, ids)
    val handler = RemindersHandler(
      codec,
      clock,
      settings,
      CaptureParts.over(uow, clock, ids, StatusCards.textOnly),
      inner = (_, _, _) => Reply(toast = Some("inner"))
    )
    private val principal = Principal(IdentityId(UUID.randomUUID()), Some(AccountId(account)), linked = true)
    private val source = MessageHandle("fake", "chat", "m1")
    private var seq = 0

    private def event(body: Inbound): InboundEvent =
      seq += 1
      InboundEvent(
        EventId(UUID.randomUUID()),
        "fake",
        s"hub-$account-$seq",
        clock.now(),
        actor = PlatformIdentity("fake", "owner", None),
        chat = ChatRef("fake", "chat"),
        principal = None,
        body = body
      )

    def command(text: String): Reply =
      val name = text.drop(1).takeWhile(!_.isWhitespace)
      uow.transaction(handler.handle(event(Inbound.CommandInvoked(name, Map.empty, text)), principal, _))

    def tap(action: String, value: Long): Reply =
      val token = codec.encode(CallbackMode.Direct, ActionRegistry.byName(action).get, account, value).wire
      val ref = CallbackRef(ActionRegistry.byName(action).get.id, account, value, slot = false, raw = token)
      uow.transaction(handler.handle(event(Inbound.InteractionSubmitted(ref, Some(source))), principal, _))

  private def text(reply: Reply): String =
    (reply.replace.toList ++ reply.followUps).flatMap(_.body).map(_.toString).mkString("\n")

  private def labels(reply: Reply): List[String] =
    (reply.replace.toList ++ reply.followUps).flatMap(_.blocks).flatMap:
      case Block.Choices(set) => set.choices.map(_.label)
      case _                  => Nil

  test("the hub lists every prompt kind as off, the medication line and the day, with a button per kind"):
    val rig = Rig()
    val hub = rig.command("/reminders")
    val shown = text(hub)
    List("Water: off", "Mood: off", "Bedtime: off", "Morning: off", "Medication: no schedules", "Mon to Fri 09:00-18:00",
      "22:00-07:00").foreach(line => assert(shown.contains(line), s"'$line' in $shown"))
    assertEquals(labels(hub).take(4), List("Water", "Mood", "Bedtime", "Morning"))

  test("a preset is previewed before it is saved, then listed with its name and next time"):
    val rig = Rig(ConsentPurpose.Hydration)
    val choose = rig.tap("reminders.kind", PromptKind.Water.ordinal)
    assert(labels(choose).contains("Work hours, every 30 min"), labels(choose))
    val preview = rig.tap("reminders.preview", PromptPresets.indexOf(PromptPresets.WaterWorkHours))
    assert(text(preview).contains("Next: Mon 09:30, Mon 10:00, Mon 10:30, Mon 11:00, Mon 11:30."), text(preview))
    assertEquals(rig.uow.transaction(_.reminders.prompts.schedules(rig.account)), Nil, "nothing is saved by a preview")
    val applied = rig.tap("reminders.apply", PromptPresets.indexOf(PromptPresets.WaterWorkHours))
    assert(text(applied).contains("Work hours, every 30 min is on."), text(applied))
    assert(text(applied).contains("Water: Work hours, every 30 min, next Mon 09:30"), text(applied))

  test("without the domain's consent no plan is stored"):
    val rig = Rig()
    val refused = rig.tap("reminders.apply", PromptPresets.indexOf(PromptPresets.WaterWorkHours))
    assertEquals(refused.toast, Some("Turn the domain on first: use its command once, for example /water or /mood."))
    assertEquals(rig.uow.transaction(_.reminders.prompts.schedules(rig.account)), Nil)

  test("changing the work hours moves the preset that uses them"):
    val rig = Rig(ConsentPurpose.Hydration)
    rig.tap("reminders.apply", PromptPresets.indexOf(PromptPresets.WaterWorkHours))
    val saved = rig.command("/reminders work mon-thu 10:00-19:00")
    assert(text(saved).contains("Work: Mon to Thu 10:00-19:00"), text(saved))
    assert(text(rig.command("/reminders")).contains("next Mon 10:30"))
    assertEquals(rig.command("/reminders work mon-fri 18:00-09:00").toast, Some("Work hours must start before they end, on at least one day."))

  test("the anchors and the quiet hours are edited by command"):
    val rig = Rig()
    rig.command("/reminders wake 06:30")
    rig.command("/reminders quiet 23:00-06:00")
    val profile = rig.uow.transaction(rig.settings.profile(_, rig.account))
    assertEquals(profile.wake, LocalTime.of(6, 30))
    assertEquals(profile.quiet.start.value, "23:00")
    assertEquals(rig.command("/reminders lunch soon").toast.isDefined, true, "not a time")

  test("pause all for an hour, then resume; medication reminders are named as not affected"):
    val rig = Rig()
    val paused = rig.tap("reminders.pause_all", 1)
    assert(text(paused).contains("paused until Mon 09:00"), text(paused))
    assert(text(paused).contains("Medication reminders are not affected."))
    assertEquals(labels(paused).contains("Resume"), true)
    val resumed = rig.command("/reminders resume")
    assert(!text(resumed).contains("paused until"), text(resumed))

  test("a custom water interval is a command"):
    val rig = Rig(ConsentPurpose.Hydration)
    val applied = rig.command("/reminders water every 45 09:00-17:00")
    assert(text(applied).contains("Custom interval is on."), text(applied))
    assert(text(applied).contains("next Mon 09:45"), text(applied))
    assertEquals(rig.command("/reminders water every 10 09:00-17:00").toast.isDefined, true, "below the minimum")

  test("another account's hub button is a stale button, and other input goes on"):
    val rig = Rig()
    val other = Rig()
    val token = codec.encode(CallbackMode.Direct, ActionRegistry.byName("reminders.hub").get, other.account, 0).wire
    val ref = CallbackRef(ActionRegistry.byName("reminders.hub").get.id, other.account, 0, slot = false, raw = token)
    val principal = Principal(IdentityId(UUID.randomUUID()), Some(AccountId(rig.account)), linked = true)
    val event = InboundEvent(
      EventId(UUID.randomUUID()),
      "fake",
      "foreign",
      start,
      actor = PlatformIdentity("fake", "owner", None),
      chat = ChatRef("fake", "chat"),
      principal = None,
      body = Inbound.InteractionSubmitted(ref, None)
    )
    assertEquals(rig.uow.transaction(rig.handler.handle(event, principal, _)).toast, Some("This button is no longer valid."))
    assertEquals(rig.command("/water").toast, Some("inner"))
