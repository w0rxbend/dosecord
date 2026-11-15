package dosegram.infra.perf

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.ReminderPolicy
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.ports.Clock
import dosegram.core.scheduling.CreateSchedule
import dosegram.core.scheduling.ScheduleLifecycle
import dosegram.infra.crypto.DataKeys
import dosegram.infra.crypto.KekRing
import dosegram.infra.db.Database
import dosegram.infra.db.Fixtures
import dosegram.infra.db.PgUnitOfWork

import java.time.ZoneId
import java.time.ZoneOffset

/** docs/PERFORMANCE.md, busy RSS: seeds a throwaway database (the local compose stack, never a database that holds
  * real data) with `accounts` accounts, each with a daily dose at `time` UTC and a console delivery channel, sealed
  * with the stack's own key-encryption key, so the running container sends that many reminders at once. Run:
  * `./mill infra.test.runMain dosegram.infra.perf.loadSeed <jdbc url> <user> <password> <KEK> <accounts> <HH:MM>`.
  */
@main def loadSeed(url: String, user: String, password: String, kek: String, accounts: Int, time: String): Unit =
  val ring = KekRing.parse(kek).fold(problem => sys.error(problem), identity)
  val ds = Database.pooled(url, user, password)
  try
    val clock = Clock.system
    val lifecycle = ScheduleLifecycle(PgUnitOfWork(ds, clock, DataKeys(ring, clock)), clock)
    val fixtures = Fixtures(ds)
    val rule = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe(time)))))
    (1 to accounts).foreach: i =>
      val account = fixtures.medicationAccount("UTC")
      val created = lifecycle.create(
        CreateSchedule(account, s"Load medication $i", rule, ZoneId.of("UTC"), None, None, None,
          ReminderPolicy.Default, true)
      )
      require(created.isRight, created.toString)
      fixtures.deliveryChannel(account, "console", s"load-$i", clock.now().atOffset(ZoneOffset.UTC).toInstant)
    println(s"seeded $accounts accounts due at $time UTC")
  finally ds.close()
