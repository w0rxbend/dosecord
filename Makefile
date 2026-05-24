# Makefile for Dosecord

.PHONY: help setup up up-apps down logs bot backend backend-init-db outbox test fmt lint clean

help:
	@echo "Dosecord Development Commands"
	@echo "=============================="
	@echo "make setup         - Install dependencies for all services"
	@echo "make up            - Start local infrastructure"
	@echo "make up-apps       - Start infrastructure plus app containers"
	@echo "make down          - Stop all services"
	@echo "make bot           - Start Discord bot service"
	@echo "make backend       - Start backend service"
	@echo "make backend-init-db - Create backend tables for local development"
	@echo "make outbox        - Start backend outbox publisher"
	@echo "make logs          - View Docker logs"
	@echo "make test          - Run tests"
	@echo "make fmt           - Format code"
	@echo "make lint          - Lint code"
	@echo "make clean         - Clean up temporary files"
	@echo "make db-shell      - Connect to database"

setup:
	cd services/discord-bot && poetry install
	cd services/backend && poetry install

up:
	docker-compose -f docker/docker-compose.yml up -d
	@echo "Infrastructure started. Waiting for Kafka..."
	sleep 5
	@echo "Use 'make bot' and 'make backend' in separate terminals"

up-apps:
	docker-compose -f docker/docker-compose.yml --profile apps up -d --build

down:
	docker-compose -f docker/docker-compose.yml down

logs:
	docker-compose -f docker/docker-compose.yml logs -f

bot:
	cd services/discord-bot && PYTHONPATH=../.. poetry run python -m src.main

backend:
	cd services/backend && PYTHONPATH=../.. poetry run python -m src.main

backend-init-db:
	cd services/backend && PYTHONPATH=../.. poetry run python -m src.workers.init_db

outbox:
	cd services/backend && PYTHONPATH=../.. poetry run python -m src.workers.outbox_publisher

test:
	cd services/discord-bot && PYTHONPATH=../.. poetry run pytest
	cd services/backend && PYTHONPATH=../.. poetry run pytest

fmt:
	cd services/discord-bot && poetry run black src/
	cd services/backend && poetry run black src/

lint:
	cd services/discord-bot && poetry run flake8 src/
	cd services/backend && poetry run flake8 src/

clean:
	find . -type d -name __pycache__ -exec rm -rf {} +
	find . -type f -name "*.pyc" -delete
	rm -rf .pytest_cache .coverage htmlcov

db-shell:
	PGPASSWORD=dosecord psql -h localhost -U dosecord -d dosecord_db

kafka-topics:
	docker-compose -f docker/docker-compose.yml exec kafka kafka-topics --bootstrap-server localhost:9092 --list

kafka-console:
	docker-compose -f docker/docker-compose.yml exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic dosecord.commands --from-beginning
