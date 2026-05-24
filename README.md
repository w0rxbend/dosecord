# Dosecord - Wellbeing Tracking Discord Bot

A comprehensive Discord bot for personal wellbeing tracking, including mood tracking, medicine intake monitoring, habit management, and extended statistics.

## Project Architecture

This is a **monorepo** supporting multiple services communicating via Kafka:

```
dosecord/
├── services/
│   ├── discord-bot/        # Discord frontend service (handles DMs)
│   ├── backend/            # Business logic & data persistence
│   └── [future-services]/  # Telegram bot, API service, etc.
├── shared/                 # Shared libraries & protocols
├── docker/                 # Docker & container configs
└── docs/                   # Project documentation
```

## Features

- **Mood Tracking**: Daily mood/wellbeing monitoring
- **Medicine Tracking**: Track medication intake with reminders
- **Habit Management**: Create and track personal habits
- **Extended Statistics**: Detailed analytics on wellbeing data
- **Data Exchange**: Share wellbeing insights with other users
- **Multi-Client Support**: Discord, Telegram, and API integrations (future)
- **Rich Presence**: Status integration with Discord activities

## Services

### Discord Bot Service
- Handles all Discord DM interactions
- User command processing
- Message-based UI for bot interactions
- Publishes events to Kafka for backend processing

### Backend Service
- Business logic implementation
- Data persistence
- Statistics calculation
- Integration with external APIs
- Handles messages from Kafka queue

## Technology Stack

- **Language**: Python 3.10+
- **Discord Library**: discord.py
- **Message Queue**: Kafka
- **Database**: PostgreSQL (planned)
- **Container**: Docker & Docker Compose

## Quick Start

### Prerequisites
- Python 3.10+
- Docker & Docker Compose
- Poetry (Python dependency management)

### Setup Discord Bot

```bash
cd services/discord-bot
poetry install
cp .env.example .env
# Add your Discord bot token to .env
poetry run python -m src.main
```

### Environment Variables

```env
DISCORD_TOKEN=your_bot_token_here
KAFKA_BROKERS=localhost:9092
LOG_LEVEL=INFO
```

## Development

### Project Structure
- `services/discord-bot/src/` - Discord bot source code
- `services/backend/src/` - Backend service source code
- `shared/` - Shared utilities, models, and Kafka producers/consumers
- `docker/` - Docker Compose files for local development

### Running Locally

```bash
# Start local infrastructure only
docker-compose -f docker/docker-compose.yml up

# Or start infrastructure plus app containers
DISCORD_TOKEN=your_bot_token docker-compose -f docker/docker-compose.yml --profile apps up --build

# Or run individual services
cd services/discord-bot && PYTHONPATH=../.. poetry run python -m src.main
```

## Contributing

1. Each service is independently versioned
2. Shared code goes in `shared/`
3. Follow PEP 8 style guidelines
4. Write tests for all features

## License

TBD
