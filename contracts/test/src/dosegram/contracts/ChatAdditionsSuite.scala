package dosegram.contracts

/** The Telegram-native additions of ADR-015: validation at construction, so no adapter sees a malformed value. */
class ChatAdditionsSuite extends munit.FunSuite:

  private val alphabet = "AZaz09_-"

  test("a start payload holds 1 to 64 characters of A-Z a-z 0-9 _ -"):
    assertEquals(StartPayload(alphabet * 8).value.length, 64)
    intercept[IllegalArgumentException](StartPayload(alphabet * 8 + "x"))
    intercept[IllegalArgumentException](StartPayload(""))
    intercept[IllegalArgumentException](StartPayload("with space"))
    intercept[IllegalArgumentException](StartPayload("кирилиця"))

  test("parsing untrusted text yields a start payload only inside the grammar"):
    assertEquals(StartPayload.parse("invite_abc-1"), Some(StartPayload("invite_abc-1")))
    assertEquals(StartPayload.parse("not a payload!"), None)
    assertEquals(StartPayload.parse(""), None)

  test("an app route follows the same grammar as a start payload"):
    assertEquals(AppRoute("med_AAAAAAAAAAAAAAAAAAAAAA").wire, "med_AAAAAAAAAAAAAAAAAAAAAA")
    intercept[IllegalArgumentException](AppRoute("today?x=1"))
    intercept[IllegalArgumentException](AppRoute(""))

  test("the three choice actions build through named factories"):
    assertEquals(Choice.callback("Stats", "dc:token").action, ChoiceAction.Callback("dc:token"))
    assertEquals(Choice.openApp("Open app", AppRoute("today")).action, ChoiceAction.OpenApp(AppRoute("today")))
    assertEquals(
      Choice.openUrl("Privacy notice", "https://example.org/privacy").action,
      ChoiceAction.OpenUrl("https://example.org/privacy")
    )

  test("a URL choice opens https links only"):
    intercept[IllegalArgumentException](Choice.openUrl("Notice", "http://example.org"))
    intercept[IllegalArgumentException](Choice.openUrl("Notice", "javascript:alert(1)"))

  test("an attachment needs an asset key, a MIME type and a text equivalent of at most 1024 characters"):
    val photo = AttachmentRef(MediaKind.Photo, "asset-1", "image/png", "Week of 9 November: 12 of 14 doses taken.")
    assertEquals(photo.fileName, None)
    assertEquals(photo.copy(altText = "x" * 1024).altText.length, 1024)
    intercept[IllegalArgumentException](photo.copy(altText = ""))
    intercept[IllegalArgumentException](photo.copy(altText = "x" * 1025))
    intercept[IllegalArgumentException](photo.copy(assetKey = ""))
    intercept[IllegalArgumentException](photo.copy(mimeType = ""))

  test("a rendered message may have no text only when it carries an attachment"):
    val photo = AttachmentRef(MediaKind.Photo, "asset-1", "image/png", "A chart.")
    assertEquals(RenderedMessage(chunks = Nil, attachment = Some(photo)).chunks, Nil)
    intercept[IllegalArgumentException](RenderedMessage(chunks = Nil))
    intercept[IllegalArgumentException](RenderedMessage(chunks = List(""), attachment = Some(photo)))

  test("a rendered callback choice is numbered from 1 and a link choice is never numbered"):
    assertEquals(RenderedChoice(1, "Stats", ChoiceAction.Callback("dc:token")).index, 1)
    assertEquals(RenderedChoice(0, "Open app", ChoiceAction.OpenApp(AppRoute("today"))).index, 0)
    intercept[IllegalArgumentException](RenderedChoice(0, "Stats", ChoiceAction.Callback("dc:token")))
    intercept[IllegalArgumentException](RenderedChoice(1, "Open app", ChoiceAction.OpenApp(AppRoute("today"))))

  test("a media reference needs an id"):
    assertEquals(MediaRef("file-1").mediaId, "file-1")
    intercept[IllegalArgumentException](MediaRef(""))
