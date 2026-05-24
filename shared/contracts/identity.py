"""Identity command payloads for account setup and linking."""

from typing import Optional

from pydantic import BaseModel

from shared.contracts.actors import Actor
from shared.contracts.envelope import MessageEnvelope


IDENTITY_START_REQUESTED = "dosecord.identity.start_requested.v1"
IDENTITY_SIGNUP_REQUESTED = "dosecord.identity.signup_requested.v1"
IDENTITY_PLATFORM_LINK_REQUESTED = "dosecord.identity.platform_link_requested.v1"
IDENTITY_RESTORE_REQUESTED = "dosecord.identity.restore_requested.v1"


class IdentityStartRequestedData(BaseModel):
    locale: Optional[str] = None
    timezone: Optional[str] = None


def identity_start_requested(
    *,
    actor: Actor,
    source: str,
    idempotency_key: Optional[str] = None,
) -> MessageEnvelope[IdentityStartRequestedData]:
    return MessageEnvelope(
        type=IDENTITY_START_REQUESTED,
        source=source,
        subject=actor.partition_key,
        dataschema="dosecord://schemas/identity/start-requested/v1",
        actor=actor,
        data=IdentityStartRequestedData(),
        idempotency_key=idempotency_key,
        reply_to="dosecord.chat.responses",
    )
