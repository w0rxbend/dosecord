package dosegram.adapter.telegram

import java.nio.charset.StandardCharsets.UTF_8

/** The Bot API shape fixtures of `test/resources/telegram/fixtures` (see the README there for their provenance). */
object BotApiFixtures:

  final case class ErrorFixture(name: String, method: String, status: Int, body: ujson.Value, expect: String)

  private def load(name: String): ujson.Value =
    val stream = getClass.getResourceAsStream(s"/telegram/fixtures/$name")
    require(stream != null, s"fixture $name is not on the classpath")
    try ujson.read(String(stream.readAllBytes(), UTF_8))
    finally stream.close()

  private lazy val updates = load("updates.json")
  private lazy val responses = load("responses.json")

  lazy val updateNames: List[String] = updates.obj.keys.toList

  /** A fresh copy, so a test may change it. */
  def update(name: String): ujson.Value = ujson.copy(updates(name))

  def response(name: String): ujson.Value = ujson.copy(responses(name))

  lazy val errors: List[ErrorFixture] =
    load("errors.json").arr.toList.map: entry =>
      ErrorFixture(
        entry("name").str,
        entry("method").str,
        entry("status").num.toInt,
        entry("body"),
        entry("expect").str
      )

  def error(name: String): ErrorFixture =
    errors.find(_.name == name).getOrElse(throw new NoSuchElementException(s"no error fixture '$name'"))
end BotApiFixtures
