"""Chat response contracts emitted by backend and rendered by adapters."""

from typing import Literal, Optional

from pydantic import BaseModel, Field

from shared.contracts.actors import Actor
from shared.contracts.envelope import MessageEnvelope


CHAT_RESPONSE_SEND_REQUESTED = "dosecord.chat.response_send_requested.v1"


class ChatChannelData(BaseModel):
    kind: Literal["dm"] = "dm"
    platform_channel_id: Optional[str] = None


class ChatMessageData(BaseModel):
    text: str
    format: Literal["plain", "markdown"] = "plain"


class ChatResponseSendRequestedData(BaseModel):
    channel: ChatChannelData = Field(default_factory=ChatChannelData)
    message: ChatMessageData


def chat_response_send_requested(
    *,
    actor: Actor,
    text: str,
    source: str = "dosecord.backend",
    causation_id: Optional[str] = None,
    correlation_id: Optional[str] = None,
) -> MessageEnvelope[ChatResponseSendRequestedData]:
    return MessageEnvelope(
        type=CHAT_RESPONSE_SEND_REQUESTED,
        source=source,
        subject=actor.partition_key,
        dataschema="dosecord://schemas/chat/response-send-requested/v1",
        actor=actor,
        data=ChatResponseSendRequestedData(message=ChatMessageData(text=text)),
        causation_id=causation_id,
        correlation_id=correlation_id or causation_id,
    )
