package dosegram.infra

/** The Mini App switch of ROADMAP G1.12 and the start-up rules of ARCHITECTURE CFG-01. */
class MiniAppSettingsSuite extends munit.FunSuite:

  private val base = Map(
    "ENABLED_ADAPTERS" -> "console",
    "DATABASE_URL" -> "jdbc:postgresql://localhost:5432/dosegram",
    "CALLBACK_KEYS" -> "0:MDEyMzQ1Njc4OWFiY2RlZg",
    "CONSOLE_USER_ID" -> "owner"
  )
  private val enabled = base ++ Map("ENVIRONMENT" -> "development", "MINIAPP_ENABLED" -> "true")
  private val testKey = "40055058a4ee38156a06562e52eece92a771bcd8346a8c4615cb7376eddf72ec"

  private def parse(env: Map[String, String]) = Settings.parse(env, path => sys.error(s"no secret file $path"))

  private def errorsOf(env: Map[String, String]): List[String] = parse(env).left.toOption.getOrElse(Nil)

  test("by default the environment is production, the public port is 8081 and the Mini App is off"):
    val Right(settings) = parse(base): @unchecked
    assertEquals(settings.environment, Environment.Production)
    assertEquals(settings.httpPort, 8081)
    assertEquals(settings.miniApp, None)

  test("MINIAPP_ENABLED=true is refused in production"):
    val errors = errorsOf(base ++ Map("MINIAPP_ENABLED" -> "true", "TELEGRAM_BOT_ID" -> "7000000001"))
    assert(errors.exists(_.startsWith("MINIAPP_ENABLED=true is refused while ENVIRONMENT=production")), errors)

  test("enabled outside production with a bot id: launch data is checked with Telegram's production key"):
    val Right(settings) = parse(enabled ++ Map("TELEGRAM_BOT_ID" -> "7000000001", "HTTP_PORT" -> "18081")): @unchecked
    assertEquals(settings.environment, Environment.Development)
    assertEquals(settings.httpPort, 18081)
    assertEquals(
      settings.miniApp,
      Some(MiniAppSettings(staticDir = None, botId = Some(7000000001L), initDataPublicKey = None, hmacFallback = false))
    )

  test("a test key, a static directory and the HMAC fallback parse together"):
    val Right(settings) = parse(
      enabled ++ Map(
        "ENVIRONMENT" -> "test",
        "TELEGRAM_BOT_ID" -> "7000000001",
        "MINIAPP_INITDATA_PUBLIC_KEY" -> testKey,
        "MINIAPP_STATIC_DIR" -> "miniapp/dist",
        "MINIAPP_HMAC_FALLBACK" -> "true",
        "TELEGRAM_TOKEN" -> "t"
      )
    ): @unchecked
    assertEquals(
      settings.miniApp,
      Some(MiniAppSettings(Some("miniapp/dist"), Some(7000000001L), Some(testKey), hmacFallback = true))
    )

  test("enabled without a bot id and without the HMAC fallback is refused: nothing could verify launch data"):
    assert(errorsOf(enabled).exists(_.startsWith("MINIAPP_ENABLED needs TELEGRAM_BOT_ID")), errorsOf(enabled))

  test("the HMAC fallback needs the bot token, and the error does not print it"):
    val errors = errorsOf(enabled ++ Map("MINIAPP_HMAC_FALLBACK" -> "true"))
    assert(errors.exists(_.startsWith("MINIAPP_HMAC_FALLBACK=true needs TELEGRAM_TOKEN")), errors)
    val Right(settings) = parse(enabled ++ Map("MINIAPP_HMAC_FALLBACK" -> "true", "TELEGRAM_TOKEN" -> "t")): @unchecked
    assertEquals(settings.miniApp.map(_.hmacFallback), Some(true))

  test("a key that is not 64 hex digits is refused without echoing it"):
    val errors = errorsOf(enabled ++ Map("TELEGRAM_BOT_ID" -> "1", "MINIAPP_INITDATA_PUBLIC_KEY" -> "canary-key"))
    assert(errors.exists(_.startsWith("MINIAPP_INITDATA_PUBLIC_KEY must be 64 hex digits")), errors)
    assert(!errors.exists(_.contains("canary")), errors)

  test("bad values of the new variables are named"):
    val errors = errorsOf(
      base ++ Map(
        "ENVIRONMENT" -> "staging",
        "HTTP_PORT" -> "0",
        "MINIAPP_ENABLED" -> "yes",
        "MINIAPP_HMAC_FALLBACK" -> "1",
        "TELEGRAM_BOT_ID" -> "bot"
      )
    )
    List("ENVIRONMENT must be", "HTTP_PORT must be", "MINIAPP_ENABLED must be", "MINIAPP_HMAC_FALLBACK must be",
      "TELEGRAM_BOT_ID must be").foreach: prefix =>
      assert(errors.exists(_.startsWith(prefix)), s"$prefix missing in $errors")

  test("with the switch off the other Mini App variables are parsed and ignored"):
    val Right(settings) = parse(base ++ Map("TELEGRAM_BOT_ID" -> "7000000001", "MINIAPP_STATIC_DIR" -> "dist")): @unchecked
    assertEquals(settings.miniApp, None)

  // ---------- production from checkpoint CP3-a (ADR-016 addendum, 2026-11-13) ----------

  private val production = base ++ Map("MINIAPP_ENABLED" -> "true", "TELEGRAM_BOT_ID" -> "7000000001")

  test("in production the Mini App needs an https MINI_APP_URL and an explicit HTTP_PORT; both are named"):
    val errors = errorsOf(production)
    assert(errors.exists(e => e.contains("MINI_APP_URL") && e.contains("HTTP_PORT")), errors)

  test("in production an https MINI_APP_URL and an explicit HTTP_PORT switch the Mini App on with Telegram's key"):
    val Right(settings) = parse(
      production ++ Map("MINI_APP_URL" -> "https://dosegram.example.com/app/", "HTTP_PORT" -> "8081")
    ): @unchecked
    assertEquals(settings.environment, Environment.Production)
    assertEquals(
      settings.miniApp,
      Some(MiniAppSettings(staticDir = None, botId = Some(7000000001L), initDataPublicKey = None, hmacFallback = false))
    )

  test("in production a MINI_APP_URL without a host is refused"):
    val errors = errorsOf(production ++ Map("MINI_APP_URL" -> "https:///app/", "HTTP_PORT" -> "8081"))
    assert(errors.exists(_.startsWith("MINI_APP_URL must be")), errors)

  test("in production a test key stays refused when the Mini App is otherwise complete"):
    val errors = errorsOf(
      production ++ Map(
        "MINI_APP_URL" -> "https://dosegram.example.com/app/",
        "HTTP_PORT" -> "8081",
        "MINIAPP_INITDATA_PUBLIC_KEY" -> testKey
      )
    )
    assertEquals(errors, List("MINIAPP_INITDATA_PUBLIC_KEY is refused while ENVIRONMENT=production"))

  // ---------- listen addresses ----------

  test("both listeners bind to the loopback interface by default"):
    val Right(settings) = parse(base): @unchecked
    assertEquals(settings.healthAddress, "127.0.0.1")
    assertEquals(settings.httpAddress, "127.0.0.1")

  test("HEALTH_ADDRESS and HTTP_ADDRESS take an IPv4 or IPv6 literal"):
    val Right(settings) = parse(base ++ Map("HEALTH_ADDRESS" -> "0.0.0.0", "HTTP_ADDRESS" -> "::")): @unchecked
    assertEquals(settings.healthAddress, "0.0.0.0")
    assertEquals(settings.httpAddress, "::")

  test("a host name or a malformed address is refused for both listeners, without a lookup"):
    val errors = errorsOf(base ++ Map("HEALTH_ADDRESS" -> "localhost", "HTTP_ADDRESS" -> "10.0.0.256"))
    List("HEALTH_ADDRESS must be", "HTTP_ADDRESS must be").foreach: prefix =>
      assert(errors.exists(_.startsWith(prefix)), s"$prefix missing in $errors")

  test("in production the HMAC fallback is refused: launch data is checked with Ed25519 only (ARCHITECTURE CFG-01)"):
    val errors = errorsOf(
      production ++ Map(
        "MINI_APP_URL" -> "https://dosegram.example.com/app/",
        "HTTP_PORT" -> "8081",
        "MINIAPP_HMAC_FALLBACK" -> "true",
        "TELEGRAM_TOKEN" -> "t"
      )
    )
    assertEquals(errors, List("MINIAPP_HMAC_FALLBACK=true is refused while ENVIRONMENT=production"))
