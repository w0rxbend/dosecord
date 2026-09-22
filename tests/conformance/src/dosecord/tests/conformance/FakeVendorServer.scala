package dosecord.tests.conformance

import dosecord.contracts.*

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** Suite A (ROADMAP M0.13, DESIGN.md section 11 test layer 4): one JSON scenario set every adapter runs against a fake
  * vendor transport. This file is the vendor-neutral vocabulary — the wire events the fake server delivers, the faults
  * it can raise, and what it observes.
  */

/** A raw vendor event as the fake wire delivers it. `wireId` is the vendor-native identity where one exists (a Telegram
  * update id, a Discord interaction id); a transport without server-side event identity (the console's stdin) ignores
  * it and declares no `redelivery` trait, so the duplicate scenario does not apply to it.
  */
enum WireEvent:
  case Message(wireId: String, chat: String, text: String, replyToMessageId: Option[String])
  case Command(wireId: String, chat: String, name: String, text: String)
  case Callback(wireId: String, chat: String, callback: String, sourceMessageId: String)

  def wireKey: String = this match
    case Message(id, _, _, _)  => id
    case Command(id, _, _, _)  => id
    case Callback(id, _, _, _) => id

  def chatKey: String = this match
    case Message(_, chat, _, _)  => chat
    case Command(_, chat, _, _)  => chat
    case Callback(_, chat, _, _) => chat

/** Vendor faults the fake wire raises, with the adapter-contract mappings of DESIGN.md section 4.5: NotModified and
  * DuplicateReaction are success; DeleteWindow and EditNotFound are `Permanent(channelFatal = false)`; Blocked is
  * `Unreachable` (`channelFatal = true`); RateLimited carries the vendor's Retry-After.
  */
enum VendorFault:
  case RateLimited(retryAfterSeconds: Long)
  case NotModified
  case DuplicateReaction
  case DeleteWindow
  case EditNotFound
  case Blocked

/** One message the vendor observed the adapter send. `messageId` is the vendor-native message id the scenario steps
  * reference (`$last` resolves to the most recent observation's id).
  */
final case class SentObservation(messageId: Option[String], text: String, raw: String)

/** One ack the vendor observed (the deadline-auto-ack scenario), stamped with the fake clock. */
final case class AckObservation(kind: String, at: Instant)

/** The vendor-side test double (the "fake wire"): it delivers raw events inbound, records what the adapter sends, acks
  * and modal-opens, raises faults, owns the fake clock and captures logs. One instance per scenario run.
  */
trait FakeVendorServer:
  def deliver(event: WireEvent): Unit
  def deliveries: List[(WireEvent, InboundEvent)]
  def sent: List[SentObservation]
  def acks: List[AckObservation]
  def modalPayloads: List[String]
  def failNext(fault: VendorFault): Unit
  def clock: Instant
  def advanceClock(by: Duration): Unit
  def logs: List[String]

  /** The runner reports the scenario's `start` step here before calling `adapter.start`, so transports that mint their
    * own cursor (the console's stdin sequence) can continue it; the default needs nothing.
    */
  def onStart(resumeFrom: Option[String]): Unit = ()

/** Records every event the adapter pushes to its sink. */
final class RecordingSink extends InboundSink:
  private val events = ConcurrentLinkedQueue[InboundEvent]()
  override def push(event: InboundEvent): Boolean =
    events.add(event)
    true
  def all: List[InboundEvent] = events.asScala.toList

/** One adapter wired to a fresh fake server: everything a single scenario run touches. */
final case class AdapterUnderTest(adapter: ChatAdapter, server: FakeVendorServer, sink: RecordingSink)

/** Per-adapter wiring: how to build the adapter against the fake wire, and which transport traits it has. Traits gate
  * scenario applicability: `redelivery` (the vendor can deliver the same wire event twice), `httpStatus` (the vendor
  * answers status codes such as 429), `wireModal` (the vendor carries a raw modal payload). Capability requirements
  * (`edit`, `delete`, `react`, `buttons`, `select`, `modal`, `ephemeral`, `nativeCommands`, `transientAck`,
  * `ackDeadline`, `silent`) are evaluated against the adapter's CapabilityProfile.
  */
trait AdapterWiring:
  def vendor: String
  def profile: CapabilityProfile
  def transportTraits: Set[String]
  def defaultChat: ChatRef
  def start(scenario: String): AdapterUnderTest
