package dosecord.core.application

import dosecord.contracts.*
import dosecord.core.application.identity.AccountCreateFlow
import dosecord.core.application.mood.MoodCommand
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.ChatHandler
import dosecord.core.chat.WizardEngine
import dosecord.core.domain.copy.HelpCopy
import dosecord.core.domain.copy.IdentityCopy
import dosecord.core.ports.Clock
import dosecord.core.ports.Tx
import dosecord.core.ports.UnitOfWork

/** The M0.12d handler composition (DESIGN.md sections 3 and 4.6 step 6): the identity gate in front of the
  * [[WizardEngine]] (which owns the account-create flow's session), in front of the plain command handlers.
  *
  * `/start` and `ConversationStarted` are deliberately NOT engine `flowCommands`: the gate checks the stamped principal
  * first, so a second create from an already-linked identity answers with the catalogue error and writes no row — not
  * even a session (R3).
  */
object FirstFlows:

  def flows(clock: Clock): Map[String, dosecord.core.chat.Flow] =
    Map(AccountCreateFlow.Id -> AccountCreateFlow.flow(clock))

  def handler(uow: UnitOfWork, adapters: Map[String, ChatAdapter], codec: CallbackCodec, clock: Clock): ChatHandler =
    val engine = WizardEngine(
      flows = flows(clock),
      flowCommands = Map.empty,
      codec = codec,
      clock = clock,
      inner = CommandHandlers(clock),
      uow = uow,
      adapters = adapters
    )
    IdentityGate(engine)

  /** Routes `ConversationStarted` and `/start` into the create flow, guarding the already-linked case. */
  private final class IdentityGate(engine: WizardEngine) extends ChatHandler:
    override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
      event.body match
        case Inbound.ConversationStarted                              => startCreate(event, principal, tx)
        case Inbound.CommandInvoked(CommandRegistry.Start.name, _, _) => startCreate(event, principal, tx)
        case _                                                        => engine.handle(event, principal, tx)

    private def startCreate(event: InboundEvent, principal: Principal, tx: Tx): Reply =
      if principal.linked then
        Reply(followUps = List(FirstFlows.outbound(event, "identity.linked", IdentityCopy.alreadyLinked)))
      else engine.startFlow(event, principal, AccountCreateFlow.Id, tx)

  /** The non-wizard commands of the slice: `/mood`, `/help` (rendered from the registry) and the fallbacks. `/menu`
    * shows help until M1.9 builds the real menu; the engine still treats it as the global interrupt first.
    */
  private final class CommandHandlers(clock: Clock) extends ChatHandler:
    private val mood = MoodCommand(clock)

    override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
      event.body match
        case Inbound.CommandInvoked(CommandRegistry.Mood.name, _, _) => mood.handle(event, principal, tx)
        case Inbound.CommandInvoked(CommandRegistry.Help.name, _, _) => help(event)
        case Inbound.CommandInvoked(CommandRegistry.Menu.name, _, _) => help(event)
        case Inbound.CommandInvoked(_, _, _)                         =>
          Reply(followUps = List(FirstFlows.outbound(event, "help.unknown", HelpCopy.unknownCommand)))
        case _: Inbound.MessageReceived =>
          Reply(followUps = List(FirstFlows.outbound(event, "help.unknown", HelpCopy.unknownMessage)))
        case _ => Reply.empty

    /** Rendered from the [[CommandRegistry]] so it cannot omit a registered command — including `/help` itself (K9). */
    private def help(event: InboundEvent): Reply =
      val lines =
        HelpCopy.intro +: CommandRegistry.all.map(spec => s"${CommandRegistry.usage(spec)} — ${spec.description}")
      Reply(followUps =
        List(
          OutboundMessage(
            body = lines.map(line => Node.Paragraph(List(Inline.Text(line)))),
            dedupeKey = s"help:${event.vendor}:${event.vendorEventId}",
            correlationId = s"${event.vendor}:${event.vendorEventId}"
          )
        )
      )

  private def outbound(event: InboundEvent, kind: String, text: String): OutboundMessage =
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text(text)))),
      dedupeKey = s"$kind:${event.vendor}:${event.vendorEventId}",
      correlationId = s"${event.vendor}:${event.vendorEventId}"
    )
end FirstFlows
