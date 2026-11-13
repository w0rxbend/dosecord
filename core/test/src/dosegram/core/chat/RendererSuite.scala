package dosegram.core.chat

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

import dosegram.contracts.*

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

  private val days = List("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

  private def daysMessage: OutboundMessage =
    val cs = ChoiceSet(
      id = "wizard.days",
      prompt = Some(List(Node.Paragraph(List(Inline.Text("Which days?"))))),
      choices = days.map(d => Choice.callback(d, s"cb:$d")),
      minSelect = 1,
      maxSelect = 7
    )
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text("Set up your schedule.")))),
      blocks = List(Block.Choices(cs)),
      dedupeKey = "d",
      correlationId = "c"
    )

  private def digestMessage(items: Int): OutboundMessage =
    val blocks = (1 to items).toList.map { i =>
      Block.Choices(
        ChoiceSet(
          id = s"digest.item$i",
          prompt = Some(List(Node.Paragraph(List(Inline.Text(s"Dose $i"))))),
          choices = List(
            Choice.callback("Taken", s"cb:t$i", ChoiceStyle.Success),
            Choice.callback("Snooze 10m", s"cb:s$i"),
            Choice.callback("Skip", s"cb:k$i")
          )
        )
      )
    }
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text("While I was away.")))),
      blocks = blocks,
      importance = Importance.Bulk,
      dedupeKey = "d",
      correlationId = "c"
    )

  private def buttonCount(message: RenderedMessage): Int =
    message.controls.collect { case RenderedControls.Buttons(rows) => rows.flatten.size }.sum

  test("reminder on telegram: buttons rung, two rows, Taken success, Skip secondary"):
    val (ops, report) = Renderer.render(reminderMessage, chat, CapabilityProfiles.Telegram, "k1")
    assertEquals(report.rungs("reminder.main"), Renderer.Rung.Buttons)
    assertEquals(report.rungs("reminder.more"), Renderer.Rung.Buttons)
    val rows = sends(ops).head.message.controls.collect { case RenderedControls.Buttons(rows) => rows }
    assertEquals(
      rows.map(_.map(_.map(_.label))),
      List(List(List("Taken", "Snooze 10m", "Skip")), List(List("Snooze 30m", "Snooze 1h")))
    )
    val flat = rows.flatten.flatten
    assertEquals(flat.find(_.label == "Taken").map(_.style), Some(ChoiceStyle.Success))
    assertEquals(flat.find(_.label == "Skip").map(_.style), Some(ChoiceStyle.Secondary))

  test("reminder on the console: one numbered legend, numbered continuously across the two sets"):
    val (ops, report) = Renderer.render(reminderMessage, chat, CapabilityProfiles.Console, "k1")
    assertEquals(report.rungs("reminder.main"), Renderer.Rung.Numbered)
    val message = sends(ops).head.message
    val numbered = message.controls.collect { case n: RenderedControls.Numbered => n }
    assertEquals(numbered.flatMap(_.choices.map(_.index)), List(1, 2, 3, 4, 5))
    assert(message.chunks.head.contains("1) Taken 2) Snooze 10m 3) Skip"), message.chunks.head)
    assertEquals(message.choiceMap.map(_.index), List(1, 2, 3, 4, 5))

  test("a single-select of more than ten choices stays on the buttons rung up to the product limit"):
    val cs = ChoiceSet(
      id = "picker",
      choices = (1 to ControlLimits.MaxButtonsPerMessage).toList.map(i => Choice.callback(s"Zone $i", s"cb:$i"))
    )
    val msg = OutboundMessage(body = List(Node.Paragraph(List(Inline.Text("Pick a zone.")))), blocks = List(Block.Choices(cs)), dedupeKey = "d", correlationId = "c")
    val (ops, report) = Renderer.render(msg, chat, CapabilityProfiles.Telegram, "k1")
    assertEquals(report.rungs("picker"), Renderer.Rung.Buttons)
    assertEquals(buttonCount(sends(ops).head.message), ControlLimits.MaxButtonsPerMessage)
    assert(!report.paged)

  test("multi-select on the console: numbered legend with the typed-reply instruction"):
    val (ops, report) = Renderer.render(daysMessage, chat, CapabilityProfiles.Console, "k1")
    assertEquals(report.rungs("wizard.days"), Renderer.Rung.Numbered)
    val text = sends(ops).head.message.chunks.mkString("\n")
    assert(text.contains("1) Mon 2) Tue 3) Wed 4) Thu 5) Fri 6) Sat 7) Sun"), text)
    assert(text.contains(Renderer.MultiSelectInstruction), text)

  test("multi-select on telegram: a toggle keyboard, with no typed-reply instruction"):
    val (ops, report) = Renderer.render(daysMessage, chat, CapabilityProfiles.Telegram, "k1")
    assertEquals(report.rungs("wizard.days"), Renderer.Rung.ToggleKeyboard)
    val message = sends(ops).head.message
    assertEquals(buttonCount(message), 7)
    assert(!message.chunks.mkString.contains(Renderer.MultiSelectInstruction), message.chunks.mkString)

  test("a form is asked one field per message: the first question is in the text on every profile"):
    val form = Form(
      id = "medication.add",
      title = "Add medication",
      fields = List(Field("name", "Name", FieldType.Text), Field("dose", "Dose", FieldType.Text)),
      submit = "cb:submit"
    )
    val msg = OutboundMessage(body = Nil, blocks = List(Block.FormBlock(form)), dedupeKey = "d", correlationId = "c")
    CapabilityProfiles.all.foreach { (name, profile) =>
      val (ops, report) = Renderer.render(msg, chat, profile, "k1")
      assertEquals(report.rungs("medication.add"), Renderer.Rung.FormRunner, name)
      val message = sends(ops).head.message
      assertEquals(message.form.map(_.fields.map(_.key)), Some(List("name", "dose")), name)
      assert(message.chunks.head.contains("Add medication — question 1 of 2:"), message.chunks.head)
    }

  test("finalize ladder: edit in place, else replace and delete, else replace only"):
    val handle = MessageHandle("test", "dm:u1", "m42")
    val summary = List(Node.Paragraph(List(Inline.Text("Taken at 09:03."))))
    val keep = Some(Controls.postTaken(CallbackToken("dc:undo"), CallbackToken("dc:correct")))

    val (editOps, editReport) =
      Renderer.renderFinalize(handle, summary, keep, chat, CapabilityProfiles.Telegram, "fk1")
    assertEquals(editReport.rungs("finalize"), Renderer.Rung.FinalizeNativeEdit)
    val edit = editOps.collect { case e: VendorOp.Edit => e }.head
    assertEquals(edit.handle, handle)
    assertEquals(edit.message.choiceMap.map(_.label), List("Undo", "Correct"))
    assertEquals(editReport.rungs("post_taken.followup"), Renderer.Rung.Buttons)

    val windowed = CapabilityProfiles.Telegram.copy(editOwn = EditCapability.Window(java.time.Duration.ofMinutes(10)))
    val (replaceOps, replaceReport) =
      Renderer.renderFinalize(handle, summary, keep, chat, windowed, "fk1", RenderContext(withinEditWindow = false))
    assertEquals(replaceReport.rungs("finalize"), Renderer.Rung.FinalizeReplaceAndDelete)
    assertEquals(replaceOps.map(_.productPrefix), List("Send", "Delete"))

    val (consoleOps, consoleReport) =
      Renderer.renderFinalize(handle, summary, keep, chat, CapabilityProfiles.Console, "fk1")
    assertEquals(consoleReport.rungs("finalize"), Renderer.Rung.FinalizeReplaceOnly)
    assertEquals(sends(consoleOps).map(_.replyTo), List(Some(handle)))

  test("text over maxText splits at paragraph boundaries; blocks attach to the last chunk"):
    val paragraph = "the quick brown fox " * 30 // 600 chars
    val msg = OutboundMessage(
      body = List.fill(9)(Node.Paragraph(List(Inline.Text(paragraph)))),
      blocks = List(Block.Choices(Controls.postTaken(CallbackToken("dc:u"), CallbackToken("dc:c")))),
      dedupeKey = "d",
      correlationId = "c"
    )
    val (ops, report) = Renderer.render(msg, chat, CapabilityProfiles.Telegram, "k1")
    val send = sends(ops).head
    assertEquals(send.message.chunks.size, 2)
    assertEquals(report.splitInto, 2)
    assert(send.message.chunks.forall(c => c.nonEmpty && c.length <= CapabilityProfiles.Telegram.maxText))
    assert(send.message.controls.nonEmpty, "blocks attach to the last chunk")

  test("digest paging: a message carries at most the product limit of buttons; the console keeps one message"):
    val (ops, report) = Renderer.render(digestMessage(9), chat, CapabilityProfiles.Telegram, "k1")
    assert(report.paged)
    assertEquals(sends(ops).map(s => buttonCount(s.message)), List(24, 3))
    assertEquals(sends(ops).map(_.sendKey), List("k1:p1", "k1:p2"))
    val (fits, fitsReport) = Renderer.render(digestMessage(8), chat, CapabilityProfiles.Telegram, "k1")
    assert(!fitsReport.paged)
    assertEquals(fits.size, 1)
    val (consoleOps, consoleReport) = Renderer.render(digestMessage(9), chat, CapabilityProfiles.Console, "k1")
    assert(!consoleReport.paged)
    assertEquals(consoleOps.size, 1)

  test("discreet: previews suppressed and a leftover name wrapped in the vendor spoiler"):
    val named = reminderMessage.copy(discreet = true)
    val (ops, _) =
      Renderer.render(named, chat, CapabilityProfiles.Telegram, "k1", RenderContext(discreetNames = List("Vitamin D")))
    val send = sends(ops).head
    assert(send.message.suppressPreview)
    val text = send.message.chunks.mkString("\n")
    assert(text.contains("<tg-spoiler>Vitamin D</tg-spoiler>"), text)
    val (plainOps, _) =
      Renderer.render(named, chat, CapabilityProfiles.Console, "k1", RenderContext(discreetNames = List("Vitamin D")))
    val plainText = sends(plainOps).head.message.chunks.mkString("\n")
    assert(!plainText.contains("Vitamin D"), plainText)
    assert(plainText.contains("your dose"))

  test("markup escaping: telegram HTML escapes angle brackets"):
    val body = List(Node.Paragraph(List(Inline.Text("a <b> & *star*"))))
    val msg = OutboundMessage(body = body, dedupeKey = "d", correlationId = "c")
    val (tgOps, _) = Renderer.render(msg, chat, CapabilityProfiles.Telegram, "k1")
    assert(sends(tgOps).head.message.chunks.head.contains("a &lt;b&gt; &amp; *star*"))

  test("Time renders as HH:mm in the user's zone"):
    val at = Instant.parse("2026-09-20T06:03:00Z")
    val kyiv = ZoneId.of("Europe/Kyiv")
    val body = List(Node.Paragraph(List(Inline.Time(at, kyiv, TimeStyle.Time))))
    val msg = OutboundMessage(body = body, dedupeKey = "d", correlationId = "c")
    val (coOps, _) = Renderer.render(msg, chat, CapabilityProfiles.Console, "k1")
    assert(sends(coOps).head.message.chunks.head.contains("09:03"))

  private val weekPhoto =
    AttachmentRef(MediaKind.Photo, "asset-week-45", "image/png", "Week 45: 12 of 14 doses taken.")

  private def cardMessage: OutboundMessage =
    val row = ChoiceSet(
      id = "card.nav",
      choices = List(
        Choice.callback("Stats", "cb:stats"),
        Choice.openApp("Open app", AppRoute("today")),
        Choice.openUrl("Privacy notice", "https://example.org/privacy"),
        Choice.callback("Menu", "cb:menu")
      )
    )
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text("Your week.")))),
      blocks = List(Block.Choices(row)),
      attachment = Some(weekPhoto),
      dedupeKey = "d",
      correlationId = "c"
    )

  test("an attachment on a media profile travels beside the caption (media rung)"):
    val (ops, report) = Renderer.render(cardMessage, chat, CapabilityProfiles.Telegram, "k1")
    val message = sends(ops).head.message
    assertEquals(report.rungs("attachment"), Renderer.Rung.AttachmentMedia)
    assertEquals(message.attachment, Some(weekPhoto))
    assertEquals(message.chunks, List("Your week."))

  test("an attachment without a caption renders with no text on a media profile"):
    val bare = OutboundMessage(body = Nil, attachment = Some(weekPhoto), dedupeKey = "d", correlationId = "c")
    val (ops, _) = Renderer.render(bare, chat, CapabilityProfiles.Telegram, "k1")
    assertEquals(sends(ops).head.message.chunks, Nil)
    assertEquals(sends(ops).head.message.attachment, Some(weekPhoto))

  test("an attachment on the console becomes its text equivalent (text rung)"):
    val (ops, report) = Renderer.render(cardMessage, chat, CapabilityProfiles.Console, "k1")
    val message = sends(ops).head.message
    assertEquals(report.rungs("attachment"), Renderer.Rung.AttachmentText)
    assertEquals(message.attachment, None)
    assert(message.chunks.head.startsWith("[image: Week 45: 12 of 14 doses taken.]\n\nYour week."), message.chunks.head)

  test("a document on the console is named by its text equivalent"):
    val csv =
      AttachmentRef(MediaKind.Document, "asset-export", "text/csv", "Your export, 214 rows.", Some("export.csv"))
    val msg = OutboundMessage(body = Nil, attachment = Some(csv), dedupeKey = "d", correlationId = "c")
    val (ops, _) = Renderer.render(msg, chat, CapabilityProfiles.Console, "k1")
    assertEquals(sends(ops).head.message.chunks, List("[document: Your export, 214 rows.]"))

  test("link choices share a button row with callback choices and stay out of the choice map"):
    val (ops, report) = Renderer.render(cardMessage, chat, CapabilityProfiles.Telegram, "k1")
    val message = sends(ops).head.message
    assertEquals(report.rungs("card.nav"), Renderer.Rung.Buttons)
    val row = message.controls.collect { case RenderedControls.Buttons(rows) => rows.flatten }.flatten
    assertEquals(
      row.map(c => c.index -> c.action),
      List(
        1 -> ChoiceAction.Callback("cb:stats"),
        0 -> ChoiceAction.OpenApp(AppRoute("today")),
        0 -> ChoiceAction.OpenUrl("https://example.org/privacy"),
        2 -> ChoiceAction.Callback("cb:menu")
      )
    )
    assertEquals(message.choiceMap.map(e => e.index -> e.callback), List(1 -> "cb:stats", 2 -> "cb:menu"))

  test("link choices on the console are shown, not numbered: [app: route] and [link: url]"):
    val (ops, _) = Renderer.render(cardMessage, chat, CapabilityProfiles.Console, "k1")
    val message = sends(ops).head.message
    val legend = "1) Stats [app: today] Open app [link: https://example.org/privacy] Privacy notice 2) Menu"
    assert(message.chunks.head.contains(legend), message.chunks.head)
    assertEquals(message.choiceMap.map(_.index), List(1, 2))

  test("a real CallbackToken passes through rendering unchanged and still decodes"):
    val codec = CallbackCodec(CallbackKeys(CallbackKey(2, Array.fill(32)(9.toByte)), None))
    val subject = UUID.randomUUID()
    val token = codec.encode(CallbackMode.Direct, ActionRegistry.byName("dose.taken").get, subject, 0)
    val cs = ChoiceSet(id = "s", choices = List(Choice.callback("Taken", token.wire)))
    val msg = OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text("hi")))),
      blocks = List(Block.Choices(cs)),
      dedupeKey = "d",
      correlationId = "c"
    )
    val (ops, _) = Renderer.render(msg, chat, CapabilityProfiles.Console, "k1")
    val entry = sends(ops).head.message.choiceMap.head
    assertEquals(entry.callback, token.wire)
    assertEquals(codec.decode(entry.callback).map(_.subject), Right(subject))

  test("RenderReport names the rung used for every block"):
    val (_, report) = Renderer.render(reminderMessage, chat, CapabilityProfiles.Console, "k1")
    assertEquals(report.rungs.keySet, Set("reminder.main", "reminder.more"))
    assert(report.rungs.values.forall(_.nonEmpty))
end RendererSuite
