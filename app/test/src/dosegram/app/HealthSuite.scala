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

  // Verification of 2026-11-28, third round: a scraper holding a keep-alive connection when the process stops leaves
  // the listener's port in TIME_WAIT for a minute, and the next start failed to bind it ("Address already in use") and
  // exited, so a stop and start of a deploy could fail.
  test("a restart binds the port at once although the stopped listener closed a kept-alive connection"):
    supervised {
      val port  = freePort()
      val first = Health.start("127.0.0.1", port, ready)
      val client = java.net.Socket("127.0.0.1", port)
      try
        client.getOutputStream.write(s"GET /healthz HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".getBytes("US-ASCII"))
        val answer = new Array[Byte](12)
        assertEquals(String(answer, 0, client.getInputStream.read(answer), "US-ASCII"), "HTTP/1.1 200")
        first.stop() // the server closes the open connection first, so its side of it waits in TIME_WAIT
        val second = Health.start("127.0.0.1", port, ready)
        try assertEquals(get(port, "/healthz")._1, 200)
        finally second.stop()
      finally client.close()
    }
