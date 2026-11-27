package dosegram.infra.db

import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepKind
import dosegram.infra.FilePurgeList

import java.nio.file.Files
import java.sql.Connection
import java.time.Duration
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** Finding 2 of the verification of 2026-11-27 and BOT_UX BUX-CONS-04 on Testcontainers Postgres 18, from the
  * production code path: a night typed before the `sleep` consent is recorded once the consent is confirmed, so the
  * user does not type it again. While the consent is open the night is held sealed, "Not now" forgets it, and after
  * 30 minutes "Confirm" opens the sleep card instead.
  */
class SleepConsentPgSuite extends PgSuite:

  private val Kyiv = ZoneId.of("Europe/Kyiv")

  private def withRig(user: String)(test: ProductChatRig => Unit): Unit =
    val purge = FilePurgeList(Files.createTempFile("purge-list", ".txt"))
    ox.supervised(test(ProductChatRig(dataSource, MutableClock(ProductChatRig.Start), purge, user)))

  private def heldPayloads(account: UUID): List[String] =
    withConnection: conn =>
      given Connection = conn
      sql"SELECT payload::text FROM callback_slots WHERE account_id = $account".query[String]()

  private def latestNight(rig: ProductChatRig, account: UUID): Option[SleepEpisode] =
    rig.uow.transaction(_.tracking.sleep.latestLiveEpisode(account))

  private def clockTimes(episode: SleepEpisode): (String, String) =
    (episode.interval.onset.at.atZone(Kyiv).toLocalTime.toString, episode.interval.wake.at.atZone(Kyiv).toLocalTime.toString)

  test("a night typed before the consent is recorded after the confirming tap, as of the typed message"):
    withRig("sleep-1"): rig =>
      val account = rig.onboard()
      val asked = rig.line("slept 23:30 7:10")
      assert(asked.exists(_.contains("I agree that")), asked.toString)
      val held = heldPayloads(account)
      assertEquals(held.size, 1, "the night is held while the consent is open")
      assert(held.forall(payload => !payload.contains(SleepKind.Main.dbValue)), "the held night is sealed")
      rig.clock.advance(Duration.ofMinutes(5))
      rig.tap("I agree that", "I agree")
      val confirmed = rig.tap("Please confirm", "Confirm")
      assert(confirmed.exists(_.contains("Slept 23:30 to 07:10")), confirmed.toString)
      val night = latestNight(rig, account).getOrElse(fail("no night was recorded"))
      assertEquals(night.kind, SleepKind.Main)
      assertEquals(clockTimes(night), ("23:30", "07:10"))
      assertEquals(heldPayloads(account), Nil, "nothing stays held")

  test("Not now forgets the held night, and a later consent records no night from it"):
    withRig("sleep-2"): rig =>
      val account = rig.onboard()
      rig.line("slept 23:30 7:10")
      rig.tap("I agree that", "Not now")
      assertEquals(heldPayloads(account), Nil)
      rig.line("/sleep")
      rig.consent()
      assertEquals(latestNight(rig, account), None)

  test("after 30 minutes the confirming tap opens the sleep card and records no night"):
    withRig("sleep-3"): rig =>
      val account = rig.onboard()
      rig.line("slept 23:30 7:10")
      rig.clock.advance(Duration.ofMinutes(31))
      rig.consent()
      assertEquals(latestNight(rig, account), None)
      assertEquals(heldPayloads(account), Nil, "the expired night is removed, not kept")
