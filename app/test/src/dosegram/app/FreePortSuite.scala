package dosegram.app

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

/** Finding 7 of the verification of 2026-11-14: [[freePort]] handed out a port the kernel had just chosen for a bind to
  * port 0, which is also where every outgoing connection of the test JVM takes its local port, so another socket could
  * take it before the listener bound it. The ports it gives now come from below the kernel's ephemeral range, and no
  * two calls give the same one.
  */
class FreePortSuite extends munit.FunSuite:

  /** The lowest port the kernel assigns on its own (Linux; 32768 is its default). */
  private def ephemeralLow: Int =
    val range = Path.of("/proc/sys/net/ipv4/ip_local_port_range")
    // A file of /proc reports no size, so it is read by line: readString would stop early.
    if Files.exists(range) then Files.readAllLines(range).get(0).trim.split("\\s+").head.toInt else 32768

  test("a port from freePort is outside the kernel's ephemeral range, unique to its call, and can be bound"):
    val ports = List.fill(200)(freePort())
    assertEquals(ports.distinct.size, ports.size, "two calls gave the same port")
    assert(ports.forall(_ < ephemeralLow), s"a port in the ephemeral range: ${ports.filter(_ >= ephemeralLow)}")
    ports.take(5).foreach: port =>
      val socket = ServerSocket(port)
      socket.close()

  test("a port that is in use is skipped"):
    val taken = freePort() + 1
    val holder = scala.util.Try(ServerSocket(taken))
    try assert(List.fill(50)(freePort()).forall(port => holder.isFailure || port != taken))
    finally holder.foreach(_.close())
