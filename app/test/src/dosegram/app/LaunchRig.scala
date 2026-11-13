package dosegram.app

import dosegram.adapter.console.ConsoleAdapter
import dosegram.contracts.ChatAdapter
import dosegram.contracts.ChatError
import dosegram.contracts.ChatRef
import dosegram.contracts.MessageHandle
import dosegram.contracts.RenderedMessage
import dosegram.core.ports.Clock

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PipedReader
import java.io.PipedWriter
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The injected clock of the launch-path tests: the test steps it, the forks of the launched application read it. */
final class SteppedClock(start: Instant) extends Clock:
  private val current = AtomicReference(start)

  override def now(): Instant = current.get()

  def advance(by: Duration): Unit =
    current.updateAndGet(_.plus(by))
    ()

/** The real console adapter on streams the test owns: what the user types goes in through a pipe, what the adapter
  * prints is captured.
  */
final class LaunchRig(clock: Clock) extends AutoCloseable:
  private val typed = PipedWriter()
  private val printed = ByteArrayOutputStream()

  val console: ConsoleAdapter =
    ConsoleAdapter(
      LaunchRig.ConsoleUser,
      BufferedReader(PipedReader(typed)),
      PrintStream(printed, true, UTF_8),
      "launch",
      () => clock.now()
    )

  /** One entry per message the adapter printed (`[#<n>] <text>`), in send order. */
  def messages: List[String] =
    printed.toString(UTF_8).split("(?m)^(?=\\[#\\d+\\] )").toList.filter(_.nonEmpty)

  def typeLine(line: String): Unit =
    typed.write(line + "\n")
    typed.flush()

  /** Ends the adapter's reader thread: its `readLine` sees the end of the stream. */
  override def close(): Unit = typed.close()

object LaunchRig:
  val ConsoleUser = "owner"

/** The console adapter behind a vendor that refuses the first send with a retryable error. */
final class FailingFirstSend(console: ChatAdapter) extends ChatAdapter:
  export console.{send as _, *}

  private val failed = AtomicBoolean(false)

  override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle =
    if failed.compareAndSet(false, true) then throw ChatError.Retryable("launch-path test: first send refused")
    else console.send(chat, rendered, sendKey)
