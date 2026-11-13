package dosegram.app

import dosegram.adapter.console.ConsoleAdapter
import dosegram.contracts.ChatAdapter
import dosegram.core.application.Application
import dosegram.core.application.CommandRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.ChatMediator
import dosegram.infra.Adapter
import dosegram.infra.Settings
import dosegram.infra.db.PgUnitOfWork
import ox.Ox

import javax.sql.DataSource

/** Adapter wiring (ROADMAP M0.12c/d, M1.9, M2.1): builds the adapters named by `ENABLED_ADAPTERS`, wires them into the
  * mediator as their inbound sink and starts them inside the Ox root scope. The handler is the M1.9 [[Application]]
  * composition (account create, add-medication wizard, main menu, Today, History, pause/resume, Account -> Timezone,
  * `/mood`, `/help`); the mediator's ack policy reads the [[CommandRegistry]].
  */
object Adapters:

  /** The adapters named by `ENABLED_ADAPTERS`, by vendor. Building one opens no connection: the gateway half starts it,
    * the worker half only sends through it.
    */
  def build(settings: Settings): Map[String, ChatAdapter] =
    settings.enabledAdapters.map {
      case Adapter.Console =>
        val userId = settings.consoleUserId.getOrElse(
          throw IllegalArgumentException("CONSOLE_USER_ID is required when the console adapter is enabled")
        )
        "console" -> ConsoleAdapter.stdio(userId)
      case other =>
        throw IllegalArgumentException(s"adapter '${other.envName}' is not implemented in this build")
    }.toMap

  /** Starts every adapter as a gateway; returns `(name, stop)` pairs for the drain. */
  def start(settings: Settings, dataSource: DataSource, parts: Launch.Parts, log: String => Unit)(using
      Ox
  ): List[(String, () => Unit)] =
    val adapters = parts.adapters
    val uow = PgUnitOfWork(dataSource, parts.clock)
    val codec = CallbackCodec(settings.callbackKeys)
    val mediator = ChatMediator(
      uow,
      adapters,
      codec,
      Application.handler(uow, adapters, codec, parts.clock),
      parts.clock,
      commands = CommandRegistry.byName
    )
    adapters.foreach((name, adapter) =>
      adapter.start(mediator, None)
      // The command surface is registered at startup (R53); a no-op on the text-only console profile.
      adapter.registerCommands(CommandRegistry.all)
      log(s"adapter started: $name")
    )
    adapters.toList.map((name, adapter) => name -> (() => adapter.stop()))
