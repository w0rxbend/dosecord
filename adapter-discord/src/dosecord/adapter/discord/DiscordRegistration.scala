package dosecord.adapter.discord

import dosecord.contracts.CommandArg
import dosecord.contracts.CommandSpec

/** The global slash registration plan (ROADMAP M2.1, ADR-001 addendum): `USER_INSTALL` only — the M0.8 GO verdict
  * dropped `GUILD_INSTALL` and the home-guild step — and the `BOT_DM` context only, so a guild or group-DM invocation
  * is impossible by registration. The ephemeral redirect for non-DM contexts stays as defence in depth only.
  *
  * Options come from the core's `CommandSpec` (R53: slash commands only, no prefix commands): an argument whose choices
  * are all integers registers as an INTEGER option (`/mood` 1-10, `/snooze` minutes), everything else as STRING;
  * `autocomplete` args (medication names) enable Discord's autocomplete, answered over `medications.name_norm`.
  */
object DiscordRegistration:

  def plan(specs: List[CommandSpec]): List[DiscordCommand] =
    specs.map: spec =>
      DiscordCommand(
        name = spec.name,
        description = spec.description,
        options = spec.args.map(toOption),
        integrationTypes = Set(DiscordIntegrationType.UserInstall),
        contexts = Set(DiscordContext.BotDm)
      )

  private def toOption(arg: CommandArg): DiscordCommandOption =
    val integerChoices = arg.choices.nonEmpty && arg.choices.forall(_.toIntOption.isDefined)
    DiscordCommandOption(
      name = arg.name,
      description = arg.description,
      required = arg.required,
      kind = if integerChoices then DiscordOptionKind.Integer else DiscordOptionKind.String,
      choices = arg.choices,
      autocomplete = arg.autocomplete
    )
