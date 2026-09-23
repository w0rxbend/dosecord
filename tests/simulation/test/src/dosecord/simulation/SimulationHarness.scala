package dosecord.simulation

import dosecord.contracts.ChatRef
import dosecord.contracts.EventId
import dosecord.contracts.Inbound
import dosecord.contracts.InboundEvent
import dosecord.contracts.MessageHandle
import dosecord.contracts.PlatformIdentity
import dosecord.core.application.Application
import dosecord.core.application.CommandRegistry
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.CallbackKey
import dosecord.core.chat.CallbackKeys
import dosecord.core.chat.ChatMediator
import dosecord.core.domain.Rule
import dosecord.core.ports.Wake
import dosecord.core.scheduling.CatalogueOutboxRenderer
import dosecord.core.scheduling.Materialiser
import dosecord.core.scheduling.OutboxDispatcher
import dosecord.core.scheduling.ReminderLoop
import dosecord.core.scheduling.ScheduleLifecycle
import dosecord.infra.db.Fixtures
import dosecord.infra.db.MutableClock
import dosecord.infra.db.PgUnitOfWork
import dosecord.infra.db.RowMapper
import dosecord.infra.db.sql

import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource
import scala.collection.mutable
import scala.language.implicitConversions

/** One seeded persona: a linked console identity, its healthy primary delivery channel, and its schedule. */
final case class PersonaRuntime(
    personaId: String,
    accountId: UUID,
    scheduleId: UUID,
    medicationName: String,
    chatId: String,
    identity: PlatformIdentity,
    console: PersonaConsole
)

/** The M1.11 simulation harness: the REAL ReminderLoop, Materialiser, OutboxDispatcher, ChatMediator and M1.10
  * Application handler composition, all on the one injected virtual clock, against Testcontainers Postgres. The
  * harness never decides anything itself: user behaviour arrives as console-style wire events (numbered quoted
  * replies, `/commands`, plain text) through the real mediator, and the only direct product call is the same-day
  * schedule edit, whose UI is M3.2 (the M1.5 `ScheduleLifecycle.edit` data side the M1.9 update path already uses).
  *
  * Time stepping is event-driven: the clock jumps to the next occurrence `next_action_at` or the next scripted user
  * action, whichever is earlier; the 6 h outage is simply a window with no ticks (the worker is down), which is what
  * makes the day-12 catch-up exercise M1.8's materialiser-first startup, `unknown(outage)` rows and one digest per
  * account.
  */
final class SimulationHarness(dataSource: DataSource, val clock: MutableClock, transcript: SimTranscript)(using ox.Ox):

  val uow: PgUnitOfWork = PgUnitOfWork(dataSource, clock)
  val codec: CallbackCodec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  val fixtures: Fixtures = Fixtures(dataSource)

  private val personas = mutable.LinkedHashMap.empty[String, PersonaRuntime]
  private var eventSeq = 0
  private var ticking = true

  private lazy val mux = ConsoleMux(personas.values.map(_.console).toList, transcript)
  private lazy val mediator = ChatMediator(
    uow,
    Map("console" -> mux),
    codec,
    Application.handler(uow, Map("console" -> mux), codec, clock),
    clock,
    CommandRegistry.byName
  )
  private lazy val loop =
    ReminderLoop(uow, Materialiser(uow, clock), Wake.polling, clock, instance = "sim-worker")
  private lazy val dispatcher =
    val rng = new scala.util.Random(0x5EEDL) // seeded: the M1.7 backoff jitter draw is injectable
    OutboxDispatcher(
      uow,
      Map("console" -> mux),
      CatalogueOutboxRenderer(uow, codec, clock),
      clock,
      random = () => rng.nextDouble()
    )

  /** Forces the lazy engine graph after seeding. */
  def startEngine(): Unit = (mediator, loop, dispatcher)

  // ---------- Seeding ----------

  /** A linked console identity + healthy primary channel with deterministic ids, plus the regimen through the real
    * ScheduleLifecycle (the M1.5 Fixtures builder).
    */
  def seedPersona(
      personaId: String,
      zone: String,
      medicationName: String,
      rule: Rule,
      doseAmount: Option[BigDecimal],
      doseUnit: Option[String],
      instructions: Option[String]
  ): PersonaRuntime =
    val accountId = detUuid(s"sim-account-$personaId")
    val identityId = detUuid(s"sim-identity-$personaId")
    val channelId = detUuid(s"sim-channel-$personaId")
    val chatId = s"dm:$personaId"
    val conn = dataSource.getConnection
    try
      given Connection = conn
      sql"INSERT INTO users (id, timezone, status) VALUES ($accountId, $zone, 'active')".execute()
      sql"""INSERT INTO platform_identities (id, user_id, vendor, vendor_user_id, dm_channel_id, linked_at)
            VALUES ($identityId, $accountId, 'console', $personaId, $chatId, ${clock.now()})""".execute()
      sql"""INSERT INTO delivery_channels (id, account_id, platform_identity_id, role, priority, state, updated_at)
            VALUES ($channelId, $accountId, $identityId, 'primary', 0, 'healthy', ${clock.now()})""".execute()
    finally conn.close()
    val outcome = fixtures.schedule(
      clock,
      accountId,
      medicationName,
      rule,
      ZoneId.of(zone),
      doseAmount = doseAmount,
      doseUnit = doseUnit,
      instructions = instructions
    )
    val console = PersonaConsole(personaId, personaId, clock)
    val runtime =
      PersonaRuntime(personaId, accountId, outcome.scheduleId, medicationName, chatId,
        PlatformIdentity("console", personaId), console)
    personas += personaId -> runtime
    runtime

  private def detUuid(key: String): UUID = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8))

  // ---------- User interactions (console wire events through the real mediator) ----------

  /** A quoted numbered reply `#<n> <index>` on the newest message matching `selector`, choosing the entry labelled
    * `label` — exactly what a console user types.
    */
  def tap(personaId: String, selector: Selector, label: String): Unit =
    val persona = personas(personaId)
    val message = transcript
      .messages(personaId)
      .reverse
      .find(selector.matches)
      .getOrElse:
        val recent = transcript
          .messages(personaId)
          .takeRight(5)
          .map(m => s"#${m.handle.messageId} [${m.sendKey.take(60)}] ${m.rendered.chunks.headOption.getOrElse("").take(60)}")
          .mkString("\n  ")
        throw new NoSuchElementException(s"$personaId: no message matching $selector; recent sends:\n  $recent")
    val entry = message.rendered.choiceMap
      .find(_.label == label)
      .orElse(message.rendered.choiceMap.find(_.label.contains(label)))
      .getOrElse(
        throw new NoSuchElementException(
          s"$personaId: message #${message.handle.messageId} has no choice '$label' " +
            s"(has: ${message.rendered.choiceMap.map(_.label)})"
        )
      )
    transcript.script(s"$personaId #${message.handle.messageId} ${entry.index} ($label)")
    push(persona, Inbound.MessageReceived(entry.index.toString, Some(message.handle), truncated = false))

  def command(personaId: String, raw: String): Unit =
    val persona = personas(personaId)
    transcript.script(s"$personaId $raw")
    push(persona, Inbound.CommandInvoked(raw.drop(1).takeWhile(c => !c.isWhitespace), Map.empty, raw))

  def sendText(personaId: String, body: String): Unit =
    val persona = personas(personaId)
    transcript.script(s"""$personaId "$body"""")
    push(persona, Inbound.MessageReceived(body, None, truncated = false))

  /** The same-day schedule edit: the M3.2 edit UI does not exist yet, so the harness calls the M1.5 data side the
    * M1.9 update path uses (recorded in the transcript as a script line).
    */
  def editSchedule(personaId: String, rule: Rule, note: String): Unit =
    val persona = personas(personaId)
    transcript.script(s"$personaId schedule edit: $note")
    ScheduleLifecycle(uow, clock).edit(persona.scheduleId, rule)

  private def push(persona: PersonaRuntime, body: Inbound): Unit =
    eventSeq += 1
    val vendorEventId = s"console:msg:sim:${persona.personaId}:$eventSeq"
    val event = InboundEvent(
      EventId(detUuid(vendorEventId)),
      "console",
      vendorEventId,
      clock.now(),
      Some(clock.now()),
      persona.identity,
      ChatRef("console", persona.chatId),
      body = body
    )
    mediator.push(event)
    // Ordering barrier: same vendor:chat key -> the keyed executor runs the sentinel after the event's whole
    // process() (including the post-commit delivery), so awaiting the sentinel's inbound row awaits the event.
    val ackId = s"$vendorEventId:ack"
    mediator.push(
      InboundEvent(
        EventId(detUuid(ackId)),
        "console",
        ackId,
        clock.now(),
        Some(clock.now()),
        persona.identity,
        ChatRef("console", persona.chatId),
        body = Inbound.ReactionChanged("sim", MessageHandle("console", persona.chatId, "0"), added = false)
      )
    )
    awaitProcessed(ackId)
    // The production dispatcher runs in its own fork; drain here so user-action finalizes dispatch promptly
    // instead of waiting for the next loop tick.
    drain()

  private def awaitProcessed(vendorEventId: String): Unit =
    val deadline = System.nanoTime() + 30_000_000_000L
    var done = false
    while !done && System.nanoTime() < deadline do
      done = uow.transaction(_.inboundEvents.find("console", vendorEventId)).nonEmpty
      if !done then Thread.sleep(5)
    if !done then throw new AssertionError(s"event $vendorEventId was not processed within 30 s")

  // ---------- The worker ----------

  /** One loop iteration (materialiser safety net + tick + heartbeat) followed by the dispatcher drain. */
  def tickOnce(): Unit =
    loop.tickOnce(clock.now())
    drain()

  private def drain(): Unit =
    var claimed = dispatchBatch()
    while claimed > 0 do claimed = dispatchBatch()

  private def dispatchBatch(): Int =
    transcript.beginBatch()
    val claimed = try dispatcher.dispatchOnce()
    finally transcript.endBatch()
    claimed

  private def nextDbAction(): Option[Instant] =
    val conn = dataSource.getConnection
    try
      given Connection = conn
      given RowMapper[Option[Instant]] =
        rs => Option(rs.getObject("m", classOf[java.time.OffsetDateTime])).map(_.toInstant)
      sql"""SELECT min(next_action_at) AS m FROM dose_occurrences
            WHERE status IN ('pending', 'due', 'snoozed') AND next_action_at IS NOT NULL"""
        .queryOne[Option[Instant]]()
        .flatten
    finally conn.close()

  /** The wake the production poll would raise for the materialiser safety net (DESIGN.md section 7.2): the least
    * `materialized_through` minus `(SafetyNetLagTicks - 1) * pollInterval` — the claim's strict `< now + lag`
    * predicate fires one poll step earlier than the lag itself, so an event-driven harness must wake inside that
    * window (the production 10 s poll wakes at `mt - 30 s` for a 40 s lag). Without it an event-driven harness
    * deadlocks: the 48 h horizon boundary can fall between two occurrence actions (e.g. overnight) and the next
    * morning's rows are never materialised.
    */
  private def nextSafetyNet(): Option[Instant] =
    val conn = dataSource.getConnection
    try
      given Connection = conn
      given RowMapper[Option[Instant]] =
        rs => Option(rs.getObject("m", classOf[java.time.OffsetDateTime])).map(_.toInstant)
      sql"SELECT min(materialized_through) AS m FROM medication_schedules WHERE status = 'active'"
        .queryOne[Option[Instant]]()
        .flatten
        .map(_.minus(ReminderLoop.PollInterval.multipliedBy((ReminderLoop.SafetyNetLagTicks - 1).toLong)))
    finally conn.close()

  // ---------- The 30-day event-driven run ----------

  /** Steps the virtual clock through the scenario: at every iteration the clock jumps to the earlier of the next
    * occurrence action and the next scripted user step; stale rows (the outage) are caught up at the current instant.
    */
  def run(script: List[Scripted], end: Instant): Unit =
    var remaining = script
    while true do
      val now = clock.now()
      if !now.isBefore(end) then
        tickOnce() // final settle
        return
      val scriptAt = remaining.headOption.map(_.at).filter(_.isBefore(end))
      val dbAt = if ticking then nextDbAction().filter(_.isBefore(end)) else None
      val safetyAt = if ticking then nextSafetyNet().filter(_.isBefore(end)) else None
      if List(dbAt, safetyAt).flatten.exists(!_.isAfter(now)) then tickOnce() // catch-up at the current instant
      else
        val next = List(scriptAt, dbAt, safetyAt).flatten.minOption.getOrElse(end)
        if !next.isBefore(end) then advanceTo(end)
        else
          advanceTo(next)
          val (due, later) = remaining.span(_.at == next)
          remaining = later
          due.foreach(_.steps.foreach(runStep))
          if ticking && (dbAt.contains(next) || safetyAt.contains(next)) then tickOnce()
  end run

  private def advanceTo(target: Instant): Unit =
    clock.advance(Duration.between(clock.now(), target))

  private def runStep(step: Step): Unit = step match
    case Step.Tap(persona, selector, label)     => tap(persona, selector, label)
    case Step.Command(persona, raw)             => command(persona, raw)
    case Step.Text(persona, body)               => sendText(persona, body)
    case Step.EditSchedule(persona, rule, note) => editSchedule(persona, rule, note)
    case Step.WorkerDown                        =>
      ticking = false
      transcript.script("6 h outage begins: the worker is not ticked")
    case Step.WorkerUp                          =>
      ticking = true
      transcript.script("outage ends: materialiser-first restart, catch-up tick")

  def personaIds: List[String] = personas.keys.toList
  def persona(personaId: String): PersonaRuntime = personas(personaId)
