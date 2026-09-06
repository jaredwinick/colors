import type { PaletteColor } from "../../db/capture-archive.ts";

export const MINIMUM_SWATCH_WIDTH = 0.06;

/**
 * Applies a visual floor to rare colors without changing the stored weights.
 * Colors above the floor retain their relative proportions in the remaining
 * space. The returned values always sum to one.
 */
export function accentPreservingWidths(
  palette: readonly PaletteColor[],
  minimum = MINIMUM_SWATCH_WIDTH,
): number[] {
  if (palette.length === 0) return [];
  if (minimum <= 0 || !Number.isFinite(minimum)) {
    throw new Error("minimum swatch width must be a positive finite number");
  }
  if (palette.length * minimum >= 1) {
    return palette.map(() => 1 / palette.length);
  }

  const total = palette.reduce((sum, color) => sum + color.weight, 0);
  const normalized = palette.map((color) => color.weight / total);
  const widths = Array<number>(palette.length).fill(0);
  const available = new Set(palette.map((_, index) => index));
  let remainingWidth = 1;
  let remainingWeight = 1;

  // Water-fill values that would fall below the floor, then distribute the
  // rest proportionally. This can take several passes after renormalization.
  while (available.size > 0) {
    const newlyFloored = [...available].filter(
      (index) =>
        (normalized[index] / remainingWeight) * remainingWidth < minimum,
    );

    if (newlyFloored.length === 0) {
      for (const index of available) {
        widths[index] =
          (normalized[index] / remainingWeight) * remainingWidth;
      }
      break;
    }

    for (const index of newlyFloored) {
      widths[index] = minimum;
      remainingWidth -= minimum;
      remainingWeight -= normalized[index];
      available.delete(index);
    }
  }

  return widths;
}
