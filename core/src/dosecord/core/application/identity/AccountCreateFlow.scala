package dosecord.core.application.identity

import dosecord.contracts.*
import dosecord.core.chat.Flow
import dosecord.core.chat.FlowContext
import dosecord.core.chat.Step
import dosecord.core.chat.StepInput
import dosecord.core.chat.StepKind
import dosecord.core.chat.StepTransition
import dosecord.core.domain.copy.IdentityCopy
import dosecord.core.domain.copy.Labels
import dosecord.core.domain.copy.WizardCopy
import dosecord.core.ports.Clock

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/** The account-create flow (ROADMAP M0.12d, DESIGN.md section 6): `ConversationStarted` / `/start` run it. It asks for
  * the timezone ONLY — a `ChoiceSet(select)` of zones grouped by current offset with "Other" leading to text entry —
  * then confirms with the live echo "It is 14:32 for you now, right? [Yes][Change]" (R10). The handle is generated and
  * the display name left NULL (both editable later, never the vendor nickname, K8). Link and Restore are absent until
  * M4.1/M5.3 — not hidden menu entries, no "available later" copy.
  *
  * Completing the flow inserts `users`, links `platform_identities` and inserts the `delivery_channels` row in the
  * mediator's transaction, and answers with `dosecord.identity.account_created.v1` on the reply.
  */
object AccountCreateFlow:

  val Id = "account.create"

  private val OtherKey = "tz.other"

  // Session-data keys.
  private val TimezoneKey = "timezone"
  private val ErrorKey = "tz_error"

  /** The curated picker zones; labels are computed per render so the offset grouping follows DST. Together with the
    * "Other" entry the picker stays inside the 25-choice `ChoiceSet` bound.
    */
  val pickerZones: List[ZoneId] = List(
    "Pacific/Honolulu",
    "America/Anchorage",
    "America/Los_Angeles",
    "America/Denver",
    "America/Chicago",
    "America/New_York",
    "America/Sao_Paulo",
    "Atlantic/Azores",
    "UTC",
    "Europe/London",
    "Europe/Berlin",
    "Europe/Paris",
    "Europe/Kyiv",
    "Europe/Moscow",
    "Asia/Dubai",
    "Asia/Kolkata",
    "Asia/Bangkok",
    "Asia/Shanghai",
    "Asia/Tokyo",
    "Australia/Sydney",
    "Pacific/Auckland"
  ).map(ZoneId.of)

  def parseZone(text: String): Option[ZoneId] =
    try Some(ZoneId.of(text.trim))
    catch case _: DateTimeException => None

  private def offsetLabel(zone: ZoneId, now: Instant): String =
    val offset = zone.getRules.getOffset(now).getTotalSeconds
    val sign = if offset < 0 then "-" else "+"
    val abs = math.abs(offset)
    f"UTC$sign${abs / 3600}%02d:${(abs % 3600) / 60}%02d"

  /** Zones grouped (sorted) by their current offset, the offset shown in the label, "Other" last. */
  def pickerOptions(now: Instant): List[(String, String)] =
    pickerZones
      .sortBy(z => (z.getRules.getOffset(now).getTotalSeconds, z.getId))
      .map(z => z.getId -> s"${offsetLabel(z, now)} — ${z.getId}") :+
      (OtherKey -> IdentityCopy.timezoneOther)

  private def paragraph(text: String): Node = Node.Paragraph(List(Inline.Text(text)))

  def flow(clock: Clock): Flow = Flow(
    id = Id,
    firstStep = "timezone",
    steps = Map(
      "timezone" -> Step(
        id = "timezone",
        kind = StepKind.Choices(_ => pickerOptions(clock.now()), ChoiceLayout.Select),
        render = data =>
          data.get(ErrorKey).filter(_.nonEmpty).map(e => paragraph(e)).toList ++
            List(paragraph(IdentityCopy.welcome), paragraph(IdentityCopy.timezonePrompt)),
        accept = {
          case (StepInput.Chosen(OtherKey), _) => Right(StepTransition.Next("timezone_custom"))
          case (StepInput.Chosen(key), _) if pickerZones.exists(_.getId == key) =>
            Right(StepTransition.Next("confirm", Map(TimezoneKey -> key, ErrorKey -> "")))
          case _ => Left(IdentityCopy.invalidZone)
        }
      ),
      "timezone_custom" -> Step(
        id = "timezone_custom",
        kind = StepKind.Text,
        render = _ => List(paragraph(IdentityCopy.timezoneCustomPrompt)),
        accept = {
          case (StepInput.TextEntered(text), _) =>
            parseZone(text) match
              case Some(zone) =>
                Right(StepTransition.Next("confirm", Map(TimezoneKey -> zone.getId, ErrorKey -> "")))
              // An invalid zone re-shows the picker (with the note), and writes no row (acceptance 3).
              case None => Right(StepTransition.Next("timezone", Map(ErrorKey -> IdentityCopy.invalidZone)))
          case _ => Left(IdentityCopy.invalidZone)
        }
      ),
      "confirm" -> Step(
        id = "confirm",
        kind = StepKind.choices(List("yes" -> Labels.Yes, "change" -> Labels.Change)),
        render = data =>
          val zone = ZoneId.of(data(TimezoneKey))
          val local = clock.now().atZone(zone)
          List(paragraph(WizardCopy.timezoneConfirm(f"${local.getHour}%02d:${local.getMinute}%02d")))
        ,
        accept = {
          case (StepInput.Chosen("yes"), _)    => Right(StepTransition.Complete)
          case (StepInput.Chosen("change"), _) => Right(StepTransition.Next("timezone", Map(ErrorKey -> "")))
          case _                               => Left(IdentityCopy.confirmHint)
        }
      )
    ),
    onComplete = (data, ctx) => complete(clock, data, ctx)
  )

  private def complete(clock: Clock, data: Map[String, String], ctx: FlowContext): Reply =
    ctx.principal.accountId match
      // Defensive: the identity gate refuses a second create before a session starts.
      case Some(_) =>
        Reply(followUps =
          List(outbound(IdentityCopy.alreadyLinked, s"account.create:linked:${ctx.event.eventId.uuid}", ctx))
        )
      case None =>
        val accountId = ctx.tx.accounts.createAccount(ctx.principal.identityId, data(TimezoneKey), clock.now())
        Reply(
          followUps =
            List(outbound(IdentityCopy.accountCreated, s"account.create:done:${ctx.event.eventId.uuid}", ctx)),
          domainEvents = List(Event.AccountCreated(accountId, ctx.principal.identityId, ctx.event.vendor))
        )

  private def outbound(text: String, dedupeKey: String, ctx: FlowContext): OutboundMessage =
    OutboundMessage(
      body = List(paragraph(text)),
      dedupeKey = dedupeKey,
      correlationId = s"${ctx.event.vendor}:${ctx.event.vendorEventId}"
    )
end AccountCreateFlow
