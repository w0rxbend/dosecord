package dosegram.app

import ox.supervised

import java.net.HttpURLConnection
import java.net.URI

class HealthSuite extends munit.FunSuite:

  private val ready = () => Readiness.Report(ready = true, """{"status":"ready"}""")

  /** Status, content type and body of a GET on the listener. */
  private def get(port: Int, path: String): (Int, String, String) =
    val conn = URI(s"http://127.0.0.1:$port$path").toURL.openConnection().asInstanceOf[HttpURLConnection]
    try
      val status = conn.getResponseCode
      val stream = if status < 400 then conn.getInputStream else conn.getErrorStream
      (status, Option(conn.getContentType).getOrElse(""), String(stream.readAllBytes()))
    finally conn.disconnect()

  test("/healthz answers 200 ok"):
    supervised {
      val port    = freePort()
      val binding = Health.start("127.0.0.1", port, ready)
      try
        val (status, _, body) = get(port, "/healthz")
        assertEquals(status, 200)
        assertEquals(body, "ok")
      finally binding.stop()
    }

  test("/readyz answers 200 with the report when ready and 503 when not"):
    supervised {
      @volatile var report = Readiness.Report(ready = true, """{"status":"ready"}""")
      val port    = freePort()
      val binding = Health.start("127.0.0.1", port, () => report)
      try
        assertEquals(get(port, "/readyz"), (200, "application/json", """{"status":"ready"}"""))
        report = Readiness.Report(ready = false, """{"status":"not_ready","database":"down"}""")
        assertEquals(get(port, "/readyz"), (503, "application/json", """{"status":"not_ready","database":"down"}"""))
      finally binding.stop()
    }

  test("/metrics serves the Prometheus exposition of the registry"):
    supervised {
      dosegram.infra.Metrics.outboxSent("telegram", "reminder")
      val port    = freePort()
      val binding = Health.start("127.0.0.1", port, ready)
      try
        val (status, contentType, body) = get(port, "/metrics")
        assertEquals(status, 200)
        assert(contentType.startsWith("text/plain"), contentType)
        assert(body.linesIterator.exists(_.startsWith("dosegram_outbox_sent_total{")), body.take(500))
      finally binding.stop()
    }

  test("the listener binds to the address it is given, not to every interface"):
    supervised {
      val binding = Health.start("127.0.0.1", freePort(), ready)
      try assertEquals(binding.localSocket.getAddress.getHostAddress, "127.0.0.1")
      finally binding.stop()
    }
