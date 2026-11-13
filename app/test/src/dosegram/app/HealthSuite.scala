package dosegram.app

import ox.supervised

import java.net.HttpURLConnection
import java.net.URI

class HealthSuite extends munit.FunSuite:

  test("/healthz answers 200 ok"):
    supervised {
      val port    = freePort()
      val binding = Health.start("127.0.0.1", port)
      try
        val conn = URI(s"http://127.0.0.1:$port/healthz").toURL.openConnection().asInstanceOf[HttpURLConnection]
        try
          assertEquals(conn.getResponseCode, 200)
          assertEquals(String(conn.getInputStream.readAllBytes()), "ok")
        finally conn.disconnect()
      finally binding.stop()
    }

  test("the listener binds to the address it is given, not to every interface"):
    supervised {
      val binding = Health.start("127.0.0.1", freePort())
      try assertEquals(binding.localSocket.getAddress.getHostAddress, "127.0.0.1")
      finally binding.stop()
    }
