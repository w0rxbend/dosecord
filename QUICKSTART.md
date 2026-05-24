# Quick Start Guide

## 🚀 Get Started in 5 Minutes

### 1. Prerequisites
```bash
# Check Python version
python --version  # Should be 3.10+

# Install Poetry
curl -sSL https://install.python-poetry.org | python3 -
```

### 2. Setup Infrastructure
```bash
cd ~/Workspace/dosecord

# Start Kafka, PostgreSQL, and other services
docker-compose -f docker/docker-compose.yml up -d

# Wait for services to start (about 10 seconds)
sleep 10
```

To run the bot and backend in Docker too:
```bash
DISCORD_TOKEN=your_token_here docker-compose -f docker/docker-compose.yml --profile apps up --build
```

### 3. Get Discord Bot Token
1. Go to https://discord.com/developers/applications
2. Click "New Application"
3. Go to "Bot" section → "Add Bot"
4. Copy the token

### 4. Setup Discord Bot
```bash
cd services/discord-bot

# Install dependencies
poetry install

# Create .env file
cp .env.example .env

# Edit .env and add your Discord token
DISCORD_TOKEN=your_token_here

# Run the bot
PYTHONPATH=../.. poetry run python -m src.main
```

### 5. Setup Backend (in another terminal)
```bash
cd services/backend

# Install dependencies
poetry install

# Create .env file (use defaults if running locally)
cp .env.example .env

# Run backend
PYTHONPATH=../.. poetry run python -m src.main
```

### 6. Test the Bot
1. Open Discord and go to your bot's DMs
2. Try these commands:
   - `/start` - Get welcome message
   - `/mood 8` - Log your mood
   - `/medicine aspirin` - Log medicine
   - `/habit meditation` - Log habit
   - `/help` - Show help

### 7. View Kafka Events (Optional)
Visit http://localhost:8080 to see Kafka UI and monitor events being published.

---

## 📁 Project Structure at a Glance

```
dosecord/
├── services/
│   ├── discord-bot/     ← Discord bot handles DMs
│   └── backend/         ← Processes events & stores data
├── docker/              ← Infrastructure setup
├── docs/                ← Documentation
└── Makefile            ← Helpful commands
```

---

## 🔧 Useful Commands

```bash
# View all logs
make logs

# Format code
make fmt

# Run tests
make test

# Connect to database
make db-shell

# View Kafka topics
make kafka-topics

# View events in real-time
make kafka-console
```

---

## 🐛 Troubleshooting

**Bot not responding?**
```bash
# Check bot is running and logs look good
# Verify Discord token is correct
# Make sure bot has DM permissions
```

**Can't connect to Kafka?**
```bash
# Check Docker is running
docker ps

# Check Kafka container is healthy
docker-compose -f docker/docker-compose.yml ps
```

**Database connection error?**
```bash
# Verify PostgreSQL is running
docker-compose -f docker/docker-compose.yml ps postgres

# Check connection
make db-shell
```

---

## 📚 Next Steps

- Read [ARCHITECTURE.md](docs/ARCHITECTURE.md) for system design
- Check [API.md](docs/API.md) for command reference
- See [DEVELOPMENT.md](docs/DEVELOPMENT.md) for advanced setup

---

## 💡 Tips

- Use `LOG_LEVEL=DEBUG` in .env for detailed logs
- Kafka UI at http://localhost:8080 shows all events in real-time
- Both services can be run in separate terminals for easier debugging
- Always start with `docker-compose up` first to have infrastructure ready
