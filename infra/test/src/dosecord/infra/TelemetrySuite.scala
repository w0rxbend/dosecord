package dosecord.infra

import dosecord.core.telemetry.LogContext

import munit.FunSuite
import ox.supervised
import scribe.Level
import scribe.LogRecord
import scribe.Logger
import scribe.output.LogOutput
import scribe.output.format.OutputFormat
import scribe.writer.Writer

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** ROADMAP M0.11 acceptance: masking shows `[redacted]` for a body at INFO, every line carries `correlation_id`
  * when bound through the `ForkLocal` context, and `LOG_LEVEL` is honoured.
  */
final class TelemetrySuite extends FunSuite:

  final class CaptureWriter extends Writer:
    private val lines = ConcurrentLinkedQueue[String]()
    override def write(record: LogRecord, output: LogOutput, outputFormat: OutputFormat): Unit =
      lines.add(output.plainText)
    def captured: List[String] = lines.asScala.toList

  private def capture(format: LogFormat, minimum: Level): (Logger, CaptureWriter) =
    val writer = CaptureWriter()
    val logger = Logger(s"telemetry.test.${System.nanoTime()}")
      .orphan()
      .withHandler(Telemetry.handler(format, writer, minimum))
    (logger, writer)

  test("a body logged at INFO is redacted") {
    val (logger, writer) = capture(LogFormat.Json, Level.Info)
    logger.info("intake recorded", Telemetry.data.body("Time for Vitamin D, 1000 IU."))
    val lines = writer.captured
    assertEquals(lines.size, 1)
    val line = lines.head
    assert(line.contains("\"body\":\"[redacted]\""), line)
    assert(!line.contains("Vitamin D"), line)
  }

  test("a body logged at DEBUG is kept") {
    val (logger, writer) = capture(LogFormat.Json, Level.Debug)
    logger.debug("rendered", Telemetry.data.body("Time for Vitamin D, 1000 IU."))
    val lines = writer.captured
    assertEquals(lines.size, 1)
    assert(lines.head.contains("Time for Vitamin D, 1000 IU."), lines.head)
  }

  test("every line carries correlation_id when the LogContext binds it") {
    val (logger, writer) = capture(LogFormat.Json, Level.Info)
    supervised {
      LogContext.scopedWith(_.copy(correlationId = Some("corr-42"), handler = Some("dose.taken"))) {
        logger.info("one")
        logger.info("two")
      }
    }
    val lines = writer.captured
    assertEquals(lines.size, 2)
    lines.foreach { line =>
      assert(line.contains("\"correlation_id\":\"corr-42\""), line)
      assert(line.contains("\"handler\":\"dose.taken\""), line)
    }
  }

  test("correlation_id is absent when the LogContext does not bind it") {
    val (logger, writer) = capture(LogFormat.Json, Level.Info)
    logger.info("loose")
    val lines = writer.captured
    assertEquals(lines.size, 1)
    assert(!lines.head.contains("correlation_id"), lines.head)
  }

  test("LOG_LEVEL is honoured: DEBUG suppressed at INFO, emitted at DEBUG") {
    val (infoLogger, infoWriter) = capture(LogFormat.Json, Level.Info)
    infoLogger.debug("hidden")
    infoLogger.info("shown")
    val infoLines = infoWriter.captured
    assertEquals(infoLines.size, 1)
    assert(infoLines.head.contains("\"message\":\"shown\""), infoLines.head)
    assert(!infoLines.head.contains("hidden"), infoLines.head)

    val (debugLogger, debugWriter) = capture(LogFormat.Json, Level.Debug)
    debugLogger.debug("hidden")
    val debugLines = debugWriter.captured
    assertEquals(debugLines.size, 1)
    assert(debugLines.head.contains("\"message\":\"hidden\""), debugLines.head)
    assert(debugLines.head.contains("\"level\":\"DEBUG\""), debugLines.head)
  }

  test("a line is a single JSON object with the context and data keys") {
    val (logger, writer) = capture(LogFormat.Json, Level.Info)
    supervised {
      LogContext.scopedWith(_.copy(correlationId = Some("corr-7"))) {
        logger.info("parsed", Telemetry.data.note("slept well"))
      }
    }
    val line   = writer.captured.head
    val parsed = ujson.read(line)
    assertEquals(parsed("level").str, "INFO")
    assertEquals(parsed("message").str, "parsed")
    assertEquals(parsed("correlation_id").str, "corr-7")
    assertEquals(parsed("note").str, "[redacted]")
  }

  test("the tzdb version is exported as dosecord_tzdb_info") {
    Metrics.registerTzdb("test-version")
    val exposition = Metrics.scrape()
    assert(exposition.contains("dosecord_tzdb_info"), exposition)
    assert(exposition.contains("""version="test-version""""), exposition)
  }
