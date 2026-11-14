package dosegram.app

import com.dimafeng.testcontainers.PostgreSQLContainer
import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.Evaluator
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.infra.Adapter
import dosegram.infra.Environment
import dosegram.infra.LogFormat
import dosegram.infra.LogLevel
import dosegram.infra.MiniAppSettings
import dosegram.infra.Role
import dosegram.infra.Settings
import dosegram.infra.db.Database
import dosegram.infra.db.Fixtures
import dosegram.infra.db.Migrator
import dosegram.infra.db.sql
import org.testcontainers.utility.DockerImageName

import java.sql.Connection
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import scala.language.implicitConversions

/** The backend of the browser suite (ROADMAP G3.12, `pnpm --dir miniapp run e2e`): the assembled application of
  * [[Launch.start]] on a throwaway PostgreSQL, with the console adapter and the Mini App on one listener. Launch data
  * is verified with Ed25519 against the suite's own public key, which only a non-production environment accepts
  * (ARCHITECTURE API-02). One account exists, linked to the suite's Telegram user, with the consents of medication,
  * water, mood and sleep and one daily medication. The process runs until it is killed; Testcontainers removes the
  * database then.
  *
  * Arguments: the public port, the built Mini App directory, the public key (64 hex digits), the bot id and the
  * Telegram user id.
  */
@main def e2eBackend(port: Int, staticDir: String, publicKey: String, botId: Long, telegramUserId: Long): Unit =
  val postgres = PostgreSQLContainer.Def(dockerImageName = DockerImageName.parse("postgres:18-alpine")).start()
  val dataSource = Database.pooled(postgres.jdbcUrl, postgres.username, postgres.password)
  Migrator(dataSource).migrate()
  E2eSeed.seed(dataSource, telegramUserId)
  val separator = if postgres.jdbcUrl.contains("?") then "&" else "?"
  val settings = Settings(
    enabledAdapters = List(Adapter.Console),
    databaseUrl = s"${postgres.jdbcUrl}${separator}user=${postgres.username}&password=${postgres.password}",
    callbackKeys = Settings.parseCallbackKeys(E2eSeed.CallbackKeys).fold(sys.error, identity),
    role = Role.All,
    instanceId = "e2e",
    logLevel = LogLevel.Info,
    logFormat = LogFormat.Plain,
    consoleUserId = Some(LaunchRig.ConsoleUser),
    publicBaseUrl = None,
    healthPort = freePort(),
    environment = Environment.Test,
    httpPort = port,
    miniApp = Some(MiniAppSettings(Some(staticDir), Some(botId), Some(publicKey), hmacFallback = false))
  )
  val rig = LaunchRig(Clock.system)
  val exitCode = Shutdown.serve(
    body = {
      val parts = Launch.Parts(Clock.system, Map("console" -> rig.console), Workers.Cadence.Production)
      val drain = Launch.start(settings, dataSource, parts, line => println(s"e2e backend: $line"))
      println(s"e2e backend: ready on 127.0.0.1:$port")
      drain
    },
    log = line => println(s"e2e backend: $line")
  )
  sys.exit(exitCode)

object E2eSeed:
  // A development key, as in .env.example; it signs nothing outside this process.
  val CallbackKeys = "0:MDEyMzQ1Njc4OWFiY2RlZg"

  /** The name the suite looks for on Today and in the registry. */
  val MedicationName = "Vitamin D"

  def seed(dataSource: DataSource, telegramUserId: Long): Unit =
    val fixtures = Fixtures(dataSource)
    val accountId = fixtures.account("UTC")
    fixtures.grant(
      accountId,
      ConsentPurpose.Medication,
      ConsentPurpose.Hydration,
      ConsentPurpose.Mood,
      ConsentPurpose.Sleep,
      ConsentPurpose.Nutrition
    )
    val conn = dataSource.getConnection
    try
      given Connection = conn
      // As onboarding leaves it: the identity with its private chat, and the chat as the primary channel, where a
      // confirmation the app asks for is sent (MINI_APP 8.8).
      val identityId = UUID.randomUUID()
      sql"""INSERT INTO platform_identities (id, user_id, vendor, vendor_user_id, dm_channel_id, linked_at)
            VALUES ($identityId, $accountId, 'telegram', ${telegramUserId.toString}, ${telegramUserId.toString},
                    ${Clock.system.now()})""".execute()
      sql"""INSERT INTO delivery_channels (id, account_id, platform_identity_id, role, priority, state, updated_at)
            VALUES (${UUID.randomUUID()}, $accountId, $identityId, 'primary', 0, 'healthy', ${Clock.system.now()})"""
        .execute()
    finally conn.close()
    val times = List(HhMm.unsafe("09:00"), HhMm.unsafe("21:00"))
    val created = fixtures.schedule(
      Clock.system,
      accountId,
      MedicationName,
      Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times))),
      ZoneOffset.UTC,
      doseAmount = Some(BigDecimal(1000)),
      doseUnit = Some("IU")
    )
    // A new schedule materialises from now on; today's earlier slots are added so that Today is never empty. The
    // first one is answered, as the chat would leave it.
    val now = Clock.system.now()
    val today = now.atZone(ZoneOffset.UTC).toLocalDate
    times.zipWithIndex.foreach: (time, index) =>
      val at = today.atTime(time.hour, time.minute).toInstant(ZoneOffset.UTC)
      if at.isBefore(now) then
        val status = if index == 0 then OccurrenceStatus.Taken else OccurrenceStatus.Pending
        fixtures.occurrenceAt(created.scheduleId, at, Evaluator.fixedSlotKey(time), status)
        ()
