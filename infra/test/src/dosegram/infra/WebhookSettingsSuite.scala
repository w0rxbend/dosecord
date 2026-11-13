package dosegram.infra

/** ROADMAP G1.7: the webhook settings, required exactly when `TELEGRAM_UPDATES_MODE=webhook`. */
class WebhookSettingsSuite extends munit.FunSuite:

  private val pathSecret = "CANARY-path-secret-0123456789"
  private val headerSecret = "CANARY-header-secret-0123456789"

  private val webhook = Map(
    "ENABLED_ADAPTERS" -> "telegram",
    "DATABASE_URL" -> "jdbc:postgresql://localhost:5432/dosegram",
    "CALLBACK_KEYS" -> "0:MDEyMzQ1Njc4OWFiY2RlZg",
    "TELEGRAM_TOKEN" -> "42:token",
    "TELEGRAM_BOT_ID" -> "42",
    "TELEGRAM_UPDATES_MODE" -> "webhook",
    "TELEGRAM_ALLOWED_USER_IDS" -> "7001",
    "PUBLIC_BASE_URL" -> "https://bot.example.com/",
    "TELEGRAM_WEBHOOK_PATH_SECRET" -> pathSecret,
    "TELEGRAM_WEBHOOK_SECRET_TOKEN" -> headerSecret
  )

  private def parse(env: Map[String, String]) = Settings.parse(env, path => sys.error(s"no secret file $path"))

  test("webhook mode parses its URL and both secrets"):
    val Right(settings) = parse(webhook): @unchecked
    val parsed = settings.telegram.flatMap(_.webhook).getOrElse(fail("no webhook settings"))
    assertEquals(parsed.url, s"https://bot.example.com/tg/webhook/$pathSecret")
    assertEquals(parsed.pathSecret, pathSecret)
    assertEquals(parsed.secretToken, headerSecret)

  test("webhook mode needs both secrets and an https PUBLIC_BASE_URL; all are reported together"):
    val Left(errors) = parse(
      webhook - "TELEGRAM_WEBHOOK_PATH_SECRET" - "TELEGRAM_WEBHOOK_SECRET_TOKEN" + ("PUBLIC_BASE_URL" -> "http://x")
    ): @unchecked
    assert(errors.contains("TELEGRAM_WEBHOOK_PATH_SECRET is required when TELEGRAM_UPDATES_MODE=webhook"), errors.toString)
    assert(errors.contains("TELEGRAM_WEBHOOK_SECRET_TOKEN is required when TELEGRAM_UPDATES_MODE=webhook"), errors.toString)
    assert(errors.contains("PUBLIC_BASE_URL must be an https address when TELEGRAM_UPDATES_MODE=webhook"), errors.toString)

  test("a secret shorter than 16 characters or with characters Telegram refuses is rejected, and never quoted"):
    val Left(errors) = parse(
      webhook ++ Map("TELEGRAM_WEBHOOK_PATH_SECRET" -> "CANARY-short", "TELEGRAM_WEBHOOK_SECRET_TOKEN" -> "CANARY secret/with space")
    ): @unchecked
    val rule = "must be 16 to 256 characters of A-Z, a-z, 0-9, _ and -"
    assert(errors.contains(s"TELEGRAM_WEBHOOK_PATH_SECRET $rule"), errors.toString)
    assert(errors.contains(s"TELEGRAM_WEBHOOK_SECRET_TOKEN $rule"), errors.toString)
    assert(!errors.exists(_.contains("CANARY")), errors.toString)

  test("poll mode needs no webhook settings and ignores them"):
    val Right(settings) = parse(
      webhook - "TELEGRAM_WEBHOOK_PATH_SECRET" - "TELEGRAM_WEBHOOK_SECRET_TOKEN" + ("TELEGRAM_UPDATES_MODE" -> "poll")
    ): @unchecked
    assertEquals(settings.telegram.flatMap(_.webhook), None)

  test("no text form of the settings shows a webhook secret"):
    val Right(settings) = parse(webhook): @unchecked
    assert(!settings.toString.contains("CANARY"), settings.toString)
