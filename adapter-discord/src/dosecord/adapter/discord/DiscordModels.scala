package dosecord.adapter.discord

import java.time.Instant

/** The Discord-shaped wire model of the adapter (ROADMAP M2.1): vendor-specific but gateway-free, so the whole inbound
  * mapping, the ack policy and the registration plan are testable against a fake transport (suite A) with no JDA
  * gateway. `JdaTransport` is the only file that translates these into `net.dv8tion` types.
  */

/** Where an interaction happened. `BotDm` is the only context the app registers (ROADMAP M2.1, R45/R47); the other two
  * are defence-in-depth refusals with the ephemeral catalogue redirect.
  */
enum DiscordContext:
  case BotDm, PrivateChannel, Guild

final case class DiscordUser(id: String, name: String)

/** One typed slash option, already flattened to text (Discord options arrive typed; the core's `CommandInvoked.args`
  * are strings).
  */
final case class DiscordOption(name: String, value: String)

enum DiscordIntegrationType:
  case UserInstall, GuildInstall

enum DiscordOptionKind:
  case String, Integer

final case class DiscordCommandOption(
    name: String,
    description: String,
    required: Boolean,
    kind: DiscordOptionKind,
    choices: List[String],
    autocomplete: Boolean
)

/** The registration plan for one global slash command (ROADMAP M2.1: `USER_INSTALL` only — the M0.8 GO verdict and the
  * ADR-001 addendum — and the bot-DM context only, so a guild or group-DM invocation is impossible by registration).
  */
final case class DiscordCommand(
    name: String,
    description: String,
    options: List[DiscordCommandOption],
    integrationTypes: Set[DiscordIntegrationType],
    contexts: Set[DiscordContext]
)

enum DiscordButtonStyle:
  case Primary, Secondary, Danger, Success

final case class DiscordButton(label: String, customId: String, style: DiscordButtonStyle)

final case class DiscordSelectOption(label: String, value: String)

enum DiscordComponent:
  case ButtonRow(buttons: List[DiscordButton])
  case Select(customId: String, options: List[DiscordSelectOption], minSelect: Int, maxSelect: Int)

/** A lowered outbound message: text plus component rows. `silent` maps to `SUPPRESS_NOTIFICATIONS`; ephemeral is only
  * meaningful on an interaction response (the [[DiscordInteraction]] ops carry it), never on a channel send.
  */
final case class DiscordMessage(
    text: String,
    components: List[DiscordComponent] = Nil,
    silent: Boolean = false,
    suppressPreview: Boolean = false
)

final case class DiscordModalField(
    key: String,
    label: String,
    placeholder: Option[String],
    required: Boolean,
    value: Option[String]
)

final case class DiscordModal(customId: String, title: String, fields: List[DiscordModalField])

/** A vendor API failure at the transport boundary. `JdaTransport` raises it from JDA's `ErrorResponseException` (code
  * and Retry-After extracted there); the fake transport raises it to exercise the adapter's error mapping
  * ([[DiscordErrorMapper]]).
  */
final case class DiscordApiException(code: Int, retryAfterSeconds: Option[Long], meaning: String)
    extends RuntimeException(s"discord api error $code: $meaning")

/** Discord snowflakes carry the creation timestamp in their high 42 bits (DESIGN.md section 4.6 step 1: ack budgets are
  * measured from the snowflake, ADR-013).
  */
object Snowflake:
  private val DiscordEpochMs = 1420070400000L

  def createdAt(id: String): Instant =
    Instant.ofEpochMilli((id.toLong >>> 22) + DiscordEpochMs)

  /** Mints a snowflake for a timestamp + sequence — the fake wire's ids (suite A). */
  def mint(at: Instant, seq: Int): String =
    (((at.toEpochMilli - DiscordEpochMs) << 22) | (seq & 0x3fffff)).toString
