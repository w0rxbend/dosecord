package dosegram.app

import dosegram.infra.perf.StatementLog
import dosegram.infra.perf.Timings

import java.time.Duration

/** docs/PERFORMANCE.md, the chat round trip on the fake wire: the production process of [[ProductionPathSuite]]
  * (`/start` on the fake Telegram wire, the inbox, the mediator, the outbox), then a typed command or water amount, timed
  * from the update entering the wire to its answer's outbox row, and to the answer's `sendMessage` on the wire. Run:
  * `./mill app.test.runMain dosegram.app.chatRoundTripProbe [samples]`.
  */
abstract class ChatRoundTripRig extends ProductionPathSuite:

  private def outboxRows: Int = strings("SELECT count(*)::text FROM outbox_messages").head.toInt

  private def until(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos
    while !condition do
      if System.nanoTime() > deadline then sys.error("no answer within 30 s")
      Thread.onSpinWait()

  def run(samples: Int): Unit =
    beforeAll()
    try
      val clock = SteppedClock(start)
      launched(clock): chat =>
        onboard(chat)
        addDailyMedication(chat)
        def stored: Boolean = strings(s"SELECT 1 FROM inbound_inbox WHERE vendor_event_id = '${chat.lastUpdate}'").nonEmpty
        // Wall-clock milliseconds from the update on the wire: stored in the inbox, its answer in the outbox, sent.
        def roundTrip(text: String): (Double, Double, Double) =
          // A new update id, and the stepped clock past the adapter's one message per chat and second.
          chat.nextUpdate()
          val (rows, sent) = (outboxRows, chat.messages.size)
          val started = System.nanoTime()
          def since: Double = (System.nanoTime() - started) / 1e6
          chat.send(text)
          until(stored)
          val toInbox = since
          until(outboxRows > rows)
          val toOutbox = since
          until(chat.messages.size > sent)
          (toInbox, toOutbox, since)
        List("/help", "/today").foreach(text => (1 to 5).foreach(_ => roundTrip(text)))
        val help = Vector.fill(samples)(roundTrip("/help"))
        val today = Vector.fill(samples)(roundTrip("/today"))
        val counted = StatementLog.during(pg, dataSource)(roundTrip("/today"))
        println(f"statements of one /today round trip: ${counted.total}, ${counted.serverMillis}%.1f ms in the server")
        println(counted.top(30))
        println(Timings.Header)
        List("/help" -> help, "/today" -> today).foreach: (command, runs) =>
          println(Timings(s"$command: update to inbox row", runs.map(_._1)).row)
          println(Timings(s"$command: update to outbox row", runs.map(_._2)).row)
          println(Timings(s"$command: inbox row to outbox row", runs.map(r => r._2 - r._1)).row)
          println(Timings(s"$command: update to sendMessage", runs.map(_._3)).row)
    finally afterAll()

@main def chatRoundTripProbe(samples: Int): Unit =
  (new ChatRoundTripRig {}).run(samples)
  // The launched process leaves its HTTP and pool threads behind; the probe is done.
  sys.exit(0)
