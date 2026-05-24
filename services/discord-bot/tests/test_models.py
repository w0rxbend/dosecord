import pytest
from pydantic import ValidationError

from shared.contracts import Actor, Platform
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
