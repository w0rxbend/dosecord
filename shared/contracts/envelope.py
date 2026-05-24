"""Flexible CloudEvents-style Kafka message envelope."""

from datetime import datetime, timezone
from typing import Generic, Literal, Optional, TypeVar
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field

from shared.contracts.actors import Actor


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


DataT = TypeVar("DataT", bound=BaseModel)


class MessageEnvelope(BaseModel, Generic[DataT]):
    """Common envelope for commands, events, and chat responses."""

    model_config = ConfigDict(extra="forbid")

    specversion: Literal["1.0"] = "1.0"
    id: str = Field(default_factory=lambda: str(uuid4()))
    type: str
    source: str
    subject: Optional[str] = None
    time: datetime = Field(default_factory=utc_now)
    datacontenttype: Literal["application/json"] = "application/json"
    dataschema: Optional[str] = None
    schema_version: int = 1
    correlation_id: str = Field(default_factory=lambda: str(uuid4()))
    causation_id: Optional[str] = None
    reply_to: Optional[str] = None
    traceparent: Optional[str] = None
    idempotency_key: Optional[str] = None
    actor: Actor
    data: DataT

    @property
    def partition_key(self) -> str:
        return self.actor.partition_key
