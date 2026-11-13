package dosegram.core.application.identity

import dosegram.contracts.*
import dosegram.core.chat.Flow
import dosegram.core.chat.FlowContext
import dosegram.core.chat.Step
import dosegram.core.chat.StepInput
import dosegram.core.chat.StepKind
import dosegram.core.chat.StepTransition
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.domain.input.ZoneText
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps

import java.time.Instant
import java.time.ZoneId

/** The account-create flow (ROADMAP M0.12d and the consent part of G2.8; BOT_UX section 5.3): `ConversationStarted` /
  * `/start` run it. The notice card names what is stored and offers the domains; the two consent stages follow for the
  * one domain the user chose (BUX-CONS-03), and only then the timezone — a `ChoiceSet(select)` of zones grouped by
  * current offset with "Other" leading to text entry, confirmed with the live echo "It is 14:32 for you now, right?
  * [Yes][Change]" (R10). The handle is generated and the display name left NULL (both editable later, never the vendor
  * nickname, K8). Link and Restore are absent until M4.1/M5.3 — not hidden menu entries, no "available later" copy.
  *
  * Until the flow completes, the wizard session is the only row about the person (PRODUCT_SPEC R-IDC-3): it holds the
  * step, the chosen domain and the two tap times. Completing the flow inserts `users`, links `platform_identities`,
  * inserts the `delivery_channels` row and records the consent with both tap times in the mediator's transaction, so no
  * account exists without its consent. It answers with the consent receipt, a message of its own (BUX-ONB-01), and with
  * `dosegram.identity.account_created.v1` on the reply.
  */
object AccountCreateFlow:

  val Id = "account.create"

  private val OtherKey = "tz.other"

  // Session-data keys.
  private[identity] val TimezoneKey = "timezone"
  private val ErrorKey = "tz_error"
  private val PurposeKey = "consent_purpose"
  private val AgreedAtKey = "consent_agreed_at"
  private val ConfirmedAtKey = "consent_confirmed_at"

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

  /** A typed city, zone id or UTC offset (the shared `ZoneText` grammar); an offset prefers a picker zone. */
  def parseZone(text: String, now: Instant): Option[ZoneId] = ZoneText.parse(text, now, pickerZones)

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
    firstStep = "notice",
    steps = consentSteps(clock) ++ timezonePickerSteps(clock, intro = Nil),
    onComplete = (data, ctx) => complete(data, ctx)
  )

  private def purposeOf(data: Map[String, String]): ConsentPurpose =
    data
      .get(PurposeKey)
      .flatMap(ConsentPurpose.ofId)
      .getOrElse(throw IllegalStateException("the set-up session reached a consent step without a chosen domain"))

  /** Notice, stage 1 and stage 2 (BOT_UX section 5.5). Nothing is recorded here: each tap time goes into the session,
    * and the ledger row is written when the account is.
    */
  private def consentSteps(clock: Clock): Map[String, Step] = Map(
    "notice" -> Step(
      id = "notice",
      kind = StepKind.choices(ConsentCopy.onboardingPurposes.map(p => p.id -> ConsentCopy.onboardingLabel(p))),
      render = _ => ConsentCopy.notice.map(paragraph),
      accept = {
        case (StepInput.Chosen(key), _) if ConsentCopy.onboardingPurposes.exists(_.id == key) =>
          Right(StepTransition.Next("consent_agree", Map(PurposeKey -> key)))
        case _ => Left(ConsentCopy.onboardingChoiceHint)
      }
    ),
    "consent_agree" -> Step(
      id = "consent_agree",
      kind = StepKind.choices(List("agree" -> ConsentCopy.Agree)),
      render = data => List(paragraph(ConsentCopy.statement(purposeOf(data)))),
      accept = {
        case (StepInput.Chosen("agree"), _) =>
          Right(StepTransition.Next("consent_confirm", Map(AgreedAtKey -> clock.now().toString)))
        case _ => Left(ConsentCopy.agreeHint)
      }
    ),
    "consent_confirm" -> Step(
      id = "consent_confirm",
      kind = StepKind.choices(List("confirm" -> ConsentCopy.Confirm)),
      render = data => List(paragraph(ConsentCopy.confirmation(purposeOf(data)))),
      accept = {
        case (StepInput.Chosen("confirm"), _) =>
          Right(StepTransition.Next("timezone", Map(ConfirmedAtKey -> clock.now().toString, ErrorKey -> "")))
        case _ => Left(ConsentCopy.confirmHint)
      }
    )
  )

  /** The timezone picker + custom entry + live-echo confirm steps, shared with Account -> Timezone (M1.9): both flows
    * capture the zone the same way and differ only in what completion does with it. `intro` is the first body line (the
    * create flow's welcome; the timezone change passes its own or none).
    */
  def timezonePickerSteps(clock: Clock, intro: List[Node]): Map[String, Step] = Map(
    "timezone" -> Step(
      id = "timezone",
      kind = StepKind.Choices(_ => pickerOptions(clock.now())),
      render = data =>
        data.get(ErrorKey).filter(_.nonEmpty).map(e => paragraph(e)).toList ++
          intro ++ List(paragraph(IdentityCopy.timezonePrompt)),
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
          parseZone(text, clock.now()) match
            case Some(zone) =>
              Right(StepTransition.Next("confirm", Map(TimezoneKey -> zone.getId, ErrorKey -> "")))
            // An invalid zone re-shows the picker with a note naming what was typed, and writes no row
            // (acceptance 3): the buttons of common choices come back after the first failure.
            case None => Right(StepTransition.Next("timezone", Map(ErrorKey -> IdentityCopy.zoneNotFound(text))))
        case _ => Left(IdentityCopy.invalidZone)
      }
    ),
    "confirm" -> Step(
      id = "confirm",
      kind = StepKind.choices(List("yes" -> Labels.Yes, "change" -> Labels.Change)),
      render = data =>
        val zone = ZoneId.of(data(TimezoneKey))
        val local = clock.now().atZone(zone)
        List(paragraph(WizardCopy.timezoneConfirm(f"${local.getHour}%02d:${local.getMinute}%02d", zone.getId)))
      ,
      accept = {
        case (StepInput.Chosen("yes"), _)    => Right(StepTransition.Complete)
        case (StepInput.Chosen("change"), _) => Right(StepTransition.Next("timezone", Map(ErrorKey -> "")))
        case _                               => Left(IdentityCopy.confirmHint)
      }
    )
  )

  private def complete(data: Map[String, String], ctx: FlowContext): Reply =
    ctx.principal.accountId match
      // Defensive: the identity gate refuses a second create before a session starts.
      case Some(_) =>
        Reply(followUps =
          List(outbound(IdentityCopy.alreadyLinked, s"account.create:linked:${ctx.event.eventId.uuid}", ctx))
        )
      case None =>
        val purpose = purposeOf(data)
        val taps = ConsentTaps(Instant.parse(data(AgreedAtKey)), Instant.parse(data(ConfirmedAtKey)))
        val notice = ConsentNotice(ConsentCopy.NoticeVersion, ConsentCopy.statement(purpose), ConsentCopy.Locale)
        // The account dates from the confirming tap: the account and its consent are one record.
        val accountId = ctx.tx.accounts.createAccount(ctx.principal.identityId, data(TimezoneKey), taps.confirmedAt)
        ctx.tx.consents.grant(accountId.uuid, purpose, notice, taps)
        val date = ConsentCopy.dateOf(taps.confirmedAt, ZoneId.of(data(TimezoneKey)))
        Reply(
          followUps = List(
            outbound(ConsentCopy.receipt(purpose, date), s"account.create:receipt:${ctx.event.eventId.uuid}", ctx),
            outbound(IdentityCopy.accountCreated, s"account.create:done:${ctx.event.eventId.uuid}", ctx)
          ),
          domainEvents = List(Event.AccountCreated(accountId, ctx.principal.identityId, ctx.event.vendor))
        )

  private def outbound(text: String, dedupeKey: String, ctx: FlowContext): OutboundMessage =
    OutboundMessage(
      body = List(paragraph(text)),
      dedupeKey = dedupeKey,
      correlationId = s"${ctx.event.vendor}:${ctx.event.vendorEventId}"
    )
end AccountCreateFlow
