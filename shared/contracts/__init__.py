"""Shared Kafka contracts used by all Dosecord services."""

from shared.contracts.actors import Actor, Platform
from shared.contracts.envelope import MessageEnvelope
from shared.contracts.registry import parse_message

__all__ = ["Actor", "MessageEnvelope", "Platform", "parse_message"]
