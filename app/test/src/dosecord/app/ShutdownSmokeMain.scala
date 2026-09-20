package dosecord.app

import ox.fork

/** SIGTERM smoke process for [[ShutdownSuite]]: starts one fake 4 s in-flight unit, prints READY, and drains on
  * SIGTERM through the real shutdown machinery. Exit 0 means the in-flight fork completed within the 20 s budget.
  */
@main def shutdownSmokeMain(): Unit =
  val inFlight = InFlightRegistry()
  Shutdown.serve(
    body = {
      inFlight.begin()
      fork {
        Thread.sleep(4000)
        inFlight.end()
        println("in-flight work completed")
      }
      println("READY")
      Drain(Nil, Nil, inFlight, Shutdown.DrainBudget, println(_))
    },
    log = println(_)
  )
