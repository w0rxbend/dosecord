package dosecord.core.application.identity

import dosecord.contracts.*
import dosecord.core.chat.Flow
import dosecord.core.chat.FlowContext
import dosecord.core.domain.copy.IdentityCopy
import dosecord.core.domain.copy.WizardCopy
import dosecord.core.ports.Clock
import dosecord.core.scheduling.ScheduleLifecycle

import java.time.ZoneId

/** Account -> Timezone (ROADMAP M1.9, R10): the same picker and live-echo confirm as the account-create flow, but
  * completion re-revisions the account's active `tz_follows_user` schedules into the new zone (the M1.5 data side) and
  * answers with the echo "It is 14:32 for you now." Non-following schedules are untouched; the M3.2 question about them
  * is out of scope here.
  */
object AccountTimezoneFlow:

  val Id = "account.timezone"

  def flow(clock: Clock, lifecycle: ScheduleLifecycle): Flow = Flow(
    id = Id,
    firstStep = "timezone",
    steps = AccountCreateFlow.timezonePickerSteps(clock, intro = Nil),
    onComplete = (data, ctx) => complete(clock, lifecycle, data, ctx)
  )

  private def complete(clock: Clock, lifecycle: ScheduleLifecycle, data: Map[String, String], ctx: FlowContext): Reply =
    ctx.principal.accountId match
      case None =>
        Reply(followUps =
          List(outbound(IdentityCopy.createFirst, s"account.timezone:unlinked:${ctx.event.eventId.uuid}", ctx))
        )
      case Some(account) =>
        val zoneId = data(AccountCreateFlow.TimezoneKey)
        val now = clock.now()
        ctx.tx.accounts.setTimezone(account.uuid, zoneId, now)
        lifecycle.rezoneFollowingSchedulesInTx(ctx.tx, account.uuid, ZoneId.of(zoneId), now)
        val local = now.atZone(ZoneId.of(zoneId))
        Reply(
          followUps = List(
            outbound(
              WizardCopy.timezoneEcho(f"${local.getHour}%02d:${local.getMinute}%02d"),
              s"account.timezone:done:${ctx.event.eventId.uuid}",
              ctx
            )
          )
        )

  private def outbound(text: String, dedupeKey: String, ctx: FlowContext): OutboundMessage =
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text(text)))),
      dedupeKey = dedupeKey,
      correlationId = s"${ctx.event.vendor}:${ctx.event.vendorEventId}"
    )
end AccountTimezoneFlow
