package dosegram.adapter.console

import dosegram.contracts.*
import dosegram.core.application.CommandRegistry
import dosegram.core.application.FirstFlows
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackKey
import dosegram.core.chat.CallbackKeys
import dosegram.core.chat.ChatMediator
import dosegram.core.ports.Clock

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import scala.io.Source
import scala.util.Using

/** M0.12d acceptance on the real console module: the `/start` -> timezone -> confirm -> `/help` transcript
  * through the actual `ConsoleAdapter` (its input grammar, its `[#n]` printing), golden-filed. The core-profile half is
  * `FirstFlowsSuite`; the Postgres half is `FirstFlowsPgSuite`.
  */
class ConsoleFirstFlowsSuite extends munit.FunSuite:

  test("first flows transcript golden on the real console adapter"):
    ox.supervised:
      val rig = ConsoleFlowRig()
      val transcript = rig.drive(ConsoleFlowRig.Script)
      assertEquals(
        transcript,
        ConsoleFlowRig.expectedGolden,
        "transcript drifted; regenerate with " +
          "./mill adapter-console.test.runMain dosegram.adapter.console.consoleFirstFlowsGoldenGenerate"
      )

      // Row-level effects on the in-memory stores.
      assertEquals(rig.uow.accounts.all.size, 1, "exactly one account")
      assertEquals(rig.uow.accounts.all.head.currentTimezone, "Europe/Kyiv")
      assertEquals(rig.uow.accounts.all.head.displayName, None, "display name stays neutral")
      assertEquals(rig.uow.domainEvents.all.map(_.eventType), List(Event.AccountCreatedType))
      rig.uow.domainEvents.all.foreach: e =>
        assertEquals(e.accountId, Some(rig.uow.accounts.all.head.accountId), s"${e.eventType} account_id")
      assertEquals(rig.uow.sessions.all, Nil, "no leftover sessions")

/** Drives the real ConsoleAdapter line by line and interleaves input with its printed output. */
private final class ConsoleFlowRig(using ox.Ox):
  import ConsoleFlowRig.*

  val uow = ConsoleFlowFakes.InMemoryUnitOfWork()
  private val out = ByteArrayOutputStream()
  private val adapter = ConsoleAdapter("owner", BufferedReader(StringReader("")), PrintStream(out), "s1", () => t0)
  private val adapters: Map[String, ChatAdapter] = Map("console" -> adapter)
  // One event can print several messages (the consent receipt, then the welcome): a reply is complete only when the
  // mediator's FIFO for the chat has reached the fence behind it, not when the first output appears.
  private val barrier = ConsoleFlowFakes.DeliveryBarrier(uow)
  private val mediator = ChatMediator(
    barrier.uow,
    adapters,
    codec,
    FirstFlows.handler(uow, adapters, codec, clock),
    clock,
    CommandRegistry.byName
  )

  private def printed: String = out.toString("UTF-8")

  /** Pushes one typed line and returns the newly printed output. */
  def line(input: String): String =
    val before = printed.length
    val event = adapter.eventFor(input)
    mediator.push(event)
    barrier.drain(mediator, event)
    assert(printed.length > before, s"'$input' produced output")
    printed.substring(before)

  def drive(script: List[String]): String =
    script.map(input => s"> $input\n" + line(input)).mkString("")

private object ConsoleFlowRig:
  val t0: Instant = Instant.parse("2026-09-21T00:00:00Z")
  val clock: Clock = new Clock:
    override def now(): Instant = t0
  val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  /** The acceptance script: create with a wrong turn, the refused /mood, help, and the refused second create. */
  val Script: List[String] = List(
    "/start",
    "1", // Medication
    "1", // I agree
    "1", // Confirm
    "22", // Other — type your timezone
    "Mars/Olympus_Mons", // invalid zone: the picker is re-shown, no row written
    "13", // UTC+03:00 — Europe/Kyiv
    "1", // Yes
    "/mood 8", // no mood consent yet: not a command (BUX-CMD-08)
    "/help",
    "/start" // already linked: catalogue error, no row
  )

  val ResourcePath = "/goldens/first-flows.console.golden"
  val FilePath = "adapter-console/test/resources/goldens/first-flows.console.golden"

  def expectedGolden: String =
    val stream = Option(getClass.getResourceAsStream(ResourcePath))
      .getOrElse(throw IllegalStateException(s"$ResourcePath is not on the test classpath"))
    Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).get

/** Regenerates the console transcript golden:
  * `./mill adapter-console.test.runMain dosegram.adapter.console.consoleFirstFlowsGoldenGenerate`.
  */
@main def consoleFirstFlowsGoldenGenerate(): Unit =
  ox.supervised:
    val transcript = ConsoleFlowRig().drive(ConsoleFlowRig.Script)
    val target = Path.of(ConsoleFlowRig.FilePath)
    Option(target.getParent).foreach(Files.createDirectories(_))
    Files.writeString(target, transcript)
    println(s"wrote $target")
