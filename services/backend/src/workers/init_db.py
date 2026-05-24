"""Development helper to create database tables from SQLAlchemy metadata."""

import asyncio

from dotenv import load_dotenv

from src.config import Config
from src.infrastructure.db.models import Base
from src.infrastructure.db.session import make_engine

load_dotenv()


async def main():
    engine = make_engine(Config.from_env())
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    await engine.dispose()


if __name__ == "__main__":
    asyncio.run(main())
