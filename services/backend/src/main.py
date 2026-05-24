"""
Backend service entry point

Consumes wellbeing events from Kafka and processes them:
- Stores events in database
- Calculates statistics
- Manages reminders
- Handles data queries
"""

import logging
import asyncio
from dotenv import load_dotenv

from src.config import Config
from src.kafka_consumer import KafkaEventConsumer

# Load environment
load_dotenv()

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


async def main():
    """Main backend service"""
    config = Config.from_env()
    logger.info(f"Starting {config.service_name}...")
    
    # Initialize consumer
    consumer = KafkaEventConsumer(config)
    
    try:
        # Start consuming events
        await consumer.start()
    except KeyboardInterrupt:
        logger.info("Shutting down...")
        await consumer.stop()
    except Exception as e:
        logger.error(f"Fatal error: {e}")
        raise


if __name__ == '__main__':
    asyncio.run(main())
