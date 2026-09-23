package dosecord.core.application

import dosecord.contracts.CommandArg
import dosecord.contracts.CommandSpec

/** The registered commands (ROADMAP M0.12d, R51, K9; `/today` and `/history` from M1.9; `/taken`, `/snooze`, `/skip`,
  * `/log` from M1.10). `/help` renders from this registry, so it cannot omit a command — including itself; the
  * mediator's ack policy reads the same specs.
  */
object CommandRegistry:

  val Start: CommandSpec = CommandSpec(
    name = "start",
    description = "Create your account and pick a timezone."
  )

  val Mood: CommandSpec = CommandSpec(
    name = "mood",
    description = "Log your mood.",
    args = List(
      CommandArg("level", "1-10 or a word like okay or great", choices = (1 to 10).map(_.toString).toList),
      CommandArg("note", "An optional note", required = false),
      CommandArg("tags", "Optional #tags", required = false)
    )
  )

  val Today: CommandSpec = CommandSpec(
    name = "today",
    description = "Show today's doses and record them."
  )

  val History: CommandSpec = CommandSpec(
    name = "history",
    description = "Show the last 7 days."
  )

  val Taken: CommandSpec = CommandSpec(
    name = "taken",
    description = "Record the latest dose as taken.",
    args = List(CommandArg("medication", "Which medication; omitted takes the latest open dose", required = false))
  )

  val Snooze: CommandSpec = CommandSpec(
    name = "snooze",
    description = "Snooze the latest dose.",
    args = List(CommandArg("minutes", "How many minutes, e.g. /snooze 10"))
  )

  val Skip: CommandSpec = CommandSpec(
    name = "skip",
    description = "Skip the latest dose.",
    args = List(CommandArg("medication", "Which medication; omitted skips the latest open dose", required = false))
  )

  val Log: CommandSpec = CommandSpec(
    name = "log",
    description = "Log a dose you already took.",
    args = List(CommandArg("medication", "Which medication; omitted shows a picker", required = false))
  )

  val Menu: CommandSpec = CommandSpec(
    name = "menu",
    description = "Open the main menu."
  )

  val Cancel: CommandSpec = CommandSpec(
    name = "cancel",
    description = "Cancel the current setup."
  )

  val Help: CommandSpec = CommandSpec(
    name = "help",
    description = "Show this help."
  )

  /** Display order is the registration order. */
  val all: List[CommandSpec] = List(Start, Today, History, Taken, Snooze, Skip, Log, Mood, Menu, Cancel, Help)

  val byName: Map[String, CommandSpec] = all.map(spec => spec.name -> spec).toMap

  /** `/mood <level> [note] [tags]` — usage from the spec's args so help cannot drift from the registry. */
  def usage(spec: CommandSpec): String =
    val args = spec.args.map(arg => if arg.required then s"<${arg.name}>" else s"[${arg.name}]")
    (("/" + spec.name) +: args).mkString(" ")
end CommandRegistry
