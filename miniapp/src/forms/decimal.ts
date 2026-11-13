import type { Decimal } from '../api/types';

export interface DecimalLimits {
  readonly integerDigits: number;
  readonly fractionDigits: number;
}

/**
 * Reads what a person typed into a quantity field. A comma or a dot separates the fraction in
 * every locale (MINI_APP 7.4 behaviour 2). Returns the canonical decimal, or null when the text
 * is not a quantity within the limits.
 */
export function parseDecimal(text: string, limits: DecimalLimits): Decimal | null {
  const match = /^(\d+)(?:[.,](\d+))?$/.exec(text.trim());
  if (!match) return null;
  const integer = match[1]!.replace(/^0+(?=\d)/, '');
  const fraction = (match[2] ?? '').replace(/0+$/, '');
  if (integer.length > limits.integerDigits || fraction.length > limits.fractionDigits) return null;
  return fraction ? `${integer}.${fraction}` : integer;
}

export function isPositive(value: Decimal): boolean {
  return Number(value) > 0;
}
