package dosegram.core.application.reports

import dosegram.contracts.ChatRef
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.application.SequentialIds
import dosegram.core.chat.MediatorFakes
import dosegram.core.chat.MediatorFakes.FixedClock
import dosegram.core.ports.CardPage
import dosegram.core.ports.ReportPage
import dosegram.contracts.IdentityId

import java.time.Instant
import java.util.UUID

/** One account in Kyiv, its chat and a clock at 10:00 UTC (12:00 in Kyiv), over the in-memory transaction. */
private final class ReportWorld:
  val t0: Instant = Instant.parse("2026-11-14T10:00:00Z")
  val uow = MediatorFakes.InMemoryUnitOfWork()
  val clock = FixedClock(t0)
  val ids = SequentialIds()
  val chat: ChatRef = ChatRef("fake", "chat-1")
  val account: UUID = uow.accounts.createAccount(IdentityId(UUID.randomUUID()), "Europe/Kyiv", t0).uuid

  def otherAccount(): UUID = uow.accounts.createAccount(IdentityId(UUID.randomUUID()), "UTC", t0).uuid

private object ReportFixtures:

  def page(n: Int, template: String = "water"): ReportPage =
    ReportPage(
      CardPage(template, s"<svg id='$n'/>", s"Water today: $n of 2.0 l", s"$template.png", hasSpoiler = false),
      OutboundMessage(
        body = List(Node.Paragraph(List(Inline.Text(s"caption $n")))),
        dedupeKey = s"image:$n",
        correlationId = "fake:event"
      )
    )

  def lines(message: OutboundMessage): List[String] =
    message.body.collect { case Node.Paragraph(inlines) => inlines.collect { case Inline.Text(text) => text }.mkString }
