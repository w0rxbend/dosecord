package dosecord.contracts

import scala.compiletime.summonAll

import upickle.default.*

/** Acceptance 2: every `ChatAdapter` argument and return type derives `ReadWriter` — the ADR-010/M6.3-alt
  * out-of-process escape hatch speaks the trait over JSON, so its whole data surface must stay serialisable. The two
  * non-data types are excluded by design and listed here so a signature change fails loudly: `InboundSink` (a
  * mediator-side callback; the M6.3-alt host stubs it with `InboundEvent` as the wire payload) and `InteractionHandle`
  * (a live vendor handle, never crosses a wire).
  */
class AdapterReadWriterSuite extends munit.FunSuite:

  // Mirrors ChatAdapter's method signatures, in declaration order:
  //   vendor: String; capabilities: CapabilityProfile
  //   start(sink: InboundSink*, resumeFrom: Option[String]): Unit            * excluded (callback)
  //   stop(): Unit
  //   send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle
  //   edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle
  //   delete(handle: MessageHandle): Unit
  //   react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit
  //   registerCommands(specs: List[CommandSpec]): Unit
  //   resolveChat(identity: PlatformIdentity): ChatRef
  //   renderText(text: RichText): List[String]
  private type ChatAdapterSignatureTypes = (
      String,
      CapabilityProfile,
      Option[String],
      Unit,
      ChatRef,
      RenderedMessage,
      MessageHandle,
      Boolean,
      List[CommandSpec],
      PlatformIdentity,
      RichText,
      List[String]
  )

  test("every ChatAdapter argument and return type derives ReadWriter"):
    summonAll[Tuple.Map[ChatAdapterSignatureTypes, ReadWriter]]
    // The payloads the sink direction and the op/error channels cross the wire with.
    summon[ReadWriter[InboundEvent]]
    summon[ReadWriter[VendorOp]]
    summon[ReadWriter[ChatError]]
    ()
