package dosecord.app

import dosecord.adapter.console.ConsoleAdapter
import dosecord.adapter.discord.DiscordAdapter
import dosecord.contracts.ChatAdapter
import dosecord.contracts.PlatformIdentity
import dosecord.core.application.Application
import dosecord.core.application.CommandRegistry
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.ChatMediator
import dosecord.core.ports.Clock
import dosecord.infra.Adapter
import dosecord.infra.Metrics
import dosecord.infra.Settings
import dosecord.infra.db.PgUnitOfWork
import io.micrometer.core.instrument.Timer
import ox.Ox

import java.util.Locale
import javax.sql.DataSource

/** Adapter wiring (ROADMAP M0.12c/d, M1.9, M2.1): builds the adapters named by `ENABLED_ADAPTERS`, wires them into the
  * mediator as their inbound sink and starts them inside the Ox root scope. The handler is the M1.9 [[Application]]
  * composition (account create, add-medication wizard, main menu, Today, History, pause/resume, Account -> Timezone,
  * `/mood`, `/help`); the mediator's ack policy reads the [[CommandRegistry]].
  */
object Adapters:

  /** Starts every configured adapter; returns `(name, stop)` pairs for the drain. */
  def start(settings: Settings, dataSource: DataSource, log: String => Unit)(using Ox): List[(String, () => Unit)] =
    val uow = PgUnitOfWork(dataSource, Clock.system)
    val adapters: Map[String, ChatAdapter] = settings.enabledAdapters.map {
      case Adapter.Console =>
        val userId = settings.consoleUserId.getOrElse(
          throw IllegalArgumentException("CONSOLE_USER_ID is required when the console adapter is enabled")
        )
        "console" -> ConsoleAdapter.stdio(userId)
      case Adapter.Discord =>
        val token = settings.discordToken.getOrElse(
          throw IllegalArgumentException("DISCORD_TOKEN is required when the discord adapter is enabled")
        )
        "discord" -> DiscordAdapter.jda(token, autocompleteProvider(uow), d => ackLatencyTimer.record(d), log = log)
      case other =>
        throw IllegalArgumentException(s"adapter '${other.envName}' is not implemented in this build")
    }.toMap
    val codec = CallbackCodec(settings.callbackKeys)
    val mediator = ChatMediator(
      uow,
      adapters,
      codec,
      Application.handler(uow, adapters, codec, Clock.system),
      Clock.system,
      commands = CommandRegistry.byName,
      recordFormDegraded = reason => Metrics.formDegraded(reason)
    )
    adapters.foreach((name, adapter) =>
      adapter.start(mediator, None)
      // The slash surface is registered at startup (R53): global commands with USER_INSTALL and the bot-DM context
      // (ROADMAP M2.1); a no-op on the text-only console profile.
      adapter.registerCommands(CommandRegistry.all)
      log(s"adapter started: $name")
    )
    adapters.toList.map((name, adapter) => name -> (() => adapter.stop()))

  /** Slash-option autocomplete over `medications.name_norm` (ROADMAP M2.1, the hard requirement deferred from M0.8):
    * one indexed identity read plus the prefix search, scoped to the resolved account; nothing for unlinked identities.
    */
  private def autocompleteProvider(uow: PgUnitOfWork): (PlatformIdentity, String) => List[String] =
    (identity, query) =>
      val prefix = query.trim.toLowerCase(Locale.ROOT)
      if prefix.isEmpty then Nil
      else
        uow.transaction: tx =>
          tx.identities.find(identity).flatMap(_.accountId) match
            case None          => Nil
            case Some(account) =>
              tx.medications.searchByNameNormPrefix(account.uuid, prefix, AutocompleteLimit).map(_.name)

  private val AutocompleteLimit = 25 // Discord's autocomplete choice cap.

  /** `dosecord_interaction_ack_latency_seconds{vendor="discord"}` (DESIGN.md section 10): measured from the interaction
    * snowflake by the adapter's ack path.
    */
  private[app] val ackLatencyTimer: Timer =
    Timer
      .builder("dosecord_interaction_ack_latency_seconds")
      .tag("vendor", "discord")
      .publishPercentileHistogram()
      .register(Metrics.registry)
