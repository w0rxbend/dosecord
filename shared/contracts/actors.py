"""Platform and actor references for cross-client messages."""

from enum import Enum
from typing import Optional

from pydantic import BaseModel


class Platform(str, Enum):
    """External platform that originated an interaction."""

    DISCORD = "discord"
    TELEGRAM = "telegram"
    API = "api"
    SYSTEM = "system"


class Actor(BaseModel):
    """Identity reference carried on every Kafka message.

    `account_id` is optional because platform users are not linked during signup,
    restore, or first-contact flows.
    """

    platform: Platform
    platform_user_id: str
    platform_username: Optional[str] = None
    account_id: Optional[str] = None

    @property
    def partition_key(self) -> str:
        if self.account_id:
            return f"account:{self.account_id}"
        return f"platform:{self.platform.value}:{self.platform_user_id}"
