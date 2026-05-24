"""Kafka producer wrapper for backend outbox publishing."""

import logging
from typing import Any

from confluent_kafka import Producer

from src.config import Config

logger = logging.getLogger(__name__)


class KafkaProducer:
    """Thin wrapper around confluent-kafka producer."""

    def __init__(self, config: Config):
        self.producer = Producer(
            {
                "bootstrap.servers": config.kafka_brokers,
                "client.id": f"{config.service_name}-producer",
            }
        )

    def publish(
        self,
        *,
        topic: str,
        key: str,
        payload: dict[str, Any],
        message_type: str,
    ) -> None:
        self.producer.produce(
            topic=topic,
            key=key,
            value=_json_bytes(payload),
            headers={"type": message_type},
            callback=self._delivery_report,
        )
        self.producer.poll(0)

    def flush(self, timeout: float = 5.0) -> int:
        return self.producer.flush(timeout=timeout)

    def _delivery_report(self, err, msg):
        if err is not None:
            logger.error("Backend Kafka delivery failed: %s", err)
            return
        logger.debug("Backend Kafka message delivered to %s[%s]", msg.topic(), msg.partition())


def _json_bytes(payload: dict[str, Any]) -> bytes:
    import json

    return json.dumps(payload, default=str, separators=(",", ":")).encode("utf-8")
