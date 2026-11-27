package dosegram.infra.db

import dosegram.core.domain.copy.WhatsNewCopy
import dosegram.core.ports.WhatsNewState
import dosegram.infra.FilePurgeList

import java.nio.file.Files
import java.sql.Connection
import java.util.UUID
import scala.language.implicitConversions

/** The "What's new" note (supervisor decision, 2026-11-28; V26) on Testcontainers Postgres 18, from `/start` on the
  * production composition: an account that existed before the notes began (V26 gave it 0) is told once, after its
  * next command; a new account is never told; "Don't show these" turns the note off and `/whatsnew` back on.
  */
class WhatsNewPgSuite extends PgSuite:

  private def withRig(user: String)(test: ProductChatRig => Unit): Unit =
    val purge = FilePurgeList(Files.createTempFile("purge-list", ".txt"))
    ox.supervised(test(ProductChatRig(dataSource, MutableClock(ProductChatRig.Start), purge, user)))

  /** The account as V26 leaves one that existed before it: told of no release yet. */
  private def existedBefore(account: UUID): Unit =
    withConnection: conn =>
      given Connection = conn
      sql"UPDATE users SET whats_new_seen = 0 WHERE id = $account".execute()
      ()

  private def state(rig: ProductChatRig, account: UUID): WhatsNewState =
    rig.uow.transaction(_.accounts.whatsNewOf(account))

  private def noted(answer: List[String]): Boolean = answer.exists(_.startsWith(WhatsNewCopy.title))

  test("V26 starts a new account at no release: it records the current one and is never told"):
    withRig("news-1"): rig =>
      val account = rig.onboard()
      assertEquals(state(rig, account), WhatsNewState(None, enabled = true))
      val water = rig.line("/water")
      assert(!noted(water), water.toString)
      assertEquals(state(rig, account), WhatsNewState(Some(WhatsNewCopy.Release), enabled = true))
      assert(!noted(rig.line("/stats")))

  test("an account from before the notes is told once, after the answer to its next command"):
    withRig("news-2"): rig =>
      val account = rig.onboard()
      existedBefore(account)
      val answer = rig.line("/water")
      assert(noted(answer), answer.toString)
      assert(!noted(answer.take(answer.size - 1)), s"the note comes after the answer: $answer")
      assert(answer.last.contains("/stats year"), answer.last)
      assertEquals(state(rig, account).seen, Some(WhatsNewCopy.Release))
      assert(!noted(rig.line("/water")), "once per release")

  test("Don't show these turns the note off; an account that turned it off is not told, and /whatsnew turns it on"):
    withRig("news-3"): rig =>
      val account = rig.onboard()
      existedBefore(account)
      rig.line("/water")
      val off = rig.tap(WhatsNewCopy.title, WhatsNewCopy.StopThese)
      assertEquals(off, List(WhatsNewCopy.off))
      assertEquals(state(rig, account).enabled, false)
      existedBefore(account)
      assert(!noted(rig.line("/water")), "off means no note")
      assertEquals(state(rig, account).seen, Some(WhatsNewCopy.Release))
      val card = rig.line("/whatsnew")
      assert(noted(card), card.toString)
      assertEquals(rig.tap(WhatsNewCopy.title, WhatsNewCopy.TurnOn), List(WhatsNewCopy.on))
      assertEquals(state(rig, account).enabled, true)
end WhatsNewPgSuite
