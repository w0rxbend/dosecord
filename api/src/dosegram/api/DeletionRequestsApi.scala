package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.given
import dosegram.api.ApiJson.jsonBody
import dosegram.core.application.privacy.Confirmation
import dosegram.core.application.privacy.DeletionRequests
import dosegram.core.ports.Clock
import dosegram.core.ports.DataDomain
import dosegram.core.ports.UnitOfWork
import sttp.model.StatusCode
import sttp.tapir.*

import java.time.Instant
import java.util.UUID

/** The answer to a deletion request: the confirmation is in the chat until `expiresAt` (MINI_APP section 8.8). */
final case class DeletionRequestView(status: String, expiresAt: Instant) derives ReadWriter, Schema

/** The deletion requests of the Mini App (ARCHITECTURE section 9.5, identity-and-consent; MINI_APP MA-SET-7; ROADMAP
  * G2.17, G5.7). Neither route deletes anything: each sends the confirmation into the account's private chat, where the
  * tap is the step-up (MA-STEP-1), and answers 202.
  */
final class DeletionRequestsApi(uow: UnitOfWork, clock: Clock, requests: DeletionRequests):

  def account(accountId: UUID): Either[ApiFailure, DeletionRequestView] =
    sent(uow.transaction(requests.account(_, accountId)))

  def domain(accountId: UUID, code: String): Either[ApiFailure, DeletionRequestView] =
    DataDomain.ofCode(code) match
      case None         => Left(ApiFailure.notFound())
      case Some(domain) => sent(uow.transaction(requests.domain(_, accountId, domain)))

  /** Without a chat to confirm in, nothing can be confirmed: the user starts the bot first. */
  private def sent(confirmationSent: Boolean): Either[ApiFailure, DeletionRequestView] =
    Either.cond(
      confirmationSent,
      DeletionRequestView("confirmation_sent", clock.now().plus(Confirmation.Lifetime)),
      ApiFailure.accountRequired()
    )

/** The Tapir definitions of the deletion routes, mounted under `/api/v1` like the others. */
object DeletionEndpoints:

  val requestAccountDeletion =
    Endpoints.secured.post
      .in("account" / "deletion")
      .out(statusCode(StatusCode.Accepted))
      .out(jsonBody[DeletionRequestView])
      .name("requestAccountDeletion")
      .description(
        "Sends the confirmation of the account deletion into the chat. Nothing is deleted before the two taps there."
      )

  val requestDomainDeletion =
    Endpoints.secured.delete
      .in("domains" / path[String]("domain") / "data")
      .out(statusCode(StatusCode.Accepted))
      .out(jsonBody[DeletionRequestView])
      .name("requestDomainDeletion")
      .description(
        "Sends the confirmation of the deletion of one domain (medication, hydration, mood, sleep, nutrition) into " +
          "the chat. Nothing is deleted before the tap there."
      )

  val documented: List[AnyEndpoint] = List(requestAccountDeletion, requestDomainDeletion)
end DeletionEndpoints
