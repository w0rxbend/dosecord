"""Configuration management for Discord Bot Service."""

from pydantic_settings import BaseSettings
from pydantic_settings import SettingsConfigDict


class Config(BaseSettings):
    """Application configuration"""

    model_config = SettingsConfigDict(env_file=".env", case_sensitive=False)
    
    discord_token: str
    kafka_brokers: str = "localhost:9092"
    kafka_topic_events: str = "dosecord.commands"
    log_level: str = "INFO"
    debug: bool = False
    service_name: str = "dosecord-discord-bot"
    
    @classmethod
    def from_env(cls) -> "Config":
        """Load configuration from environment variables"""
        config = cls()
        if not config.discord_token:
            raise ValueError("DISCORD_TOKEN is required to start the Discord bot service")
        return config
