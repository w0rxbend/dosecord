package dosecord.app

import ox.supervised

import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

class HealthSuite extends munit.FunSuite:

  private def freePort(): Int =
    val socket = ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  test("/healthz answers 200 ok"):
    supervised {
      val port    = freePort()
      val binding = Health.start(port)
      try
        val conn = URI(s"http://127.0.0.1:$port/healthz").toURL.openConnection().asInstanceOf[HttpURLConnection]
        try
          assertEquals(conn.getResponseCode, 200)
          assertEquals(String(conn.getInputStream.readAllBytes()), "ok")
        finally conn.disconnect()
      finally binding.stop()
    }
