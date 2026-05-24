"""Medication scheduling command payloads."""

from datetime import date
from typing import Literal, Optional

from pydantic import BaseModel, Field

from shared.contracts.actors import Actor
from shared.contracts.envelope import MessageEnvelope


MEDICATION_SCHEDULE_CREATE_REQUESTED = "dosecord.medication.schedule_create_requested.v1"

Weekday = Literal["mon", "tue", "wed", "thu", "fri", "sat", "sun"]


class MedicationData(BaseModel):
    name: str
    dose_amount: Optional[str] = None
    dose_unit: Optional[str] = None
    instructions: Optional[str] = None


class FixedTimeScheduleData(BaseModel):
    kind: Literal["fixed_times"] = "fixed_times"
    timezone: str
    days: list[Weekday]
    times: list[str] = Field(..., min_length=1)
    start_date: date
    end_date: Optional[date] = None


class ReminderPolicyData(BaseModel):
    initial_offset_minutes: int = 0
    snooze_options_minutes: list[int] = Field(default_factory=lambda: [10, 30, 60])
    miss_after_minutes: int = 120
    max_reminders: int = 3
    discreet: bool = False


class MedicationScheduleCreateRequestedData(BaseModel):
    medication: MedicationData
    schedule: FixedTimeScheduleData
    reminder_policy: ReminderPolicyData = Field(default_factory=ReminderPolicyData)


def medication_schedule_create_requested(
    *,
    actor: Actor,
    data: MedicationScheduleCreateRequestedData,
    source: str,
    idempotency_key: Optional[str] = None,
) -> MessageEnvelope[MedicationScheduleCreateRequestedData]:
    return MessageEnvelope(
        type=MEDICATION_SCHEDULE_CREATE_REQUESTED,
        source=source,
        subject=actor.partition_key,
        dataschema="dosecord://schemas/medication/schedule-create-requested/v1",
        actor=actor,
        data=data,
        idempotency_key=idempotency_key,
        reply_to="dosecord.chat.responses",
    )
