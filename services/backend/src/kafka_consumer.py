"""Kafka command consumer for backend service."""

import logging
from confluent_kafka import Consumer, KafkaError

from src.application.command_processor import CommandProcessor
from src.config import Config
from src.infrastructure.db.session import make_session_factory
from shared.contracts import parse_message

logger = logging.getLogger(__name__)


class KafkaEventConsumer:
    """Consumes platform-neutral command envelopes from Kafka."""
    
    def __init__(self, config: Config):
        self.config = config
        self.command_processor = CommandProcessor(
            config=config,
            session_factory=make_session_factory(config),
        )
        
        # Configure consumer
        consumer_config = {
            'bootstrap.servers': config.kafka_brokers,
            'group.id': config.kafka_consumer_group,
            'auto.offset.reset': 'earliest',
            'enable.auto.commit': False,
        }
        
        self.consumer = Consumer(consumer_config)
        self.consumer.subscribe([config.kafka_topic_events])
        logger.info(f"Consumer subscribed to {config.kafka_topic_events}")
    
    async def start(self):
        """Start consuming events"""
        logger.info("Starting command consumer...")
        try:
            while True:
                msg = self.consumer.poll(timeout=1.0)
                
                if msg is None:
                    continue
                
                if msg.error():
                    if msg.error().code() == KafkaError._PARTITION_EOF:
                        continue
                    else:
                        logger.error(f"Consumer error: {msg.error()}")
                        break
                
                try:
                    command = parse_message(msg.value())
                    await self.command_processor.process(command)
                    self.consumer.commit(message=msg, asynchronous=False)
                except Exception:
                    logger.exception(
                        "Failed to process Kafka message at %s[%s] offset %s",
                        msg.topic(),
                        msg.partition(),
                        msg.offset(),
                    )
                    # Production path: publish a dosecord.system.message_rejected.v1
                    # envelope to dosecord.dlq before committing or parking.
                
        except KeyboardInterrupt:
            logger.info("Consumer interrupted")
        finally:
            self.consumer.close()
    
    async def stop(self):
        """Stop consumer"""
        self.consumer.close()
        logger.info("Consumer stopped")
