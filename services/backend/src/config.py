"""Backend service configuration."""

from pydantic_settings import BaseSettings
from pydantic_settings import SettingsConfigDict


class Config(BaseSettings):
    """Backend configuration"""

    model_config = SettingsConfigDict(env_file=".env", case_sensitive=False)
    
    kafka_brokers: str = "localhost:9092"
    kafka_consumer_group: str = "dosecord-backend"
    kafka_topic_events: str = "wellbeing.events"
    database_url: str = "postgresql://user:pass@localhost/db"
    log_level: str = "INFO"
    debug: bool = False
    service_name: str = "dosecord-backend"
    
    @classmethod
    def from_env(cls) -> "Config":
        """Load from environment"""
        return cls()
