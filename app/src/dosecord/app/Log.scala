package dosecord.app

import dosecord.infra.LogFormat
import dosecord.infra.LogLevel

/** Minimal stdout logger honouring `LOG_LEVEL`/`LOG_FORMAT` until M0.11 lands scribe. Never logs secret values, message
  * bodies or medication names.
  */
final class Log(level: LogLevel, format: LogFormat):
  private def enabled(l: LogLevel) = l.ordinal >= level.ordinal

  private def emit(l: LogLevel, msg: String): Unit =
    if enabled(l) then
      format match
        case LogFormat.Json  => println(s"""{"level":"${l.envName}","msg":"${Log.escape(msg)}"}""")
        case LogFormat.Plain => println(s"${l.envName} $msg")

  def debug(msg: String): Unit = emit(LogLevel.Debug, msg)
  def info(msg: String): Unit = emit(LogLevel.Info, msg)
  def warn(msg: String): Unit = emit(LogLevel.Warn, msg)
  def error(msg: String): Unit = emit(LogLevel.Error, msg)

object Log:
  private def escape(s: String): String =
    s.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    }
