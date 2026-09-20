package dosecord.infra

import dosecord.core.chat.CallbackKey
import dosecord.core.chat.CallbackKeys

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/** A chat vendor adapter that can be enabled through `ENABLED_ADAPTERS` (DESIGN.md sections 3 and 12).
  */
enum Adapter(val envName: String):
  case Console extends Adapter("console")
  case Discord extends Adapter("discord")
  case Telegram extends Adapter("telegram")
  case Zulip extends Adapter("zulip")
  case Matrix extends Adapter("matrix")

object Adapter:
  def parse(value: String): Option[Adapter] = values.find(_.envName == value)

/** Process role (DESIGN.md section 3). Roles are accepted and stored in M0.5; the gateway/worker split is wired in
  * M4.3.
  */
enum Role(val envName: String):
  case Gateway extends Role("gateway")
  case Worker extends Role("worker")
  case All extends Role("all")

object Role:
  def parse(value: String): Option[Role] = values.find(_.envName == value)

enum LogLevel(val envName: String):
  case Debug extends LogLevel("DEBUG")
  case Info extends LogLevel("INFO")
  case Warn extends LogLevel("WARN")
  case Error extends LogLevel("ERROR")

object LogLevel:
  def parse(value: String): Option[LogLevel] = values.find(_.envName == value.toUpperCase)

enum LogFormat(val envName: String):
  case Json extends LogFormat("json")
  case Plain extends LogFormat("plain")

object LogFormat:
  def parse(value: String): Option[LogFormat] = values.find(_.envName == value.toLowerCase)

final case class ZulipCredentials(site: String, email: String, apiKey: String)
final case class MatrixCredentials(homeserver: String, user: String, token: String)

/** Runtime configuration parsed from environment variables and Docker secrets (DESIGN.md section 12). Every field maps
  * to exactly one env var in [[Settings.spec]]; any of them may instead be supplied as `<NAME>_FILE` pointing at a
  * Docker secret whose content becomes the value.
  */
final case class Settings(
    enabledAdapters: List[Adapter],
    databaseUrl: String,
    callbackKeys: CallbackKeys,
    role: Role,
    instanceId: String,
    logLevel: LogLevel,
    logFormat: LogFormat,
    consoleUserId: Option[String],
    discordToken: Option[String],
    telegramToken: Option[String],
    zulip: Option[ZulipCredentials],
    matrix: Option[MatrixCredentials],
    publicBaseUrl: Option[String]
)

object Settings:

  /** Declarative description of one env var: the single source for parsing, validation and `.env.example` generation,
    * so the committed example cannot drift from the case class (DESIGN.md section 12).
    */
  final case class EnvSpec(
      name: String,
      required: Boolean,
      secret: Boolean,
      doc: String,
      example: String,
      default: Option[String] = None
  )

  val spec: List[EnvSpec] = List(
    EnvSpec(
      "ENABLED_ADAPTERS",
      required = true,
      secret = false,
      "Comma-separated adapters to start; each non-console vendor needs its credentials below.",
      "console"
    ),
    EnvSpec(
      "DATABASE_URL",
      required = true,
      secret = true,
      "JDBC URL; pgjdbc takes credentials as query parameters (or use DATABASE_URL_FILE).",
      "jdbc:postgresql://localhost:5432/dosecord?user=dosecord&password=change-me"
    ),
    EnvSpec(
      "CALLBACK_KEYS",
      required = true,
      secret = true,
      "HMAC keys for callback tokens (ADR-006): '<id>:<base64url>'[, '<id>:<base64url>']; first signs, both verify.",
      "0:MDEyMzQ1Njc4OWFiY2RlZg"
    ),
    EnvSpec(
      "ROLE",
      required = false,
      secret = false,
      "Process role: gateway | worker | all (wired in M4.3).",
      "all",
      Some("all")
    ),
    EnvSpec(
      "INSTANCE_ID",
      required = false,
      secret = false,
      "Stable id of this process instance (worker_heartbeat); defaults to the hostname.",
      "dosecord-1"
    ),
    EnvSpec("LOG_LEVEL", required = false, secret = false, "DEBUG | INFO | WARN | ERROR.", "INFO", Some("INFO")),
    EnvSpec("LOG_FORMAT", required = false, secret = false, "json | plain.", "json", Some("json")),
    EnvSpec(
      "CONSOLE_USER_ID",
      required = false,
      secret = false,
      "Actor handle of the console adapter; required when the console adapter is enabled.",
      "owner"
    ),
    EnvSpec(
      "DISCORD_TOKEN",
      required = false,
      secret = true,
      "Discord bot token; required when the discord adapter is enabled.",
      "change-me"
    ),
    EnvSpec(
      "TELEGRAM_TOKEN",
      required = false,
      secret = true,
      "Telegram bot token; required when the telegram adapter is enabled.",
      "change-me"
    ),
    EnvSpec(
      "ZULIP_SITE",
      required = false,
      secret = false,
      "Zulip realm base URL; required (with ZULIP_EMAIL/ZULIP_API_KEY) when the zulip adapter is enabled.",
      "https://zulip.example.com"
    ),
    EnvSpec("ZULIP_EMAIL", required = false, secret = false, "Zulip bot email.", "bot@zulip.example.com"),
    EnvSpec("ZULIP_API_KEY", required = false, secret = true, "Zulip bot API key.", "change-me"),
    EnvSpec(
      "MATRIX_HOMESERVER",
      required = false,
      secret = false,
      "Matrix homeserver URL; required (with MATRIX_USER/MATRIX_TOKEN) when the matrix adapter is enabled.",
      "https://matrix.example.com"
    ),
    EnvSpec("MATRIX_USER", required = false, secret = false, "Matrix bot user id.", "@dosecord:matrix.example.com"),
    EnvSpec("MATRIX_TOKEN", required = false, secret = true, "Matrix access token.", "change-me"),
    EnvSpec(
      "PUBLIC_BASE_URL",
      required = false,
      secret = false,
      "External base URL of the auth-link page (M4.1); unused until then.",
      "https://dosecord.example.com"
    )
  )

  /** Load from the process environment, reading Docker secrets through `<NAME>_FILE`. Throws `IllegalArgumentException`
    * listing every problem; secret values are never included in messages.
    */
  def fromEnv(env: Map[String, String] = sys.env): Settings =
    parse(env, name => Files.readString(Path.of(name)).trim) match
      case Right(settings) => settings
      case Left(errors)    => throw IllegalArgumentException(errors.mkString("invalid settings: ", "; ", ""))

  /** Pure parse: `readFile` resolves `<NAME>_FILE` indirections (Docker secrets). */
  def parse(env: Map[String, String], readFile: String => String): Either[List[String], Settings] =
    var errors = List.empty[String]

    def lookup(name: String): Option[String] =
      env
        .get(name)
        .filter(_.nonEmpty)
        .orElse {
          env.get(s"${name}_FILE").filter(_.nonEmpty).map { path =>
            try readFile(path)
            catch
              case e: Exception => { errors = errors :+ s"$name: cannot read $path (${e.getClass.getSimpleName})"; "" }
          }
        }
        .filter(_.nonEmpty)

    def required(name: String): Option[String] =
      lookup(name) match
        case some @ Some(_) => some
        case None           => { errors = errors :+ s"$name is required"; None }

    val adapters = required("ENABLED_ADAPTERS").map { raw =>
      raw
        .split(",")
        .toList
        .map(_.trim)
        .filter(_.nonEmpty)
        .map { name =>
          Adapter.parse(name) match
            case Some(adapter) => adapter
            case None          => { errors = errors :+ s"ENABLED_ADAPTERS: unknown adapter '$name'"; Adapter.Console }
        }
        .distinct
    }

    val role = lookup("ROLE").map(Role.parse(_) match
      case Some(r) => r
      case None    => { errors = errors :+ "ROLE must be gateway, worker or all"; Role.All })

    val logLevel = lookup("LOG_LEVEL").map(LogLevel.parse(_) match
      case Some(l) => l
      case None    => { errors = errors :+ "LOG_LEVEL must be DEBUG, INFO, WARN or ERROR"; LogLevel.Info })

    val logFormat = lookup("LOG_FORMAT").map(LogFormat.parse(_) match
      case Some(f) => f
      case None    => { errors = errors :+ "LOG_FORMAT must be json or plain"; LogFormat.Json })

    val databaseUrl = required("DATABASE_URL")

    val keys = required("CALLBACK_KEYS").flatMap(parseCallbackKeys(_) match
      case Right(ks) => Some(ks)
      case Left(err) => { errors = errors :+ err; None })

    val consoleUserId = lookup("CONSOLE_USER_ID")
    val discordToken = lookup("DISCORD_TOKEN")
    val telegramToken = lookup("TELEGRAM_TOKEN")
    val zulip =
      (lookup("ZULIP_SITE"), lookup("ZULIP_EMAIL"), lookup("ZULIP_API_KEY")) match
        case (Some(site), Some(email), Some(key)) => Some(ZulipCredentials(site, email, key))
        case (None, None, None)                   => None
        case _ => { errors = errors :+ "ZULIP_SITE, ZULIP_EMAIL and ZULIP_API_KEY must be set together"; None }
    val matrix =
      (lookup("MATRIX_HOMESERVER"), lookup("MATRIX_USER"), lookup("MATRIX_TOKEN")) match
        case (Some(hs), Some(user), Some(token)) => Some(MatrixCredentials(hs, user, token))
        case (None, None, None)                  => None
        case _ => { errors = errors :+ "MATRIX_HOMESERVER, MATRIX_USER and MATRIX_TOKEN must be set together"; None }

    adapters.foreach { list =>
      if list.contains(Adapter.Console) && consoleUserId.isEmpty then
        errors = errors :+ "CONSOLE_USER_ID is required when the console adapter is enabled"
      if list.contains(Adapter.Discord) && discordToken.isEmpty then
        errors = errors :+ "DISCORD_TOKEN is required when the discord adapter is enabled"
      if list.contains(Adapter.Telegram) && telegramToken.isEmpty then
        errors = errors :+ "TELEGRAM_TOKEN is required when the telegram adapter is enabled"
      if list.contains(Adapter.Zulip) && zulip.isEmpty then
        errors = errors :+ "ZULIP_SITE, ZULIP_EMAIL and ZULIP_API_KEY are required when the zulip adapter is enabled"
      if list.contains(Adapter.Matrix) && matrix.isEmpty then
        errors =
          errors :+ "MATRIX_HOMESERVER, MATRIX_USER and MATRIX_TOKEN are required when the matrix adapter is enabled"
    }

    if errors.nonEmpty then Left(errors)
    else
      Right(
        Settings(
          enabledAdapters = adapters.get,
          databaseUrl = databaseUrl.get,
          callbackKeys = keys.get,
          role = role.getOrElse(Role.All),
          instanceId = lookup("INSTANCE_ID").getOrElse(defaultInstanceId()),
          logLevel = logLevel.getOrElse(LogLevel.Info),
          logFormat = logFormat.getOrElse(LogFormat.Json),
          consoleUserId = consoleUserId,
          discordToken = discordToken,
          telegramToken = telegramToken,
          zulip = zulip,
          matrix = matrix,
          publicBaseUrl = lookup("PUBLIC_BASE_URL")
        )
      )

  /** `CALLBACK_KEYS` = comma-separated `<id>:<base64url>` pairs; the first key signs, both verify (ADR-006). */
  def parseCallbackKeys(raw: String): Either[String, CallbackKeys] =
    val entries = raw.split(",").toList.map(_.trim).filter(_.nonEmpty)
    val parsed = entries.map { entry =>
      entry.split(":", 2).toList match
        case List(idRaw, secretRaw) =>
          for
            id <- idRaw.toIntOption
              .filter(i => i >= 0 && i <= 15)
              .toRight(s"CALLBACK_KEYS: key id '$idRaw' is not 0-15")
            bytes <-
              try Right(Base64.getUrlDecoder.decode(secretRaw))
              catch case _: IllegalArgumentException => Left(s"CALLBACK_KEYS: key $id is not base64url")
            key <-
              try Right(CallbackKey(id, bytes))
              catch case e: IllegalArgumentException => Left(s"CALLBACK_KEYS: key $id: ${e.getMessage}")
          yield key
        case _ => Left(s"CALLBACK_KEYS: entry '$entry' is not '<id>:<base64url>'")
    }
    parsed.collect { case Left(err) => err } match
      case firstErr :: _ => Left(firstErr)
      case Nil           =>
        val keys = parsed.collect { case Right(k) => k }
        keys match
          case current :: Nil            => Right(CallbackKeys(current, None))
          case current :: overlap :: Nil =>
            try Right(CallbackKeys(current, Some(overlap)))
            catch case e: IllegalArgumentException => Left(s"CALLBACK_KEYS: ${e.getMessage}")
          case Nil => Left("CALLBACK_KEYS: at least one key is required")
          case _   => Left("CALLBACK_KEYS: at most two active keys (current + overlap)")

  private def defaultInstanceId(): String =
    try InetAddress.getLocalHost.getHostName
    catch case _: Exception => UUID.randomUUID().toString

  /** The `.env.example` content derived from [[spec]] (DESIGN.md section 12: generated from the case class so it cannot
    * drift). The committed `.env.example` must equal this string; `dosecord.app.envExampleCheck` enforces it.
    */
  def envExample: String =
    val sb = StringBuilder()
    sb.append("# Dosecord configuration (DESIGN.md section 12).\n")
    sb.append("#\n")
    sb.append("# GENERATED FILE — do not edit by hand. Regenerate with:\n")
    sb.append("#   ./mill app.test.runMain dosecord.app.envExampleGenerate .env.example\n")
    sb.append("# The committed copy is diff-checked in CI (dosecord.app.envExampleCheck).\n")
    sb.append("#\n")
    sb.append("# Every variable may instead be supplied as <NAME>_FILE pointing at a file\n")
    sb.append("# (Docker secret) whose content becomes the value.\n")
    for entry <- spec do
      sb.append("\n")
      sb.append(s"# ${entry.doc}\n")
      val requirement = (entry.required, entry.default) match
        case (true, _)        => "required"
        case (false, Some(d)) => s"optional, default: $d"
        case (false, None)    => "optional"
      val secrecy = if entry.secret then ", secret — prefer <NAME>_FILE" else ""
      sb.append(s"# ($requirement$secrecy)\n")
      sb.append(s"${entry.name}=${entry.example}\n")
    sb.toString
