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

  /** Display order is the registration order. A domain has no command here before it has a consent purpose and its
    * capture slice has shipped (BOT_UX BUX-CMD-08).
    */
  val all: List[CommandSpec] = List(Start, Today, Meds, History, Taken, Snooze, Skip, Log, Menu, App, Cancel, Help)

  val byName: Map[String, CommandSpec] = all.map(spec => spec.name -> spec).toMap

  /** `/snooze <minutes>` — usage from the spec's args so help cannot drift from the registry. */
  def usage(spec: CommandSpec): String =
    val args = spec.args.map(arg => if arg.required then s"<${arg.name}>" else s"[${arg.name}]")
    (("/" + spec.name) +: args).mkString(" ")
end CommandRegistry
