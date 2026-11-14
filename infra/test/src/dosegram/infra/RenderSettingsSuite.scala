package dosegram.infra

/** The image renderer is optional and needs both of its settings (ADR-022). */
class RenderSettingsSuite extends munit.FunSuite:

  private val base = Map(
    "ENABLED_ADAPTERS" -> "console",
    "DATABASE_URL" -> "jdbc:postgresql://localhost:5432/dosegram",
    "CALLBACK_KEYS" -> "0:MDEyMzQ1Njc4OWFiY2RlZg",
    "CONSOLE_USER_ID" -> "owner",
    "KEK" -> "test:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
  )

  private def parse(env: Map[String, String]) = Settings.parse(env, path => sys.error(s"no secret file $path"))

  test("without the renderer settings no renderer is configured"):
    val Right(settings) = parse(base): @unchecked
    assertEquals(settings.render, None)

  test("the binary and the font directory parse together"):
    val env = base ++ Map("RESVG_PATH" -> "/usr/local/bin/resvg", "RENDER_FONTS_DIR" -> "/opt/dosegram/fonts")
    val Right(settings) = parse(env): @unchecked
    assertEquals(settings.render, Some(RenderSettings("/usr/local/bin/resvg", "/opt/dosegram/fonts")))

  test("one of the two without the other is refused"):
    val message = "RESVG_PATH and RENDER_FONTS_DIR must be set together"
    assertEquals(parse(base + ("RESVG_PATH" -> "/usr/local/bin/resvg")).left.toOption, Some(List(message)))
    assertEquals(parse(base + ("RENDER_FONTS_DIR" -> "/opt/dosegram/fonts")).left.toOption, Some(List(message)))
