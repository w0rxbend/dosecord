# Development Guide

## Getting Started

### Prerequisites

- Python 3.10+
- Docker & Docker Compose
- Poetry (Python package manager)
- Git

### Initial Setup

1. **Clone/Navigate to project**
   ```bash
   cd ~/Workspace/dosecord
   ```

2. **Start infrastructure**
   ```bash
   docker-compose -f docker/docker-compose.yml up
   ```
   This starts:
   - Kafka & Zookeeper
   - PostgreSQL database
   - Kafka UI (http://localhost:8080)

   To run the application services as containers too:
   ```bash
   DISCORD_TOKEN=your_token_here docker-compose -f docker/docker-compose.yml --profile apps up --build
   ```

3. **Get Discord Bot Token**
   - Create application at https://discord.com/developers/applications
   - Create bot in the application
   - Copy token and add to `.env`

### Running Services

#### Discord Bot

```bash
cd services/discord-bot

# Install dependencies
poetry install

# Copy environment
cp .env.example .env

# Add your DISCORD_TOKEN to .env

# Run bot
PYTHONPATH=../.. poetry run python -m src.main
```

#### Backend Service

```bash
cd services/backend

# Install dependencies
poetry install

# Copy environment
cp .env.example .env

# Run backend (in another terminal)
PYTHONPATH=../.. poetry run python -m src.main
```

## Project Structure

```
services/discord-bot/
├── src/
│   ├── main.py              # Bot entry point
│   ├── config.py            # Configuration management
│   ├── kafka_producer.py    # Event publishing
│   ├── models.py            # Kafka event models
│   └── handlers/
│       ├── message_handler.py
│       └── command_handler.py
└── pyproject.toml

services/backend/
├── src/
│   ├── main.py              # Backend entry point
│   ├── config.py            # Configuration
│   ├── kafka_consumer.py    # Event consumption
│   └── models.py            # DB models (TBD)
└── pyproject.toml

docker/
├── docker-compose.yml       # Local dev infrastructure
├── Dockerfile.discord-bot   # Bot image
└── Dockerfile.backend       # Backend image

shared/
└── __init__.py             # Shared utilities (expand as needed)

docs/
├── ARCHITECTURE.md         # System design
└── DEVELOPMENT.md          # This file
```

## Adding Features

### Adding a New Bot Command

1. **Define in `command_handler.py`:**
   ```python
   async def handle_new_feature(self, ctx: commands.Context, param: str):
       """Handle new feature"""
       # Implement logic
   ```

2. **Register in `main.py`:**
   ```python
   @commands.command(name='feature')
   async def new_feature(self, ctx: commands.Context, param: str):
       await self.command_handler.handle_new_feature(ctx, param)
   ```

3. **Create event model in `models.py`:**
   ```python
   class NewFeatureEvent(WellbeingEvent):
       event_type: str = "new_feature"
       # ... fields
   ```

### Adding a Backend Handler

1. **Create handler in `services/backend/src/handlers/`:**
   ```python
   async def _handle_new_feature_event(self, event: dict):
       # Process event
       # Store in database
       # Update statistics
   ```

2. **Call from consumer in `kafka_consumer.py`:**
   ```python
   elif event_type == 'new_feature':
       await self._handle_new_feature_event(event_data)
   ```

## Testing

```bash
# Run tests
poetry run pytest

# Run with coverage
poetry run pytest --cov=src

# Run specific test
poetry run pytest tests/test_handlers.py
```

## Code Quality

```bash
# Format code
poetry run black src/

# Lint
poetry run flake8 src/

# Type check
poetry run mypy src/
```

## Environment Variables

### Discord Bot (.env)

```env
DISCORD_TOKEN=your_token_here
KAFKA_BROKERS=localhost:9092
KAFKA_TOPIC_EVENTS=dosecord.commands
LOG_LEVEL=INFO
DEBUG=false
```

### Backend (.env)

```env
KAFKA_BROKERS=localhost:9092
KAFKA_CONSUMER_GROUP=dosecord-backend
KAFKA_TOPIC_EVENTS=dosecord.commands
DATABASE_URL=postgresql://dosecord:dosecord@localhost:5432/dosecord_db
LOG_LEVEL=INFO
DEBUG=false
```

## Common Issues

### Kafka Connection Failed
- Ensure Docker containers are running: `docker-compose ps`
- Check Kafka is healthy: `curl localhost:9092`

### Bot Not Responding
- Verify Discord token is correct
- Check bot has DM permissions
- Look at logs with `LOG_LEVEL=DEBUG`

### Database Connection Failed
- Verify PostgreSQL is running
- Check DATABASE_URL format
- Ensure database exists: `psql -U dosecord -d dosecord_db`

## Contributing

1. Create feature branch: `git checkout -b feature/new-feature`
2. Make changes and commit: `git commit -m "Add new feature"`
3. Push branch: `git push origin feature/new-feature`
4. Create Pull Request

## Next Steps

- [ ] Implement database models and migrations
- [ ] Add authentication and authorization
- [ ] Implement statistics calculation
- [ ] Add reminder scheduling
- [ ] Create REST API for frontend
- [ ] Add comprehensive tests
- [ ] Set up CI/CD pipeline
- [ ] Deploy to production
