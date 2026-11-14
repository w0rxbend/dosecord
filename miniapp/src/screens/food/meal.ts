import type { LocalDate, Meal, MealInput, MealKind } from '../../api/types';
import { localInstant } from '../../forms/instant';

export const MEAL_KINDS: readonly MealKind[] = ['breakfast', 'lunch', 'dinner', 'snack'];
export const MAIN_MEALS: readonly MealKind[] = ['breakfast', 'lunch', 'dinner'];

/** What the meal sheet edits; `replaces` names the meal an edit supersedes. */
export interface MealDraft {
  readonly mealType: MealKind;
  readonly date: LocalDate;
  readonly time: string;
  readonly description: string;
  /** A new meal of today whose time the person left alone: the server records it at now. */
  readonly atNow: boolean;
  readonly replaces?: string;
}

/** A new meal of a day: now on today, midday on another day; the person changes the time freely. */
export function newDraft(mealType: MealKind, date: LocalDate, today: LocalDate, now: Date): MealDraft {
  const time = date === today ? `${pad(now.getHours())}:${pad(now.getMinutes())}` : '12:00';
  return { mealType, date, time, description: '', atNow: date === today };
}

export function editDraft(meal: Meal): MealDraft {
  return {
    mealType: meal.mealType,
    date: meal.date,
    time: meal.time,
    description: meal.description ?? '',
    atNow: false,
    replaces: meal.id,
  };
}

export function toMealInput(draft: MealDraft): MealInput {
  const description = draft.description.trim();
  return {
    mealType: draft.mealType,
    occurredAt: draft.atNow ? undefined : localInstant(draft.date, draft.time),
    description: description === '' ? undefined : description,
    supersedes: draft.replaces,
  };
}

/** The meals of a day by type, in the fixed order of the diary (MINI_APP 7.8). */
export function byType(meals: readonly Meal[]): Readonly<Record<MealKind, readonly Meal[]>> {
  return {
    breakfast: meals.filter((meal) => meal.mealType === 'breakfast'),
    lunch: meals.filter((meal) => meal.mealType === 'lunch'),
    dinner: meals.filter((meal) => meal.mealType === 'dinner'),
    snack: meals.filter((meal) => meal.mealType === 'snack'),
  };
}

export function shiftDate(date: LocalDate, days: number): LocalDate {
  const shifted = new Date(`${date}T12:00:00Z`);
  shifted.setUTCDate(shifted.getUTCDate() + days);
  return shifted.toISOString().slice(0, 10);
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}
