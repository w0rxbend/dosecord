package dosegram.infra.db

import dosegram.contracts.*
import dosegram.core.application.Application
import dosegram.core.application.exports.ExportChat
import dosegram.core.application.exports.ExportMessages
import dosegram.core.application.exports.ExportOrder
import dosegram.core.application.exports.ExportWorker
import dosegram.core.application.exports.RecordSummaries
import dosegram.core.application.insights.RollupService
import dosegram.core.chat.*
import dosegram.core.domain.copy.ExportCopy
import dosegram.core.domain.copy.ForbiddenPhrases
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.MedicationStats
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupMetrics
import dosegram.core.ports.ExportStatus
import dosegram.core.ports.IdSource
import dosegram.core.ports.RecordSummaryRenderer
import dosegram.core.ports.SummaryBlock

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.random.RandomGenerator
import scala.language.implicitConversions

/** `/export` on Testcontainers Postgres 18 (BOT_UX section 20.2, ROADMAP G3.10 and G10.4): the tap in the chat is the
  * confirmation; the worker makes the file and tells the chat; the file goes into the chat only after both taps of its
  * warning, which record the per-export consent; a button of another account's export is stale; the record summary
  * holds the figures of the statistics and no forbidden phrase.
  */
class ExportChatPgSuite extends PgSuite:

  private val now = Instant.parse("2026-11-12T18:00:00Z")
  private val zone = ZoneId.of("Europe/Kyiv")
  private val ids = IdSource.uuidV7(RandomGenerator.getDefault)
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  private val messages = ExportMessages(codec)

  private final class Rig(val account: UUID):
    val clock = MutableClock(now)
    val uow = PgUnitOfWork(dataSource, clock)
    val requests = Application.exportRequests(clock, codec)
    val handler = ExportChat(clock, requests, messages, inner = (_, _, _) => Reply(toast = Some("inner")))
    val summaries = RecordSummaries(RollupService(uow, clock))
    val worker = ExportWorker(uow, clock, ids, summaries, (_ => Right("%PDF".getBytes)): RecordSummaryRenderer,
      messages, _ => ())
    private val principal = Principal(IdentityId(UUID.randomUUID()), Some(AccountId(account)), linked = true)
    private val source = MessageHandle("fake", "chat", "m1")
    private var seq = 0

    private def event(body: Inbound): InboundEvent =
      seq += 1
      InboundEvent(
        EventId(UUID.randomUUID()),
        "fake",
        s"export-$account-$seq",
        clock.now(),
        actor = PlatformIdentity("fake", "owner", None),
        chat = ChatRef("fake", "chat"),
        principal = None,
        body = body
      )

    def command(): Reply =
      uow.transaction(handler.handle(event(Inbound.CommandInvoked("export", Map.empty, "/export")), principal, _))

    def tap(action: String, subject: UUID, value: Long): Reply =
      val entry = ActionRegistry.byName(action).get
      val ref = CallbackRef(entry.id, subject, value, slot = false, raw = codec.encode(CallbackMode.Direct, entry, subject, value).wire)
      uow.transaction(handler.handle(event(Inbound.InteractionSubmitted(ref, Some(source))), principal, _))

    def jobs: List[dosegram.core.ports.ExportJob] = uow.transaction(_.exports.recent(account, 10))

    /** The worker takes the queue of every account in the shared database: it runs until the queue is empty. */
    def drain(): Int = Iterator.continually(worker.runOnce()).takeWhile(identity).size

  private def seeded(): ExportFixture = ExportFixture(dataSource, now, zone).seed()

  private def text(reply: Reply): String =
    (reply.replace.toList ++ reply.followUps).flatMap(_.body).map(_.toString).mkString("\n")

  private def labels(reply: Reply): List[String] =
    (reply.replace.toList ++ reply.followUps).flatMap(_.blocks).flatMap:
      case Block.Choices(set) => set.choices.map(_.label)
      case _                  => Nil

  private def outboxPayloads(account: UUID): List[String] =
    withConnection: conn =>
      given Connection = conn
      sql"""SELECT payload::text FROM outbox_messages WHERE account_id = $account AND kind = 'export'
            ORDER BY created_at""".query[String]()

  test("/export offers the full export and the record summary; nothing is made before a tap"):
    val rig = Rig(seeded().account)
    val card = rig.command()
    assert(text(card).contains(ExportCopy.confirm), text(card))
    assertEquals(labels(card), List("Export my data", "Record summary"))
    assertEquals(rig.jobs, Nil)

  test("the tap is the confirmation: the worker makes the archive and the chat hears that it is ready"):
    val rig = Rig(seeded().account)
    val preparing = rig.tap("export.start", rig.account, 0L)
    assert(text(preparing).contains(ExportCopy.preparing), text(preparing))
    assertEquals(rig.jobs.map(_.status), List(ExportStatus.Queued))
    assert(rig.drain() >= 1)
    val job = rig.jobs.head
    assertEquals(job.status, ExportStatus.Ready)
    assertEquals(job.expiresAt, Some(now.plus(Duration.ofMinutes(15))))
    val ready = outboxPayloads(rig.account).map(OutboundMessage.fromJson)
    assertEquals(ready.size, 1)
    assert(ready.head.silent, "BUX-EXP-03: one silent message")
    val buttons = ready.head.blocks.collect { case Block.Choices(set) => set.choices }.flatten
    assertEquals(buttons.map(_.label), List("Download in the app", "Send the file to this chat"))
    assertEquals(buttons.head.action, ChoiceAction.OpenApp(AppRoute("reports")))

  test("the file goes into the chat only after both taps of the warning, which record the consent with the export"):
    val rig = Rig(seeded().account)
    rig.tap("export.start", rig.account, 0L)
    rig.drain()
    val id = rig.jobs.head.id
    val warning = rig.tap("export.send_ask", id, 0L)
    assert(text(warning).contains("stores all your records in Telegram's cloud"), text(warning))
    assertEquals(labels(warning), List("I understand"))
    rig.clock.advance(Duration.ofMinutes(1))
    val second = rig.tap("export.send_agree", id, 0L)
    assertEquals(labels(second), List("Send the file"))
    val agreedMinute = rig.clock.now().getEpochSecond / 60
    rig.clock.advance(Duration.ofSeconds(20))
    val sent = rig.tap("export.send", id, agreedMinute)
    val document = sent.followUps.flatMap(_.attachment)
    assertEquals(document.map(_.kind), List(MediaKind.Document))
    assertEquals(document.map(_.assetKey), List(ExportMessages.assetKey(id)))
    assertEquals(document.map(_.mimeType), List("application/zip"))
    assert(document.head.altText.contains("Contains health records."))
    assertEquals(rig.jobs.head.status, ExportStatus.Downloaded)
    withConnection: conn =>
      given Connection = conn
      val consent = sql"""SELECT subject_ref FROM consent_events
                          WHERE account_id = ${rig.account} AND purpose = 'export_to_chat'""".query[UUID]()
      assertEquals(consent, List(id))

  test("after 15 minutes the file is gone and its buttons say so"):
    val rig = Rig(seeded().account)
    rig.tap("export.start", rig.account, 0L)
    rig.drain()
    val id = rig.jobs.head.id
    rig.clock.advance(Duration.ofMinutes(15))
    assert(rig.worker.sweep() >= 1)
    assert(text(rig.tap("export.send_ask", id, 0L)).contains(ExportCopy.gone))
    assertEquals(rig.uow.transaction(_.exports.fileForDelivery(id, rig.clock.now())), None)

  test("a button of another account's export is stale and does nothing"):
    val owner = Rig(seeded().account)
    owner.tap("export.start", owner.account, 0L)
    owner.drain()
    val id = owner.jobs.head.id
    val stranger = Rig(Fixtures(dataSource).account())
    assertEquals(stranger.tap("export.send", id, now.getEpochSecond / 60).toast, Some("This button is no longer valid."))
    assertEquals(stranger.tap("export.start", owner.account, 0L).toast, Some("This button is no longer valid."))
    assertEquals(owner.jobs.head.status, ExportStatus.Ready)

  test("a request from the app is made only after its confirming tap; Not now ends it"):
    val rig = Rig(seeded().account)
    val made = rig.uow.transaction(rig.requests.fromApp(_, rig.account, ExportOrder.FullArchive)).toOption.get
    val dropped = rig.uow.transaction(rig.requests.fromApp(_, rig.account, ExportOrder.FullArchive)).toOption.get
    rig.drain()
    assertEquals(rig.jobs.map(_.status).toSet, Set(ExportStatus.AwaitingConfirmation), "nothing is made before the tap")
    val confirmation = OutboundMessage.fromJson(outboxPayloads(rig.account).head)
    assert(confirmation.body.toString.contains("The app asked for an export"), confirmation.body.toString)
    assert(text(rig.tap("export.confirm", made.id, 0L)).contains(ExportCopy.preparing))
    assert(text(rig.tap("export.cancel", dropped.id, 0L)).contains(ExportCopy.cancelled))
    assertEquals(rig.tap("export.confirm", dropped.id, 0L).toast, Some("This button is no longer valid."))
    rig.drain()
    assertEquals(rig.uow.transaction(_.exports.find(rig.account, made.id)).map(_.status), Some(ExportStatus.Ready))
    assertEquals(rig.uow.transaction(_.exports.find(rig.account, dropped.id)).map(_.status), Some(ExportStatus.Expired))

  test("the fourth request within 24 hours is refused with the time of the next one"):
    val rig = Rig(seeded().account)
    (1 to 3).foreach(_ => rig.tap("export.start", rig.account, 0L))
    val refused = rig.tap("export.start", rig.account, 0L)
    assert(text(refused).contains("You can export 3 times in 24 hours. The next export is possible at 20:00 on 13 Nov."), text(refused))
    assertEquals(rig.jobs.size, 3)

  test("the record summary holds the dose figures of the statistics, names the medication and no forbidden phrase"):
    val fixture = seeded()
    val rig = Rig(fixture.account)
    val period = DateRange.endingOn(LocalDate.ofInstant(now, zone), 30)
    val document = rig.uow.transaction(rig.summaries.document(_, fixture.account, period, zone, now))
    val words = document.sections.flatMap(section =>
      section.heading :: section.blocks.flatMap:
        case SummaryBlock.Paragraph(text)            => List(text)
        case SummaryBlock.Subheading(text)           => List(text)
        case SummaryBlock.Table(header, rows, _) => header ++ rows.flatten
    )
    assertEquals(document.title, "Record summary")
    assertEquals(document.notice, "Recorded entries only. Not medical advice.")
    assert(words.exists(_.contains(fixture.medicationName)), words)
    words.foreach(line => assertEquals(ForbiddenPhrases.violations(line), Nil, line))
    assert(!words.exists(_.contains(fixture.account.toString)), "no account identifier (VA-DOC-01c)")
    val days = rig.uow.transaction(_.tracking.rollups.list(fixture.account, RollupDomain.Medication, period))
      .collect { case (date, RollupMetrics.Medication(day)) => date -> day }
    val total = MedicationStats.total(days.values)
    val taken = total.takenOnTime + total.takenLate
    val resolved = taken + total.skipped + total.missed
    assert(resolved > 0, "the fixture has resolved doses")
    assert(words.exists(_.startsWith(s"Taken: $taken of $resolved resolved doses")), words.filter(_.startsWith("Taken")))

  test("a record summary of a domain without its consent shows nothing of it"):
    val rig = Rig(Fixtures(dataSource).account())
    val period = DateRange.endingOn(LocalDate.ofInstant(now, zone), 30)
    val document = rig.uow.transaction(rig.summaries.document(_, rig.account, period, zone, now))
    val bodies = document.sections.drop(1).dropRight(1).map(_.blocks)
    assert(bodies.forall(_ == List(SummaryBlock.Paragraph(ExportCopy.notShownWithoutConsent))), bodies)

  test("the worker makes a record summary as a PDF named for its day"):
    val rig = Rig(seeded().account)
    rig.tap("export.start", rig.account, 90L)
    rig.drain()
    val job = rig.jobs.head
    assertEquals(job.status, ExportStatus.Ready)
    assertEquals(job.fileName, Some("dosegram-report-20261112.pdf"))
    assertEquals(job.period.map(_.days), Some(90))
end ExportChatPgSuite
