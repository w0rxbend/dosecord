// The mock's meal journal, for development and the component tests. Sentences follow the server's
// (api/src/dosegram/api/Nutrition.scala), but the server stays the authority.
import { ApiError } from '../api/client';
import type {
  LocalDate,
  Meal,
  MealInput,
  MealKind,
  MealRecorded,
  NutritionDay,
  NutritionSettings,
  NutritionSummary,
} from '../api/types';
import { formatDate, toTime } from './dates';
import { lastDays, seeded } from './scenes';

const MAIN: readonly MealKind[] = ['breakfast', 'lunch', 'dinner'];
const LABELS: Readonly<Record<MealKind, string>> = { breakfast: 'Breakfast', lunch: 'Lunch', dinner: 'Dinner', snack: 'Snack' };
const MAX_CHARS = 300;
/** The mock's zone is Europe/Kyiv; November has no clock change, so the offset is fixed. */
const OFFSET = '+02:00';

const count = (n: number, noun: string): string => (n === 1 ? `1 ${noun}` : `${n} ${noun}s`);
const dayLabel = (date: LocalDate): string => formatDate(date, { weekday: 'short', day: 'numeric', month: 'short' });

interface Stored {
  readonly id: string;
  readonly date: LocalDate;
  readonly minute: number;
  readonly mealType: MealKind;
  readonly description?: string;
}

export class MockFood {
  private meals: Stored[] = [];
  private planned: readonly MealKind[] = MAIN;
  private version = 1;
  private nextId = 1;

  constructor(
    private readonly today: () => LocalDate,
    private readonly minute: () => number,
    private readonly gate: () => void,
    withHistory: boolean,
  ) {
    if (withHistory) this.seed();
  }

  private seed(): void {
    const random = seeded(57);
    const words = ['Porridge, coffee', 'Soup and bread', 'Pasta with salad', 'Apple'];
    for (const date of lastDays(this.today(), 14).slice(0, -1)) {
      if (random() < 0.85) this.store(date, 8 * 60 + 15, 'breakfast', words[0]);
      if (random() < 0.7) this.store(date, 13 * 60 + 5, 'lunch', words[1]);
      if (random() < 0.9) this.store(date, 19 * 60 + 40, 'dinner', random() < 0.5 ? words[2] : undefined);
      if (random() < 0.4) this.store(date, 16 * 60 + 40, 'snack', words[3]);
    }
  }

  private store(date: LocalDate, minute: number, mealType: MealKind, description?: string): Stored {
    const meal = { id: `meal-${this.nextId++}`, date, minute, mealType, description };
    this.meals.push(meal);
    return meal;
  }

  private view(stored: Stored): Meal {
    const time = toTime(stored.minute);
    const label = LABELS[stored.mealType];
    return {
      id: stored.id,
      date: stored.date,
      time,
      occurredAt: `${stored.date}T${time}:00${OFFSET}`,
      mealType: stored.mealType,
      label,
      text: stored.description === undefined ? `${label} at ${time}` : `${label} at ${time}: ${stored.description}`,
      description: stored.description,
    };
  }

  day(date: LocalDate): NutritionDay {
    const own = this.meals.filter((meal) => meal.date === date);
    const logged = MAIN.filter((meal) => own.some((stored) => stored.mealType === meal));
    const snacks = own.filter((meal) => meal.mealType === 'snack').length;
    const plannedLogged = this.planned.filter((meal) => logged.includes(meal)).length;
    const meals =
      this.planned.length === 0
        ? logged.length === 0
          ? 'No meal logged'
          : `${count(logged.length, 'meal')} logged`
        : `${plannedLogged} of ${count(this.planned.length, 'planned meal')} logged`;
    return {
      date,
      label: formatDate(date, { weekday: 'short' }),
      text: `${dayLabel(date)}: ${snacks === 0 ? meals : `${meals}, ${count(snacks, 'snack')}`}`,
      meals: own.length,
      snacks,
      planned: this.planned.length,
      plannedLogged,
      logged,
      notLogged: MAIN.filter((meal) => this.planned.includes(meal) && !logged.includes(meal)),
    };
  }

  summary(from?: LocalDate, to?: LocalDate): NutritionSummary {
    this.gate();
    const end = to ?? this.today();
    const length = from === undefined ? 7 : 1 + Math.round((Date.parse(end) - Date.parse(from)) / 86_400_000);
    const days = lastDays(end, length).map((date) => this.day(date));
    const perMeal = MAIN.map((meal) => ({
      mealType: meal,
      label: LABELS[meal],
      days: days.filter((day) => day.logged.includes(meal)).length,
    }));
    return {
      today: this.day(this.today()),
      days,
      perMeal,
      text: `${perMeal.map((meal) => `${meal.label} ${meal.days}`).join(', ')} of ${count(days.length, 'day')}`,
    };
  }

  mealsIn(from: LocalDate, to: LocalDate): readonly Meal[] {
    this.gate();
    return this.meals
      .filter((meal) => meal.date >= from && meal.date <= to)
      .sort((a, b) => (a.date === b.date ? a.minute - b.minute : a.date < b.date ? -1 : 1))
      .map((meal) => this.view(meal));
  }

  add(input: MealInput): MealRecorded {
    this.gate();
    const refuse = (field: string, code: string) => new ApiError('validation', 'MOCK-0422', [{ field, code }]);
    const description = input.description?.trim() || undefined;
    if (description !== undefined && [...description].length > MAX_CHARS) throw refuse('description', 'text_too_long');
    const original = input.supersedes === undefined ? undefined : this.meals.find((meal) => meal.id === input.supersedes);
    if (input.supersedes !== undefined && original === undefined) throw new ApiError('not_found', 'MOCK-0404');
    const [date, minute] =
      input.occurredAt === undefined
        ? original
          ? [original.date, original.minute]
          : [this.today(), this.minute()]
        : [input.occurredAt.slice(0, 10), Number(input.occurredAt.slice(11, 13)) * 60 + Number(input.occurredAt.slice(14, 16))];
    if (date > this.today() || (date === this.today() && minute > this.minute() + 5)) throw refuse('occurredAt', 'in_future');
    if (original) this.meals = this.meals.filter((meal) => meal !== original);
    const stored = this.store(date, minute, input.mealType, description);
    return { meal: this.view(stored), day: this.day(date) };
  }

  remove(id: string): NutritionDay {
    this.gate();
    const found = this.meals.find((meal) => meal.id === id);
    if (!found) throw new ApiError('not_found', 'MOCK-0404');
    this.meals = this.meals.filter((meal) => meal !== found);
    return this.day(found.date);
  }

  recent(mealType: MealKind): readonly string[] {
    this.gate();
    const texts = this.meals
      .filter((meal) => meal.mealType === mealType && meal.description !== undefined)
      .sort((a, b) => (a.date === b.date ? b.minute - a.minute : a.date < b.date ? 1 : -1))
      .map((meal) => meal.description!);
    return [...new Set(texts)].slice(0, 5);
  }

  settings(): NutritionSettings {
    this.gate();
    return { planned: this.planned, version: this.version };
  }

  saveSettings(planned: readonly MealKind[], version: number): NutritionSettings {
    this.gate();
    if (version !== this.version) throw new ApiError('version_conflict', 'MOCK-0412');
    if (planned.includes('snack')) throw new ApiError('validation', 'MOCK-0422', [{ field: 'planned', code: 'snack_not_planned' }]);
    this.planned = MAIN.filter((meal) => planned.includes(meal));
    this.version += 1;
    return this.settings();
  }
}
