package dosegram.infra

import dosegram.core.chat.CallbackKey
import dosegram.core.chat.CallbackKeys

import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import scala.util.Try

/** A chat vendor adapter that can be enabled through `ENABLED_ADAPTERS` (DESIGN.md sections 3 and 12).
  */
enum Adapter(val envName: String):
  case Console extends Adapter("console")
  case Telegram extends Adapter("telegram")

object Adapter:
  def parse(value: String): Option[Adapter] = values.find(_.envName == value)

/** Process role (DESIGN.md section 3): `gateway` connects the chat adapters, `worker` runs the reminder loop, the
  * materialiser, the outbox dispatchers and the session sweeper, `all` runs both in one process.
  */
enum Role(val envName: String):
  case Gateway extends Role("gateway")
  case Worker extends Role("worker")
  case All extends Role("all")

object Role:
  def parse(value: String): Option[Role] = values.find(_.envName == value)

/** Where the process runs (ARCHITECTURE section 16.1). `production` is the default, so a forgotten variable never
  * relaxes a start-up rule (CFG-01). Outside `development` the Telegram allowlist must not be empty, and only a
  * development bot may have its webhook deleted to make room for long polling (ROADMAP G1.6).
  */
enum Environment(val envName: String):
  case Development extends Environment("development")
  case Test extends Environment("test")
  case Production extends Environment("production")

object Environment:
  def parse(value: String): Option[Environment] = values.find(_.envName == value)

/** The Mini App surface, present only when `MINIAPP_ENABLED=true` (ROADMAP G1.12): the static files under `/app/` and
  * every `/api/` route. Launch data is checked with Ed25519 for `botId` (Telegram's production key unless
  * `initDataPublicKey` names another, which only a non-production environment may do), and with the HMAC of the bot
  * token when `hmacFallback` is set (ARCHITECTURE API-02, API-03).
  */
final case class MiniAppSettings(
    staticDir: Option[String],
    botId: Option[Long],
    initDataPublicKey: Option[String],
    hmacFallback: Boolean
)

/** The image renderer, present when `RESVG_PATH` and `RENDER_FONTS_DIR` are both set (ADR-022): the resvg binary and
  * the directory of the pinned font pack. Without it every image is sent as its text equivalent.
  */
final case class RenderSettings(resvgPath: String, fontsDir: String)

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

/** How Telegram updates reach the process (ARCHITECTURE section 7.2): long polling or the webhook. */
enum TelegramUpdates(val envName: String):
  case Poll extends TelegramUpdates("poll")
  case Webhook extends TelegramUpdates("webhook")

object TelegramUpdates:
  def parse(value: String): Option[TelegramUpdates] = values.find(_.envName == value.toLowerCase)

/** A secret whose text form never shows it, so a log line that prints the settings cannot leak it. */
final case class Secret(value: String):
  override def toString: String = "***"

/** What the Telegram adapter is configured with; present exactly when the adapter is enabled. `botId` is the id the
  * token must belong to, `allowedUserIds` the Telegram users who may use the bot (empty: everyone, development only).
  */
final case class TelegramSettings(
    token: String,
    botId: Long,
    updates: TelegramUpdates,
    allowedUserIds: Set[Long],
    webhook: Option[TelegramWebhookSettings] = None
):
  // The token must not show in a log line that prints the settings.
  override def toString: String = s"TelegramSettings(***, $botId, $updates, ${allowedUserIds.size} allowed)"

/** The webhook of ROADMAP G1.7, present exactly when `TELEGRAM_UPDATES_MODE=webhook`: the URL that `setWebhook`
  * registers, which ends in the path secret, and the header secret Telegram sends with every delivery. Neither secret
  * shows in a `toString`.
  */
final case class TelegramWebhookSettings(url: String, pathSecret: String, secretToken: String):
  override def toString: String = "TelegramWebhookSettings(***)"

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
    publicBaseUrl: Option[String],
    healthPort: Int,
    telegram: Option[TelegramSettings] = None,
    environment: Environment = Environment.Production,
    miniAppUrl: Option[String] = None,
    httpPort: Int = Settings.DefaultHttpPort,
    miniApp: Option[MiniAppSettings] = None,
    // The bot token as configured, whether or not the adapter is enabled: the Mini App derives its HMAC key from it
    // (ARCHITECTURE API-03). The adapter reads its own copy from `telegram`.
    telegramToken: Option[Secret] = None,
    render: Option[RenderSettings] = None,
    // The interfaces the two listeners bind to. Both default to the loopback interface: `/healthz` is for the host
    // (ARCHITECTURE section 9.1), and the public origin is reached through a TLS proxy (docs/DEPLOY_MINIAPP.md).
    healthAddress: String = Settings.DefaultBindAddress,
    httpAddress: String = Settings.DefaultBindAddress,
    // The chat that gets the owner alerts (OPS-A2); without it, the one user of the Telegram allowlist.
    ownerAlertChat: Option[String] = None,
    // The purge list of ROADMAP G2.17: a file outside the database, appended on every account deletion.
    purgeListPath: String = Settings.DefaultPurgeListPath
):
  /** Where the owner alerts go (supervisor decision, 2026-11-14, ARCHITECTURE section 18.4): `OWNER_ALERT_CHAT`, else
    * the private chat of the only allowlisted Telegram user (a private chat's id is the user's id). With several
    * allowlisted users and no setting there is no owner chat, and no alert is sent.
    */
  def ownerChat: Option[String] =
    ownerAlertChat.orElse(telegram.map(_.allowedUserIds.toList).collect { case List(only) => only.toString })

object Settings:

  val DefaultHealthPort: Int = 8080

  /** The public listener of ARCHITECTURE section 9.1 (`/app/`, `/api/v1/`); `/healthz` stays on its own port. */
  val DefaultHttpPort: Int = 8081

  /** The interface both listeners bind to unless `HEALTH_ADDRESS` or `HTTP_ADDRESS` names another. */
  val DefaultBindAddress: String = "127.0.0.1"

  /** The purge list file unless `PURGE_LIST_PATH` names another (ARCHITECTURE SEC-16). */
  val DefaultPurgeListPath: String = "data/purge-list.txt"

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
      "jdbc:postgresql://localhost:5432/dosegram?user=dosegram&password=change-me"
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
      "Process role: gateway (chat adapters) | worker (reminder loop, materialiser, outbox, sweeper) | all.",
      "all",
      Some("all")
    ),
    EnvSpec(
      "INSTANCE_ID",
      required = false,
      secret = false,
      "Stable id of this process instance (worker_heartbeat); defaults to the hostname.",
      "dosegram-1"
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
      "TELEGRAM_TOKEN",
      required = false,
      secret = true,
      "Telegram bot token; required when the telegram adapter is enabled.",
      "change-me"
    ),
    EnvSpec(
      "TELEGRAM_BOT_ID",
      required = false,
      secret = false,
      "Numeric id of the bot the token must belong to; required with telegram. Mini App launch data is verified for it.",
      "123456789"
    ),
    EnvSpec(
      "TELEGRAM_UPDATES_MODE",
      required = false,
      secret = false,
      "How updates arrive: poll (getUpdates) | webhook. Required with telegram; there is no default.",
      "poll"
    ),
    EnvSpec(
      "TELEGRAM_ALLOWED_USER_IDS",
      required = false,
      secret = false,
      "Comma-separated Telegram user ids that may use the bot; may be empty in development only.",
      "123456789"
    ),
    EnvSpec(
      "MINI_APP_URL",
      required = false,
      secret = false,
      "https address of the Mini App (menu button, app buttons); without it none is shown.",
      "https://dosegram.example.com/app/"
    ),
    EnvSpec(
      "PUBLIC_BASE_URL",
      required = false,
      secret = false,
      "External https base URL; the Telegram webhook is registered under it. Required with TELEGRAM_UPDATES_MODE=webhook.",
      "https://dosegram.example.com"
    ),
    EnvSpec(
      "HEALTH_PORT",
      required = false,
      secret = false,
      "TCP port of the /healthz listener.",
      DefaultHealthPort.toString,
      Some(DefaultHealthPort.toString)
    ),
    EnvSpec(
      "ENVIRONMENT",
      required = false,
      secret = false,
      "development | test | production. Start-up rules are strictest in production.",
      "production",
      Some("production")
    ),
    EnvSpec(
      "HTTP_PORT",
      required = false,
      secret = false,
      "TCP port of the public listener (/app/, /api/v1/, /tg/webhook/); opened for the Mini App or the webhook only.",
      DefaultHttpPort.toString,
      Some(DefaultHttpPort.toString)
    ),
    EnvSpec(
      "MINIAPP_ENABLED",
      required = false,
      secret = false,
      "true | false. Serves the Mini App and its API; in production it needs MINI_APP_URL and an explicit HTTP_PORT.",
      "false",
      Some("false")
    ),
    EnvSpec(
      "MINIAPP_STATIC_DIR",
      required = false,
      secret = false,
      "Directory of the built Mini App (miniapp/dist); without it /app/ answers 404 and only the API is served.",
      "miniapp/dist"
    ),
    EnvSpec(
      "MINIAPP_INITDATA_PUBLIC_KEY",
      required = false,
      secret = false,
      "Ed25519 key (64 hex digits) that signs launch data, instead of Telegram's production key; never in production.",
      "40055058a4ee38156a06562e52eece92a771bcd8346a8c4615cb7376eddf72ec"
    ),
    EnvSpec(
      "MINIAPP_HMAC_FALLBACK",
      required = false,
      secret = false,
      "true | false. Accepts launch data without a signature by its HMAC; needs TELEGRAM_TOKEN; never in production.",
      "false",
      Some("false")
    ),
    EnvSpec(
      "RESVG_PATH",
      required = false,
      secret = false,
      "The resvg binary that draws status images; set it together with RENDER_FONTS_DIR, or images are sent as text.",
      "/usr/local/bin/resvg"
    ),
    EnvSpec(
      "RENDER_FONTS_DIR",
      required = false,
      secret = false,
      "Directory of the pinned font pack (report-renderer/fonts); required with RESVG_PATH.",
      "report-renderer/fonts"
    ),
    EnvSpec(
      "TELEGRAM_WEBHOOK_PATH_SECRET",
      required = false,
      secret = true,
      "Last path segment of the webhook URL; 16 to 256 of A-Z a-z 0-9 _ -. Required with TELEGRAM_UPDATES_MODE=webhook.",
      "change-me-path-secret"
    ),
    EnvSpec(
      "TELEGRAM_WEBHOOK_SECRET_TOKEN",
      required = false,
      secret = true,
      "Header secret Telegram sends with each update; 16 to 256 of A-Z a-z 0-9 _ -. Required with the webhook.",
      "change-me-header-secret"
    ),
    EnvSpec(
      "HEALTH_ADDRESS",
      required = false,
      secret = false,
      "IP address the /healthz listener binds to; 0.0.0.0 inside a container whose health port is published.",
      DefaultBindAddress,
      Some(DefaultBindAddress)
    ),
    EnvSpec(
      "HTTP_ADDRESS",
      required = false,
      secret = false,
      "IP address the public listener (/app/, /api/v1/) binds to; the TLS proxy connects to it.",
      DefaultBindAddress,
      Some(DefaultBindAddress)
    ),
    EnvSpec(
      "OWNER_ALERT_CHAT",
      required = false,
      secret = false,
      "Telegram chat id for owner alerts (dead outbox rows, quarantined updates); default: the only allowlisted user.",
      "123456789"
    ),
    EnvSpec(
      "PURGE_LIST_PATH",
      required = false,
      secret = false,
      "File of deleted account ids, kept outside the database and its backups so a restore deletes them again.",
      DefaultPurgeListPath,
      Some(DefaultPurgeListPath)
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

    val healthPort = lookup("HEALTH_PORT").map(_.toIntOption.filter(port => port >= 1 && port <= 65535) match
      case Some(port) => port
      case None       => { errors = errors :+ "HEALTH_PORT must be a TCP port from 1 to 65535"; DefaultHealthPort })

    def parsed[A](name: String, expected: String, fallback: A)(parse: String => Option[A]): Option[A] =
      lookup(name).map(parse(_) match
        case Some(value) => value
        case None        => { errors = errors :+ s"$name must be $expected"; fallback })

    def flag(name: String): Boolean =
      parsed(name, "true or false", false)(_.toBooleanOption).getOrElse(false)

    val environment =
      parsed("ENVIRONMENT", "development, test or production", Environment.Production)(Environment.parse)
        .getOrElse(Environment.Production)
    val httpPort = parsed("HTTP_PORT", "a TCP port from 1 to 65535", DefaultHttpPort)(
      _.toIntOption.filter(port => port >= 1 && port <= 65535)
    )
    val botId = parsed("TELEGRAM_BOT_ID", "the numeric id of the bot", 0L)(_.toLongOption.filter(_ > 0))
    val telegramToken = lookup("TELEGRAM_TOKEN")
    val initDataPublicKey = parsed("MINIAPP_INITDATA_PUBLIC_KEY", "64 hex digits", "")(key =>
      Option.when(key.matches("[0-9a-fA-F]{64}"))(key.toLowerCase)
    )
    val miniAppEnabled = flag("MINIAPP_ENABLED")
    val hmacFallback = flag("MINIAPP_HMAC_FALLBACK")
    val staticDir = lookup("MINIAPP_STATIC_DIR")
    // A literal only: a host name would make start-up depend on a name lookup.
    def address(name: String): String =
      parsed(name, "an IPv4 or IPv6 address", DefaultBindAddress)(raw =>
        Try(InetAddress.ofLiteral(raw)).toOption.map(_ => raw)
      ).getOrElse(DefaultBindAddress)
    val healthAddress = address("HEALTH_ADDRESS")
    val httpAddress = address("HTTP_ADDRESS")
    val render = (lookup("RESVG_PATH"), lookup("RENDER_FONTS_DIR")) match
      case (Some(binary), Some(fonts)) => Some(RenderSettings(binary, fonts))
      case (None, None)                => None
      case _ => { errors = errors :+ "RESVG_PATH and RENDER_FONTS_DIR must be set together"; None }

    val databaseUrl = required("DATABASE_URL")
    val ownerAlertChat = parsed("OWNER_ALERT_CHAT", "a Telegram chat id (an integer)", "")(chat =>
      Option.when(chat.matches("-?[0-9]{1,20}"))(chat)
    )

    val keys = required("CALLBACK_KEYS").flatMap(parseCallbackKeys(_) match
      case Right(ks) => Some(ks)
      case Left(err) => { errors = errors :+ err; None })

    val consoleUserId = lookup("CONSOLE_USER_ID")

    val miniAppUrl = lookup("MINI_APP_URL").filter: url =>
      val secure = isHttpsAddress(url)
      if !secure then errors = errors :+ "MINI_APP_URL must be an https address"
      secure

    adapters.foreach { list =>
      if list.contains(Adapter.Console) && consoleUserId.isEmpty then
        errors = errors :+ "CONSOLE_USER_ID is required when the console adapter is enabled"
    }

    val telegram = adapters
      .filter(_.contains(Adapter.Telegram))
      .flatMap: _ =>
        parseTelegram(TelegramIdentity(telegramToken, botId), lookup, environment) match
          case Right(settings) => Some(settings)
          case Left(problems)  => { errors = errors ++ problems; None }

    val inProduction = environment == Environment.Production
    if miniAppEnabled then
      // Lifted at checkpoint CP3-a (ADR-016 addendum): production serves the app only on a named https origin and an
      // explicitly chosen public port, so a forgotten variable never opens a listener nobody meant to open.
      if inProduction && (miniAppUrl.isEmpty || httpPort.isEmpty) then
        errors = errors :+
          "MINIAPP_ENABLED=true is refused while ENVIRONMENT=production without MINI_APP_URL and an explicit HTTP_PORT"
      if botId.isEmpty && !hmacFallback then
        errors = errors :+ "MINIAPP_ENABLED needs TELEGRAM_BOT_ID, or MINIAPP_HMAC_FALLBACK=true with TELEGRAM_TOKEN"
      if hmacFallback && telegramToken.isEmpty then errors = errors :+ "MINIAPP_HMAC_FALLBACK=true needs TELEGRAM_TOKEN"
      if inProduction && initDataPublicKey.isDefined then
        errors = errors :+ "MINIAPP_INITDATA_PUBLIC_KEY is refused while ENVIRONMENT=production"
      // CFG-01: the fallback accepts launch data that anyone holding the bot token can mint (ADR-016).
      if inProduction && hmacFallback then
        errors = errors :+ "MINIAPP_HMAC_FALLBACK=true is refused while ENVIRONMENT=production"

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
          publicBaseUrl = lookup("PUBLIC_BASE_URL"),
          healthPort = healthPort.getOrElse(DefaultHealthPort),
          telegram = telegram,
          environment = environment,
          miniAppUrl = miniAppUrl,
          httpPort = httpPort.getOrElse(DefaultHttpPort),
          miniApp = Option.when(miniAppEnabled)(MiniAppSettings(staticDir, botId, initDataPublicKey, hmacFallback)),
          telegramToken = telegramToken.map(Secret(_)),
          render = render,
          healthAddress = healthAddress,
          httpAddress = httpAddress,
          ownerAlertChat = ownerAlertChat,
          purgeListPath = lookup("PURGE_LIST_PATH").getOrElse(DefaultPurgeListPath)
        )
      )

  /** The token and the bot id as read from the environment; a bot id that is not a number is already reported. */
  private final case class TelegramIdentity(token: Option[String], botId: Option[Long])

  /** The Telegram block (ROADMAP G1.6): token, bot id and updates mode are required together; outside development the
    * allowlist must name at least one user. No message quotes the token.
    */
  private def parseTelegram(
      identity: TelegramIdentity,
      lookup: String => Option[String],
      environment: Environment
  ): Either[List[String], TelegramSettings] =
    val whenEnabled = "when the telegram adapter is enabled"
    val token = identity.token.toRight(s"TELEGRAM_TOKEN is required $whenEnabled")
    val botId = identity.botId.toRight(s"TELEGRAM_BOT_ID is required $whenEnabled")
    val updates = lookup("TELEGRAM_UPDATES_MODE")
      .toRight(s"TELEGRAM_UPDATES_MODE is required $whenEnabled (poll or webhook)")
      .flatMap(TelegramUpdates.parse(_).toRight("TELEGRAM_UPDATES_MODE must be poll or webhook"))
    val allowed = parseUserIds(lookup("TELEGRAM_ALLOWED_USER_IDS").getOrElse("")).filterOrElse(
      ids => ids.nonEmpty || environment == Environment.Development,
      "TELEGRAM_ALLOWED_USER_IDS must name at least one user outside development"
    )
    val webhook = updates match
      case Right(TelegramUpdates.Webhook) => parseWebhook(lookup).map(Some(_))
      case _                              => Right(None)
    val problems = List(token, botId, updates, allowed).collect { case Left(problem) => problem } ++
      webhook.left.toOption.toList.flatten
    (token, botId, updates, allowed, webhook) match
      case (Right(t), Right(id), Right(mode), Right(ids), Right(hook)) =>
        Right(TelegramSettings(t, id, mode, ids, hook))
      case _ => Left(problems)

  /** The webhook of ROADMAP G1.7: an https base URL and two secrets of the characters Telegram accepts in a
    * `secret_token` (research brief 02). No message quotes a secret.
    */
  private def parseWebhook(lookup: String => Option[String]): Either[List[String], TelegramWebhookSettings] =
    val whenWebhook = "when TELEGRAM_UPDATES_MODE=webhook"
    def secret(name: String): Either[String, String] =
      lookup(name)
        .toRight(s"$name is required $whenWebhook")
        .filterOrElse(
          _.matches("[A-Za-z0-9_-]{16,256}"),
          s"$name must be 16 to 256 characters of A-Z, a-z, 0-9, _ and -"
        )
    val base = lookup("PUBLIC_BASE_URL")
      .filter(isHttpsAddress)
      .toRight(s"PUBLIC_BASE_URL must be an https address $whenWebhook")
    val path = secret("TELEGRAM_WEBHOOK_PATH_SECRET")
    val header = secret("TELEGRAM_WEBHOOK_SECRET_TOKEN")
    (base, path, header) match
      case (Right(url), Right(p), Right(h)) =>
        Right(TelegramWebhookSettings(s"${url.stripSuffix("/")}/tg/webhook/$p", p, h))
      case _ => Left(List(base, path, header).collect { case Left(problem) => problem })

  private def parseUserIds(raw: String): Either[String, Set[Long]] =
    val entries = raw.split(",").toList.map(_.trim).filter(_.nonEmpty)
    val ids = entries.flatMap(_.toLongOption.filter(_ > 0))
    Either.cond(
      ids.size == entries.size,
      ids.toSet,
      "TELEGRAM_ALLOWED_USER_IDS must be comma-separated numeric user ids"
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

  /** An absolute `https` address with a host: what Telegram opens a Mini App from. */
  private def isHttpsAddress(url: String): Boolean =
    Try(URI(url)).toOption.exists(uri => uri.getScheme == "https" && Option(uri.getHost).exists(_.nonEmpty))

  private def defaultInstanceId(): String =
    try InetAddress.getLocalHost.getHostName
    catch case _: Exception => UUID.randomUUID().toString

  /** The `.env.example` content derived from [[spec]] (DESIGN.md section 12: generated from the case class so it cannot
    * drift). The committed `.env.example` must equal this string; `dosegram.app.envExampleCheck` enforces it.
    */
  def envExample: String =
    val sb = StringBuilder()
    sb.append("# Dosegram configuration (DESIGN.md section 12).\n")
    sb.append("#\n")
    sb.append("# GENERATED FILE — do not edit by hand. Regenerate with:\n")
    sb.append("#   ./mill app.test.runMain dosegram.app.envExampleGenerate .env.example\n")
    sb.append("# The committed copy is diff-checked in CI (dosegram.app.envExampleCheck).\n")
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
