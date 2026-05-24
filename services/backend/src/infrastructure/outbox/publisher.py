"""Publishes committed outbox rows to Kafka."""

import logging
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from src.config import Config
from src.infrastructure.db.models import OutboxMessage
from src.infrastructure.kafka.producer import KafkaProducer

logger = logging.getLogger(__name__)


class OutboxPublisher:
    """Polls pending outbox rows and publishes them to Kafka."""

    def __init__(
        self,
        *,
        config: Config,
        session_factory: async_sessionmaker[AsyncSession],
        producer: KafkaProducer | None = None,
        batch_size: int = 100,
    ):
        self.session_factory = session_factory
        self.producer = producer or KafkaProducer(config)
        self.batch_size = batch_size

    async def publish_once(self) -> int:
        """Publish one batch of pending outbox messages."""
        async with self.session_factory() as session:
            async with session.begin():
                result = await session.execute(
                    select(OutboxMessage)
                    .where(OutboxMessage.status == "pending")
                    .order_by(OutboxMessage.created_at)
                    .limit(self.batch_size)
                    .with_for_update(skip_locked=True)
                )
                rows = list(result.scalars())

                for row in rows:
                    self.producer.publish(
                        topic=row.topic,
                        key=row.partition_key,
                        payload=row.payload,
                        message_type=row.message_type,
                    )

                if rows and self.producer.flush() != 0:
                    raise RuntimeError("Timed out while flushing backend outbox messages")

                for row in rows:
                    row.status = "published"
                    row.attempts += 1
                    row.published_at = datetime.utcnow()

        if rows:
            logger.info("Published %s outbox messages", len(rows))
        return len(rows)
