package dosecord.app

import dosecord.adapter.console.ConsoleAdapter
import dosecord.contracts.ChatAdapter
import dosecord.core.application.Application
import dosecord.core.application.CommandRegistry
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.ChatMediator
import dosecord.core.ports.Clock
import dosecord.infra.Adapter
import dosecord.infra.Metrics
import dosecord.infra.Settings
import dosecord.infra.db.PgUnitOfWork
import ox.Ox

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
      recordFormDegraded = () => formDegradedCounter.increment()
    )
    adapters.foreach((name, adapter) =>
      adapter.start(mediator, None)
      // The command surface is registered at startup (R53); a no-op on the text-only console profile.
      adapter.registerCommands(CommandRegistry.all)
      log(s"adapter started: $name")
    )
    adapters.toList.map((name, adapter) => name -> (() => adapter.stop()))

  private[app] val formDegradedCounter: io.micrometer.core.instrument.Counter =
    io.micrometer.core.instrument.Counter.builder("dosecord_form_degraded_total").register(Metrics.registry)
