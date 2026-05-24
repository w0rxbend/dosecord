# API Documentation (Planned)

## Discord Bot Commands

### Mood Tracking

**Command:** `/mood <level>`

**Parameters:**
- `level` (required): 1-10 scale or keywords (terrible, bad, okay, good, great)

**Example:**
```
/mood 8
/mood great
```

**Response:**
- Confirmation message
- Mood stored in database
- Statistics updated

---

### Medicine Tracking

**Command:** `/medicine <name>`

**Parameters:**
- `name` (required): Medicine/medication name

**Example:**
```
/medicine aspirin
/medicine vitamin-d
```

**Response:**
- Confirmation message
- Medicine logged with timestamp
- Adherence statistics updated

---

### Habit Tracking

**Command:** `/habit <name>`

**Parameters:**
- `name` (required): Habit name

**Example:**
```
/habit meditation
/habit running
```

**Response:**
- Confirmation message
- Habit logged
- Streak counter updated

---

### Statistics

**Command:** `/stats`

**Response:**
- Mood trends (7-day, 30-day)
- Medicine adherence percentage
- Habit completion rates
- Streak information

---

### Help

**Command:** `/help`

**Response:**
- Command list
- Usage examples
- Feature overview

---

## Kafka Event Schema

### MoodEvent

```json
{
  "user_id": 123456789,
  "event_type": "mood",
  "mood_level": 8,
  "mood_description": "Had a great day",
  "tags": ["positive", "work"],
  "timestamp": "2024-05-24T10:30:00Z",
  "data": {}
}
```

### MedicineEvent

```json
{
  "user_id": 123456789,
  "event_type": "medicine",
  "medicine_name": "Aspirin",
  "dosage": "500mg",
  "notes": "Took after breakfast",
  "timestamp": "2024-05-24T08:00:00Z",
  "data": {}
}
```

### HabitEvent

```json
{
  "user_id": 123456789,
  "event_type": "habit",
  "habit_name": "meditation",
  "duration_minutes": 20,
  "notes": "Morning session",
  "timestamp": "2024-05-24T07:00:00Z",
  "data": {}
}
```

---

## Backend REST API (Future)

### Get User Statistics

**Endpoint:** `GET /api/v1/users/{user_id}/stats`

**Response:**
```json
{
  "user_id": 123456789,
  "mood_stats": {
    "avg_7d": 7.2,
    "avg_30d": 6.8,
    "current_trend": "improving"
  },
  "medicine_adherence": {
    "percentage": 95,
    "missed_doses": 1,
    "total_tracked": 20
  },
  "habits": {
    "active_count": 5,
    "avg_completion_rate": 0.82,
    "current_streaks": [
      {"name": "meditation", "days": 14},
      {"name": "exercise", "days": 7}
    ]
  }
}
```

### Get Mood History

**Endpoint:** `GET /api/v1/users/{user_id}/mood?days=30`

**Response:**
```json
{
  "entries": [
    {
      "date": "2024-05-24",
      "mood_level": 8,
      "tags": ["great", "productive"]
    },
    {
      "date": "2024-05-23",
      "mood_level": 7,
      "tags": ["good"]
    }
  ]
}
```

### Get Medicine Schedule

**Endpoint:** `GET /api/v1/users/{user_id}/medicine/schedule`

**Response:**
```json
{
  "medicines": [
    {
      "name": "Aspirin",
      "dosage": "500mg",
      "frequency": "daily",
      "time": "09:00",
      "last_taken": "2024-05-24T09:15:00Z"
    }
  ]
}
```

---

## Error Handling

### Common Error Responses

**400 Bad Request**
```json
{
  "error": "Invalid mood level",
  "message": "Mood level must be between 1 and 10"
}
```

**401 Unauthorized**
```json
{
  "error": "Authentication required",
  "message": "Please log in to continue"
}
```

**429 Too Many Requests**
```json
{
  "error": "Rate limited",
  "message": "Too many requests. Please try again later."
}
```

---

## Rate Limiting

- **Default:** 10 requests per minute per user
- **Burst:** 30 requests per minute
- **Daily limit:** 1000 requests per user

---

## Authentication (Planned)

- Discord OAuth2 for web clients
- API tokens for third-party integrations
- JWT bearer tokens for requests

