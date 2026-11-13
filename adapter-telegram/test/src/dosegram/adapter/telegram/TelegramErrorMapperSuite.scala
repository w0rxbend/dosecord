package dosegram.adapter.telegram

import dosegram.contracts.ChatError

import java.time.Duration

/** ROADMAP G1.4: the error mapper maps every fixture to exactly one case, and no error the core sees carries text that
  * came from the wire (ARCHITECTURE section 7.10, TG-02).
  */
class TelegramErrorMapperSuite extends munit.FunSuite:

  private def classify(fixture: BotApiFixtures.ErrorFixture): TelegramError =
    BotApi.readReply(fixture.status, ujson.write(fixture.body)) match
      case Left(error) => error
      case Right(_)    => fail(s"${fixture.name} decoded as a success")

  BotApiFixtures.errors.foreach: fixture =>
    test(s"fixture ${fixture.name} maps to ${fixture.expect}"):
      assertEquals(classify(fixture).productPrefix, fixture.expect)

  test("a 429 carries the wait the API asked for"):
    assertEquals(classify(BotApiFixtures.error("flood-429")), TelegramError.RateLimited(Duration.ofSeconds(30)))

  test("a 429 without retry_after waits the default"):
    assertEquals(
      TelegramErrorMapper.classify(429, "Too Many Requests", None),
      TelegramError.RateLimited(TelegramErrorMapper.DefaultRetryAfter)
    )

  test("a description is matched the same way under every default locale"):
    DefaultLocale.turkish:
      assertEquals(
        TelegramErrorMapper.classify(400, "Bad Request: MESSAGE_ID_INVALID", None),
        TelegramError.MissingTarget
      )

  test("a blocked user is unreachable and fatal for the channel"):
    val error = TelegramErrorMapper.toChatError(TelegramError.Unreachable, "send")
    assert(error.exists(_.isInstanceOf[ChatError.Unreachable]), error.toString)
    assert(error.exists(_.channelFatal))

  test("a missing edit target is a typed, non-fatal permanent error"):
    TelegramErrorMapper.toChatError(TelegramError.MissingTarget, "edit") match
      case Some(ChatError.Permanent(_, fatal, reason)) =>
        assert(!fatal)
        assertEquals(reason, ChatError.PermanentReason.MissingTarget)
      case other => fail(s"unexpected mapping: $other")

  test("a delete outside the window is permanent and leaves the channel alone"):
    TelegramErrorMapper.toChatError(TelegramError.NotDeletable, "delete") match
      case Some(ChatError.Permanent(_, fatal, reason)) =>
        assert(!fatal)
        assertEquals(reason, ChatError.PermanentReason.General)
      case other => fail(s"unexpected mapping: $other")

  test("an unchanged edit and an expired callback answer have no error: they are successes"):
    assertEquals(TelegramErrorMapper.toChatError(TelegramError.NotModified, "edit"), None)
    assertEquals(TelegramErrorMapper.toChatError(TelegramError.CallbackExpired, "answer"), None)

  test("a rejected token never marks the user's channel dead"):
    val error = TelegramErrorMapper.toChatError(TelegramError.TokenInvalid, "send")
    assert(error.exists(e => e.isInstanceOf[ChatError.Retryable] && !e.channelFatal), error.toString)

  test("no error the core sees quotes the description of the wire"):
    val fixture = BotApiFixtures.error("parse-entities-400")
    val error = TelegramErrorMapper.toChatError(classify(fixture), "send")
    assert(error.isDefined)
    assert(!error.exists(_.getMessage.contains("CANARY-DESCRIPTION")), error.map(_.getMessage).toString)
