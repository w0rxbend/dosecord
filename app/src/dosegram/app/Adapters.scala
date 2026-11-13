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
import dosegram.core.ports.InboundInbox
import dosegram.infra.Adapter
import dosegram.infra.Settings
import dosegram.infra.db.PgUnitOfWork
import ox.Ox
import ox.discard
import ox.fork

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
  def build(settings: Settings, clock: Clock, images: StatusImages, inbox: InboundInbox)(using
      Ox
  ): Map[String, ChatAdapter] =
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
        val adapter = TelegramAssembly.production(settings, telegram, clock)(images, inbox)
        images.notices.attach(TelegramAdapter.Vendor, adapter.showUploadingPhoto)
        TelegramAdapter.Vendor -> adapter
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
      startInbox(adapter)
      log(s"adapter started: $name")
    )
    adapters.toList.map((name, adapter) => name -> (() => adapter.stop()))

  /** The loop of an adapter's durable inbox (ROADMAP G1.7), in a fork of the root scope; the adapter's `stop` ends it.
    */
  private def startInbox(adapter: ChatAdapter)(using Ox): Unit =
    adapter match
      case telegram: TelegramAdapter => telegram.inboxProcessor.foreach(processor => fork(processor.run()).discard)
      case _                         => ()
