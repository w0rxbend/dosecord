package dosegram.core.application

import dosegram.contracts.CommandArg
import dosegram.contracts.CommandSpec

/** The registered commands (ROADMAP M0.12d, R51, K9; `/today` and `/history` from M1.9; `/taken`, `/snooze`, `/skip`,
  * `/log` from M1.10; `/meds` and `/app` from BOT_UX section 6.1). `/help` renders from this registry, so it cannot
  * omit a command — including itself; the mediator's ack policy reads the same specs.
  */
object CommandRegistry:

  val Start: CommandSpec = CommandSpec(
    name = "start",
    description = "Create your account and pick a timezone."
  )

  val Today: CommandSpec = CommandSpec(
    name = "today",
    description = "Show today's doses and record them."
  )

  val Meds: CommandSpec = CommandSpec(
    name = "meds",
    description = "Show your medications."
  )

  val History: CommandSpec = CommandSpec(
    name = "history",
    description = "Show the last 7 days."
  )

  val Taken: CommandSpec = CommandSpec(
    name = "taken",
    description = "Record the latest dose as taken.",
    args = List(
      CommandArg(
        "medication",
        "Which medication; omitted takes the latest open dose",
        required = false
      )
    )
  )

  val Snooze: CommandSpec = CommandSpec(
    name = "snooze",
    description = "Snooze the latest dose.",
    args = List(
      CommandArg("minutes", "How many minutes, e.g. /snooze 10")
    )
  )

  val Skip: CommandSpec = CommandSpec(
    name = "skip",
    description = "Skip the latest dose.",
    args = List(
      CommandArg(
        "medication",
        "Which medication; omitted skips the latest open dose",
        required = false
      )
    )
  )

  val Log: CommandSpec = CommandSpec(
    name = "log",
    description = "Log a dose you already took.",
    args = List(
      CommandArg(
        "medication",
        "Which medication; omitted shows a picker",
        required = false
      )
    )
  )

  val Menu: CommandSpec = CommandSpec(
    name = "menu",
    description = "Open the main menu."
  )

  val App: CommandSpec = CommandSpec(
    name = "app",
    description = "Open the app."
  )

  val Cancel: CommandSpec = CommandSpec(
    name = "cancel",
    description = "Cancel the current setup."
  )

  val Help: CommandSpec = CommandSpec(
    name = "help",
    description = "Show this help."
  )

  val Water: CommandSpec = CommandSpec(
    name = "water",
    description = "Log water.",
    args = List(CommandArg("amount", "How much, e.g. /water 300; omitted shows the water card", required = false))
  )

  val Mood: CommandSpec = CommandSpec(
    name = "mood",
    description = "Log mood.",
    args = List(CommandArg("step", "A step from 1 to 5; omitted shows the prompt", required = false))
  )

  val Sleep: CommandSpec = CommandSpec(
    name = "sleep",
    description = "Log sleep.",
    args = List(
      CommandArg(
        "times",
        "Bedtime and wake time, e.g. /sleep 23:30 7:10; omitted shows the sleep card",
        required = false
      )
    )
  )

  val Food: CommandSpec = CommandSpec(
    name = "food",
    description = "Log a meal.",
    args = List(CommandArg("meal", "e.g. /food lunch: soup; omitted shows the meal buttons", required = false))
  )

  /** `/meal`: the hidden alias of `/food` (BOT_UX section 6.1). */
  val Meal: CommandSpec = CommandSpec(
    name = "meal",
    description = "Log a meal.",
    args = List(CommandArg("meal", "e.g. /meal lunch: soup; omitted shows the meal buttons", required = false))
  )

  val Stats: CommandSpec = CommandSpec(
    name = "stats",
    description = "Show statistics.",
    args = List(CommandArg("period", "today, 7, 30 or month; omitted shows the last 7 days", required = false))
  )

  val Privacy: CommandSpec = CommandSpec(
    name = "privacy",
    description = "Show and withdraw your consents."
  )

  /** Display order is the registration order. A domain has no command here before it has a consent purpose and its
    * capture slice has shipped (BOT_UX BUX-CMD-08).
    */
  val all: List[CommandSpec] = List(Start, Today, Meds, History, Taken, Snooze, Skip, Log, Menu, App, Cancel, Help)

  val byName: Map[String, CommandSpec] = all.map(spec => spec.name -> spec).toMap

  /** The commands of the capture domains and of their consents. They exist only in a composition that has a consent
    * ledger (BUX-CMD-08): [[Application.withCapture]] handles them, [[all]] stays the list of a composition without
    * one.
    */
  val capture: List[CommandSpec] = List(Water, Mood, Sleep, Food, Stats, Privacy)

  /** The command list of the composition with capture, with the capture commands ahead of `/menu`. */
  val withCapture: List[CommandSpec] =
    all.flatMap(spec => if spec == Menu then capture :+ spec else List(spec))

  /** `/reminders` (ADR-023): hidden, so the listed set stays at its limit (BOT_UX BUX-CMD-06); it is still declared, so
    * the mediator acknowledges it before work.
    */
  val Reminders: CommandSpec = CommandSpec(
    name = "reminders",
    description = "Reminders, presets, work and quiet hours.",
    args = List(CommandArg("setting", "e.g. wake 06:30, work mon-fri 09:00-18:00, pause 1", required = false))
  )

  /** `/insights` (VISUALS section 8.5): hidden like `/reminders`, so the listed set does not grow (BUX-CMD-06). */
  val Insights: CommandSpec = CommandSpec(
    name = "insights",
    description = "Patterns in your records."
  )

  /** `/export` (BOT_UX section 20.2): hidden, like `/reminders`, and declared so the mediator acknowledges it. */
  val Export: CommandSpec = CommandSpec(
    name = "export",
    description = "Export your records, or make a record summary."
  )

  val withCaptureByName: Map[String, CommandSpec] =
    (withCapture :+ Reminders :+ Meal :+ Insights :+ Export).map(spec => spec.name -> spec).toMap

  /** `/snooze <minutes>` — usage from the spec's args so help cannot drift from the registry. */
  def usage(spec: CommandSpec): String =
    val args = spec.args.map(arg => if arg.required then s"<${arg.name}>" else s"[${arg.name}]")
    (("/" + spec.name) +: args).mkString(" ")
end CommandRegistry
