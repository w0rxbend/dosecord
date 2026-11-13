package dosegram.adapter.telegram

import dosegram.contracts.AppRoute
import dosegram.contracts.ChatError
import dosegram.contracts.ChoiceAction
import dosegram.contracts.ChoiceStyle
import dosegram.contracts.RenderedChoice
import dosegram.contracts.RenderedControls
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

/** BOT_UX sections 4.2 and 4.3: rendered controls as an inline keyboard, and callback data inside 64 bytes. */
class KeyboardsSuite extends ScalaCheckSuite:

  private val keyboards = Keyboards(Some("https://app.example/"))

  private def callback(index: Int, label: String, token: String, style: ChoiceStyle = ChoiceStyle.Secondary) =
    RenderedChoice(index, label, ChoiceAction.Callback(token), style)

  test("button rows keep their order, labels, tokens and styles"):
    val controls = List(
      RenderedControls.Buttons(
        List(List(callback(1, "Taken", "dc:a", ChoiceStyle.Success), callback(2, "Skip", "dc:b")))
      ),
      RenderedControls.Buttons(List(List(callback(3, "Delete", "dc:c", ChoiceStyle.Danger))))
    )
    assertEquals(
      keyboards.inline(controls),
      InlineKeyboard(
        List(
          List(InlineButton.Callback("Taken", "dc:a", Some("success")), InlineButton.Callback("Skip", "dc:b", None)),
          List(InlineButton.Callback("Delete", "dc:c", Some("danger")))
        )
      )
    )

  test("an app choice becomes a web_app button whose URL carries the route"):
    val app = RenderedChoice(0, "Open app", ChoiceAction.OpenApp(AppRoute("meds")))
    assertEquals(
      keyboards.inline(List(RenderedControls.Buttons(List(List(app))))),
      InlineKeyboard(List(List(InlineButton.WebApp("Open app", "https://app.example/?route=meds"))))
    )

  test("a Mini App address that already has a query keeps it"):
    val app = RenderedChoice(0, "Open app", ChoiceAction.OpenApp(AppRoute("today")))
    assertEquals(
      Keyboards(Some("https://app.example/?v=2")).inline(List(RenderedControls.Buttons(List(List(app))))),
      InlineKeyboard(List(List(InlineButton.WebApp("Open app", "https://app.example/?v=2&route=today"))))
    )

  test("without a Mini App address the app button is left out, and a row it leaves empty goes with it"):
    val app = RenderedChoice(0, "Open app", ChoiceAction.OpenApp(AppRoute("today")))
    val controls = List(RenderedControls.Buttons(List(List(callback(1, "Menu", "dc:m"), app), List(app))))
    assertEquals(
      Keyboards(None).inline(controls),
      InlineKeyboard(List(List(InlineButton.Callback("Menu", "dc:m", None))))
    )

  test("a link choice becomes a URL button"):
    val link = RenderedChoice(0, "Privacy notice", ChoiceAction.OpenUrl("https://example.org/privacy"))
    assertEquals(
      keyboards.inline(List(RenderedControls.Buttons(List(List(link))))),
      InlineKeyboard(List(List(InlineButton.Url("Privacy notice", "https://example.org/privacy"))))
    )

  test("a numbered legend and no controls have no keyboard"):
    val numbered = RenderedControls.Numbered("set", List(callback(1, "One", "dc:a")))
    assert(keyboards.inline(List(numbered, RenderedControls.NoControls)).isEmpty)

  test("callback data over 64 bytes is refused as a permanent error before anything is sent"):
    val oversized = callback(1, "Too long", "dc:" + "x" * 62)
    val error = intercept[ChatError.Permanent](keyboards.inline(List(RenderedControls.Buttons(List(List(oversized))))))
    assert(!error.channelFatal)

  // BUX-CB-01: every action with extreme values stays inside the 64 byte budget.
  property("every token the codec mints for any action, subject, value and mode fits callback_data"):
    val anyAction = Gen.oneOf(ActionRegistry.entries)
    val anyValue = Gen.oneOf(Gen.const(0L), Gen.const(CallbackCodec.MaxValue), Gen.choose(0L, CallbackCodec.MaxValue))
    val anyMode = Gen.oneOf(CallbackMode.Direct, CallbackMode.Slot)
    forAll(anyAction, Gen.uuid, anyValue, anyMode): (action, subject: UUID, value, mode) =>
      val token = TelegramRig.codec.encode(mode, action, subject, value).wire
      assert(token.getBytes(UTF_8).length <= Keyboards.MaxCallbackBytes)
      val keyboard = keyboards.inline(List(RenderedControls.Buttons(List(List(callback(1, "x", token))))))
      assertEquals(keyboard.rows, List(List(InlineButton.Callback("x", token, None))))
