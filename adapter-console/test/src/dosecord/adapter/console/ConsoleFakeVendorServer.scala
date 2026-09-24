package dosecord.adapter.console

import dosecord.contracts.*
import dosecord.tests.conformance.*

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import scala.collection.mutable.ListBuffer

/** The console's fake vendor wire (suite A, ROADMAP M0.13): a pipe stands in for stdin and a buffer for stdout; the
  * printed `[#n] ...` records are parsed back into sent observations. The console has no vendor faults, acks, modal
  * payloads or log output, so those surfaces are empty (and the scenarios needing them skip on this profile).
  */
final class ConsoleFakeVendorServer(sink: RecordingSink) extends FakeVendorServer:
  private val pipeIn = PipedInputStream()
  private val pipeOut = PipedOutputStream(pipeIn)
  private val buffer = ByteArrayOutputStream()
  private val deliveriesBuf = ListBuffer.empty[(WireEvent, InboundEvent)]
  @volatile private var at = Instant.parse("2026-09-21T00:00:00Z")

  val reader: BufferedReader = BufferedReader(InputStreamReader(pipeIn, StandardCharsets.UTF_8))
  val out: PrintStream = PrintStream(buffer, true, StandardCharsets.UTF_8)

  /** The adapter's clock: the fake wire's clock. */
  def now: Instant = at

  override def deliver(event: WireEvent): Unit =
    val line = event match
      case WireEvent.Message(_, _, text, None)         => text
      case WireEvent.Message(_, _, text, Some(target)) => s"#$target $text"
      case WireEvent.Command(_, _, _, text)            => text
      case WireEvent.Callback(_, _, _, _) =>
        throw new IllegalArgumentException("the console transport has no callback wire event (text-only profile)")
      case WireEvent.Select(_, _, _, _) =>
        throw new IllegalArgumentException("the console transport has no select wire event (text-only profile)")
      case WireEvent.ModalSubmit(_, _, _, _, _) =>
        throw new IllegalArgumentException("the console transport has no modal wire event (text-only profile)")
    val before = sink.all.size
    pipeOut.write((line + "\n").getBytes(StandardCharsets.UTF_8))
    pipeOut.flush()
    await(sink.all.size > before, s"no inbound event for line '$line'")
    deliveriesBuf += ((event, sink.all.last))

  override def deliveries: List[(WireEvent, InboundEvent)] = deliveriesBuf.toList

  override def sent: List[SentObservation] =
    ConsoleFakeVendorServer.parsePrinted(buffer.toString(StandardCharsets.UTF_8))

  override def acks: List[AckObservation] = Nil
  override def modalPayloads: List[String] = Nil
  override def failNext(fault: VendorFault): Unit =
    throw new IllegalStateException(s"the console transport has no vendor faults (got $fault)")
  override def clock: Instant = at
  override def advanceClock(by: Duration): Unit = at = at.plus(by)
  override def logs: List[String] = Nil

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 10_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(10)
      ok = cond
    if !ok then throw AssertionError(clue)

object ConsoleFakeVendorServer:
  private val Record = "(?m)^\\[#(\\d+)\\] ".r

  /** The adapter prints `[#n] <chunks joined by \n>` per message; parse the buffer back into observations. */
  def parsePrinted(text: String): List[SentObservation] =
    val starts = Record.findAllMatchIn(text).toList
    starts.zipWithIndex.map { (m, i) =>
      val end = if i + 1 < starts.size then starts(i + 1).start else text.length
      val content = text.substring(m.end, end).stripSuffix("\n")
      SentObservation(Some(m.group(1)), content, text.substring(m.start, end).stripSuffix("\n"))
    }
