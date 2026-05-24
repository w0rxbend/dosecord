"""Platform-neutral Kafka event models for Dosecord."""

from datetime import datetime, timezone
from enum import Enum
from uuid import uuid4
from typing import Optional, Dict, Any
from pydantic import BaseModel, ConfigDict, Field


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class Platform(str, Enum):
    """External client platform that originated an event."""

    DISCORD = "discord"
    TELEGRAM = "telegram"
    API = "api"


class EventActor(BaseModel):
    """External identity that performed an action."""

    platform: Platform
    platform_user_id: str
    platform_username: Optional[str] = None
    account_id: Optional[str] = None

    @property
    def partition_key(self) -> str:
        return self.account_id or f"{self.platform.value}:{self.platform_user_id}"


class WellbeingEvent(BaseModel):
    """Base event envelope.

    The shape follows CloudEvents concepts while keeping local fields explicit.
    Domain-specific event subclasses add payload fields.
    """

    model_config = ConfigDict(json_encoders={datetime: lambda value: value.isoformat()})

    specversion: str = "1.0"
    id: str = Field(default_factory=lambda: str(uuid4()))
    type: str = Field(..., description="Stable event type, e.g. dosecord.mood.logged.v1")
    source: str = "dosecord.discord-bot"
    subject: Optional[str] = None
    time: datetime = Field(default_factory=utc_now)
    datacontenttype: str = "application/json"
    dataschema: Optional[str] = None
    schema_version: int = 1
    correlation_id: str = Field(default_factory=lambda: str(uuid4()))
    causation_id: Optional[str] = None
    traceparent: Optional[str] = None
    actor: EventActor
    data: Dict[str, Any] = Field(default_factory=dict)

    @property
    def event_id(self) -> str:
        return self.id

    @property
    def event_type(self) -> str:
        return self.type

    @property
    def partition_key(self) -> str:
        return self.actor.partition_key

    @property
    def timestamp(self) -> datetime:
        return self.time


class MoodEvent(WellbeingEvent):
    """Mood tracking event"""
    
    type: str = "dosecord.wellbeing.mood.logged.v1"
    dataschema: str = "dosecord://schemas/wellbeing/mood-logged/v1"
    mood_level: int = Field(..., ge=1, le=10, description="Mood level 1-10")
    mood_description: Optional[str] = None
    tags: list[str] = Field(default_factory=list)


class MedicineEvent(WellbeingEvent):
    """Medicine intake tracking event"""
    
    type: str = "dosecord.wellbeing.medicine.intake_logged.v1"
    dataschema: str = "dosecord://schemas/wellbeing/medicine-intake-logged/v1"
    medicine_name: str = Field(..., description="Name of medicine")
    dosage: Optional[str] = None
    notes: Optional[str] = None


class HabitEvent(WellbeingEvent):
    """Habit completion tracking event"""
    
    type: str = "dosecord.wellbeing.habit.completed.v1"
    dataschema: str = "dosecord://schemas/wellbeing/habit-completed/v1"
    habit_name: str = Field(..., description="Name of habit")
    duration_minutes: Optional[int] = None
    notes: Optional[str] = None


class UserProfile(BaseModel):
    """User profile data"""
    
    user_id: int
    discord_username: str
    timezone: str = "UTC"
    preferences: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=datetime.utcnow)
