"""Message parsing and type registry."""

from typing import Any

from pydantic import BaseModel

from shared.contracts.chat import (
    CHAT_RESPONSE_SEND_REQUESTED,
    ChatResponseSendRequestedData,
)
from shared.contracts.envelope import MessageEnvelope
from shared.contracts.identity import (
    IDENTITY_SIGNUP_REQUESTED,
    IDENTITY_START_REQUESTED,
    IdentitySignupRequestedData,
    IdentityStartRequestedData,
)
from shared.contracts.medication import (
    MEDICATION_SCHEDULE_CREATE_REQUESTED,
    MedicationScheduleCreateRequestedData,
)
from shared.contracts.wellbeing import (
    HABIT_CHECKIN_RECORD_REQUESTED,
    MEDICATION_INTAKE_MARK_TAKEN_REQUESTED,
    MOOD_CHECKIN_RECORD_REQUESTED,
    HabitCheckinRecordRequestedData,
    MedicationIntakeMarkTakenRequestedData,
    MoodCheckinRecordRequestedData,
)


PAYLOAD_REGISTRY: dict[str, type[BaseModel]] = {
    CHAT_RESPONSE_SEND_REQUESTED: ChatResponseSendRequestedData,
    IDENTITY_START_REQUESTED: IdentityStartRequestedData,
    IDENTITY_SIGNUP_REQUESTED: IdentitySignupRequestedData,
    MEDICATION_SCHEDULE_CREATE_REQUESTED: MedicationScheduleCreateRequestedData,
    MOOD_CHECKIN_RECORD_REQUESTED: MoodCheckinRecordRequestedData,
    MEDICATION_INTAKE_MARK_TAKEN_REQUESTED: MedicationIntakeMarkTakenRequestedData,
    HABIT_CHECKIN_RECORD_REQUESTED: HabitCheckinRecordRequestedData,
}


def parse_message(raw: bytes | str) -> MessageEnvelope[Any]:
    """Decode and validate a Kafka message using the registered payload model."""
    base = MessageEnvelope[dict[str, Any]].model_validate_json(raw)
    payload_model = PAYLOAD_REGISTRY.get(base.type)
    if payload_model is None:
        raise ValueError(f"Unknown message type: {base.type}")

    payload = payload_model.model_validate(base.data)
    return MessageEnvelope[payload_model].model_validate(
        {**base.model_dump(), "data": payload}
    )
