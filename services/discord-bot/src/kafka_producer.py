"""Kafka producer for publishing platform-neutral Dosecord commands."""

import logging
from typing import Any

from confluent_kafka import Producer

from src.config import Config
from shared.contracts.envelope import MessageEnvelope

logger = logging.getLogger(__name__)


class KafkaEventProducer:
    """Produces command envelopes to Kafka."""
    
    def __init__(self, config: Config):
        self.config = config
        self.topic = config.kafka_topic_events
        
        # Configure Kafka producer
        producer_config = {
            'bootstrap.servers': config.kafka_brokers,
            'client.id': f"{config.service_name}-producer",
        }
        
        self.producer = Producer(producer_config)
        logger.info(f"Kafka producer initialized for topic: {self.topic}")
    
    def _delivery_report(self, err, msg):
        """Callback for message delivery"""
        if err is not None:
            logger.error(f"Message delivery failed: {err}")
        else:
            logger.debug(f"Message delivered to {msg.topic()} [{msg.partition()}]")
    
    def publish(self, message: MessageEnvelope[Any]) -> bool:
        """Publish a validated command envelope to Kafka."""
        try:
            payload = message.model_dump_json().encode("utf-8")
            self.producer.produce(
                topic=self.topic,
                key=message.partition_key,
                value=payload,
                headers={
                    "type": message.type,
                    "correlation_id": message.correlation_id,
                    "schema_version": str(message.schema_version),
                },
                callback=self._delivery_report
            )
            self.producer.poll(0)
            logger.info(
                "Command published: %s for %s",
                message.type,
                message.partition_key,
            )
            return True
            
        except Exception as e:
            logger.error(f"Failed to publish command: {e}")
            return False

    def publish_event(self, event: MessageEnvelope[Any]) -> bool:
        """Backward-compatible alias while callers migrate to `publish`."""
        return self.publish(event)
    
    def close(self):
        """Close the producer"""
        self.producer.flush()
        logger.info("Kafka producer closed")
