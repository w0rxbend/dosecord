"""Wellbeing command payloads and constructors."""

from typing import Optional

from pydantic import BaseModel, Field

from shared.contracts.actors import Actor
from shared.contracts.envelope import MessageEnvelope


MOOD_CHECKIN_RECORD_REQUESTED = "dosecord.mood.checkin_record_requested.v1"
MEDICATION_INTAKE_MARK_TAKEN_REQUESTED = (
    "dosecord.medication.intake_mark_taken_requested.v1"
)
HABIT_CHECKIN_RECORD_REQUESTED = "dosecord.habit.checkin_record_requested.v1"


class MoodCheckinRecordRequestedData(BaseModel):
    mood_level: int = Field(..., ge=1, le=10)
    note: Optional[str] = None
    tags: list[str] = Field(default_factory=list)


class MedicationIntakeMarkTakenRequestedData(BaseModel):
    medication_name: str
    dosage: Optional[str] = None
    note: Optional[str] = None


class HabitCheckinRecordRequestedData(BaseModel):
    habit_name: str
    duration_minutes: Optional[int] = None
    note: Optional[str] = None


def mood_checkin_record_requested(
    *,
    actor: Actor,
    mood_level: int,
    source: str,
    note: Optional[str] = None,
    tags: Optional[list[str]] = None,
    idempotency_key: Optional[str] = None,
) -> MessageEnvelope[MoodCheckinRecordRequestedData]:
    return MessageEnvelope(
        type=MOOD_CHECKIN_RECORD_REQUESTED,
        source=source,
        subject=actor.partition_key,
        dataschema="dosecord://schemas/mood/checkin-record-requested/v1",
        actor=actor,
        data=MoodCheckinRecordRequestedData(
            mood_level=mood_level,
            note=note,
            tags=tags or [],
        ),
        idempotency_key=idempotency_key,
        reply_to="dosecord.chat.responses",
    )


def medication_intake_mark_taken_requested(
    *,
    actor: Actor,
    medication_name: str,
    source: str,
    dosage: Optional[str] = None,
    note: Optional[str] = None,
    idempotency_key: Optional[str] = None,
) -> MessageEnvelope[MedicationIntakeMarkTakenRequestedData]:
    return MessageEnvelope(
        type=MEDICATION_INTAKE_MARK_TAKEN_REQUESTED,
        source=source,
        subject=actor.partition_key,
        dataschema="dosecord://schemas/medication/intake-mark-taken-requested/v1",
        actor=actor,
        data=MedicationIntakeMarkTakenRequestedData(
            medication_name=medication_name,
            dosage=dosage,
            note=note,
        ),
        idempotency_key=idempotency_key,
        reply_to="dosecord.chat.responses",
    )


def habit_checkin_record_requested(
    *,
    actor: Actor,
    habit_name: str,
    source: str,
    duration_minutes: Optional[int] = None,
    note: Optional[str] = None,
    idempotency_key: Optional[str] = None,
) -> MessageEnvelope[HabitCheckinRecordRequestedData]:
    return MessageEnvelope(
        type=HABIT_CHECKIN_RECORD_REQUESTED,
        source=source,
        subject=actor.partition_key,
        dataschema="dosecord://schemas/habit/checkin-record-requested/v1",
        actor=actor,
        data=HabitCheckinRecordRequestedData(
            habit_name=habit_name,
            duration_minutes=duration_minutes,
            note=note,
        ),
        idempotency_key=idempotency_key,
        reply_to="dosecord.chat.responses",
    )
