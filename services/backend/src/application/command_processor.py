"""Transactional command processing for Kafka commands."""

import logging
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from shared.contracts import MessageEnvelope
from shared.contracts.chat import chat_response_send_requested
from shared.contracts.identity import IDENTITY_SIGNUP_REQUESTED, IDENTITY_START_REQUESTED
from shared.contracts.medication import MEDICATION_SCHEDULE_CREATE_REQUESTED
from shared.contracts.wellbeing import (
    HABIT_CHECKIN_RECORD_REQUESTED,
    MEDICATION_INTAKE_MARK_TAKEN_REQUESTED,
    MOOD_CHECKIN_RECORD_REQUESTED,
)

from src.config import Config
from src.infrastructure.db.models import (
    ConversationSession,
    Habit,
    HabitCheckin,
    Medication,
    MedicationSchedule,
    MoodCheckin,
    OutboxMessage,
    PlatformIdentity,
    ProcessedMessage,
    User,
)

logger = logging.getLogger(__name__)


class CommandProcessor:
    """Applies a command in one DB transaction and writes outbox messages."""

    def __init__(
        self,
        *,
        config: Config,
        session_factory: async_sessionmaker[AsyncSession],
    ):
        self.config = config
        self.session_factory = session_factory

    async def process(self, command: MessageEnvelope[Any]) -> bool:
        """Process a command.

        Returns True when this command was newly processed, False when it was a
        duplicate idempotency key and the caller may commit the Kafka offset.
        """
        async with self.session_factory() as session:
            async with session.begin():
                if await self._is_duplicate(session, command):
                    return False

                await self._route(session, command)
                await self._record_processed(session, command)
                return True

    async def _is_duplicate(
        self,
        session: AsyncSession,
        command: MessageEnvelope[Any],
    ) -> bool:
        if not command.idempotency_key:
            return False

        processed = await session.get(ProcessedMessage, command.idempotency_key)
        if processed is not None:
            logger.info(
                "Duplicate command skipped: %s idempotency_key=%s",
                command.type,
                command.idempotency_key,
            )
            return True
        return False

    async def _route(
        self,
        session: AsyncSession,
        command: MessageEnvelope[Any],
    ) -> None:
        if command.type == IDENTITY_START_REQUESTED:
            await self._handle_identity_start(session, command)
        elif command.type == IDENTITY_SIGNUP_REQUESTED:
            await self._handle_identity_signup(session, command)
        elif command.type == MOOD_CHECKIN_RECORD_REQUESTED:
            await self._handle_mood_checkin(session, command)
        elif command.type == MEDICATION_SCHEDULE_CREATE_REQUESTED:
            await self._handle_medication_schedule_create(session, command)
        elif command.type == MEDICATION_INTAKE_MARK_TAKEN_REQUESTED:
            await self._handle_medication_taken(session, command)
        elif command.type == HABIT_CHECKIN_RECORD_REQUESTED:
            await self._handle_habit_checkin(session, command)
        else:
            await self._enqueue_response(
                session,
                command,
                "I received a command I do not understand yet.",
            )

    async def _handle_identity_start(
        self,
        session: AsyncSession,
        command: MessageEnvelope[Any],
    ) -> None:
        await session.merge(
            ConversationSession(
                platform=command.actor.platform.value,
                platform_user_id=command.actor.platform_user_id,
                state="account_menu",
                state_data={
                    "platform_username": command.actor.platform_username,
                    "correlation_id": command.correlation_id,
                },
                expires_at=datetime.utcnow() + timedelta(minutes=30),
            )
        )
        await self._enqueue_response(
            session,
            command,
            (
                "Welcome to Dosecord. Use /signup <handle> to create an "
                "account, or link/restore from the account menu soon."
            ),
        )

    async def _handle_identity_signup(
        self,
        session: AsyncSession,
        command: MessageEnvelope[Any],
    ) -> None:
        existing_identity = await self._resolve_user_id(session, command)
        if existing_identity is not None:
            await self._enqueue_response(
                session,
                command,
                "This Discord account is already linked to a Dosecord account.",
            )
            return

        existing_handle = await session.execute(
            select(User).where(User.handle == command.data.handle)
        )
        if existing_handle.scalar_one_or_none() is not None:
            await self._enqueue_response(
                session,
                command,
                "That handle is already taken. Try another one.",
            )
            return

        user = User(
            handle=command.data.handle,
            display_name=command.data.display_name,
            timezone=command.data.timezone,
            status="pending_credentials",
        )
        session.add(user)
        await session.flush()
        session.add(
            PlatformIdentity(
                user_id=user.id,
                platform=command.actor.platform.value,
                platform_user_id=command.actor.platform_user_id,
                platform_username=command.actor.platform_username,
                platform_display_name=command.data.display_name,
            )
        )
        await self._enqueue_response(
            session,
            command,
            (
                f"Account @{command.data.handle} created and linked to Discord. "
                "Password setup through a secure link is the next production step."
            ),
        )

    async def _handle_mood_checkin(
        self,
        session: AsyncSession,
        command: MessageEnvelope[Any],
    ) -> None:
        user_id = await self._resolve_user_id(session, command)
        if user_id is None:
            await self._enqueue_account_required(session, command)
            return

        session.add(
            MoodCheckin(
                user_id=user_id,
                mood_level=command.data.mood_level,
                note=command.data.note,
            )
        )
        await self._enqueue_response(
            session,
            command,
            f"Recorded your mood as {command.data.mood_level}/10.",
        )

    async def _handle_medication_schedule_create(
        self,
        session: AsyncSession,
        command: MessageEnvelope[Any],
    ) -> None:
        user_id = await self._resolve_user_id(session, command)
        if user_id is None:
            await self._enqueue_account_required(session, command)
            return

        medication = Medication(
            user_id=user_id,
            name=command.data.medication.name,
            dose_amount=command.data.medication.dose_amount,
            dose_unit=command.data.medication.dose_unit,
            instructions=command.data.medication.instructions,
            timezone=command.data.schedule.timezone,
        )
        session.add(medication)
        await session.flush()
        session.add(
            MedicationSchedule(
                medication_id=medication.id,
                schedule_kind=command.data.schedule.kind,
                rule={
                    "days": command.data.schedule.days,
                    "times": command.data.schedule.times,
                    "reminder_policy": command.data.reminder_policy.model_dump(),
                },
                start_date=command.data.schedule.start_date,
                end_date=command.data.schedule.end_date,
                timezone=command.data.schedule.timezone,
            )
        )
        await self._enqueue_response(
            session,
            command,
            (
                f"Created daily medication schedule for {command.data.medication.name} "
                f"at {', '.join(command.data.schedule.times)}."
            ),
        )

    async def _handle_medication_taken(
        self,
        session: AsyncSession,
        command: MessageEnvelope[Any],
    ) -> None:
        user_id = await self._resolve_user_id(session, command)
        if user_id is None:
            await self._enqueue_account_required(session, command)
            return

        # Medication occurrence matching lands in the medication slice. For now
        # this proves the command/idempotency/outbox path without inventing
        # schedule state.
        await self._enqueue_response(
            session,
            command,
            f"Received medication intake for {command.data.medication_name}.",
        )

    async def _handle_habit_checkin(
        self,
        session: AsyncSession,
        command: MessageEnvelope[Any],
    ) -> None:
        user_id = await self._resolve_user_id(session, command)
        if user_id is None:
            await self._enqueue_account_required(session, command)
            return

        habit = Habit(
            user_id=user_id,
            name=command.data.habit_name,
        )
        session.add(habit)
        await session.flush()
        session.add(
            HabitCheckin(
                habit_id=habit.id,
                user_id=user_id,
                status="done",
                value=command.data.duration_minutes,
                note=command.data.note,
            )
        )
        await self._enqueue_response(
            session,
            command,
            f"Recorded habit check-in for {command.data.habit_name}.",
        )

    async def _resolve_user_id(
        self,
        session: AsyncSession,
        command: MessageEnvelope[Any],
    ) -> str | None:
        if command.actor.account_id:
            return command.actor.account_id

        result = await session.execute(
            select(PlatformIdentity.user_id).where(
                PlatformIdentity.platform == command.actor.platform.value,
                PlatformIdentity.platform_user_id == command.actor.platform_user_id,
                PlatformIdentity.unlinked_at.is_(None),
            )
        )
        return result.scalar_one_or_none()

    async def _enqueue_account_required(
        self,
        session: AsyncSession,
        command: MessageEnvelope[Any],
    ) -> None:
        await self._enqueue_response(
            session,
            command,
            "Please create or link your Dosecord account first with /start.",
        )

    async def _enqueue_response(
        self,
        session: AsyncSession,
        command: MessageEnvelope[Any],
        text: str,
    ) -> None:
        response = chat_response_send_requested(
            actor=command.actor,
            text=text,
            causation_id=command.id,
            correlation_id=command.correlation_id,
        )
        session.add(
            OutboxMessage(
                topic=self.config.kafka_topic_chat_responses,
                partition_key=response.partition_key,
                message_type=response.type,
                payload=response.model_dump(mode="json"),
            )
        )

    async def _record_processed(
        self,
        session: AsyncSession,
        command: MessageEnvelope[Any],
    ) -> None:
        if not command.idempotency_key:
            return

        session.add(
            ProcessedMessage(
                idempotency_key=command.idempotency_key,
                message_id=command.id,
                message_type=command.type,
                result={"correlation_id": command.correlation_id},
            )
        )
