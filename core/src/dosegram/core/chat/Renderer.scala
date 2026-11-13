package dosegram.core.chat

import dosegram.contracts.*

/** Inputs the pure renderer cannot read from the message itself (DESIGN.md section 4.3): whether the target message is
  * still inside the vendor's edit/delete windows, and the names a discreet message must not show.
  */
final case class RenderContext(
    withinEditWindow: Boolean = true,
    withinDeleteWindow: Boolean = true,
    discreetNames: List[String] = Nil
)

/** The pure renderer (ADR-005): `render`/`renderFinalize` lower an OutboundMessage into serialisable VendorOps through
  * the fixed degradation ladders of DESIGN.md section 4.3. First supported rung wins; the RenderReport names the rung
  * every block landed on. Golden-tested per profile (suite B1).
  */
object Renderer:

  object Rung:
    val Buttons = "choices.buttons"
    val ToggleKeyboard = "choices.toggleKeyboard"
    val Numbered = "choices.numbered"
    val FormRunner = "form.formRunner"
    val AttachmentMedia = "attachment.media"
    val AttachmentText = "attachment.text"
    val FinalizeNativeEdit = "finalize.nativeEdit"
    val FinalizeReplaceAndDelete = "finalize.replaceAndDelete"
    val FinalizeReplaceOnly = "finalize.replaceOnly"

  /** Fixed copy for multi-select on the numbered rung (ROADMAP M0.9). */
  val MultiSelectInstruction =
    "Select all that apply — reply with the numbers, separated by spaces or commas."

  private def noticePrefix(level: NoticeLevel): String =
    level match
      case NoticeLevel.Info    => "ℹ️ "
      case NoticeLevel.Success => "✅ "
      case NoticeLevel.Warning => "⚠️ "

  private def noticeNodes(notice: Notice): List[Node] =
    notice.text match
      case Node.Paragraph(inlines) :: rest => Node.Paragraph(Inline.Text(noticePrefix(notice.level)) +: inlines) +: rest
      case other => Node.Paragraph(List(Inline.Text(noticePrefix(notice.level).trim))) +: other

  private def editable(profile: CapabilityProfile, ctx: RenderContext): Boolean =
    profile.editOwn match
      case EditCapability.AnyAge    => true
      case EditCapability.Window(_) => ctx.withinEditWindow
      case EditCapability.NoEdit    => false

  private def deletable(profile: CapabilityProfile, ctx: RenderContext): Boolean =
    profile.deleteOwn match
      case DeleteCapability.AnyAge    => true
      case DeleteCapability.Window(_) => ctx.withinDeleteWindow
      case DeleteCapability.NoDelete  => false

  /** Buttons one message may carry: the profile's grid, capped by the product limit (ADR-015). */
  private def buttonBudget(profile: CapabilityProfile): Int =
    math.min(profile.maxChoicesPerRow * profile.maxRows, ControlLimits.MaxButtonsPerMessage)

  /** Numbers the callback choices of one set from `startIndex`; a link choice is not numbered (index 0). */
  private def numbered(cs: ChoiceSet, startIndex: Int): List[RenderedChoice] =
    val (_, rendered) = cs.choices.foldLeft((startIndex, Vector.empty[RenderedChoice])):
      case ((next, done), c) =>
        c.action match
          case _: ChoiceAction.Callback => (next + 1, done :+ RenderedChoice(next, c.label, c.action, c.style, c.emoji))
          case _                        => (next, done :+ RenderedChoice(0, c.label, c.action, c.style, c.emoji))
    rendered.toList

  /** Only a callback choice resolves from a typed number or label, so only it gets a choice_map row. */
  private def choiceMapEntries(setId: String, rendered: List[RenderedChoice]): List[ChoiceMapEntry] =
    rendered.collect:
      case RenderedChoice(index, label, ChoiceAction.Callback(token), _, emoji) =>
        ChoiceMapEntry(setId, index, label, emoji, token)

  /** One ChoiceSet through the ladder: the controls, the rung name, the nodes to append to the message text (prompt,
    * numbered legend, multi-select instruction), the choice_map rows and the button count for paging.
    */
  private final case class LadderResult(
      controls: RenderedControls,
      rung: String,
      extraNodes: List[Node],
      choiceMap: List[ChoiceMapEntry],
      buttons: Int
  )

  /** The text form of one choice (ADR-015 CON-02): a number to type for a callback, the target for a link. */
  private def legendEntry(choice: RenderedChoice): String =
    choice.action match
      case ChoiceAction.Callback(_)    => s"${choice.index}) ${choice.label}"
      case ChoiceAction.OpenApp(route) => s"[app: ${route.wire}] ${choice.label}"
      case ChoiceAction.OpenUrl(url)   => s"[link: $url] ${choice.label}"

  private def numberedExtras(cs: ChoiceSet, rendered: List[RenderedChoice]): List[Node] =
    val legend = Node.Paragraph(List(Inline.Text(rendered.map(legendEntry).mkString(" "))))
    val instruction =
      if cs.maxSelect > 1 then List(Node.Paragraph(List(Inline.Text(MultiSelectInstruction)))) else Nil
    legend +: instruction

  /** Ladder: buttons when the set fits the budget (a multi-select is then a keyboard its producer toggles in place by
    * editing the message) -> numbered legend answered by a typed number.
    */
  private def renderChoices(cs: ChoiceSet, profile: CapabilityProfile, startIndex: Int): LadderResult =
    val rendered = numbered(cs, startIndex)
    val promptNodes = cs.prompt.toList.flatten
    val choiceMap = choiceMapEntries(cs.id, rendered)
    val buttonsFit = profile.buttons && rendered.size <= buttonBudget(profile)
    if buttonsFit then
      LadderResult(
        RenderedControls.Buttons(rendered.grouped(profile.maxChoicesPerRow).toList),
        if cs.maxSelect == 1 then Rung.Buttons else Rung.ToggleKeyboard,
        promptNodes,
        choiceMap,
        rendered.size
      )
    else
      LadderResult(
        RenderedControls.Numbered(cs.id, rendered),
        Rung.Numbered,
        promptNodes ++ numberedExtras(cs, rendered),
        choiceMap,
        0
      )

  /** The text equivalent shown in place of an attachment (ADR-015 CON-02). */
  private def textEquivalent(attachment: AttachmentRef): Node =
    val label = attachment.kind match
      case MediaKind.Photo    => "image"
      case MediaKind.Document => "document"
    Node.Paragraph(List(Inline.Text(s"[$label: ${attachment.altText}]")))

  private def renderForm(form: Form): RenderedForm =
    RenderedForm(
      form.id,
      form.title,
      form.fields.map(f =>
        RenderedField(f.key, f.label, f.tpe, f.required, f.placeholder, f.options.map(_.label), f.value)
      ),
      form.submit
    )

  /** One FormRunner question: the title with its progress line, then the field label with its placeholder hint, its
    * current value when the step is re-rendered with previous answers (M1.9 pre-fill), and numbered options (DESIGN.md
    * section 4.3).
    */
  def formQuestionNodes(title: String, fields: List[Field], index: Int, current: Option[String] = None): List[Node] =
    val field = fields(index)
    val hint = field.placeholder.map(p => s" ($p)").getOrElse("")
    val currentText = current.filter(_.nonEmpty).map(v => s" [current: $v]").getOrElse("")
    val options =
      if field.options.nonEmpty then
        " " + field.options.zipWithIndex.map((o, i) => s"${i + 1}) ${o.label}").mkString(" ")
      else ""
    List(
      Node.Paragraph(List(Inline.Text(s"$title — question ${index + 1} of ${fields.size}:"))),
      Node.Paragraph(List(Inline.Text(s"${field.label}$hint$currentText$options")))
    )

  /** The FormRunner's first question: the mediator asks one field per message, keeps partial answers in `form_runs` and
    * emits one FormSubmitted (DESIGN.md section 4.3). A pre-filled field (M1.9 `[Back]` re-render) shows its current
    * value.
    */
  private def firstQuestionNodes(form: Form): List[Node] =
    formQuestionNodes(form.title, form.fields, 0, form.fields.head.value)

  def render(
      msg: OutboundMessage,
      chat: ChatRef,
      profile: CapabilityProfile,
      sendKey: String,
      ctx: RenderContext = RenderContext()
  ): (List[VendorOp], RenderReport) =
    // Ladder: media (the attachment travels beside the caption) -> its text equivalent heads the text.
    val media = msg.attachment.filter(_ => profile.media)
    val body = msg.attachment.filterNot(_ => profile.media).map(textEquivalent).toList ++ msg.body
    var rungs = msg.attachment
      .map(_ => "attachment" -> (if profile.media then Rung.AttachmentMedia else Rung.AttachmentText))
      .toMap
    var renderedForm = Option.empty[RenderedForm]
    var nextIndex = 1
    var buttons = 0
    // Each block's controls (None for notice/form blocks), its extra text nodes and its
    // choice_map rows, in block order — pages are assembled from these.
    var entries = List.empty[(Option[RenderedControls], List[Node], List[ChoiceMapEntry])]

    msg.blocks.foreach:
      case Block.Choices(cs) =>
        val result = renderChoices(cs, profile, nextIndex)
        entries = entries :+ (Some(result.controls), result.extraNodes, result.choiceMap)
        rungs = rungs + (cs.id -> result.rung)
        nextIndex = nextIndex + result.choiceMap.size
        buttons = buttons + result.buttons
      case Block.FormBlock(f) =>
        renderedForm = Some(renderForm(f))
        entries = entries :+ (None, firstQuestionNodes(f), Nil)
        rungs = rungs + (f.id -> Rung.FormRunner)
      case Block.NoticeBlock(n) =>
        entries = entries :+ (None, noticeNodes(n), Nil)

    val discreetNames = if msg.discreet then ctx.discreetNames else Nil
    def renderText(nodes: List[Node]): List[String] =
      VendorMarkup.split(VendorMarkup.render(nodes, profile.markup, discreetNames), profile.maxText)

    def pageMessage(
        nodes: List[Node],
        controls: List[RenderedControls],
        map: List[ChoiceMapEntry],
        attachment: Option[AttachmentRef]
    ): RenderedMessage =
      RenderedMessage(
        chunks = renderText(nodes).filter(_.nonEmpty),
        controls = controls,
        form = renderedForm,
        deleteAfter = msg.deleteAfter,
        silent = msg.silent,
        suppressPreview = msg.discreet,
        choiceMap = map,
        attachment = attachment
      )

    // Digest paging: one message carries at most `buttonBudget` buttons; longer sets are paged.
    val budget = buttonBudget(profile)
    if buttons > budget && budget > 0 then
      val (noControls, withControls) = entries.partition(_._1.isEmpty)
      val pages = paginate(withControls, budget)
      val pageCount = pages.size
      val headerNodes = body ++ noControls.flatMap(_._2)
      val ops = pages.zipWithIndex.map { (pageEntries, i) =>
        val nodes =
          if i == 0 then headerNodes ++ pageEntries.flatMap(_._2)
          else Node.Paragraph(List(Inline.Text(s"Page ${i + 1} of $pageCount"))) +: pageEntries.flatMap(_._2)
        val message =
          pageMessage(nodes, pageEntries.flatMap(_._1), pageEntries.flatMap(_._3), media.filter(_ => i == 0))
        VendorOp.Send(chat, message, s"$sendKey:p${i + 1}", msg.replaces)
      }
      (ops, RenderReport(rungs, splitInto = ops.size, paged = true))
    else
      val nodes = body ++ entries.flatMap(_._2)
      val rendered = pageMessage(nodes, entries.flatMap(_._1), entries.flatMap(_._3), media)
      (
        List(VendorOp.Send(chat, rendered, sendKey, msg.replaces)),
        RenderReport(rungs, splitInto = rendered.chunks.size)
      )

  /** Distribute control blocks across pages so no page exceeds the button budget. Whole blocks move; a block never
    * splits across pages.
    */
  private def paginate[A](
      entries: List[(Option[RenderedControls], A, List[ChoiceMapEntry])],
      budget: Int
  ): List[List[(Option[RenderedControls], A, List[ChoiceMapEntry])]] =
    def cost(entry: (Option[RenderedControls], A, List[ChoiceMapEntry])): Int =
      entry._1 match
        case Some(RenderedControls.Buttons(rows)) => rows.flatten.size
        case _                                    => 0
    entries.foldLeft(List.empty[List[(Option[RenderedControls], A, List[ChoiceMapEntry])]]) { (pages, entry) =>
      pages match
        case Nil => List(List(entry))
        case _   =>
          val last = pages.last
          if last.map(cost).sum + cost(entry) <= budget then pages.dropRight(1) :+ (last :+ entry)
          else pages :+ List(entry)
    }

  /** finalize (DESIGN.md sections 4.2, 4.3): remove controls, show the outcome. The kept ChoiceSet carries its own
    * choice_map rows, so post-resolution follow-ups ([Undo][Correct]) stay one tap (ROADMAP M0.9).
    */
  def renderFinalize(
      handle: MessageHandle,
      summary: RichText,
      keep: Option[ChoiceSet],
      chat: ChatRef,
      profile: CapabilityProfile,
      sendKey: String,
      ctx: RenderContext = RenderContext()
  ): (List[VendorOp], RenderReport) =
    val kept = keep.map(cs => (cs, renderChoices(cs, profile, 1)))
    val text = VendorMarkup.render(summary ++ kept.toList.flatMap(_._2.extraNodes), profile.markup)
    val chunks = VendorMarkup.split(text, profile.maxText)
    val outcome = RenderedMessage(
      chunks,
      controls = kept.toList.map(_._2.controls),
      choiceMap = kept.toList.flatMap(_._2.choiceMap)
    )
    val (ops, rung) =
      if editable(profile, ctx) then (List(VendorOp.Edit(handle, outcome)), Rung.FinalizeNativeEdit)
      else if deletable(profile, ctx) then
        (
          List(VendorOp.Send(chat, outcome, sendKey, Some(handle)), VendorOp.Delete(handle)),
          Rung.FinalizeReplaceAndDelete
        )
      else (List(VendorOp.Send(chat, outcome, sendKey, Some(handle))), Rung.FinalizeReplaceOnly)
    val rungs = kept.map((cs, result) => cs.id -> result.rung).toMap + ("finalize" -> rung)
    (ops, RenderReport(rungs, splitInto = chunks.size))
end Renderer
