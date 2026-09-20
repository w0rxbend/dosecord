package dosecord.core.chat

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

import dosecord.contracts.*

/** Ladder behaviour per profile, asserted on the pure render output (suite B1's goldens pin the exact bytes; these
  * tests pin the semantics).
  */
class RendererSuite extends munit.FunSuite:

  private val chat = ChatRef("test", "dm:u1")

  private def reminderMessage: OutboundMessage =
    val codec = CallbackCodec(CallbackKeys(CallbackKey(0, Array.fill(32)(1.toByte)), None))
    val subject = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    def tok(action: String, value: Long = 0) =
      codec.encode(CallbackMode.Direct, ActionRegistry.byName(action).get, subject, value)
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text("Time for Vitamin D, 1000 IU.")))),
      blocks = Controls.reminder(
        tok("dose.taken"),
        tok("dose.skip"),
        List(10 -> tok("dose.snooze", 10), 30 -> tok("dose.snooze", 30), 60 -> tok("dose.snooze", 60))
      ),
      importance = Importance.Reminder,
      dedupeKey = "d",
      correlationId = "c"
    )

  private def sends(ops: List[VendorOp]): List[VendorOp.Send] =
    ops.collect { case s: VendorOp.Send => s }

  test("reminder layout: two rows, Taken success, Skip secondary (buttons rung)"):
    val (ops, report) = Renderer.render(reminderMessage, chat, CapabilityProfiles.Discord, "k1")
    assertEquals(report.rungs("reminder.main"), Renderer.Rung.Buttons)
    assertEquals(report.rungs("reminder.more"), Renderer.Rung.Buttons)
    val msg = sends(ops).head.message
    val rows = msg.controls.collect { case RenderedControls.Buttons(rows) => rows }
    assertEquals(
      rows.map(_.map(_.map(_.label))),
      List(List(List("Taken", "Snooze 10m", "Skip")), List(List("Snooze 30m", "Snooze 1h")))
    )
    val flat = rows.flatten.flatten
    assertEquals(flat.find(_.label == "Taken").map(_.style), Some(ChoiceStyle.Success))
    assertEquals(flat.find(_.label == "Skip").map(_.style), Some(ChoiceStyle.Secondary))

  test("reminder on telegram: buttons rung, 4-per-row packing keeps the two rows"):
    val (ops, report) = Renderer.render(reminderMessage, chat, CapabilityProfiles.Telegram, "k1")
    assertEquals(report.rungs("reminder.main"), Renderer.Rung.Buttons)
    val rows = sends(ops).head.message.controls.collect { case RenderedControls.Buttons(rows) => rows }
    assertEquals(
      rows.map(_.map(_.map(_.label))),
      List(List(List("Taken", "Snooze 10m", "Skip")), List(List("Snooze 30m", "Snooze 1h")))
    )

  test("reminder on the reaction tier: Taken, default Snooze, Skip as reactions, rest by reply"):
    val (ops, report) = Renderer.render(reminderMessage, chat, CapabilityProfiles.Zulip, "k1")
    assertEquals(report.rungs("reminder.main"), Renderer.Rung.NumberedReactions)
    // eventsPerMessageBudget - 1 = 3 reactions on zulip, so reminder.more falls to text only.
    assertEquals(report.rungs("reminder.more"), Renderer.Rung.Numbered)
    val msg = sends(ops).head.message
    val numbered = msg.controls.collect { case n: RenderedControls.Numbered => n }
    assertEquals(numbered.head.reactions, List("1️⃣", "2️⃣", "3️⃣"))
    assertEquals(msg.choiceMap.size, 5)

  test("reminder on the console: numbered list only"):
    val (ops, report) = Renderer.render(reminderMessage, chat, CapabilityProfiles.Console, "k1")
    assertEquals(report.rungs("reminder.main"), Renderer.Rung.Numbered)
    val numbered = sends(ops).head.message.controls.collect { case n: RenderedControls.Numbered => n }
    assert(numbered.forall(_.reactions.isEmpty))

  test("select layout: native multi-select on discord, numbered with instruction on text tiers"):
    val days = ChoiceSet(
      id = "wizard.days",
      choices = List("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").map(l => Choice(l, "cb:" + l)),
      layout = ChoiceLayout.Select,
      minSelect = 1,
      maxSelect = 7
    )
    val msg = OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text("Pick days.")))),
      blocks = List(Block.Choices(days)),
      dedupeKey = "d",
      correlationId = "c"
    )
    val (discordOps, discordReport) = Renderer.render(msg, chat, CapabilityProfiles.Discord, "k1")
    assertEquals(discordReport.rungs("wizard.days"), Renderer.Rung.Select)
    sends(discordOps).head.message.controls.head match
      case RenderedControls.SelectMenu(_, _, min, max) => assertEquals((min, max), (1, 7))
      case other                                       => fail(s"expected select, got $other")
    CapabilityProfiles.all.foreach { (name, profile) =>
      if name != "discord" then
        val (ops, report) = Renderer.render(msg, chat, profile, "k1")
        val text = sends(ops).head.message.chunks.mkString("\n")
        assert(
          text.contains("reply with the numbers, separated by spaces or commas"),
          s"$name: multi-select instruction missing"
        )
        assert(report.rungs("wizard.days") != Renderer.Rung.Select, s"$name")
    }

  test("ephemeral ladder: native with a live interaction, auto-delete, then notice"):
    val msg = reminderMessage.copy(visibility = Visibility.Ephemeral)
    val (_, liveReport) =
      Renderer.render(msg, chat, CapabilityProfiles.Discord, "k1", RenderContext(liveInteraction = true))
    assertEquals(liveReport.rungs("visibility"), Renderer.Rung.VisibilityNative)
    val (noLiveOps, noLiveReport) = Renderer.render(msg, chat, CapabilityProfiles.Discord, "k1")
    assertEquals(noLiveReport.rungs("visibility"), Renderer.Rung.VisibilityAutoDelete)
    assertEquals(sends(noLiveOps).head.message.deleteAfter.map(_.toSeconds), Some(60L))
    val (tgOps, tgReport) = Renderer.render(msg, chat, CapabilityProfiles.Telegram, "k1")
    assertEquals(tgReport.rungs("visibility"), Renderer.Rung.VisibilityAutoDelete)
    assertEquals(sends(tgOps).head.message.deleteAfter.map(_.toSeconds), Some(60L))
    val (consoleOps, consoleReport) = Renderer.render(msg, chat, CapabilityProfiles.Console, "k1")
    assertEquals(consoleReport.rungs("visibility"), Renderer.Rung.VisibilityNotice)
    assert(sends(consoleOps).head.message.chunks.last.contains("This message will not be kept."))

  test("ephemeral auto-delete respects the delete window"):
    val msg = reminderMessage.copy(visibility = Visibility.Ephemeral)
    val (_, report) =
      Renderer.render(msg, chat, CapabilityProfiles.Telegram, "k1", RenderContext(withinDeleteWindow = false))
    assertEquals(report.rungs("visibility"), Renderer.Rung.VisibilityNotice)

  test("form ladder: native modal only on a live interaction, else FormRunner first question"):
    val form = Form(
      id = "f1",
      title = "Add medication",
      fields = List(Field("name", "Name", FieldType.Text), Field("dose", "Dose", FieldType.Text, required = false)),
      submit = "cb:submit"
    )
    val msg = reminderMessage.copy(blocks = List(Block.FormBlock(form)))
    val (liveOps, liveReport) =
      Renderer.render(msg, chat, CapabilityProfiles.Discord, "k1", RenderContext(liveInteraction = true))
    assertEquals(liveReport.rungs("f1"), Renderer.Rung.FormModal)
    assert(liveOps.exists(_.isInstanceOf[VendorOp.OpenForm]))
    val (noLiveOps, noLiveReport) = Renderer.render(msg, chat, CapabilityProfiles.Discord, "k1")
    assertEquals(noLiveReport.rungs("f1"), Renderer.Rung.FormRunner)
    val send = sends(noLiveOps).head
    assert(send.message.chunks.mkString("\n").contains("Name"))
    assertEquals(send.message.form.map(_.fields.size), Some(2))
    val (_, tgReport) = Renderer.render(msg, chat, CapabilityProfiles.Telegram, "k1")
    assertEquals(tgReport.rungs("f1"), Renderer.Rung.FormRunner)

  test("finalize ladder: native edit / reaction reply-quote / replace"):
    val handle = MessageHandle("test", "dm:u1", "m1")
    val keep = Some(
      Controls.postTaken(
        CallbackToken("dc:undo"),
        CallbackToken("dc:correct")
      )
    )
    val summary = List(Node.Paragraph(List(Inline.Text("Recorded at 09:03."))))
    val (discordOps, discordReport) =
      Renderer.renderFinalize(handle, summary, keep, chat, CapabilityProfiles.Discord, "fk1")
    assertEquals(discordReport.rungs("finalize"), Renderer.Rung.FinalizeNativeEdit)
    assertEquals(discordOps.length, 1)
    assert(discordOps.head.isInstanceOf[VendorOp.Edit])
    val (zulipOps, zulipReport) =
      Renderer.renderFinalize(handle, summary, keep, chat, CapabilityProfiles.Zulip, "fk1")
    assertEquals(zulipReport.rungs("finalize"), Renderer.Rung.FinalizeReactionReplyQuote)
    assert(zulipOps.head.isInstanceOf[VendorOp.UnreactAll])
    val quote = sends(zulipOps).head
    assertEquals(quote.replyTo, Some(handle))
    // The kept ChoiceSet posts with its own choice_map row and reaction set.
    quote.message.controls.head match
      case RenderedControls.Numbered(_, _, reactions) => assertEquals(reactions, List("1️⃣", "2️⃣"))
      case other                                      => fail(s"expected numbered, got $other")
    assertEquals(quote.message.choiceMap.map(_.index), List(1, 2))
    val (consoleOps, consoleReport) =
      Renderer.renderFinalize(handle, summary, keep, chat, CapabilityProfiles.Console, "fk1")
    assertEquals(consoleReport.rungs("finalize"), Renderer.Rung.FinalizeReplaceOnly)
    assertEquals(consoleOps.length, 1)

  test("text over maxText splits at paragraph boundaries; blocks attach to the last chunk"):
    val paragraph = "the quick brown fox " * 30 // 600 chars
    val body = List.fill(9)(Node.Paragraph(List(Inline.Text(paragraph))))
    val msg = OutboundMessage(
      body = body,
      blocks = List(Block.Choices(Controls.postTaken(CallbackToken("dc:u"), CallbackToken("dc:c")))),
      dedupeKey = "d",
      correlationId = "c"
    )
    val (ops, report) = Renderer.render(msg, chat, CapabilityProfiles.Discord, "k1")
    val send = sends(ops).head
    assertEquals(send.message.chunks.size, 3)
    assertEquals(report.splitInto, 3)
    assert(send.message.chunks.forall(_.length <= 2000))
    assert(!send.message.chunks.exists(_.isEmpty))
    assert(send.message.controls.nonEmpty, "blocks attach to the last chunk")

  test("digest paging: discord pages at the 25-component cap; the reaction tier keeps one message"):
    val items = (1 to 9).toList.map { i =>
      Block.Choices(
        ChoiceSet(
          id = s"digest.item$i",
          prompt = Some(List(Node.Paragraph(List(Inline.Text(s"Dose $i"))))),
          choices = List(
            Choice("Taken", s"cb:t$i", ChoiceStyle.Success),
            Choice("Snooze 10m", s"cb:s$i"),
            Choice("Skip", s"cb:k$i")
          )
        )
      )
    }
    val msg = OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text("While I was away.")))),
      blocks = items,
      importance = Importance.Bulk,
      dedupeKey = "d",
      correlationId = "c"
    )
    val (discordOps, discordReport) = Renderer.render(msg, chat, CapabilityProfiles.Discord, "k1")
    assert(discordReport.paged)
    assertEquals(discordOps.size, 2)
    val page1Buttons = sends(discordOps).head.message.controls.collect { case RenderedControls.Buttons(rows) =>
      rows.flatten.size
    }.sum
    assertEquals(page1Buttons, 24)
    val (zulipOps, _) = Renderer.render(msg, chat, CapabilityProfiles.Zulip, "k1")
    assertEquals(zulipOps.size, 1)
    val reactions = sends(zulipOps).head.message.controls.collect { case n: RenderedControls.Numbered => n.reactions }
    assertEquals(reactions.flatten.size, 3, "one reaction set bounded by eventsPerMessageBudget - 1")

  test("discreet: previews suppressed and a leftover name wrapped in the vendor spoiler"):
    val named = reminderMessage.copy(discreet = true)
    val (ops, _) =
      Renderer.render(named, chat, CapabilityProfiles.Discord, "k1", RenderContext(discreetNames = List("Vitamin D")))
    val send = sends(ops).head
    assert(send.message.suppressPreview)
    val text = send.message.chunks.mkString("\n")
    assert(text.contains("||Vitamin D||"), text)
    val (plainOps, _) =
      Renderer.render(named, chat, CapabilityProfiles.Console, "k1", RenderContext(discreetNames = List("Vitamin D")))
    val plainText = sends(plainOps).head.message.chunks.mkString("\n")
    assert(!plainText.contains("Vitamin D"), plainText)
    assert(plainText.contains("your dose"))

  test("markup escaping: telegram HTML escapes angle brackets, discord escapes markdown"):
    val body = List(Node.Paragraph(List(Inline.Text("a <b> & *star*"))))
    val msg = OutboundMessage(body = body, dedupeKey = "d", correlationId = "c")
    val (tgOps, _) = Renderer.render(msg, chat, CapabilityProfiles.Telegram, "k1")
    assert(sends(tgOps).head.message.chunks.head.contains("a &lt;b&gt; &amp; *star*"))
    val (dcOps, _) = Renderer.render(msg, chat, CapabilityProfiles.Discord, "k1")
    assert(sends(dcOps).head.message.chunks.head.contains("a <b\\> & \\*star\\*"))

  test("Time renders as Discord's <t:...> and as HH:mm elsewhere"):
    val at = Instant.parse("2026-09-20T06:03:00Z")
    val kyiv = ZoneId.of("Europe/Kyiv")
    val body = List(Node.Paragraph(List(Inline.Time(at, kyiv, TimeStyle.Time))))
    val msg = OutboundMessage(body = body, dedupeKey = "d", correlationId = "c")
    val (dcOps, _) = Renderer.render(msg, chat, CapabilityProfiles.Discord, "k1")
    assert(sends(dcOps).head.message.chunks.head.contains(s"<t:${at.getEpochSecond}:t>"))
    val (coOps, _) = Renderer.render(msg, chat, CapabilityProfiles.Console, "k1")
    assert(sends(coOps).head.message.chunks.head.contains("09:03"))

  test("a real CallbackToken passes through rendering unchanged and still decodes"):
    val codec = CallbackCodec(CallbackKeys(CallbackKey(2, Array.fill(32)(9.toByte)), None))
    val subject = UUID.randomUUID()
    val token = codec.encode(CallbackMode.Direct, ActionRegistry.byName("dose.taken").get, subject, 0)
    val cs = ChoiceSet(id = "s", choices = List(Choice("Taken", token.wire)))
    val msg = OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text("hi")))),
      blocks = List(Block.Choices(cs)),
      dedupeKey = "d",
      correlationId = "c"
    )
    val (ops, _) = Renderer.render(msg, chat, CapabilityProfiles.Zulip, "k1")
    val entry = sends(ops).head.message.choiceMap.head
    assertEquals(entry.callback, token.wire)
    assertEquals(codec.decode(entry.callback).map(_.subject), Right(subject))

  test("RenderReport names the rung used for every block and for visibility"):
    val (_, report) = Renderer.render(reminderMessage, chat, CapabilityProfiles.Zulip, "k1")
    assert(report.rungs.keySet == Set("visibility", "reminder.main", "reminder.more"))
    assert(report.rungs.values.forall(_.nonEmpty))
end RendererSuite
