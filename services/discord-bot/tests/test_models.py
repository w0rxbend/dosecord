import pytest
from pydantic import ValidationError

from src.models import EventActor, MoodEvent, Platform


def test_mood_event_includes_multi_service_envelope():
    event = MoodEvent(
        actor=EventActor(platform=Platform.DISCORD, platform_user_id="123"),
        mood_level=7,
        mood_description="good",
    )

    payload = event.model_dump(mode="json")

    assert payload["id"]
    assert payload["schema_version"] == 1
    assert payload["source"] == "dosecord.discord-bot"
    assert payload["type"] == "dosecord.wellbeing.mood.logged.v1"
    assert payload["actor"]["platform"] == "discord"
    assert payload["actor"]["platform_user_id"] == "123"
    assert payload["mood_level"] == 7


def test_mood_level_must_be_in_supported_range():
    with pytest.raises(ValidationError):
        MoodEvent(
            actor=EventActor(platform=Platform.DISCORD, platform_user_id="123"),
            mood_level=11,
        )
