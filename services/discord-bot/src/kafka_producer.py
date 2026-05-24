"""
Kafka Event Producer for publishing wellbeing events
"""

import logging
import json
from confluent_kafka import Producer

from src.config import Config
from src.models import WellbeingEvent

logger = logging.getLogger(__name__)


class KafkaEventProducer:
    """Produces wellbeing events to Kafka"""
    
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
    
    def publish_event(self, event: WellbeingEvent) -> bool:
        """
        Publish a wellbeing event to Kafka
        
        Args:
            event: WellbeingEvent to publish
            
        Returns:
            bool: True if successful, False otherwise
        """
        try:
            event_data = event.model_dump(mode="json")
            message = json.dumps(event_data, default=str)
            
            # Publish to Kafka
            self.producer.produce(
                topic=self.topic,
                key=event.partition_key,
                value=message.encode("utf-8"),
                callback=self._delivery_report
            )
            
            # Flush to ensure delivery
            self.producer.flush(timeout=5)
            logger.info(
                "Event published: %s for %s",
                event.event_type,
                event.partition_key,
            )
            return True
            
        except Exception as e:
            logger.error(f"Failed to publish event: {e}")
            return False
    
    def close(self):
        """Close the producer"""
        self.producer.flush()
        logger.info("Kafka producer closed")
