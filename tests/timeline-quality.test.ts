import assert from "node:assert/strict";
import test from "node:test";

import {
  formatDate,
  formatTime,
  formatTimestamp,
} from "../app/components/timeline-format.ts";
import { timelineNavigationIndex } from "../app/components/timeline-keyboard.ts";

test("archive dates and capture times use the intended display zone", () => {
  assert.equal(formatDate("2024-02-29"), "Thursday, February 29, 2024");
  assert.equal(
    formatTime("2026-08-16T12:30:00.000Z", "America/Denver"),
    "06:30",
  );
  assert.match(
    formatTimestamp("2026-08-16T12:30:00.000Z", "America/Denver"),
    /Sunday, August 16, 2026 at 6:30 AM/,
  );
});

test("interactive rows use bounded arrow, Home, and End navigation", () => {
  assert.equal(timelineNavigationIndex(3, 8, "ArrowUp"), 2);
  assert.equal(timelineNavigationIndex(3, 8, "ArrowDown"), 4);
  assert.equal(timelineNavigationIndex(0, 8, "ArrowUp"), 0);
  assert.equal(timelineNavigationIndex(7, 8, "ArrowDown"), 7);
  assert.equal(timelineNavigationIndex(5, 8, "Home"), 0);
  assert.equal(timelineNavigationIndex(2, 8, "End"), 7);
  assert.equal(timelineNavigationIndex(2, 8, "Enter"), null);
  assert.equal(timelineNavigationIndex(-1, 8, "ArrowDown"), null);
});
