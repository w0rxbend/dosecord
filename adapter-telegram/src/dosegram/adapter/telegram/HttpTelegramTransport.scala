package dosegram.adapter.telegram

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.UUID

/** The one file that touches the network (ARCHITECTURE section 7.1): Bot API calls over the JDK HTTP client. The
  * request URL contains the token, so this class never logs, never puts a URL into an exception and reports a failed
  * call as a [[WireFailure]] without the client's message (TG-02).
  */
final class HttpTelegramTransport(
    token: BotToken,
    baseUrl: String = HttpTelegramTransport.ProductionBaseUrl,
    newBoundary: () => String = () => s"dosegram-${UUID.randomUUID()}"
) extends TelegramTransport,
      AutoCloseable:

  private val client = HttpClient.newBuilder().connectTimeout(HttpTelegramTransport.ConnectTimeout).build()

  override def call(request: WireRequest): WireReply =
    val http = HttpRequest.newBuilder(methodUri(request.method)).timeout(request.timeout)
    request.file match
      case None =>
        http.header("Content-Type", "application/json")
        http.POST(HttpRequest.BodyPublishers.ofString(ujson.write(request.params), UTF_8))
      case Some(file) =>
        val boundary = newBoundary()
        http.header("Content-Type", s"multipart/form-data; boundary=$boundary")
        http.POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, request.params, file)))
    try
      val response = client.send(http.build(), HttpResponse.BodyHandlers.ofString(UTF_8))
      WireReply.Http(response.statusCode(), response.body())
    catch
      case _: HttpTimeoutException => WireReply.Failed(WireFailure.Timeout)
      case _: IOException          => WireReply.Failed(WireFailure.Connection)

  /** Drops calls that are still in flight (a long poll waits up to 50 seconds): closing happens at shutdown only. */
  override def close(): Unit = client.shutdownNow()

  /** `<base>/bot<token>/<method>` (research brief 02, section 3). A token is checked at construction to consist of
    * URL-safe characters, so building the URI cannot fail with a message that quotes it.
    */
  private def methodUri(method: String): URI = URI.create(s"$baseUrl/bot${token.secret}/$method")

  /** `multipart/form-data`: every parameter as one text part (a string as it is, anything else as JSON), then the file.
    */
  private def multipart(boundary: String, params: ujson.Obj, file: WireFile): Array[Byte] =
    val out = ByteArrayOutputStream()
    def text(value: String): Unit = out.write(value.getBytes(UTF_8))
    params.value.foreach: (name, value) =>
      text(s"--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
      text(value.strOpt.getOrElse(ujson.write(value)))
      text("\r\n")
    text(s"--$boundary\r\n")
    text(s"Content-Disposition: form-data; name=\"${file.field}\"; filename=\"${safeFileName(file.fileName)}\"\r\n")
    text(s"Content-Type: ${file.mimeType}\r\n\r\n")
    out.write(file.bytes)
    text(s"\r\n--$boundary--\r\n")
    out.toByteArray

  /** A file name is a header value here: anything that could end the header or the quoted string is replaced. */
  private def safeFileName(name: String): String =
    name.map(c => if c.isLetterOrDigit || c == '.' || c == '-' || c == '_' then c else '_')

object HttpTelegramTransport:
  val ProductionBaseUrl = "https://api.telegram.org"
  val ConnectTimeout: Duration = Duration.ofSeconds(5)
