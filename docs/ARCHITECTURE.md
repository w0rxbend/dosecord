# Architecture Documentation

> Current direction: see [PRODUCTION_PLAN.md](PRODUCTION_PLAN.md) for the flexible Kafka contract, account-linking model, and production implementation roadmap. This document describes the original MVP shape and will be folded into the production plan as the backend grows.

## Dosecord System Architecture

### Overview

Dosecord is a wellbeing tracking platform with a microservices architecture using async message queues for inter-service communication.

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Discord Users                               │
└────────────────────┬────────────────────────────────────────────────┘
                     │ Direct Messages
                     ▼
        ┌────────────────────────────┐
        │   Discord Bot Service      │
        │  (DM Handler)              │
        │                            │
        │ • Command Processing       │
        │ • Mood Tracking           │
        │ • Medicine Logging        │
        │ • Habit Recording         │
        └────────────┬───────────────┘
                     │ Events (Kafka)
                     │
        ┌────────────▼───────────────┐
        │   Kafka Message Queue      │
        │                            │
        │ Topics:                    │
        │ - wellbeing.events        │
        │ - wellbeing.commands      │
        │ - wellbeing.responses     │
        └────────────┬───────────────┘
                     │
         ┌───────────┼───────────┐
         │           │           │
         ▼           ▼           ▼
    ┌─────────┐ ┌─────────┐ ┌──────────────┐
    │ Backend │ │ Stats   │ │ Notification │
    │ Service │ │ Engine  │ │ Service      │
    │         │ │         │ │ (Future)     │
    └────┬────┘ └─────────┘ └──────────────┘
         │
         ▼
    ┌─────────────────┐
    │  PostgreSQL DB  │
    │                 │
    │ • User Data     │
    │ • Events        │
    │ • Statistics    │
    │ • Reminders     │
    └─────────────────┘
```

### Service Details

#### 1. Discord Bot Service (`services/discord-bot/`)

**Responsibilities:**
- Listens for Discord Direct Messages
- Parses user input for wellbeing tracking
- Processes commands (/mood, /medicine, /habit, /stats)
- Publishes events to Kafka
- Provides user-friendly responses

**Technology:**
- discord.py library
- Python 3.10+
- Async/await patterns

**API/Interface:**
- Discord DMs only
- Command prefix: `/`
- Event publishing: Kafka

#### 2. Backend Service (`services/backend/`)

**Responsibilities:**
- Consumes wellbeing events from Kafka
- Stores events in PostgreSQL database
- Calculates user statistics
- Manages reminders
- Provides data query APIs

**Technology:**
- Kafka consumer
- SQLAlchemy ORM
- PostgreSQL database
- Async/await patterns

**Database Schema:**
```sql
-- Users table
CREATE TABLE users (
  id BIGINT PRIMARY KEY,
  discord_username VARCHAR NOT NULL,
  timezone VARCHAR,
  created_at TIMESTAMP,
  preferences JSONB
);

-- Mood events table
CREATE TABLE mood_events (
  id SERIAL PRIMARY KEY,
  user_id BIGINT REFERENCES users(id),
  mood_level INT CHECK(mood_level >= 1 AND mood_level <= 10),
  description TEXT,
  tags VARCHAR[],
  timestamp TIMESTAMP,
  created_at TIMESTAMP
);

-- Medicine events table
CREATE TABLE medicine_events (
  id SERIAL PRIMARY KEY,
  user_id BIGINT REFERENCES users(id),
  medicine_name VARCHAR NOT NULL,
  dosage VARCHAR,
  notes TEXT,
  timestamp TIMESTAMP,
  created_at TIMESTAMP
);

-- Habits table
CREATE TABLE habits (
  id SERIAL PRIMARY KEY,
  user_id BIGINT REFERENCES users(id),
  name VARCHAR NOT NULL,
  frequency VARCHAR,
  created_at TIMESTAMP
);

-- Habit completion events
CREATE TABLE habit_events (
  id SERIAL PRIMARY KEY,
  habit_id INT REFERENCES habits(id),
  user_id BIGINT REFERENCES users(id),
  duration_minutes INT,
  notes TEXT,
  timestamp TIMESTAMP,
  created_at TIMESTAMP
);

-- Statistics (materialized view candidate)
CREATE TABLE user_statistics (
  user_id BIGINT PRIMARY KEY REFERENCES users(id),
  avg_mood FLOAT,
  medicine_adherence_pct FLOAT,
  habit_completion_rate FLOAT,
  last_updated TIMESTAMP
);
```

#### 3. Future Services

- **Stats Engine**: Real-time statistics calculation and aggregation
- **Notification Service**: Send reminders and notifications via Discord
- **API Service**: REST API for web/mobile clients
- **Telegram Bot**: Alternative chat interface
- **Data Export Service**: Export data in various formats
- **Integration Service**: Connect with health tracking apps

### Communication Patterns

#### Event Publishing (Discord Bot → Kafka)

```python
event = MoodEvent(
    user_id=12345,
    mood_level=8,
    mood_description="Had a great day",
    timestamp=datetime.utcnow()
)
producer.publish_event(event)
```

#### Event Consumption (Backend)

```python
consumer.subscribe(['wellbeing.events'])
# Receives events and processes them
# Stores in database
# Updates statistics
```

### Data Flow Example: User Logs Mood

1. User sends DM: "I'm feeling great, about 9/10"
2. Discord Bot parses message
3. Bot creates `MoodEvent(user_id=123, mood_level=9)`
4. Bot publishes to Kafka topic `wellbeing.events`
5. Backend consumer receives event
6. Backend stores event in PostgreSQL
7. Backend updates user statistics
8. Bot sends confirmation: "Great! Recorded your mood."

### Scalability Considerations

- **Horizontal Scaling**: Each service can be scaled independently
- **Kafka Partitioning**: Events partitioned by user_id for ordering
- **Database**: Connection pooling with pgBouncer for high throughput
- **Caching**: Redis layer for frequently accessed statistics (future)
- **Load Balancing**: Multiple bot instances behind load balancer (future)

### Development Setup

```bash
# Start infrastructure
docker-compose -f docker/docker-compose.yml up

# Terminal 1: Run Discord Bot
cd services/discord-bot
poetry install
poetry run python -m src.main

# Terminal 2: Run Backend
cd services/backend
poetry install
poetry run python -m src.main
```

### Monitoring & Logging

- **Logs**: Centralized logging with structured JSON format
- **Kafka UI**: Web interface at http://localhost:8080
- **Database**: Connect with psql or any PostgreSQL client
- **Metrics**: To be integrated (Prometheus/Grafana)
