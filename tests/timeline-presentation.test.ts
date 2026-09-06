import assert from "node:assert/strict";
import test from "node:test";

import {
  accentPreservingWidths,
  MINIMUM_SWATCH_WIDTH,
} from "../app/components/palette-widths.ts";
import { createDemoCaptures } from "../db/demo.ts";
import type { PaletteColor } from "../db/capture-archive.ts";

test("the normal-day fixture contains 96 newest-first eight-color palettes", () => {
  const captures = createDemoCaptures("2026-08-16", "America/Denver");

  assert.equal(captures.length, 96);
  assert.ok(captures.every((capture) => capture.palette.length === 8));
  assert.ok(
    captures.every(
      (capture, index) =>
        index === 0 ||
        Date.parse(captures[index - 1].capturedAt) >
          Date.parse(capture.capturedAt),
    ),
  );
});

test("a one-percent accent receives the visual floor without mutating weights", () => {
  const palette: PaletteColor[] = [
    { hex: "#17324D", weight: 0.54 },
    { hex: "#477EA6", weight: 0.26 },
    { hex: "#E3A06F", weight: 0.19 },
    { hex: "#F05A78", weight: 0.01 },
  ];
  const original = structuredClone(palette);

  const widths = accentPreservingWidths(palette);

  assert.deepEqual(palette, original);
  assert.equal(widths.length, palette.length);
  assert.ok(Math.abs(widths.reduce((sum, width) => sum + width, 0) - 1) < 1e-12);
  assert.equal(widths[3], MINIMUM_SWATCH_WIDTH);
  assert.ok(widths.every((width) => width >= MINIMUM_SWATCH_WIDTH));
  assert.ok(widths[0] > widths[1] && widths[1] > widths[2]);
});

test("ordinary weights retain their proportions when no floor is needed", () => {
  const palette: PaletteColor[] = [
    { hex: "#112233", weight: 0.5 },
    { hex: "#445566", weight: 0.3 },
    { hex: "#778899", weight: 0.2 },
  ];

  assert.deepEqual(accentPreservingWidths(palette), [0.5, 0.3, 0.2]);
});
