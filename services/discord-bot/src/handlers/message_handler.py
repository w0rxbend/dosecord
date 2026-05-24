"""
Message handler for processing Discord messages
"""

import logging
import re
from discord import Message
from discord.ext import commands

from src.config import Config
from src.kafka_producer import KafkaEventProducer
from shared.contracts import Actor, Platform
from shared.contracts.wellbeing import (
    habit_checkin_record_requested,
    medication_intake_mark_taken_requested,
    mood_checkin_record_requested,
)

logger = logging.getLogger(__name__)


class MessageHandler:
    """Handles incoming Discord messages and routes them appropriately"""
    
    def __init__(self, producer: KafkaEventProducer, config: Config):
        self.producer = producer
        self.config = config

    def _actor_from_message(self, message: Message) -> Actor:
        """Build a platform-neutral actor reference from Discord metadata."""
        return Actor(
            platform=Platform.DISCORD,
            platform_user_id=str(message.author.id),
            platform_username=str(message.author),
        )
    
    async def handle(self, message: Message):
        """
        Handle incoming message
        
        Args:
            message: Discord message object
        """
        content = message.content.lower().strip()
        
        # Skip empty messages
        if not content:
            return
        
        # Route based on message patterns
        if self._is_mood_message(content):
            await self._handle_mood_message(message)
        elif self._is_medicine_message(content):
            await self._handle_medicine_message(message)
        elif self._is_habit_message(content):
            await self._handle_habit_message(message)
        else:
            await message.reply(
                "I can track mood, medicine, and habits in DMs. "
                "Try `I feel good`, `I took vitamin D`, or `/help`."
            )
    
    def _is_mood_message(self, content: str) -> bool:
        """Check if message is about mood"""
        mood_keywords = ['mood', 'feeling', 'how are you', 'feel', 'feeling like']
        return any(keyword in content for keyword in mood_keywords)
    
    def _is_medicine_message(self, content: str) -> bool:
        """Check if message is about medicine"""
        medicine_keywords = ['medicine', 'medication', 'pill', 'drug', 'took', 'dosage']
        return any(keyword in content for keyword in medicine_keywords)
    
    def _is_habit_message(self, content: str) -> bool:
        """Check if message is about habits"""
        habit_keywords = ['habit', 'did', 'exercised', 'meditated', 'read', 'studied']
        return any(keyword in content for keyword in habit_keywords)
    
    async def _handle_mood_message(self, message: Message):
        """Handle mood tracking message"""
        try:
            # Extract mood from message
            mood_level = self._extract_mood_level(message.content)
            
            if mood_level is None:
                await message.reply(
                    "📊 I couldn't quite understand your mood. "
                    "Please tell me on a scale of 1-10, or use: great, good, okay, bad, terrible"
                )
                return
            
            command = mood_checkin_record_requested(
                actor=self._actor_from_message(message),
                source=self.config.service_name,
                mood_level=mood_level,
                note=message.content,
                idempotency_key=f"discord:message:{message.id}",
            )
            
            success = self.producer.publish(command)
            
            if success:
                response = self._get_mood_response(mood_level)
                await message.reply(response)
            else:
                await message.reply("❌ Failed to record your mood. Please try again.")
                
        except Exception as e:
            logger.error(f"Error handling mood message: {e}")
            await message.reply("❌ An error occurred. Please try again later.")
    
    async def _handle_medicine_message(self, message: Message):
        """Handle medicine tracking message"""
        try:
            # Extract medicine name from message
            medicine_name = self._extract_medicine_name(message.content)
            
            if not medicine_name:
                await message.reply(
                    "💊 Please tell me which medicine you took. "
                    "Example: 'I took aspirin' or 'took my vitamin D'"
                )
                return
            
            command = medication_intake_mark_taken_requested(
                actor=self._actor_from_message(message),
                source=self.config.service_name,
                medicine_name=medicine_name,
                note=message.content,
                idempotency_key=f"discord:message:{message.id}",
            )
            
            success = self.producer.publish(command)
            
            if success:
                await message.reply(
                    f"✅ Got it! Recorded that you took {medicine_name}.\n"
                    f"Stay healthy! 💪"
                )
            else:
                await message.reply("❌ Failed to record medicine intake. Please try again.")
                
        except Exception as e:
            logger.error(f"Error handling medicine message: {e}")
            await message.reply("❌ An error occurred. Please try again later.")
    
    async def _handle_habit_message(self, message: Message):
        """Handle habit tracking message"""
        try:
            # Extract habit from message
            habit_name = self._extract_habit_name(message.content)
            
            if not habit_name:
                await message.reply(
                    "🎯 Please tell me which habit you completed. "
                    "Example: 'I meditated' or 'read for 30 minutes'"
                )
                return
            
            command = habit_checkin_record_requested(
                actor=self._actor_from_message(message),
                source=self.config.service_name,
                habit_name=habit_name,
                note=message.content,
                idempotency_key=f"discord:message:{message.id}",
            )
            
            success = self.producer.publish(command)
            
            if success:
                await message.reply(
                    f"🎉 Awesome! Recorded your {habit_name}.\n"
                    f"Keep up the great work! 🌟"
                )
            else:
                await message.reply("❌ Failed to record habit. Please try again.")
                
        except Exception as e:
            logger.error(f"Error handling habit message: {e}")
            await message.reply("❌ An error occurred. Please try again later.")
    
    def _extract_mood_level(self, content: str) -> int | None:
        """Extract mood level from message"""
        # Try to extract number
        numbers = re.findall(r'\d+', content)
        if numbers:
            level = int(numbers[0])
            if 1 <= level <= 10:
                return level
        
        # Try to match keywords
        mood_mapping = {
            'terrible': 1,
            'bad': 2,
            'awful': 1,
            'okay': 5,
            'fine': 5,
            'good': 8,
            'great': 10,
            'amazing': 10,
            'wonderful': 9,
        }
        
        for keyword, level in mood_mapping.items():
            if keyword in content.lower():
                return level
        
        return None
    
    def _extract_medicine_name(self, content: str) -> str | None:
        """Extract medicine name from message"""
        # Simple extraction - look for medicine keywords
        medicines = ['aspirin', 'vitamin', 'ibuprofen', 'paracetamol', 'antibiotic']
        for medicine in medicines:
            if medicine in content.lower():
                return medicine.capitalize()
        
        # Try to extract after 'took' or 'medicine'
        match = re.search(r'(?:took|medicine|medication|pill)\s+(?:my\s+)?([a-zA-Z]+)', content)
        if match:
            return match.group(1).capitalize()
        
        return None
    
    def _extract_habit_name(self, content: str) -> str | None:
        """Extract habit name from message"""
        habits = ['meditated', 'exercised', 'read', 'studied', 'journaled', 'yoga']
        for habit in habits:
            if habit in content.lower():
                return habit
        
        return None
    
    def _get_mood_response(self, mood_level: int) -> str:
        """Get response based on mood level"""
        if mood_level <= 2:
            return (
                "😔 I'm sorry you're feeling this way. "
                "Remember, it's okay to have tough days. "
                "Is there anything that might help? 💙"
            )
        elif mood_level <= 4:
            return (
                "😐 I see you're having a rough time. "
                "Consider taking a break or doing something you enjoy. "
                "I'm here if you need support. 💙"
            )
        elif mood_level <= 6:
            return (
                "😊 That's a neutral feeling. "
                "Maybe do something nice for yourself today! "
                "I'm rooting for you! 💚"
            )
        elif mood_level <= 8:
            return (
                "😄 Great! I'm happy you're doing well! "
                "Let's keep this positive energy going! 💛"
            )
        else:
            return (
                "🎉 Awesome! You're feeling amazing! "
                "That's wonderful to hear! Keep shining! ✨"
            )
