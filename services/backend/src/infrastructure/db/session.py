"""Async SQLAlchemy session setup."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from src.config import Config


def make_engine(config: Config):
    return create_async_engine(config.database_url, pool_pre_ping=True)


def make_session_factory(config: Config) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(make_engine(config), expire_on_commit=False)


@asynccontextmanager
async def session_scope(
    session_factory: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncSession]:
    async with session_factory() as session:
        async with session.begin():
            yield session
