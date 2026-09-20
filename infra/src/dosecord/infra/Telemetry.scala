package dosecord.infra

import dosecord.core.telemetry.LogContext
import scribe.Level
import scribe.LogFeature
import scribe.LogRecord
import scribe.Logger
import scribe.Priority
import scribe.format.Formatter
import scribe.handler.LogHandler
import scribe.modify.LogModifier
import scribe.output.TextOutput
import scribe.output.format.ASCIIOutputFormat
import scribe.writer.ConsoleWriter
import scribe.writer.Writer

import java.time.Instant

/** scribe logging wiring (ROADMAP M0.11, DESIGN.md sections 3 and 10).
  *
  * Design rules:
  *   - one JSON object per line when `LOG_FORMAT=json` (a readable line when `plain`), carrying the [[LogContext]] keys
  *     bound for the fork via Ox `ForkLocal`;
  *   - message bodies, medication names, notes and credential-shaped values are only ever logged through the [[data]]
  *     helpers, which place them under [[SensitiveKeys]]; [[Masking]] replaces those values with `[redacted]` on every
  *     record above DEBUG (AGENTS.md: never log credentials, message bodies, medication names or notes);
  *   - `scribe-slf4j2` on the classpath routes library logs (HikariCP, Flyway, pgjdbc, later JDA) through the same root
  *     logger, so they get the same level filter and masking.
  */
object Telemetry:

  val Redacted = "[redacted]"

  /** Data keys whose values are replaced with [[Redacted]] on records above DEBUG. */
  val SensitiveKeys: Set[String] = Set(
    "body",
    "message_body",
    "note",
    "medication",
    "medication_name",
    "instructions",
    "token",
    "password",
    "secret"
  )

  /** `LogFeature` helpers for sensitive payloads — the only sanctioned way to log them. */
  object data:
    def body(value: => Any): LogFeature = scribe.data("body", value)
    def medicationName(value: => Any): LogFeature = scribe.data("medication_name", value)
    def note(value: => Any): LogFeature = scribe.data("note", value)

  /** Redacts [[SensitiveKeys]] on records above DEBUG (DESIGN.md section 10: message bodies and medication names are
    * never logged above DEBUG).
    */
  final case class Masking(id: String = "dosecord.masking", priority: Priority = Priority.Normal) extends LogModifier:
    override def apply(record: LogRecord): Option[LogRecord] =
      if record.levelValue <= Level.Debug.value then Some(record)
      else if !record.data.keys.exists(k => SensitiveKeys.contains(k.toLowerCase)) then Some(record)
      else
        Some(record.copy(data = record.data.map { (key, value) =>
          if SensitiveKeys.contains(key.toLowerCase) then key -> (() => Redacted)
          else key -> value
        }))
    override def withId(newId: String): LogModifier = copy(id = newId)

  def toScribe(level: LogLevel): Level = level match
    case LogLevel.Debug => Level.Debug
    case LogLevel.Info  => Level.Info
    case LogLevel.Warn  => Level.Warn
    case LogLevel.Error => Level.Error

  /** The single handler every record flows through: formatter + minimum level + masking. */
  def handler(format: LogFormat, writer: Writer, minimumLevel: Level): LogHandler =
    LogHandler(
      formatter = formatterFor(format),
      writer = writer,
      minimumLevel = Some(minimumLevel),
      modifiers = List(Masking()),
      outputFormat = ASCIIOutputFormat
    )

  def formatterFor(format: LogFormat): Formatter = format match
    case LogFormat.Json  => JsonFormatter
    case LogFormat.Plain => PlainFormatter

  /** Installs the handler on the scribe root logger; called once from the composition root before anything logs. */
  def configure(level: LogLevel, format: LogFormat): Unit =
    Logger.root
      .clearHandlers()
      .clearModifiers()
      .withMinimumLevel(toScribe(level))
      .withHandler(handler(format, ConsoleWriter, toScribe(level)))
      .replace()

  /** One JSON object per record: timestamp, level, logger, message, bound [[LogContext]] keys, then data entries. */
  object JsonFormatter extends Formatter:
    override def format(record: LogRecord): TextOutput = TextOutput(render(record, json = true))

  object PlainFormatter extends Formatter:
    override def format(record: LogRecord): TextOutput = TextOutput(render(record, json = false))

  private def render(record: LogRecord, json: Boolean): String =
    val context = LogContext.current.fields
    val data = record.data.toList.sortBy(_._1).map((key, value) => key -> String.valueOf(value()))
    val logger = record.loggerName.getOrElse(record.className)
    val message = record.logOutput.plainText
    if json then
      val fields = List(
        "timestamp" -> Instant.ofEpochMilli(record.timeStamp).toString,
        "level" -> record.level.name,
        "logger" -> logger,
        "message" -> message
      ) ::: context ::: data
      fields.map((key, value) => s""""${escape(key)}":"${escape(value)}"""").mkString("{", ",", "}")
    else
      val suffix = (context ::: data).map((key, value) => s"$key=$value").mkString(" ")
      val base = s"${Instant.ofEpochMilli(record.timeStamp)} ${record.level.name} $logger - $message"
      if suffix.nonEmpty then s"$base $suffix" else base

  private def escape(s: String): String =
    s.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    }
