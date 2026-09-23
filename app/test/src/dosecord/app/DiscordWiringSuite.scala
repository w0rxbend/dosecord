package dosecord.app

/** ROADMAP M2.1 acceptance: the ack-latency histogram exists. The meter is registered when the adapter wiring is
  * touched; its samples are recorded by the Discord adapter's ack path from the interaction snowflake.
  */
class DiscordWiringSuite extends munit.FunSuite:

  test("dosecord_interaction_ack_latency_seconds{vendor=\"discord\"} is registered"):
    val timer = Adapters.ackLatencyTimer
    assertEquals(timer.getId.getName, "dosecord_interaction_ack_latency_seconds")
    assertEquals(timer.getId.getTag("vendor"), "discord")
