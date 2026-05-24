"""
Command handler for processing Discord bot commands
"""

import logging
from discord.ext import commands

from src.config import Config
from src.kafka_producer import KafkaEventProducer
from shared.contracts import Actor, Platform
from shared.contracts.identity import identity_start_requested
from shared.contracts.wellbeing import (
    habit_checkin_record_requested,
    medication_intake_mark_taken_requested,
    mood_checkin_record_requested,
)

logger = logging.getLogger(__name__)


class CommandHandler:
    """Handles Discord bot commands"""
    
    def __init__(self, bot: commands.Bot, producer: KafkaEventProducer, config: Config):
        self.bot = bot
        self.producer = producer
        self.config = config

    def _actor_from_context(self, ctx: commands.Context) -> Actor:
        """Build a platform-neutral actor reference from Discord metadata."""
        return Actor(
            platform=Platform.DISCORD,
            platform_user_id=str(ctx.author.id),
            platform_username=str(ctx.author),
        )
    
    async def handle_start(self, ctx: commands.Context):
        """Handle /start command"""
        try:
            self.producer.publish(
                identity_start_requested(
                    actor=self._actor_from_context(ctx),
                    source=self.config.service_name,
                    idempotency_key=f"discord:message:{ctx.message.id}",
                )
            )
            embed = self._create_welcome_embed(ctx.author)
            await ctx.send(embed=embed)
        except Exception as e:
            logger.error(f"Error in handle_start: {e}")
            await ctx.send("❌ An error occurred. Please try again.")
    
    async def handle_mood(self, ctx: commands.Context, mood: str):
        """Handle /mood command"""
        try:
            # Parse mood input
            mood_level = self._parse_mood(mood)
            
            if mood_level is None:
                await ctx.send(
                    "📊 Invalid mood. Use a number (1-10) or: "
                    "terrible, bad, okay, good, great"
                )
                return
            
            command = mood_checkin_record_requested(
                actor=self._actor_from_context(ctx),
                source=self.config.service_name,
                mood_level=mood_level,
                note=mood,
                idempotency_key=f"discord:message:{ctx.message.id}",
            )
            
            success = self.producer.publish(command)
            
            if success:
                response = self._get_mood_response(mood_level)
                await ctx.send(response)
            else:
                await ctx.send("❌ Failed to record mood.")
                
        except Exception as e:
            logger.error(f"Error in handle_mood: {e}")
            await ctx.send("❌ An error occurred.")
    
    async def handle_medicine(self, ctx: commands.Context, medicine_name: str):
        """Handle /medicine command"""
        try:
            if not medicine_name:
                await ctx.send("💊 Please specify the medicine name.")
                return
            
            command = medication_intake_mark_taken_requested(
                actor=self._actor_from_context(ctx),
                source=self.config.service_name,
                medication_name=medicine_name,
                idempotency_key=f"discord:message:{ctx.message.id}",
            )
            
            success = self.producer.publish(command)
            
            if success:
                await ctx.send(
                    f"✅ Recorded: {medicine_name} intake\n"
                    f"Stay healthy! 💪"
                )
            else:
                await ctx.send("❌ Failed to record medicine.")
                
        except Exception as e:
            logger.error(f"Error in handle_medicine: {e}")
            await ctx.send("❌ An error occurred.")
    
    async def handle_habit(self, ctx: commands.Context, habit_name: str):
        """Handle /habit command"""
        try:
            if not habit_name:
                await ctx.send("🎯 Please specify the habit name.")
                return
            
            command = habit_checkin_record_requested(
                actor=self._actor_from_context(ctx),
                source=self.config.service_name,
                habit_name=habit_name,
                idempotency_key=f"discord:message:{ctx.message.id}",
            )
            
            success = self.producer.publish(command)
            
            if success:
                await ctx.send(
                    f"🎉 Logged: {habit_name}\n"
                    f"Great work! Keep it up! 🌟"
                )
            else:
                await ctx.send("❌ Failed to record habit.")
                
        except Exception as e:
            logger.error(f"Error in handle_habit: {e}")
            await ctx.send("❌ An error occurred.")
    
    async def handle_stats(self, ctx: commands.Context):
        """Handle /stats command"""
        try:
            await ctx.send(
                "📈 **Your Statistics**\n\n"
                "Stats feature is under development!\n"
                "The backend will calculate and show your:\n"
                "• Mood trends\n"
                "• Medicine adherence\n"
                "• Habit streaks\n"
                "• Detailed analytics\n\n"
                "Check back soon! 🚀"
            )
        except Exception as e:
            logger.error(f"Error in handle_stats: {e}")
            await ctx.send("❌ An error occurred.")
    
    async def handle_help(self, ctx: commands.Context):
        """Handle /help command"""
        try:
            embed = self._create_help_embed()
            await ctx.send(embed=embed)
        except Exception as e:
            logger.error(f"Error in handle_help: {e}")
            await ctx.send("❌ An error occurred.")
    
    def _parse_mood(self, mood: str) -> int | None:
        """Parse mood input"""
        try:
            # Try as number
            level = int(mood)
            if 1 <= level <= 10:
                return level
        except ValueError:
            pass
        
        # Try as keyword
        mood_map = {
            'terrible': 1, 'bad': 2, 'awful': 1,
            'okay': 5, 'fine': 5,
            'good': 8, 'great': 10, 'amazing': 10
        }
        
        return mood_map.get(mood.lower())
    
    def _get_mood_response(self, mood_level: int) -> str:
        """Get response based on mood level"""
        if mood_level <= 2:
            return "😔 I'm sorry you're struggling. Remember, better days are ahead. 💙"
        elif mood_level <= 4:
            return "😐 Take care of yourself today. You deserve some self-compassion. 💙"
        elif mood_level <= 6:
            return "😊 Thanks for sharing! Let's make today a bit brighter. 💚"
        elif mood_level <= 8:
            return "😄 That's great! Keep this positive momentum going! 💛"
        else:
            return "🎉 Absolutely wonderful! You're amazing! Keep shining! ✨"
    
    def _create_welcome_embed(self, user) -> object:
        """Create welcome embed"""
        import discord
        embed = discord.Embed(
            title="🌟 Welcome to Dosecord",
            description="Your personal wellbeing tracking companion",
            color=discord.Color.green()
        )
        embed.add_field(
            name="📊 Track Your Wellbeing",
            value="Monitor your mood, medicine intake, and habits",
            inline=False
        )
        embed.add_field(
            name="📈 View Statistics",
            value="Get insights into your wellbeing patterns",
            inline=False
        )
        embed.add_field(
            name="🔔 Get Reminders",
            value="Never miss your medicine or habits",
            inline=False
        )
        embed.add_field(
            name="Available Commands",
            value=(
                "`/mood <1-10>` - Log your mood\n"
                "`/medicine <name>` - Log medicine intake\n"
                "`/habit <name>` - Log a habit\n"
                "`/stats` - View your statistics\n"
                "`/help` - Show this message"
            ),
            inline=False
        )
        return embed
    
    def _create_help_embed(self) -> object:
        """Create help embed"""
        import discord
        embed = discord.Embed(
            title="💙 Dosecord Help",
            description="How to use Dosecord",
            color=discord.Color.blue()
        )
        embed.add_field(
            name="/start",
            value="Get started with Dosecord",
            inline=False
        )
        embed.add_field(
            name="/mood <1-10>",
            value="Log your mood on a scale of 1-10",
            inline=False
        )
        embed.add_field(
            name="/medicine <name>",
            value="Log your medicine intake",
            inline=False
        )
        embed.add_field(
            name="/habit <name>",
            value="Log completed habits",
            inline=False
        )
        embed.add_field(
            name="/stats",
            value="View your wellbeing statistics",
            inline=False
        )
        return embed
