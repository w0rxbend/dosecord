package dosegram.adapter.telegram

import dosegram.contracts.InteractionHandle
import dosegram.contracts.MessageHandle
import dosegram.contracts.RenderedMessage

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** The timing of the single callback answer (ARCHITECTURE TG-11, TG-12). All configuration. */
final case class AckTiming(
    /** If nothing answered by then, the watchdog sends the neutral answer. */
    watchdog: Duration,
    /** How long a neutral answer is held back so that a toast that follows can take its place. */
    neutralGrace: Duration,
    /** A query received longer ago than this is not answered at all: the API would reject the answer. */
    maxAge: Duration
)

object AckTiming:
  val Default: AckTiming =
    AckTiming(
      watchdog = Duration.ofMillis(1500),
      neutralGrace = Duration.ofMillis(300),
      maxAge = Duration.ofSeconds(30)
    )

/** What a [[CallbackAnswer]] needs from the adapter that created it. */
private[telegram] trait AnswerPort:
  def now(): Instant
  def schedule(delay: Duration)(task: () => Unit): Unit

  /** Puts the answer on the wire; reports a failure itself and never throws. */
  def answerQuery(queryId: String, toast: Option[String], waited: Duration): Unit
  def editMessage(handle: MessageHandle, rendered: RenderedMessage): Unit

/** The live handle of one callback query (ADR-015, ADR-019): exactly one `answerCallbackQuery` goes on the wire.
  *
  * The mediator answers with no toast before database work (ADR-013), and a handler's toast comes after it. Telegram
  * shows one answer only, so a neutral answer is held back for [[AckTiming.neutralGrace]]: a toast that arrives inside
  * that time is sent in its place, otherwise the neutral answer goes out. `acked` therefore reports whether the answer
  * is spent on the wire. The watchdog started at receipt sends the neutral answer at [[AckTiming.watchdog]] whatever
  * happened (TG-12); the first wire answer wins.
  */
private[telegram] final class CallbackAnswer(
    queryId: String,
    source: Option[MessageHandle],
    timing: AckTiming,
    port: AnswerPort
) extends InteractionHandle:

  private val receivedAt = port.now()
  private val spent = AtomicBoolean(false)
  private val neutralRequested = AtomicBoolean(false)

  port.schedule(timing.watchdog)(() => sendOnce(None))

  override def answer(toast: Option[String]): Unit =
    toast match
      case Some(_) => sendOnce(toast)
      case None    =>
        if !spent.get() && neutralRequested.compareAndSet(false, true) then
          port.schedule(timing.neutralGrace)(() => sendOnce(None))

  override def editSource(rendered: RenderedMessage): Unit = source.foreach(port.editMessage(_, rendered))

  override def acked: Boolean = spent.get()

  /** Claims the single answer and sends it. A query older than `maxAge` is claimed and not sent (TG-11). */
  private def sendOnce(toast: Option[String]): Unit =
    if spent.compareAndSet(false, true) then
      val waited = Duration.between(receivedAt, port.now())
      if waited.compareTo(timing.maxAge) <= 0 then port.answerQuery(queryId, toast, waited)
