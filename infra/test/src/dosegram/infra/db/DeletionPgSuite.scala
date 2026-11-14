package dosegram.infra.db

import dosegram.contracts.*
import dosegram.core.application.Application
import dosegram.core.application.exports.ExportRequests
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.copy.PrivacyCopy
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.DataDomain
import dosegram.core.ports.IdSource
import dosegram.core.scheduling.CreateSchedule
import dosegram.core.scheduling.ScheduleLifecycle
import dosegram.infra.FilePurgeList
import dosegram.infra.crypto.DataKeys

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.time.ZoneId
import java.util.UUID
import ox.discard
import scala.language.implicitConversions

/** ROADMAP G2.17 and G5.7 on Testcontainers Postgres 18, from the production code path: a user onboarded through the
  * chat records in every domain and asks for an export; `/privacy` then deletes one domain, or the account after two
  * confirmations. After the account deletion no row of any table holds the account id, the bot has forgotten the chat,
  * the purge list holds the account and the audit log holds the deletion under a tombstone.
  */
class DeletionPgSuite extends PgSuite:

  private val purgeFile: Path = Files.createTempFile("purge-list", ".txt")

  private def withRig(user: String)(test: ProductChatRig => Unit): Unit =
    ox.supervised(test(ProductChatRig(dataSource, MutableClock(ProductChatRig.Start), FilePurgeList(purgeFile), user)))

  private def tables: List[String] =
    withConnection: conn =>
      given Connection = conn
      sql"""SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name""".query[String]()

  /** The tables in which some row, read as text, contains `needle`. */
  private def tablesHolding(needle: String): List[(String, Int)] =
    withConnection: conn =>
      tables.flatMap: table =>
        val st = conn.prepareStatement(s"SELECT count(*) FROM $table AS r WHERE r::text LIKE ?")
        try
          st.setString(1, s"%$needle%")
          val rs = st.executeQuery()
          rs.next()
          Option.when(rs.getInt(1) > 0)(table -> rs.getInt(1))
        finally st.close()

  private def count(query: String): Int =
    withConnection: conn =>
      val rs = conn.createStatement().executeQuery(query)
      rs.next()
      rs.getInt(1)

  private def rows(table: String, account: UUID): Int =
    count(s"SELECT count(*) FROM $table WHERE account_id = '$account'")

  /** Records in every domain, an export, an image the bot sent and a Mini App session, all as the product writes them.
    */
  private def recordEverything(rig: ProductChatRig, account: UUID): String =
    rig.line("250 ml")
    rig.line("mood 4")
    rig.consent()
    rig.line("slept 23:30 7:10")
    rig.consent()
    // The consents of sleep and food resume on their cards, so the entry is typed again.
    rig.line("slept 23:30 7:10")
    rig.line("lunch: canary soup")
    rig.consent()
    rig.line("lunch: canary soup")
    rig.line("/meds")
    rig.consent()
    val everyDay = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
    ScheduleLifecycle(rig.uow, rig.clock).create(
      CreateSchedule(account, "CanaryMed", everyDay, ZoneId.of("Europe/Kyiv"))
    )
    rig.line("/export")
    rig.tap("Export all your records", "Export my data")
    val image = OutboundMessage(
      body = Nil,
      attachment = Some(AttachmentRef(MediaKind.Photo, s"card.sent-$account", "image/png", "A card", None)),
      dedupeKey = "image",
      correlationId = "image"
    )
    val other = s"card.other-${UUID.randomUUID()}"
    rig.uow.transaction(
      ExportRequests.enqueue(_, IdSource.uuidV7(SecureRandom()), account, image, "img", rig.clock.now())
    )
    // The drawn image, as the renderer stores it: sealed with the render service key (ROADMAP G2.4b).
    val renders = PgRenderAssets(dataSource, DataKeys.development)
    withConnection: conn =>
      given Connection = conn
      val now = rig.clock.now()
      List(s"card.sent-$account", other).foreach: key =>
        renders.put(key, Array[Byte](1), now)
        sql"""INSERT INTO telegram_file_cache (asset_key, bot_id, media_kind, file_id, created_at, last_used_at)
              VALUES ($key, 1, 'photo', ${s"file-$key"}, $now, $now)""".execute()
      sql"""INSERT INTO miniapp_sessions (token_hash, account_id, initdata_hash, created_at, last_used_at)
            VALUES (${s"token-$account"}, $account, 'initdata', $now, $now)""".execute()
    other

  test("after the account deletion no row of any table holds the account, and the bot has forgotten the chat"):
    withRig("del-1"): rig =>
      val account = rig.onboard()
      val other = recordEverything(rig, account)
      List("hydration_entries", "mood_checkins", "sleep_episodes", "meals", "dose_occurrences", "export_jobs")
        .foreach(table => assert(rows(table, account) >= 1, s"$table has a row before the deletion"))
      assert(tablesHolding(account.toString).size > 20, "the account is spread over the schema before the deletion")
      rig.line("/privacy")
      val oldButton = rig.wireOf("Your consents", PrivacyCopy.DeleteData)
      rig.tap("Your consents", PrivacyCopy.DeleteData)
      rig.tap("What do you want to delete?", PrivacyCopy.DeleteMyAccount)
      val first = rig.tap("Delete your account?", PrivacyCopy.Continue)
      assert(first.exists(_.contains("Last step")), first.toString)
      assert(rig.accountId.contains(account), "nothing is deleted before the second confirmation")
      val goodbye = rig.tap("Last step", PrivacyCopy.DeleteMyAccount)
      assert(goodbye.exists(_.contains("Goodbye")), goodbye.toString)

      assertEquals(tablesHolding(account.toString), Nil, "no row of any table names the account")
      assertEquals(count("SELECT count(*) FROM platform_identities WHERE vendor_user_id = 'del-1'"), 0)
      // The image the bot sent to the account goes; an image of nobody here stays.
      assertEquals(count(s"SELECT count(*) FROM render_assets WHERE asset_key = 'card.sent-$account'"), 0)
      assertEquals(count(s"SELECT count(*) FROM telegram_file_cache WHERE asset_key = 'card.sent-$account'"), 0)
      assertEquals(count(s"SELECT count(*) FROM render_assets WHERE asset_key = '$other'"), 1)
      assertEquals(count(s"SELECT count(*) FROM telegram_file_cache WHERE asset_key = '$other'"), 1)
      assert(FilePurgeList.read(purgeFile).contains(account), "the purge list holds the account")

      val tombstone = withConnection: conn =>
        given Connection = conn
        sql"SELECT tombstone_id FROM deletion_receipts WHERE scope = 'account'".query[UUID]()
      assertEquals(tombstone.size, 1)
      val events = withConnection: conn =>
        given Connection = conn
        sql"SELECT event_type FROM audit_log WHERE subject_ref = ${tombstone.head} ORDER BY occurred_at, id"
          .query[String]()
      assert(events.contains("account.delete.completed"), events.toString)
      assert(events.contains("consent.granted") && events.contains("data.export.requested"), events.toString)
      val counts = withConnection: conn =>
        given Connection = conn
        sql"SELECT row_counts::text FROM deletion_receipts WHERE tombstone_id = ${tombstone.head}".query[String]()
      assertEquals(ujson.read(counts.head)("hydration_entries").num, 1.0, counts.head)

      // A later tap on an old button is answered with the neutral notice and creates nothing.
      val users = count("SELECT count(*) FROM users")
      rig.tapWire(oldButton, None)
      assertEquals(count("SELECT count(*) FROM users"), users)
      assertEquals(rig.accountId, None)
      val again = rig.line("/start")
      assert(again.exists(_.contains("Welcome to Dosegram")), "a later /start begins onboarding again")

  test("a domain deletion removes that domain's rows and rollups and no row of another domain"):
    withRig("del-2"): rig =>
      val account = rig.onboard()
      rig.line("250 ml")
      rig.line("mood 4")
      rig.consent()
      withConnection: conn =>
        given Connection = conn
        sql"""INSERT INTO daily_rollups (account_id, local_date, domain, metrics, rollup_version, computed_at)
              VALUES ($account, '2026-09-21', 'hydration', '{}', 1, ${rig.clock.now()}),
                     ($account, '2026-09-21', 'mood', '{}', 1, ${rig.clock.now()})""".execute()
      rig.line("/privacy")
      rig.tap("Your consents", PrivacyCopy.DeleteData)
      rig.tap("What do you want to delete?", PrivacyCopy.domainLabel(DataDomain.Hydration))
      val done = rig.tap("Delete your water records?", PrivacyCopy.DeleteThisData)
      assert(done.exists(_.contains(PrivacyCopy.domainDeleted(DataDomain.Hydration))), done.toString)
      assertEquals(rows("hydration_entries", account), 0)
      assertEquals(
        count(s"SELECT count(*) FROM daily_rollups WHERE account_id = '$account' AND domain = 'hydration'"),
        0
      )
      assertEquals(rows("mood_checkins", account), 1)
      assertEquals(count(s"SELECT count(*) FROM daily_rollups WHERE account_id = '$account' AND domain = 'mood'"), 1)
      assert(!rig.uow.transaction(_.consents.isGranted(account, ConsentPurpose.Hydration)), "the consent is withdrawn")
      assert(rig.uow.transaction(_.consents.isGranted(account, ConsentPurpose.Mood)))
      assertEquals(count("SELECT count(*) FROM deletion_receipts WHERE scope = 'hydration'"), 1)

  test("a withdrawal offers Keep the data and Delete this data; keeping keeps every row"):
    withRig("del-3"): rig =>
      val account = rig.onboard()
      rig.line("250 ml")
      rig.line("mood 4")
      rig.consent()
      rig.line("/privacy")
      rig.tap("Your consents", "Withdraw: mood records")
      val kept = rig.tap("Consent withdrawn", PrivacyCopy.KeepTheData)
      assert(kept.exists(_.contains(PrivacyCopy.kept)), kept.toString)
      assertEquals(rows("mood_checkins", account), 1)
      rig.line("/privacy")
      rig.tap("Your consents", "Withdraw: water records")
      rig.tap("Consent withdrawn", PrivacyCopy.DeleteThisData)
      rig.tap("Delete your water records?", PrivacyCopy.DeleteThisData)
      assertEquals(rows("hydration_entries", account), 0)
      assertEquals(rows("mood_checkins", account), 1, "the kept domain still has its rows")

  test("a confirmation older than ten minutes deletes nothing"):
    withRig("del-4"): rig =>
      val account = rig.onboard()
      rig.line("/privacy")
      rig.tap("Your consents", PrivacyCopy.DeleteData)
      rig.tap("What do you want to delete?", PrivacyCopy.DeleteMyAccount)
      rig.tap("Delete your account?", PrivacyCopy.Continue)
      rig.clock.advance(Duration.ofMinutes(11))
      val answer = rig.tap("Last step", PrivacyCopy.DeleteMyAccount)
      assert(answer.exists(_.contains(PrivacyCopy.expired)), answer.toString)
      assert(rig.accountId.contains(account))

  test("a deletion the Mini App asks for waits for the tap in the chat, which is the step-up"):
    withRig("del-5"): rig =>
      val account = rig.onboard()
      rig.line("mood 4")
      rig.consent()
      val requests = Application.deletionRequests(rig.clock, rig.codec)
      assert(rig.uow.transaction(requests.domain(_, account, DataDomain.Mood)))
      assertEquals(rows("mood_checkins", account), 1, "the request deletes nothing")
      rig.tapQueued("The app asked to delete data", PrivacyCopy.DeleteThisData)
      assertEquals(rows("mood_checkins", account), 0)
      val events = withConnection: conn =>
        given Connection = conn
        sql"SELECT event_type FROM audit_log WHERE subject_ref = $account ORDER BY occurred_at, id".query[String]()
      assert(
        events.containsSlice(List("stepup.requested")) && events.contains("stepup.confirmed") &&
          events.contains("domain.delete.completed"),
        events.toString
      )

  test("every table of the schema has an erasure decision, and every table with an account column is erased"):
    val listed = ErasureCoverage.tables.map(_._1).toSet
    assertEquals(tables.toSet -- listed, Set.empty[String], "tables without an erasure decision")
    assertEquals(listed -- tables.toSet, Set.empty[String], "decisions for tables that do not exist")
    val owned = withConnection: conn =>
      given Connection = conn
      sql"""SELECT DISTINCT table_name FROM information_schema.columns
            WHERE table_schema = 'public' AND column_name IN ('account_id', 'user_id')""".query[String]()
    val notErased = ErasureCoverage.tables.collect { case (t, _: Erasure.NotOwned) => t }.toSet
    assertEquals(owned.toSet.intersect(notErased), Set.empty[String], "an account table marked as not owned")

  test("deleting all five domains leaves only the rows that belong to no domain"):
    withRig("del-6"): rig =>
      val account = rig.onboard()
      recordEverything(rig, account).discard
      DataDomain.values.foreach: domain =>
        rig.line("/privacy")
        rig.tap("Your consents", PrivacyCopy.DeleteData)
        rig.tap("What do you want to delete?", PrivacyCopy.domainLabel(domain))
        rig.tap("Delete your", PrivacyCopy.DeleteThisData)
      val domainOnly = ErasureCoverage.tables.collect:
        case (t, Erasure.Domain(all, by)) if by.values.forall(_ == all) => t
      val left =
        domainOnly.filter(table => count(s"SELECT count(*) FROM $table AS r WHERE r::text LIKE '%$account%'") > 0)
      assertEquals(left, Nil, "a table of domain records still holds rows of the account")
      assertEquals(count("SELECT count(*) FROM deletion_receipts WHERE scope <> 'account'") >= 5, true)
      assert(rig.accountId.contains(account), "the account itself stays")
