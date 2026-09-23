package dosecord.infra.db

import dosecord.contracts.HhMm
import dosecord.contracts.LoopDispatch
import dosecord.contracts.Weekday
import dosecord.core.domain.ReminderPolicy
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.ports.Wake
import dosecord.core.scheduling.Materialiser
import dosecord.core.scheduling.ReminderLoop

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** DESIGN.md section 7.5's restated loop-equivalence property, on Testcontainers Postgres 18 (ROADMAP M1.8
  * acceptance): for every row, the final status under continuous ticking equals the status under one jump, except
  * that `missed` with no delivery evidence becomes `unknown`; the count of `reminder_sent` actions under the jump is
  * <= the count under ticking; outbox contents are compared only by the set of occurrence ids covered. The ticking
  * scenario optionally marks the occurrence's outbox rows sent after every tick, simulating a healthy dispatcher.
  */
class LoopEquivalencePgSuite extends PgSuite, munit.ScalaCheckSuite:

  private val monday = Instant.parse("2026-09-21T08:00:00Z") // a Monday
  private val due = monday.plus(Duration.ofHours(1))         // today's 09:00 slot
  private val utc = ZoneId.of("UTC")

  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(25)

  private lazy val fixtures = Fixtures(dataSource)

  private def dailyAt(time: String): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe(time)))))

  private def truncateAll(): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE dose_actions, dose_occurrences, schedule_revisions, medication_schedules, medications, " +
            "domain_events, outbox_messages, rendered_messages, delivery_channels, platform_identities, " +
            "worker_heartbeat, users CASCADE"
        )
      finally st.close()
    }

  private final case class Outcome(status: String, reminderSent: Int, covered: Boolean)

  private def runScenario(policy: ReminderPolicy, jumpMinutes: Int, deliver: Boolean, ticking: Boolean): Outcome =
    truncateAll()
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc, policy = policy)
    fixtures.deliveryChannel(accountId, "fake", "dm:owner", monday)
    val occurrenceId = fixtures.uow
      .transaction(_.occurrences.listBySchedule(created.scheduleId))
      .find(_.scheduledFor == due)
      .map(_.id)
      .get
    val l = ReminderLoop(
      PgUnitOfWork(dataSource, clock),
      Materialiser(PgUnitOfWork(dataSource, clock), clock),
      Wake.polling,
      clock,
      instance = "equivalence-test"
    )
    val end = due.plus(Duration.ofMinutes(jumpMinutes.toLong))
    if ticking then
      var t = due
      while !t.isAfter(end) do
        l.tick(t)
        if deliver then markDelivered(occurrenceId, t)
        t = t.plusSeconds(300)
    else l.tick(end)
    readOutcome(occurrenceId)

  private def markDelivered(occurrenceId: UUID, at: Instant): Unit =
    withConnection { conn =>
      given Connection = conn
      sql"""UPDATE outbox_messages
            SET status = 'sent', sent_at = $at
            WHERE occurrence_id = $occurrenceId AND sent_at IS NULL""".execute()
      ()
    }

  private def readOutcome(occurrenceId: UUID): Outcome =
    withConnection { conn =>
      given Connection = conn
      val status =
        sql"SELECT status::text FROM dose_occurrences WHERE id = $occurrenceId".queryOne[String]().getOrElse("missing")
      val sent =
        sql"""SELECT count(*) FROM dose_actions
              WHERE occurrence_id = $occurrenceId AND action = 'reminder_sent'"""
          .queryOne[Int]()
          .getOrElse(0)
      val direct =
        sql"SELECT occurrence_id FROM outbox_messages WHERE occurrence_id IS NOT NULL".query[UUID]().toSet
      val folded = sql"SELECT payload FROM outbox_messages WHERE kind = 'digest'".query[String]().flatMap { payload =>
        LoopDispatch.fromJson(payload) match
          case d: LoopDispatch.Digest => d.items.map(_.occurrenceId)
          case _                      => Nil
      }
      // DESIGN.md section 7.5 compares outbox contents only by the set of occurrence ids covered; each scenario
      // seeds one occurrence, so the set comparison reduces to "both covered it, or neither did".
      Outcome(status, sent, (direct ++ folded).contains(occurrenceId))
    }

  private val genPolicy: Gen[ReminderPolicy] =
    for
      initialOffset <- Gen.oneOf(0, 5)
      repeatEvery   <- Gen.oneOf(5, 10, 15)
      maxReminders  <- Gen.oneOf(1, 2, 3)
      missAfter     <- Gen.oneOf(30, 60, 120)
    yield ReminderPolicy.Default.copy(
      initialOffsetMinutes = initialOffset,
      repeatEveryMinutes = repeatEvery,
      maxReminders = maxReminders,
      missAfterMinutes = missAfter
    )

  property("loop equivalence on Postgres: one jump equals continuous ticking, modulo the unknown exception"):
    val gen = for
      policy  <- genPolicy
      jump    <- Gen.choose(30, 180)
      deliver <- Gen.oneOf(true, false)
    yield (policy, jump, deliver)
    forAll(gen) { (policy, jump, deliver) =>
      val continuous = runScenario(policy, jump, deliver, ticking = true)
      val jumped = runScenario(policy, jump, deliver, ticking = false)
      val context = s"policy=$policy jump=$jump deliver=$deliver"
      assert(
        continuous.status == jumped.status ||
          (continuous.status == "missed" && jumped.status == "unknown"),
        s"status diverged: ticking=${continuous.status} jump=${jumped.status} ($context)"
      )
      assert(
        jumped.reminderSent <= continuous.reminderSent,
        s"reminder_sent count: jump=${jumped.reminderSent} > ticking=${continuous.reminderSent} ($context)"
      )
      assertEquals(jumped.covered, continuous.covered, s"coverage diverged ($context)")
    }
end LoopEquivalencePgSuite
