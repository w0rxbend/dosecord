"""Initial production database model skeleton.

These models intentionally mirror the production plan: canonical users,
platform identities, idempotency, outbox, medication/reminder occurrence
tracking, and habit/mood records.
"""

from datetime import date, datetime
from uuid import uuid4

from sqlalchemy import (
    Boolean,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


def utc_now() -> datetime:
    return datetime.utcnow()


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=lambda: str(uuid4()))
    handle: Mapped[str | None] = mapped_column(String(80), unique=True)
    display_name: Mapped[str | None] = mapped_column(String(160))
    timezone: Mapped[str] = mapped_column(String(64), default="UTC")
    status: Mapped[str] = mapped_column(String(32), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    disabled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class UserCredential(Base):
    __tablename__ = "user_credentials"

    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True)
    password_hash: Mapped[str] = mapped_column(Text)
    password_set_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    password_rehash_required: Mapped[bool] = mapped_column(Boolean, default=False)
    failed_login_count: Mapped[int] = mapped_column(Integer, default=0)
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class PlatformIdentity(Base):
    __tablename__ = "platform_identities"
    __table_args__ = (
        UniqueConstraint("platform", "platform_user_id", name="uq_platform_identity"),
        UniqueConstraint("user_id", "platform", name="uq_user_platform"),
    )

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    platform: Mapped[str] = mapped_column(String(32))
    platform_user_id: Mapped[str] = mapped_column(String(128))
    platform_username: Mapped[str | None] = mapped_column(String(160))
    platform_display_name: Mapped[str | None] = mapped_column(String(160))
    linked_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    unlinked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class ConversationSession(Base):
    __tablename__ = "conversation_sessions"
    __table_args__ = (UniqueConstraint("platform", "platform_user_id"),)

    platform: Mapped[str] = mapped_column(String(32), primary_key=True)
    platform_user_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    state: Mapped[str] = mapped_column(String(80))
    state_data: Mapped[dict] = mapped_column(JSONB, default=dict)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class ProcessedMessage(Base):
    __tablename__ = "processed_messages"

    idempotency_key: Mapped[str] = mapped_column(String(256), primary_key=True)
    message_id: Mapped[str] = mapped_column(String(80), unique=True)
    message_type: Mapped[str] = mapped_column(String(160))
    status: Mapped[str] = mapped_column(String(32), default="processed")
    processed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    result: Mapped[dict] = mapped_column(JSONB, default=dict)


class OutboxMessage(Base):
    __tablename__ = "outbox_messages"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=lambda: str(uuid4()))
    topic: Mapped[str] = mapped_column(String(160))
    partition_key: Mapped[str] = mapped_column(String(256))
    message_type: Mapped[str] = mapped_column(String(160))
    payload: Mapped[dict] = mapped_column(JSONB)
    status: Mapped[str] = mapped_column(String(32), default="pending")
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class MoodCheckin(Base):
    __tablename__ = "mood_checkins"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    mood_level: Mapped[int] = mapped_column(Integer)
    note: Mapped[str | None] = mapped_column(Text)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class Medication(Base):
    __tablename__ = "medications"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    name: Mapped[str] = mapped_column(String(160))
    dose_amount: Mapped[str | None] = mapped_column(String(80))
    dose_unit: Mapped[str | None] = mapped_column(String(80))
    instructions: Mapped[str | None] = mapped_column(Text)
    timezone: Mapped[str] = mapped_column(String(64), default="UTC")
    status: Mapped[str] = mapped_column(String(32), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class MedicationSchedule(Base):
    __tablename__ = "medication_schedules"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=lambda: str(uuid4()))
    medication_id: Mapped[str] = mapped_column(ForeignKey("medications.id"))
    schedule_kind: Mapped[str] = mapped_column(String(64))
    rule: Mapped[dict] = mapped_column(JSONB)
    start_date: Mapped[date | None] = mapped_column(Date)
    end_date: Mapped[date | None] = mapped_column(Date)
    timezone: Mapped[str] = mapped_column(String(64), default="UTC")
    status: Mapped[str] = mapped_column(String(32), default="active")


class MedicationOccurrence(Base):
    __tablename__ = "medication_occurrences"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=lambda: str(uuid4()))
    schedule_id: Mapped[str] = mapped_column(ForeignKey("medication_schedules.id"))
    medication_id: Mapped[str] = mapped_column(ForeignKey("medications.id"))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    scheduled_for: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    due_window_ends_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    status: Mapped[str] = mapped_column(String(32), default="pending")
    last_reminded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    reminder_count: Mapped[int] = mapped_column(Integer, default=0)
    snoozed_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    taken_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    skipped_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    missed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class Habit(Base):
    __tablename__ = "habits"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    name: Mapped[str] = mapped_column(String(160))
    habit_type: Mapped[str] = mapped_column(String(32), default="boolean")
    cadence: Mapped[dict] = mapped_column(JSONB, default=dict)
    streak_policy: Mapped[str] = mapped_column(String(32), default="flexible")
    status: Mapped[str] = mapped_column(String(32), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class HabitCheckin(Base):
    __tablename__ = "habit_checkins"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=lambda: str(uuid4()))
    habit_id: Mapped[str] = mapped_column(ForeignKey("habits.id"))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    status: Mapped[str] = mapped_column(String(32))
    value: Mapped[int | None] = mapped_column(Integer)
    note: Mapped[str | None] = mapped_column(Text)
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class Reminder(Base):
    __tablename__ = "reminders"

    id: Mapped[str] = mapped_column(UUID(as_uuid=False), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    title: Mapped[str] = mapped_column(String(160))
    rule: Mapped[dict] = mapped_column(JSONB)
    next_due_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    status: Mapped[str] = mapped_column(String(32), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
