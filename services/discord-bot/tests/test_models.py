import pytest
from pydantic import ValidationError

from shared.contracts import Actor, Platform
from shared.contracts.identity import identity_signup_requested
from shared.contracts.medication import (
    FixedTimeScheduleData,
    MedicationData,
    MedicationScheduleCreateRequestedData,
    medication_schedule_create_requested,
)
from shared.contracts.wellbeing import mood_checkin_record_requested


def test_mood_event_includes_multi_service_envelope():
    event = mood_checkin_record_requested(
        actor=Actor(platform=Platform.DISCORD, platform_user_id="123"),
        source="dosecord.discord-bot",
        mood_level=7,
        note="good",
    )

    payload = event.model_dump(mode="json")

    assert payload["id"]
    assert payload["schema_version"] == 1
    assert payload["source"] == "dosecord.discord-bot"
    assert payload["type"] == "dosecord.mood.checkin_record_requested.v1"
    assert payload["actor"]["platform"] == "discord"
    assert payload["actor"]["platform_user_id"] == "123"
    assert payload["data"]["mood_level"] == 7


def test_mood_level_must_be_in_supported_range():
    with pytest.raises(ValidationError):
        mood_checkin_record_requested(
            actor=Actor(platform=Platform.DISCORD, platform_user_id="123"),
            source="dosecord.discord-bot",
            mood_level=11,
        )


def test_signup_command_uses_identity_payload():
    command = identity_signup_requested(
        actor=Actor(platform=Platform.DISCORD, platform_user_id="123"),
        source="dosecord.discord-bot",
        handle="alex",
    )

    payload = command.model_dump(mode="json")

    assert payload["type"] == "dosecord.identity.signup_requested.v1"
    assert payload["data"]["handle"] == "alex"


def test_medication_schedule_command_uses_typed_payload():
    command = medication_schedule_create_requested(
        actor=Actor(platform=Platform.DISCORD, platform_user_id="123"),
        source="dosecord.discord-bot",
        data=MedicationScheduleCreateRequestedData(
            medication=MedicationData(name="VitaminD"),
            schedule=FixedTimeScheduleData(
                timezone="UTC",
                days=["mon", "tue", "wed", "thu", "fri", "sat", "sun"],
                times=["09:00"],
                start_date="2026-05-24",
            ),
        ),
    )

    payload = command.model_dump(mode="json")

    assert payload["type"] == "dosecord.medication.schedule_create_requested.v1"
    assert payload["data"]["medication"]["name"] == "VitaminD"
    assert payload["data"]["schedule"]["times"] == ["09:00"]
