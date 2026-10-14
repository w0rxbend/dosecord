package dosecord.adapter.discord

import net.dv8tion.jda.internal.JDAImpl
import net.dv8tion.jda.internal.entities.channel.concrete.PrivateChannelImpl
import net.dv8tion.jda.internal.requests.restaction.MessageCreateActionImpl
import net.dv8tion.jda.internal.utils.config.AuthorizationConfig
import net.dv8tion.jda.api.utils.data.DataObject

class JdaPayloadSuite extends munit.FunSuite:
  private def proxy[A](tpe: Class[A])(result: String => AnyRef): A =
    java.lang.reflect.Proxy
      .newProxyInstance(tpe.getClassLoader, Array(tpe), (_, method, _) => result(method.getName))
      .asInstanceOf[A]

  test("initial reply returns callback message id rather than application webhook id"):
    val message = proxy(classOf[net.dv8tion.jda.api.entities.Message])(_ => "222")
    val response = proxy(classOf[net.dv8tion.jda.api.interactions.response.InteractionCallbackResponse])(_ => message)
    val hook = proxy(classOf[net.dv8tion.jda.api.interactions.InteractionHook]): name =>
      if name == "getCallbackResponse" then response else "111"
    assertEquals(JdaTransport.callbackMessageId(hook), "222")

  test("a callback without a message is a typed nonfatal failure, never a webhook id"):
    val response = proxy(classOf[net.dv8tion.jda.api.interactions.response.InteractionCallbackResponse])(_ => null)
    val hook = proxy(classOf[net.dv8tion.jda.api.interactions.InteractionHook]): name =>
      if name == "getCallbackResponse" then response else "111"
    intercept[dosecord.contracts.ChatError.Permanent](JdaTransport.callbackMessageId(hook))

  test("initial callback serialization preserves silent and preview flags"):
    val data = JdaTransport.replyData(DiscordMessage("bounded", silent = true, suppressPreview = true)).toData
    assertEquals(data.getInt("flags") & 4096, 4096)
    assertEquals(data.getInt("flags") & 4, 4)
    val edit = JdaTransport.editData(DiscordMessage("bounded", silent = true, suppressPreview = true)).toData
    assertEquals(edit.getInt("flags") & 4, 4)

  test("pinned JDA serializes enforce_nonce and SUPPRESS_NOTIFICATIONS in the actual request body"):
    // Construct only: no build(), login(), requester or REST execution.
    val jda = new JDAImpl(new AuthorizationConfig("synthetic-offline-fixture"))
    val channel = new PrivateChannelImpl(jda, 123L, null)
    class RequestProbe extends MessageCreateActionImpl(channel):
      def payload: DataObject =
        val buffer = new okio.Buffer()
        finalizeData().writeTo(buffer)
        DataObject.fromJson(buffer.readUtf8())
    val action = new RequestProbe
    action.setContent("synthetic")
    action.setNonce(DiscordAdapter.nonceOf("fixture"))
    action.setSuppressedNotifications(true)
    val payload = action.payload
    assert(payload.getBoolean("enforce_nonce"))
    assertEquals(payload.getString("nonce"), DiscordAdapter.nonceOf("fixture"))
    assertEquals(payload.getInt("flags") & 4096, 4096)
