package dosegram.infra.db

import dosegram.core.domain.insights.DateRange
import dosegram.infra.FilePurgeList

import java.nio.file.Files
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID
import scala.language.implicitConversions

/** Finding 8 of the verification of 2026-11-14 and BOT_UX BUX-CONS-04 on Testcontainers Postgres 18, from the
  * production code path: a meal typed with words before the `nutrition` consent is recorded with those words once the
  * consent is confirmed, so the user does not type it again. While the consent is open the words are held sealed, and
  * "Not now" forgets them.
  */
class MealConsentPgSuite extends PgSuite:

  private val words = "canary-soup-with-bread"

  private def withRig(user: String)(test: ProductChatRig => Unit): Unit =
    val purge = FilePurgeList(Files.createTempFile("purge-list", ".txt"))
    ox.supervised(test(ProductChatRig(dataSource, MutableClock(ProductChatRig.Start), purge, user)))

  /** The rows of any table that hold `needle` as text. */
  private def rowsHolding(needle: String): Int =
    withConnection: conn =>
      given Connection = conn
      sql"""SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'""".query[String]().map: table =>
        val st = conn.prepareStatement(s"SELECT count(*) FROM $table AS r WHERE r::text LIKE ?")
        try
          st.setString(1, s"%$needle%")
          val rs = st.executeQuery()
          rs.next()
          rs.getInt(1)
        finally st.close()
      .sum

  private def heldRows(account: UUID): Int =
    withConnection: conn =>
      given Connection = conn
      sql"SELECT count(*) FROM callback_slots WHERE account_id = $account".queryOne[Int]().getOrElse(0)

  private def mealTexts(rig: ProductChatRig, account: UUID): List[String] =
    rig.uow
      .transaction(_.tracking.meals.listLive(account, DateRange.single(LocalDate.parse("2026-09-21"))))
      .flatMap(_.text)
      .map(_.value)

  test("a meal typed before the consent is recorded with its words after the confirming tap"):
    withRig("meal-1"): rig =>
      val account = rig.onboard()
      val asked = rig.line(s"lunch: $words")
      assert(asked.exists(_.contains("I agree that")), asked.toString)
      assertEquals(rowsHolding(words), 0, "the held words are sealed")
      rig.tap("I agree that", "I agree")
      val confirmed = rig.tap("Please confirm", "Confirm")
      assert(confirmed.exists(_.contains("Lunch saved at")), confirmed.toString)
      assertEquals(mealTexts(rig, account), List(words))
      assertEquals(heldRows(account), 0, "nothing stays held")

  test("Not now forgets the held words, and a later consent records no meal from them"):
    withRig("meal-2"): rig =>
      val account = rig.onboard()
      rig.line(s"lunch: $words")
      assertEquals(heldRows(account), 1)
      rig.tap("I agree that", "Not now")
      assertEquals(heldRows(account), 0)
      rig.line("/food")
      rig.consent()
      assertEquals(mealTexts(rig, account), Nil)
