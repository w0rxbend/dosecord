"""
Main Discord Bot Application

This is the entry point for the Discord bot service. The bot handles:
- Direct Messages from users
- Wellbeing tracking commands
- Medicine reminder interactions
- Event publishing to Kafka
"""

import os
import logging
from dotenv import load_dotenv
import discord
from discord.ext import commands

from src.config import Config
from src.handlers.message_handler import MessageHandler
from src.handlers.command_handler import CommandHandler
from src.kafka_producer import KafkaEventProducer

# Load environment variables
load_dotenv()

# Configure logging
logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


class DosecordBot(commands.Cog):
    """Main bot cog for handling wellbeing tracking"""
    
    def __init__(self, bot: commands.Bot, config: Config):
        self.bot = bot
        self.config = config
        self.kafka_producer = KafkaEventProducer(config)
        self.message_handler = MessageHandler(self.kafka_producer, config)
        self.command_handler = CommandHandler(bot, self.kafka_producer, config)
        logger.info("Dosecord Bot initialized")
    
    @commands.Cog.listener()
    async def on_ready(self):
        """Called when bot is ready"""
        logger.info(f"Bot logged in as {self.bot.user}")
        await self.bot.change_presence(
            activity=discord.Activity(
                type=discord.ActivityType.watching,
                name="your wellbeing 💚"
            )
        )
    
    @commands.Cog.listener()
    async def on_message(self, message: discord.Message):
        """Handle incoming messages - only DMs allowed"""
        # Ignore bot messages
        if message.author == self.bot.user:
            return
        
        # Only process DMs
        if not isinstance(message.channel, discord.DMChannel):
            await message.reply(
                "❌ This bot only works in Direct Messages. "
                "Please DM me directly to get started!"
            )
            return

        if message.content.startswith(tuple(self.bot.command_prefix)):
            await self.bot.process_commands(message)
            return

        await self.message_handler.handle(message)

    @commands.Cog.listener()
    async def on_command_error(self, ctx: commands.Context, error: commands.CommandError):
        """Return compact command errors in DMs."""
        if isinstance(error, commands.MissingRequiredArgument):
            await ctx.send("I need one more detail for that command. Try `/help` for examples.")
            return
        if isinstance(error, commands.CommandNotFound):
            await ctx.send("I don't know that command yet. Try `/help`.")
            return
        logger.error(
            "Command failed: %s",
            error,
            exc_info=(type(error), error, error.__traceback__),
        )
        await ctx.send("Something went wrong while handling that command. Please try again.")
    
    @commands.command(name='start')
    async def start_tracking(self, ctx: commands.Context):
        """Start wellbeing tracking"""
        await self.command_handler.handle_start(ctx)
    
    @commands.command(name='mood')
    async def log_mood(self, ctx: commands.Context, *, mood: str):
        """Log your current mood"""
        await self.command_handler.handle_mood(ctx, mood)
    
    @commands.command(name='medicine')
    async def log_medicine(self, ctx: commands.Context, *, medicine_name: str):
        """Log medicine intake"""
        await self.command_handler.handle_medicine(ctx, medicine_name)
    
    @commands.command(name='habit')
    async def log_habit(self, ctx: commands.Context, *, habit_name: str):
        """Log a habit completion"""
        await self.command_handler.handle_habit(ctx, habit_name)
    
    @commands.command(name='stats')
    async def show_statistics(self, ctx: commands.Context):
        """Show your wellbeing statistics"""
        await self.command_handler.handle_stats(ctx)
    
    @commands.command(name='help')
    async def show_help(self, ctx: commands.Context):
        """Show help message"""
        await self.command_handler.handle_help(ctx)


async def main():
    """Main function to start the bot"""
    config = Config.from_env()
    
    # Create bot with command prefix
    intents = discord.Intents.default()
    intents.message_content = True
    
    bot = commands.Bot(command_prefix='/', intents=intents, help_command=None)
    
    # Add cog
    await bot.add_cog(DosecordBot(bot, config))
    
    # Connect to Discord
    logger.info("Starting Discord bot...")
    try:
        await bot.start(config.discord_token)
    except Exception as e:
        logger.error(f"Failed to start bot: {e}")
        raise


if __name__ == '__main__':
    import asyncio
    asyncio.run(main())
