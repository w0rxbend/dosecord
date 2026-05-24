"""
Kafka Event Consumer for backend service
"""

import json
import logging
from confluent_kafka import Consumer, KafkaError
from datetime import datetime

from src.config import Config

logger = logging.getLogger(__name__)


class KafkaEventConsumer:
    """Consumes wellbeing events from Kafka"""
    
    def __init__(self, config: Config):
        self.config = config
        
        # Configure consumer
        consumer_config = {
            'bootstrap.servers': config.kafka_brokers,
            'group.id': config.kafka_consumer_group,
            'auto.offset.reset': 'earliest',
            'enable.auto.commit': True,
        }
        
        self.consumer = Consumer(consumer_config)
        self.consumer.subscribe([config.kafka_topic_events])
        logger.info(f"Consumer subscribed to {config.kafka_topic_events}")
    
    async def start(self):
        """Start consuming events"""
        logger.info("Starting event consumer...")
        
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
                
                # Process message
                await self._handle_event(msg)
                
        except KeyboardInterrupt:
            logger.info("Consumer interrupted")
        finally:
            self.consumer.close()
    
    async def stop(self):
        """Stop consumer"""
        self.consumer.close()
        logger.info("Consumer stopped")
    
    async def _handle_event(self, msg):
        """
        Handle incoming event
        
        Args:
            msg: Kafka message
        """
        try:
            # Decode message
            event_data = json.loads(msg.value().decode('utf-8'))
            
            event_type = event_data.get('event_type') or event_data.get('type')
            actor = event_data.get('actor') or {}
            user_id = event_data.get('user_id') or actor.get('account_id') or actor.get('platform_user_id')
            timestamp = event_data.get('timestamp') or event_data.get('time')
            
            logger.info(
                f"Processing {event_type} event from user {user_id} "
                f"at {timestamp}"
            )
            
            # Route based on event type
            if event_type in {'mood', 'dosecord.wellbeing.mood.logged.v1'}:
                await self._handle_mood_event(event_data)
            elif event_type in {'medicine', 'dosecord.wellbeing.medicine.intake_logged.v1'}:
                await self._handle_medicine_event(event_data)
            elif event_type in {'habit', 'dosecord.wellbeing.habit.completed.v1'}:
                await self._handle_habit_event(event_data)
            else:
                logger.warning(f"Unknown event type: {event_type}")
            
        except Exception as e:
            logger.error(f"Error processing event: {e}")
    
    async def _handle_mood_event(self, event: dict):
        """Handle mood event - store in DB and update stats"""
        logger.debug(f"Handling mood event: {event}")
        # TODO: Implement database storage
        # TODO: Update mood statistics
    
    async def _handle_medicine_event(self, event: dict):
        """Handle medicine event - store in DB and check reminders"""
        logger.debug(f"Handling medicine event: {event}")
        # TODO: Implement database storage
        # TODO: Check reminder adherence
    
    async def _handle_habit_event(self, event: dict):
        """Handle habit event - store in DB and update streaks"""
        logger.debug(f"Handling habit event: {event}")
        # TODO: Implement database storage
        # TODO: Update habit streaks and statistics
