package dosecord.core.application

import dosecord.contracts.CommandArg
import dosecord.contracts.CommandSpec

/** The registered commands (ROADMAP M0.12d, R51, K9). `/help` renders from this registry, so it cannot omit a command —
  * including itself; the mediator's ack policy reads the same specs. Unshipped commands (M1.9's `/today`, `/history`,
  * `/log`, ...) are absent here, not flagged or teased.
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
  val all: List[CommandSpec] = List(Start, Mood, Menu, Cancel, Help)

  val byName: Map[String, CommandSpec] = all.map(spec => spec.name -> spec).toMap

  /** `/mood <level> [note] [tags]` — usage from the spec's args so help cannot drift from the registry. */
  def usage(spec: CommandSpec): String =
    val args = spec.args.map(arg => if arg.required then s"<${arg.name}>" else s"[${arg.name}]")
    (("/" + spec.name) +: args).mkString(" ")
end CommandRegistry
