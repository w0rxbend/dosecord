package dosegram.core.application.settings

import dosegram.contracts.Block
import dosegram.contracts.CallbackRef
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.HhMm
import dosegram.contracts.Inbound
import dosegram.contracts.InboundEvent
import dosegram.contracts.Inline
import dosegram.contracts.MessageHandle
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.application.CommandRegistry
import dosegram.core.application.identity.AccountTimezoneFlow
import dosegram.core.application.reminders.ReminderCommand
import dosegram.core.application.reminders.ReminderRefusal
import dosegram.core.application.reminders.ReminderSettings
import dosegram.core.application.reminders.RemindersHandler
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.chat.ChatHandler
import dosegram.core.chat.WizardEngine
import dosegram.core.domain.QuietHours
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.LanguageCopy
import dosegram.core.domain.copy.PromptCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.SettingsCopy
import dosegram.core.domain.reminders.Anchor
import dosegram.core.domain.reminders.DayProfile
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptPresets
import dosegram.core.domain.reminders.WorkHours
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.AuditChannel
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.Tx

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** `/settings` (supervisor decision, 2026-11-28; BOT_UX section 17): one card with every setting of the account beside
  * its value, in sections, and a switch for each value the chat sets in place. A tap edits the card into the new state,
  * headed by the old and the new value, so nothing piles up in the chat; a switch carries the state it sets, so a
  * repeated tap changes nothing and writes nothing. Every write goes through [[AccountSettingsEdits]], the writer of
  * the Mini App's settings API, with its audit row. The day profile and the morning summary's time are edited with the
  * use cases of `/reminders`; the other reports and the consents are links to `/reminders` and `/privacy`. Nothing here
  * records or shows a health entry, so the card needs no consent of its own; "Animated mood" is offered with the mood
  * consent only.
  */
final class SettingsChat(codec: CallbackCodec, clock: Clock, reminders: ReminderSettings, inner: ChatHandler)
    extends ChatHandler:

  import SettingsChat.*

  private val edits = AccountSettingsEdits(clock)

  private var startTimezone: Option[(InboundEvent, Principal, Tx) => Reply] = None

  /** Wired by the composition root: the time zone flow starts through the engine, as the account menu starts it. */
  def wireEngine(engine: WizardEngine): Unit =
    startTimezone = Some((event, principal, tx) => engine.startFlow(event, principal, AccountTimezoneFlow.Id, tx))

  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    val account = principal.accountId.map(_.uuid)
    event.body match
      case Inbound.CommandInvoked(name, _, _) if name == CommandRegistry.Settings.name =>
        account.fold(toast(IdentityCopy.createFirst))(id => hub(Turn(event, id, None, tx), None))
      case Inbound.InteractionSubmitted(ref, source) if handles(ref) =>
        account.filter(_ == ref.subject).fold(stale)(id => tap(Turn(event, id, source, tx), principal, ref))
      case _ => inner.handle(event, principal, tx)

  private def tap(turn: Turn, principal: Principal, ref: CallbackRef): Reply =
    ActionRegistry.byId(ref.actionId).map(_.name) match
      case Some("settings.open")        => hub(turn, None)
      case Some("settings.language")    => Lang.values.lift(ref.value.toInt).fold(stale)(language(turn, _))
      case Some("settings.theme")       => ImageTheme.values.lift(ref.value.toInt).fold(stale)(theme(turn, _))
      case Some("settings.discreet")    => switch(ref.value)(discreet(turn, _))
      case Some("settings.animated")    => switch(ref.value)(animated(turn, _))
      case Some("settings.counts")      => switch(ref.value)(counts(turn, _))
      case Some("settings.whatsnew")    => switch(ref.value)(notes(turn, _))
      case Some("settings.day")         => day(turn, None)
      case Some("settings.anchor_pick") => anchorOf(ref.value).fold(stale)(anchorAsk(turn, _))
      case Some("settings.anchor_set")  => anchorTimeOf(ref.value).fold(stale)(setAnchor(turn, _, _))
      case Some("settings.work_pick")   => workAsk(turn)
      case Some("settings.work_set")    => WorkChoices.lift(ref.value.toInt).fold(stale)(setWork(turn, _))
      case Some("settings.quiet_pick")  => quietAsk(turn)
      case Some("settings.quiet_set")   => QuietChoices.lift(ref.value.toInt).fold(stale)(setQuiet(turn, _))
      case Some("settings.timezone")    =>
        startTimezone.fold(toast(ReminderCopy.failureToast))(_(turn.event, principal, turn.tx))
      case Some("settings.summary_pick") => summaryAsk(turn)
      case Some("settings.summary_set")  => summaryOf(ref.value).fold(stale)(setSummary(turn, _))
      case _                             => stale

  // ---------- the switches ----------

  /** The card is written in the chosen language at once, its headline included. The language shown is the one the user
    * reads: the stored choice, else the client's language (PRODUCT_SPEC R-LOC-1).
    */
  private def language(turn: Turn, lang: Lang): Reply =
    val before = Lang.current
    edits.save(turn.tx, turn.account, AuditChannel.Bot)(_.copy(locale = Some(lang.code))): Unit
    Lang.within(lang):
      val headline = Option.when(before != lang):
        SettingsCopy.changed(SettingsCopy.languageName, LanguageCopy.name(before), LanguageCopy.name(lang))
      hub(turn, headline)

  private def theme(turn: Turn, theme: ImageTheme): Reply =
    saved(turn, SettingsCopy.themeName, s => SettingsCopy.themeValue(s.imageTheme))(_.copy(imageTheme = theme))

  private def discreet(turn: Turn, isOn: Boolean): Reply =
    saved(turn, SettingsCopy.discreetName, s => SettingsCopy.state(s.isDiscreet))(_.copy(isDiscreet = isOn))

  private def animated(turn: Turn, isOn: Boolean): Reply =
    if !hasMood(turn) then stale
    else saved(turn, SettingsCopy.animatedName, s => SettingsCopy.state(s.animatedMood))(_.copy(animatedMood = isOn))

  private def counts(turn: Turn, isOn: Boolean): Reply =
    val changed = edits.setCounts(turn.tx, turn.account, AuditChannel.Bot, isOn)
    hub(turn, headline(changed, SettingsCopy.countsName, isOn))

  private def notes(turn: Turn, isOn: Boolean): Reply =
    val changed = edits.setWhatsNew(turn.tx, turn.account, AuditChannel.Bot, isOn)
    hub(turn, headline(changed, SettingsCopy.notesName, isOn))

  private def headline(changed: Boolean, name: String, isOn: Boolean): Option[String] =
    Option.when(changed)(SettingsCopy.changed(name, SettingsCopy.state(!isOn), SettingsCopy.state(isOn)))

  /** Saves `change` and answers with the card headed by the value `shown` before and after it. */
  private def saved(turn: Turn, name: String, shown: AccountSettings => String)(
      change: AccountSettings => AccountSettings
  ): Reply =
    edits.save(turn.tx, turn.account, AuditChannel.Bot)(change) match
      case None         => stale
      case Some(result) =>
        val (before, after) = (shown(result.before), shown(result.after))
        hub(turn, Option.when(before != after)(SettingsCopy.changed(name, before, after)))

  // ---------- the day ----------

  private def anchorAsk(turn: Turn, anchor: Anchor): Reply =
    val current = reminders.profile(turn.tx, turn.account).timeOf(anchor)
    val chips = anchorChips(anchor).map: at =>
      button(turn, PromptCopy.clock(at), AnchorSet, anchor.ordinal.toLong * AnchorFactor + at.toSecondOfDay / 60)
    show(
      turn,
      "settings.anchor",
      List(SettingsCopy.anchorAsk(anchor, current)),
      chips.grouped(3).toList :+ backToDay(turn)
    )

  private def setAnchor(turn: Turn, anchor: Anchor, at: LocalTime): Reply =
    val before = reminders.profile(turn.tx, turn.account).timeOf(anchor)
    reminders.setAnchor(turn.tx, turn.account, anchor, at) match
      case Left(_)  => stale
      case Right(_) =>
        day(turn, changedTime(SettingsCopy.anchorName(anchor), PromptCopy.clock(before), PromptCopy.clock(at)))

  private def workAsk(turn: Turn): Reply =
    val work = reminders.profile(turn.tx, turn.account).work
    val choices = WorkChoices.zipWithIndex.map: (choice, i) =>
      button(turn, SettingsCopy.workValue(choice.days, choice.start, choice.end), WorkSet, i.toLong)
    show(
      turn,
      "settings.work",
      List(SettingsCopy.workAsk(workValue(work))),
      choices.grouped(2).toList :+ backToDay(turn)
    )

  private def setWork(turn: Turn, work: WorkHours): Reply =
    val before = workValue(reminders.profile(turn.tx, turn.account).work)
    reminders.setWork(turn.tx, turn.account, work.days, work.start, work.end) match
      case Left(_)  => stale
      case Right(_) => day(turn, changedTime(SettingsCopy.workName, before, workValue(work)))

  private def quietAsk(turn: Turn): Reply =
    val quiet = reminders.profile(turn.tx, turn.account).quiet
    val choices = QuietChoices.zipWithIndex.map((choice, i) => button(turn, quietValue(choice), QuietSet, i.toLong))
    show(
      turn,
      "settings.quiet",
      List(SettingsCopy.quietAsk(quietValue(quiet))),
      choices.grouped(3).toList :+ backToDay(turn)
    )

  /** Quiet hours are an account setting, written and audited as the Mini App writes them. */
  private def setQuiet(turn: Turn, quiet: QuietHours): Reply =
    val before = quietValue(reminders.profile(turn.tx, turn.account).quiet)
    edits.save(turn.tx, turn.account, AuditChannel.Bot)(_.copy(quietHours = Some(quiet))) match
      case None    => stale
      case Some(_) => day(turn, changedTime(SettingsCopy.quietName, before, quietValue(quiet)))

  // ---------- the morning summary ----------

  /** The morning summary's time, chosen from chips or typed in `/reminders` (supervisor decision, 2026-11-28). */
  private def summaryAsk(turn: Turn): Reply =
    val current = summaryTime(turn)
    val chips = SummaryChips.map(at => button(turn, PromptCopy.clock(at), SummarySet, at.toSecondOfDay / 60L))
    val off = current.map(_ => button(turn, PromptCopy.TurnOff, SummarySet, SummaryOff)).toList
    show(
      turn,
      "settings.summary",
      List(SettingsCopy.summaryAsk(SettingsCopy.summaryValue(current))),
      chips.grouped(4).toList ++ List(off :+ button(turn, Labels.Back, Open, 0))
    )

  /** A time turns the summary on at it, off turns it off; the card comes back headed by the old and the new value. A
    * time inside the quiet hours changes nothing and says why.
    */
  private def setSummary(turn: Turn, at: Option[LocalTime]): Reply =
    val before = summaryTime(turn)
    at match
      case None =>
        reminders.turnOff(turn.tx, turn.account, PromptKind.DailyReport): Unit
        summarySaved(turn, before, None)
      case Some(time) =>
        reminders.applyMorningSummary(turn.tx, turn.account, time) match
          case Right(_)                                  => summarySaved(turn, before, at)
          case Left(ReminderRefusal.InQuietHours(quiet)) => toast(RemindersHandler.summaryInQuiet(time, quiet))
          case Left(_)                                   => stale

  private def summarySaved(turn: Turn, before: Option[LocalTime], after: Option[LocalTime]): Reply =
    val (old, now) = (SettingsCopy.summaryValue(before), SettingsCopy.summaryValue(after))
    hub(turn, Option.when(old != now)(SettingsCopy.changed(PromptCopy.kindName(PromptKind.DailyReport), old, now)))

  /** The time of the morning summary while it is on. */
  private def summaryTime(turn: Turn): Option[LocalTime] =
    turn.tx.reminders.prompts
      .schedule(turn.account, PromptKind.DailyReport)
      .filter(_.enabled)
      .flatMap(plan => PromptPresets.morningSummaryTime(plan.plan))

  private def changedTime(name: String, before: String, after: String): Option[String] =
    Option.when(before != after)(SettingsCopy.changed(name, before, after))

  private def day(turn: Turn, headline: Option[String]): Reply =
    val profile = reminders.profile(turn.tx, turn.account)
    val lines = SettingsCopy.daySection :: DayAnchors.map(a =>
      SettingsCopy.line(SettingsCopy.anchorName(a), clockOf(profile, a))
    ) ++
      List(
        SettingsCopy.line(SettingsCopy.workName, workValue(profile.work)),
        SettingsCopy.line(SettingsCopy.quietName, quietValue(profile.quiet))
      )
    val anchors =
      DayAnchors.map(a => button(turn, SettingsCopy.anchorAt(a, profile.timeOf(a)), AnchorPick, a.ordinal.toLong))
    val rows = anchors.grouped(2).toList ++ List(
      List(button(turn, SettingsCopy.WorkButton, WorkPick, 0), button(turn, SettingsCopy.QuietButton, QuietPick, 0)),
      List(button(turn, Labels.Back, Open, 0))
    )
    show(turn, "settings.day", headline.toList ++ List(lines.mkString("\n"), SettingsCopy.dayHelp), rows)

  private def backToDay(turn: Turn): List[Choice] = List(button(turn, Labels.Back, Day, 0))

  // ---------- the card ----------

  private def hub(turn: Turn, headline: Option[String]): Reply =
    val state = stateOf(turn)
    show(turn, "settings.card", headline.toList ++ cardLines(state), cardRows(turn, state))

  private def stateOf(turn: Turn): HubState =
    val zone = turn.tx.accounts.timezoneOf(turn.account).getOrElse("UTC")
    HubState(
      settings = settingsOf(turn),
      profile = reminders.profile(turn.tx, turn.account),
      zone = zone,
      now = PromptCopy.clock(clock.now().atZone(ZoneId.of(zone)).toLocalTime),
      counts = turn.tx.accounts.gamificationOf(turn.account),
      notes = turn.tx.accounts.whatsNewOf(turn.account).enabled,
      mood = hasMood(turn),
      reports =
        ReportKinds.map(kind => kind -> turn.tx.reminders.prompts.schedule(turn.account, kind).exists(_.enabled)),
      summary = summaryTime(turn)
    )

  /** The title, the four sections and the links, each section one paragraph. */
  private def cardLines(state: HubState): List[String] =
    val settings = state.settings
    val region = List(
      SettingsCopy.regionSection,
      SettingsCopy.line(SettingsCopy.languageName, LanguageCopy.name(Lang.current)),
      SettingsCopy.line(SettingsCopy.zoneName, SettingsCopy.zoneValue(state.zone, state.now))
    )
    val yourDay = List(
      SettingsCopy.daySection,
      DayAnchors.map(a => SettingsCopy.anchorAt(a, state.profile.timeOf(a))).mkString(" · "),
      SettingsCopy.line(SettingsCopy.workName, workValue(state.profile.work)),
      SettingsCopy.line(SettingsCopy.quietName, quietValue(state.profile.quiet))
    )
    val animated = SettingsCopy.animatedValue(settings.animatedMood, settings.isDiscreet)
    val images = List(
      Some(SettingsCopy.imagesSection),
      Some(SettingsCopy.line(SettingsCopy.themeName, SettingsCopy.themeValue(settings.imageTheme))),
      Some(SettingsCopy.line(SettingsCopy.discreetName, SettingsCopy.state(settings.isDiscreet))),
      Option.when(state.mood)(SettingsCopy.line(SettingsCopy.animatedName, animated)),
      Some(SettingsCopy.line(SettingsCopy.countsName, SettingsCopy.state(state.counts))),
      Some(SettingsCopy.line(SettingsCopy.notesName, SettingsCopy.state(state.notes)))
    ).flatten
    val reports = List(SettingsCopy.reportsSection, SettingsCopy.reportsLine(state.reports))
    (SettingsCopy.title :: List(region, yourDay, images, reports).map(_.mkString("\n"))) :+ SettingsCopy.links

  private def cardRows(turn: Turn, state: HubState): List[List[Choice]] =
    val settings = state.settings
    val nextTheme = ImageTheme.values((settings.imageTheme.ordinal + 1) % ImageTheme.values.length)
    val languages = Lang.values.toList.filter(_ != Lang.current)
    List(
      languages.map(lang => button(turn, LanguageCopy.name(lang), LanguageSet, lang.ordinal.toLong)),
      List(button(turn, SettingsCopy.ZoneButton, Timezone, 0), button(turn, SettingsCopy.DayButton, Day, 0)),
      List(
        button(turn, SettingsCopy.ThemeButton(settings.imageTheme), Theme, nextTheme.ordinal.toLong),
        toggle(turn, SettingsCopy.DiscreetButton(settings.isDiscreet), Discreet, settings.isDiscreet)
      ),
      List(
        Option.when(state.mood)(
          toggle(turn, SettingsCopy.AnimatedButton(settings.animatedMood), Animated, settings.animatedMood)
        ),
        Some(toggle(turn, SettingsCopy.CountsButton(state.counts), Counts, state.counts))
      ).flatten,
      List(toggle(turn, SettingsCopy.NotesButton(state.notes), WhatsNew, state.notes)),
      List(button(turn, SettingsCopy.SummaryButton(state.summary), SummaryPick, 0)),
      List(
        button(turn, SettingsCopy.RemindersButton, RemindersHub, 0),
        button(turn, SettingsCopy.PrivacyButton, Privacy, 0)
      )
    )

  // ---------- helpers ----------

  private def settingsOf(turn: Turn): AccountSettings =
    turn.tx.accounts.settingsOf(turn.account).getOrElse(AccountSettings.Default)

  private def hasMood(turn: Turn): Boolean = turn.tx.consents.isGranted(turn.account, ConsentPurpose.Mood)

  /** A switch shows the state it is in and sets the other one. */
  private def toggle(turn: Turn, label: String, action: ActionEntry, isOn: Boolean): Choice =
    button(turn, label, action, if isOn then Off else On)

  private def button(turn: Turn, label: String, action: ActionEntry, value: Long): Choice =
    Choice.callback(label, codec.encode(CallbackMode.Direct, action, turn.account, value).wire)

  private def show(turn: Turn, kind: String, paragraphs: List[String], rows: List[List[Choice]]): Reply =
    val message = OutboundMessage(
      body = paragraphs.map(text => Node.Paragraph(List(Inline.Text(text)))),
      blocks =
        rows.filter(_.nonEmpty).zipWithIndex.map((row, i) => Block.Choices(ChoiceSet(s"$kind.$i", choices = row))),
      dedupeKey = s"$kind:${turn.event.vendor}:${turn.event.vendorEventId}",
      correlationId = s"${turn.event.vendor}:${turn.event.vendorEventId}"
    )
    turn.source match
      case Some(handle) => Reply(replace = Some(message.copy(replaces = Some(handle))))
      case None         => Reply(followUps = List(message))

  private def toast(text: String): Reply = Reply(toast = Some(text))
  private def stale: Reply = toast(ReminderCopy.staleControlToast)

object SettingsChat:

  val Open: ActionEntry = named("settings.open")
  private val LanguageSet = named("settings.language")
  private val Theme = named("settings.theme")
  private val Discreet = named("settings.discreet")
  private val Animated = named("settings.animated")
  private val Counts = named("settings.counts")
  private val WhatsNew = named("settings.whatsnew")
  private val Day = named("settings.day")
  private val AnchorPick = named("settings.anchor_pick")
  private val AnchorSet = named("settings.anchor_set")
  private val WorkPick = named("settings.work_pick")
  private val WorkSet = named("settings.work_set")
  private val QuietPick = named("settings.quiet_pick")
  private val QuietSet = named("settings.quiet_set")
  private val Timezone = named("settings.timezone")
  private val SummaryPick = named("settings.summary_pick")
  private val SummarySet = named("settings.summary_set")

  /** The value of `settings.summary_set` that turns the morning summary off; any other is a minute of the day. */
  val SummaryOff: Long = 24L * 60

  /** The times offered for the morning summary: 06:30 to 10:00, 30 minutes apart. Any other is typed. */
  val SummaryChips: List[LocalTime] = List.tabulate(8)(i => LocalTime.of(6, 30).plusMinutes(i * 30L))

  private def summaryOf(value: Long): Option[Option[LocalTime]] =
    if value == SummaryOff then Some(None)
    else Option.when(value >= 0 && value < SummaryOff)(Some(LocalTime.ofSecondOfDay(value * 60)))

  /** "Privacy" opens the `/privacy` card, which the capture handler owns. */
  val Privacy: ActionEntry = named("settings.privacy")
  private val RemindersHub = named("reminders.hub")

  private val Off = 0L
  private val On = 1L

  /** `settings.anchor_set` carries the anchor's ordinal times this, plus the minute of the day. */
  private val AnchorFactor = 10_000L
  private val MinutesPerDay = 24 * 60

  /** The anchors the card edits one by one; the work hours are one choice. */
  val DayAnchors: List[Anchor] = List(Anchor.Wake, Anchor.Breakfast, Anchor.Lunch, Anchor.Dinner, Anchor.Bedtime)

  /** The first of nine times, 30 minutes apart, offered for each anchor (BUX-SET-03). */
  private val FirstChip: Map[Anchor, LocalTime] = Map(
    Anchor.Wake -> LocalTime.of(5, 30),
    Anchor.Breakfast -> LocalTime.of(6, 30),
    Anchor.Lunch -> LocalTime.of(11, 30),
    Anchor.Dinner -> LocalTime.of(17, 0),
    Anchor.Bedtime -> LocalTime.of(21, 0)
  )
  private val ChipCount = 9
  private val ChipStepMinutes = 30L

  private val Weekdays = WorkHours.Weekdays
  private val MondayToSaturday = Weekdays + DayOfWeek.SATURDAY

  val WorkChoices: List[WorkHours] = List(
    WorkHours(Weekdays, LocalTime.of(8, 0), LocalTime.of(16, 0)),
    WorkHours(Weekdays, LocalTime.of(8, 0), LocalTime.of(17, 0)),
    WorkHours(Weekdays, LocalTime.of(9, 0), LocalTime.of(17, 0)),
    WorkHours(Weekdays, LocalTime.of(9, 0), LocalTime.of(18, 0)),
    WorkHours(Weekdays, LocalTime.of(10, 0), LocalTime.of(19, 0)),
    WorkHours(MondayToSaturday, LocalTime.of(9, 0), LocalTime.of(18, 0))
  )

  val QuietChoices: List[QuietHours] =
    List(
      "21:00" -> "06:00",
      "22:00" -> "06:00",
      "22:00" -> "07:00",
      "23:00" -> "07:00",
      "23:00" -> "08:00",
      "00:00" -> "08:00"
    )
      .map((start, end) => QuietHours(HhMm.unsafe(start), HhMm.unsafe(end)))

  private val ReportKinds = List(PromptKind.DailyReport, PromptKind.WeeklyReport, PromptKind.MonthlyReport)

  /** The callback of the account menu's "Settings" entry. */
  def openToken(codec: CallbackCodec, account: UUID): String = codec.encode(CallbackMode.Direct, Open, account, 0).wire

  def anchorChips(anchor: Anchor): List[LocalTime] =
    FirstChip.get(anchor).toList.flatMap(first => List.tabulate(ChipCount)(i => first.plusMinutes(i * ChipStepMinutes)))

  private def anchorOf(value: Long): Option[Anchor] = DayAnchors.find(_.ordinal.toLong == value)

  private def anchorTimeOf(value: Long): Option[(Anchor, LocalTime)] =
    val minute = value % AnchorFactor
    anchorOf(value / AnchorFactor)
      .filter(_ => minute < MinutesPerDay)
      .map(_ -> LocalTime.ofSecondOfDay(minute * 60))

  private def switch(value: Long)(set: Boolean => Reply): Reply =
    if value == On then set(true)
    else if value == Off then set(false)
    else Reply(toast = Some(ReminderCopy.staleControlToast))

  private def clockOf(profile: DayProfile, anchor: Anchor): String = PromptCopy.clock(profile.timeOf(anchor))
  private def workValue(work: WorkHours): String = SettingsCopy.workValue(work.days, work.start, work.end)

  private def quietValue(quiet: QuietHours): String =
    SettingsCopy.hours(ReminderCommand.toLocal(quiet.start), ReminderCommand.toLocal(quiet.end))

  private val actions: Set[Int] =
    ActionRegistry.entries.filter(e => e.name.startsWith("settings.") && e != Privacy).map(_.id).toSet

  /** The taps this handler takes: every settings action but "Privacy". */
  def handles(ref: CallbackRef): Boolean = actions(ref.actionId)

  private def named(name: String): ActionEntry =
    ActionRegistry.byName(name).getOrElse(throw IllegalStateException(s"action $name is not registered"))

  /** What the card shows: the stored settings, the day as the prompts use it, the zone and its time now, the two
    * switches kept outside the settings, whether the mood consent is given and which reports are on.
    */
  private final case class HubState(
      settings: AccountSettings,
      profile: DayProfile,
      zone: String,
      now: String,
      counts: Boolean,
      notes: Boolean,
      mood: Boolean,
      reports: List[(PromptKind, Boolean)],
      summary: Option[LocalTime]
  )

  /** One inbound event of a linked account: the message a tap came from, and the transaction of the event. */
  private final case class Turn(event: InboundEvent, account: UUID, source: Option[MessageHandle], tx: Tx)
end SettingsChat
