package dosecord.core.domain.copy

/** Help copy (ROADMAP M1.4a). `/help` is rendered from the `CommandSpec` registry (M0.12d) so it cannot omit a command;
  * these are the per-command description lines it uses, keyed by the command's usage string.
  */
object HelpCopy:

  val intro = "Dosecord keeps track of your medications, reminders and mood."

  /** Fallbacks for input that matches no command (the console is free-text). */
  val unknownCommand = "I don't know that command — try /help."
  val unknownMessage = "I didn't understand that — try /help."

  /** (usage, description) per command, in display order. */
  val commands: List[(String, String)] = List(
    "/start" -> "Create your account and pick a timezone.",
    "/today" -> "Show today's doses and record them.",
    "/history" -> "Show the last 7 days.",
    "/taken [medication]" -> "Record a dose as taken.",
    "/snooze <minutes>" -> "Snooze the current reminder.",
    "/skip [medication]" -> "Mark a dose as skipped.",
    "/log <medication>" -> "Log an as-needed dose.",
    "/mood <1-10> [note] [#tag ...]" -> "Log your mood.",
    "/menu" -> "Open the main menu.",
    "/cancel" -> "Cancel the current setup.",
    "/help" -> "Show this help."
  )

  val entries: List[CopyEntry] =
    List(
      CopyEntry("help.intro", intro),
      CopyEntry("help.unknown_command", unknownCommand),
      CopyEntry("help.unknown_message", unknownMessage)
    ) ++ commands.map((usage, description) =>
      CopyEntry(s"help.command.${usage.drop(1).takeWhile(_.isLetterOrDigit)}", s"$usage — $description")
    )
end HelpCopy
