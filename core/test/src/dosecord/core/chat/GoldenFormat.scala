package dosecord.core.chat

import dosecord.contracts.*

/** Stable textual rendering of renderer output for golden files. Everything the dispatcher would act on is visible: ops
  * in order, chunks with lengths, controls with rows and styles, reaction sets, choice_map rows, and the report's
  * rungs.
  */
object GoldenFormat:

  def render(scenario: String, profile: String, ops: List[VendorOp], report: RenderReport): String =
    val b = StringBuilder()
    b ++= s"scenario: $scenario\nprofile: $profile\n"
    b ++= "report:\n"
    report.rungs.toList.sortBy(_._1).foreach((k, v) => b ++= s"  rung $k=$v\n")
    b ++= s"  splitInto=${report.splitInto} paged=${report.paged}\n"
    if report.warnings.nonEmpty then report.warnings.foreach(w => b ++= s"  warning: $w\n")
    else b ++= "  warnings: -\n"
    ops.zipWithIndex.foreach((op, i) => formatOp(b, i + 1, op))
    b.toString

  private def formatOp(b: StringBuilder, n: Int, op: VendorOp): Unit =
    op match
      case VendorOp.Send(chat, message, sendKey, replyTo) =>
        b ++= s"op $n: SEND chat=${chat.vendor}:${chat.chatId} sendKey=$sendKey replyTo=${replyTo.map(formatHandle).getOrElse("-")}\n"
        formatMessage(b, message)
      case VendorOp.Edit(handle, message) =>
        b ++= s"op $n: EDIT ${formatHandle(handle)}\n"
        formatMessage(b, message)
      case VendorOp.Delete(handle) =>
        b ++= s"op $n: DELETE ${formatHandle(handle)}\n"
      case VendorOp.React(handle, emoji, on, txnKey) =>
        b ++= s"op $n: REACT ${formatHandle(handle)} $emoji on=$on txn=$txnKey\n"
      case VendorOp.UnreactAll(handle) =>
        b ++= s"op $n: UNREACT_ALL ${formatHandle(handle)}\n"
      case VendorOp.OpenForm(form) =>
        b ++= s"op $n: OPEN_FORM ${form.id} title=${form.title} submit=${form.submit}\n"
        form.fields.foreach(f => b ++= s"  field ${f.key}: ${f.label} (${f.tpe}) required=${f.required}\n")
      case VendorOp.Ack(toast) =>
        b ++= s"op $n: ACK toast=${toast.getOrElse("-")}\n"
      case VendorOp.Typing(chat) =>
        b ++= s"op $n: TYPING ${chat.vendor}:${chat.chatId}\n"
      case VendorOp.RegisterCommands(specs) =>
        b ++= s"op $n: REGISTER_COMMANDS ${specs.map(_.name).mkString(",")}\n"

  private def formatHandle(h: MessageHandle): String =
    s"${h.vendor}:${h.chatId}:${h.messageId}@r${h.revision}"

  private def formatMessage(b: StringBuilder, m: RenderedMessage): Unit =
    b ++= s"  flags ephemeral=${m.ephemeral} silent=${m.silent} suppressPreview=${m.suppressPreview} deleteAfter=${m.deleteAfter.getOrElse("-")}\n"
    m.chunks.zipWithIndex.foreach { (chunk, i) =>
      b ++= s"  chunk ${i + 1} (${chunk.length} chars):\n"
      b ++= "  ---\n"
      chunk.linesIterator.foreach(line => b ++= s"  $line\n")
      b ++= "  ---\n"
    }
    m.controls.zipWithIndex.foreach((c, i) => formatControls(b, i, c))
    m.form.foreach { f =>
      b ++= s"  form ${f.id} (${f.fields.size} fields, via FormRunner)\n"
    }
    if m.choiceMap.nonEmpty then
      b ++= "  choiceMap:\n"
      m.choiceMap.foreach(e =>
        b ++= s"    ${e.setId} ${e.index} ${e.label} emoji=${e.emoji.getOrElse("-")} -> ${e.callback}\n"
      )

  private def formatControls(b: StringBuilder, i: Int, c: RenderedControls): Unit =
    c match
      case RenderedControls.NoControls =>
        b ++= s"  controls[$i]: none\n"
      case RenderedControls.Buttons(rows) =>
        b ++= s"  controls[$i] buttons:\n"
        rows.zipWithIndex.foreach { (row, r) =>
          val cells = row.map(formatChoice).mkString(" ")
          b ++= s"    row ${r + 1}: $cells\n"
        }
      case RenderedControls.SelectMenu(id, choices, minSelect, maxSelect) =>
        b ++= s"  controls[$i] select id=$id min=$minSelect max=$maxSelect:\n"
        choices.foreach(ch => b ++= s"    ${formatChoice(ch)}\n")
      case RenderedControls.Numbered(id, choices, reactions) =>
        b ++= s"  controls[$i] numbered id=$id reactions=${reactions.mkString(",")}\n"
        choices.foreach(ch => b ++= s"    ${formatChoice(ch)}\n")

  private def formatChoice(c: RenderedChoice): String =
    s"[${c.index}] ${c.label} -> ${c.callback} (${c.style})${c.emoji.map(e => s" emoji=$e").getOrElse("")}"
end GoldenFormat
