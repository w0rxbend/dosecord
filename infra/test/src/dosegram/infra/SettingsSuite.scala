package dosegram.infra

import java.nio.file.Files

class SettingsSuite extends munit.FunSuite:

  private val key0 = "0:MDEyMzQ1Njc4OWFiY2RlZg" // "0123456789abcdef", 16 bytes, padding omitted

  private val base = Map(
    "ENABLED_ADAPTERS" -> "console",
    "DATABASE_URL"     -> "jdbc:postgresql://localhost:5432/dosegram",
    "CALLBACK_KEYS"    -> key0,
    "CONSOLE_USER_ID"  -> "owner"
  )

  private def parse(env: Map[String, String], secrets: Map[String, String] = Map.empty) =
    Settings.parse(env, path => secrets(path))

  test("parses the minimal console configuration with defaults"):
    val Right(settings) = parse(base): @unchecked
    assertEquals(settings.enabledAdapters, List(Adapter.Console))
    assertEquals(settings.databaseUrl, "jdbc:postgresql://localhost:5432/dosegram")
    assertEquals(settings.callbackKeys.current.id, 0)
    assertEquals(settings.callbackKeys.overlap, None)
    assertEquals(settings.role, Role.All)
    assertEquals(settings.logLevel, LogLevel.Info)
    assertEquals(settings.logFormat, LogFormat.Json)
    assertEquals(settings.consoleUserId, Some("owner"))
    assertEquals(settings.healthPort, 8080)
    assert(settings.instanceId.nonEmpty)

  test("required fields are reported together, without values"):
    val Left(errors) = parse(Map.empty): @unchecked
    assert(errors.exists(_.startsWith("ENABLED_ADAPTERS is required")))
    assert(errors.exists(_.startsWith("DATABASE_URL is required")))
    assert(errors.exists(_.startsWith("CALLBACK_KEYS is required")))
    assert(!errors.exists(_.contains("jdbc:")))

  test("unknown adapter and bad enums are rejected"):
    val Left(errors) = parse(base ++ Map(
      "ENABLED_ADAPTERS" -> "console,signal",
      "ROLE"             -> "boss",
      "LOG_LEVEL"        -> "LOUD",
      "LOG_FORMAT"       -> "xml"
    )): @unchecked
    assert(errors.exists(_.contains("unknown adapter 'signal'")))
    assert(errors.exists(_.startsWith("ROLE must be")))
    assert(errors.exists(_.startsWith("LOG_LEVEL must be")))
    assert(errors.exists(_.startsWith("LOG_FORMAT must be")))

  test("enabled vendors require their credential sets"):
    val Left(errors) = parse(base ++ Map(
      "ENABLED_ADAPTERS" -> "console,telegram"
    )): @unchecked
    assert(errors.exists(_.startsWith("TELEGRAM_TOKEN is required")))

  test("full vendor credential sets parse"):
    val Right(settings) = parse(base ++ Map(
      "ENABLED_ADAPTERS" -> "console,telegram",
      "TELEGRAM_TOKEN"   -> "t",
      "TELEGRAM_BOT_ID"  -> "42",
      "TELEGRAM_UPDATES_MODE" -> "poll",
      "TELEGRAM_ALLOWED_USER_IDS" -> "7001, 7002",
      "ROLE"             -> "worker",
      "INSTANCE_ID"      -> "worker-1",
      "LOG_LEVEL"        -> "warn",
      "LOG_FORMAT"       -> "PLAIN",
      "PUBLIC_BASE_URL"  -> "https://dosegram.example.com"
    )): @unchecked
    assertEquals(settings.enabledAdapters, Adapter.values.toList)
    assertEquals(settings.role, Role.Worker)
    assertEquals(settings.instanceId, "worker-1")
    assertEquals(settings.logLevel, LogLevel.Warn)
    assertEquals(settings.logFormat, LogFormat.Plain)
    assertEquals(settings.telegram, Some(TelegramSettings("t", 42L, TelegramUpdates.Poll, Set(7001L, 7002L))))
    assertEquals(settings.publicBaseUrl, Some("https://dosegram.example.com"))

  private val telegram = base ++ Map(
    "ENABLED_ADAPTERS"          -> "telegram",
    "TELEGRAM_TOKEN"            -> "42:CANARY-token",
    "TELEGRAM_BOT_ID"           -> "42",
    "TELEGRAM_UPDATES_MODE"     -> "webhook",
    "TELEGRAM_ALLOWED_USER_IDS" -> "7001"
  )

  test("the telegram adapter needs its token, bot id and updates mode; all are reported together"):
    val Left(errors) = parse(base ++ Map("ENABLED_ADAPTERS" -> "telegram", "ENVIRONMENT" -> "development")): @unchecked
    assert(errors.exists(_.startsWith("TELEGRAM_TOKEN is required")), errors.toString)
    assert(errors.exists(_.startsWith("TELEGRAM_BOT_ID is required")), errors.toString)
    assert(errors.exists(_.startsWith("TELEGRAM_UPDATES_MODE is required")), errors.toString)

  test("without the telegram adapter its settings are absent and none is required"):
    val Right(settings) = parse(base): @unchecked
    assertEquals(settings.telegram, None)

  test("a bot id that is not a number and an unknown updates mode are rejected"):
    val Left(errors) = parse(telegram ++ Map("TELEGRAM_BOT_ID" -> "bot", "TELEGRAM_UPDATES_MODE" -> "push")): @unchecked
    assert(errors.contains("TELEGRAM_BOT_ID must be the numeric id of the bot"), errors.toString)
    assert(errors.contains("TELEGRAM_UPDATES_MODE must be poll or webhook"), errors.toString)

  test("outside development the telegram allowlist must name at least one user"):
    val Left(production) = parse(telegram - "TELEGRAM_ALLOWED_USER_IDS"): @unchecked
    assert(production.exists(_.startsWith("TELEGRAM_ALLOWED_USER_IDS must name at least one user")), production.toString)
    val Left(staging) = parse(telegram - "TELEGRAM_ALLOWED_USER_IDS" + ("ENVIRONMENT" -> "staging")): @unchecked
    assert(staging.exists(_.startsWith("TELEGRAM_ALLOWED_USER_IDS must name at least one user")), staging.toString)

  test("in development an empty telegram allowlist means everyone"):
    val Right(settings) = parse(telegram - "TELEGRAM_ALLOWED_USER_IDS" + ("ENVIRONMENT" -> "development")): @unchecked
    assertEquals(settings.telegram.map(_.allowedUserIds), Some(Set.empty[Long]))
    assertEquals(settings.environment, Environment.Development)

  test("an allowlist entry that is not a user id is rejected"):
    val Left(errors) = parse(telegram + ("TELEGRAM_ALLOWED_USER_IDS" -> "7001,@owner")): @unchecked
    assert(errors.contains("TELEGRAM_ALLOWED_USER_IDS must be comma-separated numeric user ids"), errors.toString)

  test("ENVIRONMENT defaults to production and rejects an unknown value"):
    val Right(settings) = parse(base): @unchecked
    assertEquals(settings.environment, Environment.Production)
    val Left(errors) = parse(base + ("ENVIRONMENT" -> "laptop")): @unchecked
    assert(errors.exists(_.startsWith("ENVIRONMENT must be")), errors.toString)

  test("MINI_APP_URL must be an https address"):
    val Right(settings) = parse(base + ("MINI_APP_URL" -> "https://dosegram.example.com/app/")): @unchecked
    assertEquals(settings.miniAppUrl, Some("https://dosegram.example.com/app/"))
    val Left(errors) = parse(base + ("MINI_APP_URL" -> "http://dosegram.example.com/app/")): @unchecked
    assert(errors.contains("MINI_APP_URL must be an https address"), errors.toString)

  test("no settings error and no text form of the telegram settings quotes the token"):
    val Right(settings) = parse(telegram): @unchecked
    assert(!settings.toString.contains("CANARY"), settings.toString)
    val Left(errors) = parse(telegram + ("TELEGRAM_BOT_ID" -> "x")): @unchecked
    assert(!errors.exists(_.contains("CANARY")), errors.toString)

  test("HEALTH_PORT overrides the /healthz port"):
    val Right(settings) = parse(base ++ Map("HEALTH_PORT" -> "18080")): @unchecked
    assertEquals(settings.healthPort, 18080)

  test("HEALTH_PORT rejects a value that is not a TCP port"):
    List("http", "0", "65536", "-1").foreach { raw =>
      val Left(errors) = parse(base ++ Map("HEALTH_PORT" -> raw)): @unchecked
      assert(errors.exists(_.startsWith("HEALTH_PORT must be")), s"HEALTH_PORT=$raw was accepted")
    }

  test("console adapter requires CONSOLE_USER_ID"):
    val Left(errors) = parse(base - "CONSOLE_USER_ID"): @unchecked
    assert(errors.exists(_.startsWith("CONSOLE_USER_ID is required")))

  test("CALLBACK_KEYS: current plus overlap key"):
    val Right(settings) = parse(base ++ Map("CALLBACK_KEYS" -> s"$key0,1:YWJjZGVmMDEyMzQ1Njc4OQ")): @unchecked
    assertEquals(settings.callbackKeys.current.id, 0)
    assertEquals(settings.callbackKeys.overlap.map(_.id), Some(1))

  test("CALLBACK_KEYS rejects bad ids, bad base64, short keys and three keys"):
    assert(Settings.parseCallbackKeys("16:MDEyMzQ1Njc4OWFiY2RlZg").isLeft, "id > 15")
    assert(Settings.parseCallbackKeys("0:not!base64").isLeft, "bad base64")
    assert(Settings.parseCallbackKeys("0:YWJj").isLeft, "short key")
    assert(Settings.parseCallbackKeys("").isLeft, "empty")
    assert(Settings.parseCallbackKeys(s"$key0,1:MDEyMzQ1Njc4OWFiY2RlZg,2:MDEyMzQ1Njc4OWFiY2RlZg").isLeft, "three keys")
    assert(Settings.parseCallbackKeys(s"$key0,0:YWJjZGVmMDEyMzQ1Njc4OQ").isLeft, "duplicate key id")

  test("Docker secrets: <NAME>_FILE supplies the value"):
    val Right(settings) = parse(
      base - "DATABASE_URL" ++ Map("DATABASE_URL_FILE" -> "/run/secrets/db_url"),
      Map("/run/secrets/db_url" -> "jdbc:postgresql://db:5432/dosegram?user=u&password=p")
    ): @unchecked
    assertEquals(settings.databaseUrl, "jdbc:postgresql://db:5432/dosegram?user=u&password=p")

  test("an unreadable <NAME>_FILE is an error naming the variable, not the content"):
    val Left(errors) = parse(
      base - "CALLBACK_KEYS" ++ Map("CALLBACK_KEYS_FILE" -> "/missing"),
      Map.empty
    ): @unchecked
    assert(errors.exists(e => e.startsWith("CALLBACK_KEYS: cannot read /missing")))

  test("fromEnv throws IllegalArgumentException on invalid settings"):
    intercept[IllegalArgumentException](Settings.fromEnv(Map.empty))

  test(".env.example is generated from the spec and mentions every key"):
    val example = Settings.envExample
    Settings.spec.foreach { entry =>
      assert(example.contains(s"${entry.name}="), s"${entry.name} missing from .env.example")
    }
    assertEquals(example, Settings.envExample, "generation must be deterministic")
    assert(example.contains("<NAME>_FILE"), "Docker secrets convention must be documented")

  test("fromEnv can read a real secret file"):
    val file = Files.createTempFile("dosegram-secret", ".txt")
    Files.writeString(file, "jdbc:postgresql://db:5432/dosegram\n")
    val settings = Settings.fromEnv(base - "DATABASE_URL" ++ Map("DATABASE_URL_FILE" -> file.toString))
    assertEquals(settings.databaseUrl, "jdbc:postgresql://db:5432/dosegram")
