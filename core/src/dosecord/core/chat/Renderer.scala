package dosecord.core.chat

import dosecord.contracts.*

import java.time.Duration

/** Inputs the pure renderer cannot read from the message itself (DESIGN.md section 4.3): whether a live interaction
  * exists (native ephemeral and native modal are interaction responses only), and whether the target message is still
  * inside the vendor's edit/delete windows.
  */
final case class RenderContext(
    liveInteraction: Boolean = false,
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
    val Select = "choices.select"
    val NumberedReactions = "choices.numberedReactions"
    val Numbered = "choices.numbered"
    val FormModal = "form.modal"
    val FormRunner = "form.formRunner"
    val VisibilityNative = "visibility.ephemeralNative"
    val VisibilityAutoDelete = "visibility.autoDelete"
    val VisibilityNotice = "visibility.notice"
    val VisibilityPersistent = "visibility.persistent"
    val FinalizeNativeEdit = "finalize.nativeEdit"
    val FinalizeReactionReplyQuote = "finalize.reactionReplyQuote"
    val FinalizeReplaceAndDelete = "finalize.replaceAndDelete"
    val FinalizeReplaceOnly = "finalize.replaceOnly"

  /** More than this many native buttons is worse UX than a select menu (DESIGN.md section 4.3: "native select (Discord,
    * > 10 choices)").
    */
  val MaxNativeButtons = 10

  val AutoDeleteAfter: Duration = Duration.ofSeconds(60)

  val NotKeptNotice: Notice = Notice(
    NoticeLevel.Info,
    List(Node.Paragraph(List(Inline.Text("This message will not be kept."))))
  )

  /** Fixed copy for multi-select on the text tiers (ROADMAP M0.9): reactions toggle on rung 3, reply `done` submits.
    */
  val MultiSelectInstruction =
    "Select all that apply — reply with the numbers, separated by spaces or commas."

  val DigitReactions: List[String] = List("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣")

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

  private def reactionTier(profile: CapabilityProfile): Boolean =
    !profile.buttons && profile.botReactions

  /** Reactions the bot may add to one message: at most `eventsPerMessageBudget - 1` and never more than the nine digit
    * emoji (DESIGN.md section 4.3).
    */
  private def reactionBudget(profile: CapabilityProfile): Int =
    if profile.botReactions then math.min(DigitReactions.size, profile.eventsPerMessageBudget - 1) else 0

  private def componentBudget(profile: CapabilityProfile): Int =
    profile.maxChoicesPerRow * profile.maxRows

  private def toRendered(index: Int)(c: Choice): RenderedChoice =
    RenderedChoice(index, c.label, c.callback, c.style, c.emoji)

  private def choiceMapEntries(cs: ChoiceSet, startIndex: Int, reactions: List[String]): List[ChoiceMapEntry] =
    cs.choices.zipWithIndex.map { (c, i) =>
      ChoiceMapEntry(cs.id, startIndex + i, c.label, reactions.lift(i).orElse(c.emoji), c.callback)
    }

  /** One ChoiceSet through the ladder. Returns the controls, the rung name, the nodes to append to the message text
    * (prompt, numbered legend, multi-select instruction), the reaction emojis to add (within the remaining budget), and
    * the native component count for paging.
    */
  private final case class LadderResult(
      controls: RenderedControls,
      rung: String,
      extraNodes: List[Node],
      reactions: List[String],
      components: Int,
      warnings: List[String]
  )

  private def numberedLegend(cs: ChoiceSet, startIndex: Int): Node =
    val items = cs.choices.zipWithIndex.map((c, i) => s"${startIndex + i}) ${c.label}").mkString(" ")
    Node.Paragraph(List(Inline.Text(items)))

  private def numberedExtras(cs: ChoiceSet, startIndex: Int): List[Node] =
    val legend = numberedLegend(cs, startIndex)
    val instruction =
      if cs.maxSelect > 1 then List(Node.Paragraph(List(Inline.Text(MultiSelectInstruction)))) else Nil
    legend +: instruction

  private def renderChoices(
      cs: ChoiceSet,
      profile: CapabilityProfile,
      startIndex: Int,
      reactionBudgetLeft: Int
  ): LadderResult =
    val rendered = cs.choices.zipWithIndex.map((c, i) => toRendered(startIndex + i)(c))
    val promptNodes = cs.prompt.toList.flatten
    val singleShot = cs.maxSelect == 1

    def buttonsRun(rowWidth: Int): LadderResult =
      LadderResult(
        RenderedControls.Buttons(rendered.grouped(rowWidth).toList),
        Rung.Buttons,
        promptNodes,
        Nil,
        rendered.size,
        Nil
      )

    def selectRun: LadderResult =
      LadderResult(
        RenderedControls.SelectMenu(cs.id, rendered, cs.minSelect, cs.maxSelect),
        Rung.Select,
        promptNodes,
        Nil,
        math.max(1, profile.maxChoicesPerRow),
        Nil
      )

    def numberedReactionsRun: LadderResult =
      val reactions = cs.choices.zipWithIndex
        .take(reactionBudgetLeft)
        .map((c, i) => c.emoji.getOrElse(DigitReactions(startIndex + i - 1)))
      val warnings =
        if cs.choices.size > reactionBudgetLeft then
          List(s"${cs.id}: ${cs.choices.size - reactionBudgetLeft} choices resolve by numbered reply only")
        else Nil
      LadderResult(
        RenderedControls.Numbered(cs.id, rendered, reactions),
        Rung.NumberedReactions,
        promptNodes ++ numberedExtras(cs, startIndex),
        reactions,
        0,
        warnings
      )

    def numberedRun: LadderResult =
      LadderResult(
        RenderedControls.Numbered(cs.id, rendered, Nil),
        Rung.Numbered,
        promptNodes ++ numberedExtras(cs, startIndex),
        Nil,
        0,
        Nil
      )

    def buttonsFit: Boolean =
      profile.buttons && singleShot &&
        cs.choices.size <= MaxNativeButtons &&
        cs.choices.size <= componentBudget(profile)

    cs.layout match
      case ChoiceLayout.Buttons =>
        if buttonsFit then buttonsRun(profile.maxChoicesPerRow)
        else if profile.select then selectRun
        else if reactionBudgetLeft > 0 then numberedReactionsRun
        else numberedRun
      case ChoiceLayout.Select =>
        if profile.select then selectRun
        else if profile.buttons && singleShot then buttonsRun(math.min(5, profile.maxChoicesPerRow))
        else if reactionBudgetLeft > 0 then numberedReactionsRun
        else numberedRun

  private def renderForm(form: Form): RenderedForm =
    RenderedForm(
      form.id,
      form.title,
      form.fields.map(f => RenderedField(f.key, f.label, f.tpe, f.required, f.placeholder, f.options.map(_.label))),
      form.submit
    )

  /** The FormRunner's first question (rung 2 of the form ladder): the mediator asks one field per message, keeps
    * partial answers in `form_runs` and emits one FormSubmitted (DESIGN.md section 4.3); the runner itself lands with
    * the wizard engine in M0.12b.
    */
  private def firstQuestionNodes(form: Form): List[Node] =
    val first = form.fields.head
    val hint = first.placeholder.map(p => s" ($p)").getOrElse("")
    val options =
      if first.options.nonEmpty then
        " " + first.options.zipWithIndex.map((o, i) => s"${i + 1}) ${o.label}").mkString(" ")
      else ""
    List(
      Node.Paragraph(List(Inline.Text(s"${form.title} — question 1 of ${form.fields.size}:"))),
      Node.Paragraph(List(Inline.Text(s"${first.label}$hint$options")))
    )

  def render(
      msg: OutboundMessage,
      chat: ChatRef,
      profile: CapabilityProfile,
      sendKey: String,
      ctx: RenderContext = RenderContext()
  ): (List[VendorOp], RenderReport) =
    // Ladder: ephemeral -> native (live interaction only) -> auto-delete -> notice.
    val (visibilityRung, ephemeral, deleteAfter, visibilityNotices) =
      msg.visibility match
        case Visibility.Persistent => (Rung.VisibilityPersistent, false, msg.deleteAfter, Nil)
        case Visibility.Ephemeral  =>
          if profile.ephemeral && ctx.liveInteraction then (Rung.VisibilityNative, true, msg.deleteAfter, Nil)
          else if deletable(profile, ctx) && withinWindow(profile, AutoDeleteAfter) then
            (Rung.VisibilityAutoDelete, false, Some(msg.deleteAfter.getOrElse(AutoDeleteAfter)), Nil)
          else (Rung.VisibilityNotice, false, msg.deleteAfter, List(NotKeptNotice))

    var rungs = Map("visibility" -> visibilityRung)
    var warnings = List.empty[String]
    var formOps = List.empty[VendorOp]
    var renderedForm = Option.empty[RenderedForm]
    var nextIndex = 1
    var budgetLeft = reactionBudget(profile)
    var components = 0
    // Each block's controls (None for notice/form blocks), its extra text nodes and its
    // choice_map rows, in block order — pages are assembled from these.
    var entries = List.empty[(Option[RenderedControls], List[Node], List[ChoiceMapEntry])]

    msg.blocks.foreach:
      case Block.Choices(cs) =>
        val result = renderChoices(cs, profile, nextIndex, budgetLeft)
        entries =
          entries :+ (Some(result.controls), result.extraNodes, choiceMapEntries(cs, nextIndex, result.reactions))
        rungs = rungs + (cs.id -> result.rung)
        warnings = warnings ++ result.warnings
        nextIndex = nextIndex + cs.choices.size
        budgetLeft = budgetLeft - result.reactions.size
        components = components + result.components
      case Block.FormBlock(f) =>
        // Ladder: modal (native, un-acked interaction only) -> mediator FormRunner.
        if profile.modal && ctx.liveInteraction then
          formOps = formOps :+ VendorOp.OpenForm(renderForm(f))
          rungs = rungs + (f.id -> Rung.FormModal)
        else
          renderedForm = Some(renderForm(f))
          entries = entries :+ (None, firstQuestionNodes(f), Nil)
          rungs = rungs + (f.id -> Rung.FormRunner)
      case Block.NoticeBlock(n) =>
        entries = entries :+ (None, noticeNodes(n), Nil)

    val discreetNames = if msg.discreet then ctx.discreetNames else Nil
    def renderText(nodes: List[Node]): List[String] =
      VendorMarkup.split(VendorMarkup.render(nodes, profile.markup, discreetNames), profile.maxText)

    def pageMessage(nodes: List[Node], controls: List[RenderedControls], map: List[ChoiceMapEntry]): RenderedMessage =
      RenderedMessage(
        chunks = renderText(nodes),
        controls = controls,
        form = renderedForm,
        ephemeral = ephemeral,
        deleteAfter = deleteAfter,
        silent = msg.silent,
        suppressPreview = msg.discreet,
        choiceMap = map
      )

    // Digest paging: one message carries at most maxChoicesPerRow * maxRows native components
    // (Discord's 25-component cap bounds digests at 8 doses x 3 buttons); longer sets are paged.
    val budget = componentBudget(profile)
    if components > budget && budget > 0 then
      val (noControls, withControls) = entries.partition(_._1.isEmpty)
      val pages = paginate(withControls, budget)
      val pageCount = pages.size
      val headerNodes = msg.body ++ visibilityNotices.flatMap(noticeNodes) ++ noControls.flatMap(_._2)
      val ops = pages.zipWithIndex.map { (pageEntries, i) =>
        val nodes =
          if i == 0 then headerNodes ++ pageEntries.flatMap(_._2)
          else Node.Paragraph(List(Inline.Text(s"Page ${i + 1} of $pageCount"))) +: pageEntries.flatMap(_._2)
        val message = pageMessage(nodes, pageEntries.flatMap(_._1), pageEntries.flatMap(_._3))
        VendorOp.Send(chat, message, s"$sendKey:p${i + 1}", msg.replaces)
      }
      val report = RenderReport(rungs, splitInto = ops.size, paged = true, warnings)
      (formOps ++ ops, report)
    else
      val nodes = msg.body ++ visibilityNotices.flatMap(noticeNodes) ++ entries.flatMap(_._2)
      val rendered = pageMessage(nodes, entries.flatMap(_._1), entries.flatMap(_._3))
      val report = RenderReport(rungs, splitInto = rendered.chunks.size, paged = false, warnings)
      (formOps :+ VendorOp.Send(chat, rendered, sendKey, msg.replaces), report)

  private def withinWindow(profile: CapabilityProfile, needed: Duration): Boolean =
    profile.deleteOwn match
      case DeleteCapability.Window(limit) => limit.compareTo(needed) >= 0
      case _                              => true

  /** Distribute control blocks across pages so no page exceeds the native component budget. Whole blocks move; a block
    * never splits across pages.
    */
  private def paginate[A](
      entries: List[(Option[RenderedControls], A, List[ChoiceMapEntry])],
      budget: Int
  ): List[List[(Option[RenderedControls], A, List[ChoiceMapEntry])]] =
    def cost(entry: (Option[RenderedControls], A, List[ChoiceMapEntry])): Int =
      entry._1 match
        case Some(RenderedControls.Buttons(rows))          => rows.flatten.size
        case Some(RenderedControls.SelectMenu(_, _, _, _)) => math.max(1, budget.min(5))
        case _                                             => 0
    entries.foldLeft(List.empty[List[(Option[RenderedControls], A, List[ChoiceMapEntry])]]) { (pages, entry) =>
      pages match
        case Nil => List(List(entry))
        case _   =>
          val last = pages.last
          if last.map(cost).sum + cost(entry) <= budget then pages.dropRight(1) :+ (last :+ entry)
          else pages :+ List(entry)
    }

  /** finalize (DESIGN.md sections 4.2, 4.3): remove controls, show the outcome. On the reaction tier the bot's own
    * reactions come off the target and the outcome is reply-quoted with the kept ChoiceSet carrying its own choice_map
    * row and reaction set, so post-resolution follow-ups ([Undo][Correct]) stay one tap (ROADMAP M0.9).
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
    val keepResult = keep.map { cs =>
      val result = renderChoices(cs, profile, 1, reactionBudget(profile))
      (result, choiceMapEntries(cs, 1, result.reactions))
    }
    val keepControls = keepResult.map((r, _) => List(r.controls)).getOrElse(Nil)
    val keepMap = keepResult.map(_._2).getOrElse(Nil)
    val keepRungs = keepResult.map((r, _) => Map(keep.get.id -> r.rung)).getOrElse(Map.empty)
    val keepWarnings = keepResult.map((r, _) => r.warnings).getOrElse(Nil)
    val keepNodes = keepResult.map((r, _) => r.extraNodes).getOrElse(Nil)
    val text = VendorMarkup.render(summary ++ keepNodes, profile.markup)
    val chunks = VendorMarkup.split(text, profile.maxText)

    def outcomeMessage: RenderedMessage =
      RenderedMessage(
        chunks,
        keepControls,
        None,
        ephemeral = false,
        None,
        silent = false,
        suppressPreview = false,
        keepMap
      )

    val (ops, rung) =
      if !reactionTier(profile) && editable(profile, ctx) then
        (List(VendorOp.Edit(handle, outcomeMessage)), Rung.FinalizeNativeEdit)
      else if reactionTier(profile) then
        (
          List(VendorOp.UnreactAll(handle), VendorOp.Send(chat, outcomeMessage, sendKey, Some(handle))),
          Rung.FinalizeReactionReplyQuote
        )
      else if deletable(profile, ctx) then
        (
          List(VendorOp.Send(chat, outcomeMessage, sendKey, Some(handle)), VendorOp.Delete(handle)),
          Rung.FinalizeReplaceAndDelete
        )
      else (List(VendorOp.Send(chat, outcomeMessage, sendKey, Some(handle))), Rung.FinalizeReplaceOnly)

    (ops, RenderReport(keepRungs + ("finalize" -> rung), splitInto = chunks.size, paged = false, keepWarnings))
end Renderer
