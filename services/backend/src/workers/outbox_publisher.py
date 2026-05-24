"""Outbox publisher worker entrypoint."""

import asyncio
import logging

from dotenv import load_dotenv

from src.config import Config
from src.infrastructure.db.session import make_session_factory
from src.infrastructure.outbox.publisher import OutboxPublisher

load_dotenv()

logger = logging.getLogger(__name__)


async def main():
    config = Config.from_env()
    publisher = OutboxPublisher(
        config=config,
        session_factory=make_session_factory(config),
    )

    logger.info("Starting outbox publisher...")
    while True:
        published = await publisher.publish_once()
        await asyncio.sleep(0.5 if published else 2.0)


if __name__ == "__main__":
    asyncio.run(main())
