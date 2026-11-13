package dosegram.app

import dosegram.adapter.console.ConsoleAdapter
import dosegram.adapter.telegram.TelegramAdapter
import dosegram.contracts.ChatAdapter
import dosegram.core.application.Application
import dosegram.core.application.CommandRegistry
import dosegram.core.application.capture.CaptureParts
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.ChatMediator
import dosegram.core.ports.Clock
import dosegram.core.ports.IdSource
import dosegram.infra.Adapter
import dosegram.infra.Settings
import dosegram.infra.db.PgUnitOfWork
import ox.Ox

import java.security.SecureRandom
import javax.sql.DataSource

/** Adapter wiring (ROADMAP M0.12c/d, M1.9, M2.1): builds the adapters named by `ENABLED_ADAPTERS`, wires them into the
  * mediator as their inbound sink and starts them inside the Ox root scope. The handler is the [[Application]]
  * composition with the capture surface (account create, add-medication wizard, main menu, Today, History,
  * pause/resume, Account -> Timezone, water, mood, statistics, consents, `/help`); the mediator's ack policy reads the
  * [[CommandRegistry]].
  */
object Adapters:

  /** The adapters named by `ENABLED_ADAPTERS`, by vendor. Building one opens no connection: the gateway half starts it,
    * the worker half only sends through it.
    */
  def build(settings: Settings, clock: Clock, images: StatusImages)(using Ox): Map[String, ChatAdapter] =
    settings.enabledAdapters.map {
      case Adapter.Console =>
        val userId = settings.consoleUserId.getOrElse(
          throw IllegalArgumentException("CONSOLE_USER_ID is required when the console adapter is enabled")
        )
        "console" -> ConsoleAdapter.stdio(userId)
      case Adapter.Telegram =>
        val telegram = settings.telegram.getOrElse(
          throw IllegalArgumentException("TELEGRAM_TOKEN is required when the telegram adapter is enabled")
        )
        TelegramAdapter.Vendor -> TelegramAssembly.production(settings, telegram, clock)(images)
    }.toMap

  /** Starts every adapter as a gateway; returns `(name, stop)` pairs for the drain. */
  def start(settings: Settings, dataSource: DataSource, parts: Launch.Parts, log: String => Unit)(using
      Ox
  ): List[(String, () => Unit)] =
    val adapters = parts.adapters
    val uow = PgUnitOfWork(dataSource, parts.clock)
    val codec = CallbackCodec(settings.callbackKeys)
    val capture = CaptureParts.over(uow, parts.clock, IdSource.uuidV7(SecureRandom()), parts.images.cards)
    val mediator = ChatMediator(
      uow,
      adapters,
      codec,
      Application.handler(uow, adapters, codec, parts.clock)(capture),
      parts.clock,
      commands = CommandRegistry.withCaptureByName
    )
    adapters.foreach((name, adapter) =>
      adapter.start(mediator, None)
      // The command surface is registered at startup (R53); a no-op on the text-only console profile.
      adapter.registerCommands(CommandRegistry.withCapture)
      log(s"adapter started: $name")
    )
    adapters.toList.map((name, adapter) => name -> (() => adapter.stop()))
