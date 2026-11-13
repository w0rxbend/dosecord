package dosegram.app

import dosegram.adapter.telegram.AccessPolicy
import dosegram.adapter.telegram.BotApi
import dosegram.adapter.telegram.BotToken
import dosegram.adapter.telegram.HttpTelegramTransport
import dosegram.adapter.telegram.InMemoryFileIdCache
import dosegram.adapter.telegram.MediaStore
import dosegram.adapter.telegram.OxTimer
import dosegram.adapter.telegram.TelegramAdapter
import dosegram.adapter.telegram.TelegramConfig
import dosegram.adapter.telegram.TelegramMetrics
import dosegram.adapter.telegram.TelegramTransport
import dosegram.adapter.telegram.UpdatesMode
import dosegram.core.ports.Clock
import dosegram.infra.Environment
import dosegram.infra.Metrics
import dosegram.infra.Settings
import dosegram.infra.TelegramSettings
import dosegram.infra.TelegramUpdates
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import ox.Ox
import ox.useCloseableInScope

import java.time.Duration

/** Builds the Telegram adapter from the settings (ROADMAP G1.6). The token is read here, from the settings, and goes
  * nowhere but into the HTTP transport. A launch-path test calls [[adapter]] with the fake wire in place of the
  * transport, so the test and the executable assemble the same adapter.
  */
object TelegramAssembly:

  /** The production adapter: the HTTP transport, closed with the scope. `media` holds the bytes of attachments. */
  def production(settings: Settings, telegram: TelegramSettings, clock: Clock)(media: MediaStore)(using
      Ox
  ): TelegramAdapter =
    if telegram.updates == TelegramUpdates.Webhook then
      // The endpoint needs the durable inbox of ROADMAP G1.7; a bot that receives nothing must not start silently.
      throw IllegalArgumentException("TELEGRAM_UPDATES_MODE=webhook is not served by this build; use poll")
    val transport = useCloseableInScope(HttpTelegramTransport(BotToken(telegram.token)))
    adapter(settings, telegram, transport, clock)(media)

  def adapter(settings: Settings, telegram: TelegramSettings, transport: TelegramTransport, clock: Clock)(
      media: MediaStore
  )(using Ox): TelegramAdapter =
    TelegramAdapter(
      BotApi(transport),
      config(settings, telegram),
      TelegramAdapter.Runtime(
        clock = clock,
        timer = OxTimer(),
        // An attachment whose bytes the store does not hold is sent as its text equivalent.
        media = media,
        fileIds = InMemoryFileIdCache(),
        log = line => scribe.info(line),
        metrics = MicrometerTelegramMetrics
      )
    )

  /** The adapter configuration the settings stand for. */
  def config(settings: Settings, telegram: TelegramSettings): TelegramConfig =
    TelegramConfig(
      updates = telegram.updates match
        case TelegramUpdates.Poll    => UpdatesMode.Poll
        case TelegramUpdates.Webhook => UpdatesMode.Webhook
      ,
      access =
        if telegram.allowedUserIds.isEmpty then AccessPolicy.Open else AccessPolicy.Allowlist(telegram.allowedUserIds),
      expectedBotId = Some(telegram.botId),
      developmentBot = settings.environment == Environment.Development,
      miniAppUrl = settings.miniAppUrl
    )

/** The adapter's metrics on the shared registry: `dosegram_interaction_ack_latency_seconds` (ARCHITECTURE TG-12) and
  * `dosegram_callback_expired_total` (section 7.10).
  */
object MicrometerTelegramMetrics extends TelegramMetrics:

  private val ackLatencyTimer: Timer =
    Timer.builder("dosegram_interaction_ack_latency").publishPercentileHistogram().register(Metrics.registry)

  private val callbackExpiredCounter: Counter =
    Counter.builder("dosegram_callback_expired_total").register(Metrics.registry)

  override def ackLatency(waited: Duration): Unit = ackLatencyTimer.record(waited)

  override def callbackExpired(): Unit = callbackExpiredCounter.increment()
