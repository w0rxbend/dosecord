# Analytics in chat: walk transcripts, 2026-11-27

Status: verification record of the branch `feat/chat-analytics` (analytics track). It is evidence, not a requirement:
[BOT_UX.md](../BOT_UX.md) section 12.1 (the morning summary) and section 12.5 (`/stats meds`),
[VISUALS_AND_ANALYTICS.md](../VISUALS_AND_ANALYTICS.md) sections 8.5 and 10 and
[PRODUCT_SPEC.md](../PRODUCT_SPEC.md) R-INS-12 record the supervisor decisions of 2026-11-27. No live message was sent;
everything ran on the fake wire.

## 1. Method

The walk of [the new-user review](new-user-review-2026-11-27.md) section 1, `app/test/src/dosegram/app/NewUserWalkSuite.scala`,
gained three steps: after `/reminders` the user turns the morning summary on at 08:00 (`Morning summary`, `Every day
at 08:00`, `Turn on`), then sends `/stats meds` and taps "Show as image"; after the day ends at 22:00 the stepped clock
runs on to Tuesday 08:05. The production assembly, the Postgres 18 container migrated to head (V25), and the
rasteriser that returns placeholder bytes for every card are those of the review, so images reach the wire as photos
with their captions. The test asserts that the summary arrives once, on Tuesday after 08:00, after the daily card,
with the doses, water, mood and sleep lines and "Stop these"; that `/stats meds` names both medications in registry
order and sends the strip; and every invariant of the review (nothing quarantined or dead, every image captioned, no
English button in the Ukrainian walk).

Rerun it with `WALK_OUT=<dir> ./mill app.test.testOnly dosegram.app.NewUserWalkSuite`. The sections below are the new
steps of each transcript; the steps before them are those of the review, section 6. The send times are those of the
stepped clock: the prompt loop draws the summary's card on its own transaction, so on a loaded machine the summary
reaches the wire a few minutes of clock time after its 08:00 slot.

Observed and left as they are:

- The strip's caption counts the open 20:00 dose of Magnesium as scheduled ("0 of 2 scheduled doses recorded as
  taken, 1 skipped, 1 still open"), while the text above it counts answered doses only ("0 of 1 taken · skipped 1 · 1
  still to answer, not counted yet"). Both state what is recorded; the image words it as scheduled doses, as the daily
  card does.
- The sleep line of the summary says "your check-ins" for a typed night, the same wording as `/stats` (finding 17 of
  the review).
- The daily card in the summary has no sleep line: the walk set no sleep goal, and the card draws sleep only against
  the user's own goal (VISUALS section 4.7); the text has the night.

## 2. English

### The morning summary turned on, and `/stats meds`

```text
-- The morning summary: on from /reminders, every day at 08:00
Mon 13:46:31 you tap: [Morning summary] on #43
Mon 13:46:38 bot edits #43:
      | Morning summary: yesterday in one message, with its card when images are on. Pick a time; you see the next days before it starts. It is never sent in quiet hours and counts toward your daily reminder budget.
      [Every day at 07:00]
      [Every day at 08:00]
      [Every day at 09:00]
      [Every day at 10:00]
      [Back]
Mon 13:46:38 you tap: [Every day at 08:00] on #43
Mon 13:46:45 bot edits #43:
      | Every day at 08:00. Next: Tue 08:00, Wed 08:00, Thu 08:00, Fri 08:00, Sat 08:00.
      [Turn on] [Back]
Mon 13:46:45 you tap: [Turn on] on #43
Mon 13:46:52 bot edits #43:
      | Every day at 08:00 is on.
      |
      | Reminders
      |
      | Water: Work hours, every 30 min, next Mon 14:00
      |
      | Mood: off
      |
      | Bedtime: off
      |
      | Morning: off
      |
      | Meals: off
      |
      | Weekly summary: off
      |
      | Monthly summary: off
      |
      | Morning summary: Every day at 08:00, next Tue 08:00
      |
      | Medication: 2 schedules, set in /meds.
      |
      | Work hours Mon to Fri 09:00-18:00. Quiet hours 22:00-07:00.
      [Water] [Mood] [Bedtime] [Morning]
      [Meals] [Weekly summary] [Monthly summary] [Morning summary]
      [Pause 1 h] [Pause 3 h]
      [Day and hours] [Counts in reports: on]

-- /stats meds: each medication over 7 days, and its strip
Mon 13:46:52 you: /stats meds
Mon 13:46:59 bot #44:
      | By medication · last 7 days to 21 Sep
      |
      | 1. Vitamin D: 1 of 1 taken
      |
      | 2. Magnesium: 0 of 1 taken · skipped 1 · 1 still to answer, not counted yet
      |
      | Counts of what you recorded. In the image the rows are numbered in the same order, without names.
      [7 days] [14 days]
      [Show as image]
Mon 13:46:59 you tap: [Show as image] on #44
Mon 13:47:12 bot #45:
      | [image: 15 to 21 Sep. Item 1: 1 of 1 scheduled doses recorded as taken. Item 2: 0 of 2 scheduled doses recorded as taken, 1 skipped, 1 still open.]
```

### The next morning

```text
-- The next morning: the summary of the first day at 08:00
Tue 08:01:27 bot #51 [image], caption:
      | Mon 21 Sep. Mood: Pleasant. Medication: 1 of 3 scheduled doses recorded as taken, 1 skipped, 1 not confirmed. Water: 0.25 of 2.0 l. Food: 1 of 3 meals logged.
Tue 08:01:27 bot #52:
      | Yesterday, Mon 21 Sep
      |
      | Doses: 1 of 3 taken · skipped 1 · not confirmed 1
      |
      | Water: 0.25 of 2.0 l (12%)
      |
      | Mood: 4.0 (pleasant) · 1 check-in
      |
      | Sleep: 7 h 40 min (your check-ins)
      |
      | Food: 1 of 3 planned meals logged
      [Stop these]
```


## 3. Ukrainian

### The morning summary turned on, and `/stats meds`

```text
-- The morning summary: on from /reminders, every day at 08:00
Mon 13:46:21 you tap: [Ранковий підсумок] on #43
Mon 13:46:28 bot edits #43:
      | Ранковий підсумок: учорашній день одним повідомленням, із карткою, якщо зображення ввімкнено. Виберіть час — найближчі дні ви побачите, перш ніж він запрацює. Він не надходить у тихі години й входить у ваш денний ліміт нагадувань.
      [Щодня о 07:00]
      [Щодня о 08:00]
      [Щодня о 09:00]
      [Щодня о 10:00]
      [Назад]
Mon 13:46:28 you tap: [Щодня о 08:00] on #43
Mon 13:46:35 bot edits #43:
      | Щодня о 08:00. Наступні: вт 08:00, ср 08:00, чт 08:00, пт 08:00, сб 08:00.
      [Увімкнути] [Назад]
Mon 13:46:35 you tap: [Увімкнути] on #43
Mon 13:46:41 bot edits #43:
      | «Щодня о 08:00» увімкнено.
      |
      | Нагадування
      |
      | Вода: Робочий час, кожні 30 хв, наступне о пн 14:00
      |
      | Настрій: вимкнено
      |
      | Відхід до сну: вимкнено
      |
      | Ранок: вимкнено
      |
      | Їжа: вимкнено
      |
      | Тижневий підсумок: вимкнено
      |
      | Місячний підсумок: вимкнено
      |
      | Ранковий підсумок: Щодня о 08:00, наступне о вт 08:00
      |
      | Ліки: 2 розклади, налаштовуються в /meds.
      |
      | Робочі години: пн–пт 09:00-18:00. Тихі години: 22:00-07:00.
      [Вода] [Настрій] [Відхід до сну] [Ранок]
      [Їжа] [Тижневий підсумок] [Місячний підсумок] [Ранковий підсумок]
      [Пауза на 1 год] [Пауза на 3 год]
      [День і години] [Підрахунки у звітах: увімк.]

-- /stats meds: each medication over 7 days, and its strip
Mon 13:46:41 you: /stats meds
Mon 13:46:48 bot #44:
      | За ліками · останні 7 днів до 21 вересня
      |
      | 1. Vitamin D: прийнято 1 з 1
      |
      | 2. Magnesium: прийнято 0 з 1 · пропущено 1 · ще без відповіді — 1, поки не враховано
      |
      | Це підрахунок того, що ви записали. На зображенні рядки пронумеровано в тому самому порядку, без назв.
      [7 днів] [14 днів]
      [Показати як зображення]
Mon 13:46:48 you tap: [Показати як зображення] on #44
Mon 13:46:56 bot #45 [image], caption:
      | 15–21 вересня. Позиція 1: 1 з 1 запланованої дози записано як прийняті. Позиція 2: 0 з 2 запланованих доз записано як прийняті, пропущено 1, ще відкрито 1.
```

### The next morning

```text
-- The next morning: the summary of the first day at 08:00
Tue 08:03:40 bot #51 [image], caption:
      | Пн 21 вересня. Настрій: Приємно. Ліки: 1 з 3 запланованих доз записано як прийняті, пропущено 1, не підтверджено 1. Вода: 0,25 з 2,0 л. Їжа: записано 1 з 3 прийомів їжі.
Tue 08:03:40 bot #52:
      | Учора, пн 21 вересня
      |
      | Дози: прийнято 1 з 3 · пропущено 1 · не підтверджено 1
      |
      | Вода: 0,25 з 2,0 л (12%)
      |
      | Настрій: 4,0 (приємно) · 1 позначка
      |
      | Сон: 7 год 40 хв (ваші позначки)
      |
      | Їжа: записано 1 з 3 запланованих прийомів їжі
      [Вимкнути такі]
```
