package dosecord.infra

import java.nio.file.Files

class SettingsSuite extends munit.FunSuite:

  private val key0 = "0:MDEyMzQ1Njc4OWFiY2RlZg" // "0123456789abcdef", 16 bytes, padding omitted

  private val base = Map(
    "ENABLED_ADAPTERS" -> "console",
    "DATABASE_URL"     -> "jdbc:postgresql://localhost:5432/dosecord",
    "CALLBACK_KEYS"    -> key0,
    "CONSOLE_USER_ID"  -> "owner"
  )

  private def parse(env: Map[String, String], secrets: Map[String, String] = Map.empty) =
    Settings.parse(env, path => secrets(path))

  test("parses the minimal console configuration with defaults"):
    val Right(settings) = parse(base): @unchecked
    assertEquals(settings.enabledAdapters, List(Adapter.Console))
    assertEquals(settings.databaseUrl, "jdbc:postgresql://localhost:5432/dosecord")
    assertEquals(settings.callbackKeys.current.id, 0)
    assertEquals(settings.callbackKeys.overlap, None)
    assertEquals(settings.role, Role.All)
    assertEquals(settings.logLevel, LogLevel.Info)
    assertEquals(settings.logFormat, LogFormat.Json)
    assertEquals(settings.consoleUserId, Some("owner"))
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
      "ENABLED_ADAPTERS" -> "console,discord,telegram,zulip,matrix"
    )): @unchecked
    assert(errors.exists(_.startsWith("DISCORD_TOKEN is required")))
    assert(errors.exists(_.startsWith("TELEGRAM_TOKEN is required")))
    assert(errors.exists(_.startsWith("ZULIP_SITE")))
    assert(errors.exists(_.startsWith("MATRIX_HOMESERVER")))

  test("full vendor credential sets parse"):
    val Right(settings) = parse(base ++ Map(
      "ENABLED_ADAPTERS"  -> "console,discord,telegram,zulip,matrix",
      "DISCORD_TOKEN"     -> "d",
      "TELEGRAM_TOKEN"    -> "t",
      "ZULIP_SITE"        -> "https://zulip.example.com",
      "ZULIP_EMAIL"       -> "bot@zulip.example.com",
      "ZULIP_API_KEY"     -> "z",
      "MATRIX_HOMESERVER" -> "https://matrix.example.com",
      "MATRIX_USER"       -> "@bot:matrix.example.com",
      "MATRIX_TOKEN"      -> "m",
      "ROLE"              -> "worker",
      "INSTANCE_ID"       -> "worker-1",
      "LOG_LEVEL"         -> "warn",
      "LOG_FORMAT"        -> "PLAIN",
      "PUBLIC_BASE_URL"   -> "https://dosecord.example.com"
    )): @unchecked
    assertEquals(settings.enabledAdapters, Adapter.values.toList)
    assertEquals(settings.role, Role.Worker)
    assertEquals(settings.instanceId, "worker-1")
    assertEquals(settings.logLevel, LogLevel.Warn)
    assertEquals(settings.logFormat, LogFormat.Plain)
    assertEquals(settings.zulip.map(_.email), Some("bot@zulip.example.com"))
    assertEquals(settings.matrix.map(_.user), Some("@bot:matrix.example.com"))
    assertEquals(settings.publicBaseUrl, Some("https://dosecord.example.com"))

  test("partial zulip/matrix credential sets are rejected"):
    val Left(errors) = parse(base ++ Map("ZULIP_SITE" -> "https://zulip.example.com")): @unchecked
    assert(errors.exists(_.contains("must be set together")))

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
      Map("/run/secrets/db_url" -> "jdbc:postgresql://db:5432/dosecord?user=u&password=p")
    ): @unchecked
    assertEquals(settings.databaseUrl, "jdbc:postgresql://db:5432/dosecord?user=u&password=p")

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
    val file = Files.createTempFile("dosecord-secret", ".txt")
    Files.writeString(file, "jdbc:postgresql://db:5432/dosecord\n")
    val settings = Settings.fromEnv(base - "DATABASE_URL" ++ Map("DATABASE_URL_FILE" -> file.toString))
    assertEquals(settings.databaseUrl, "jdbc:postgresql://db:5432/dosecord")
