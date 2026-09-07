import assert from "node:assert/strict";
import test from "node:test";

import {
  archiveDateNeighbors,
  archiveUrl,
  resolveArchiveDate,
} from "../app/archive-navigation.ts";
import {
  captureAdditionCount,
  mergeCaptureArchives,
} from "../app/components/archive-refresh.ts";
import type {
  CaptureArchive,
  CaptureView,
} from "../db/capture-archive.ts";

const TIME_ZONE = "America/Denver";

function capture(id: string, capturedAt: string): CaptureView {
  return {
    id,
    capturedAt,
    imageUrl: `/api/images/${id}.jpg`,
    palette: [
      { hex: "#112233", weight: 0.5 },
      { hex: "#445566", weight: 0.3 },
      { hex: "#778899", weight: 0.2 },
    ],
  };
}

function archive(captures: CaptureView[]): CaptureArchive {
  return {
    date: "2026-09-06",
    timeZone: TIME_ZONE,
    captureCount: captures.length,
    invalidCaptureCount: 0,
    isCurrentDay: true,
    captures,
  };
}

test("archive dates default and normalize to the current configured-zone day", () => {
  assert.deepEqual(resolveArchiveDate(undefined, "2026-09-06", TIME_ZONE), {
    date: "2026-09-06",
    isCanonical: false,
  });
  assert.deepEqual(
    resolveArchiveDate("not-a-date", "2026-09-06", TIME_ZONE),
    { date: "2026-09-06", isCanonical: false },
  );
  assert.deepEqual(
    resolveArchiveDate("2026-09-07", "2026-09-06", TIME_ZONE),
    { date: "2026-09-06", isCanonical: false },
  );
  assert.deepEqual(
    resolveArchiveDate("2026-09-05", "2026-09-06", TIME_ZONE),
    { date: "2026-09-05", isCanonical: true },
  );
});

test("day navigation crosses calendar boundaries and never links past today", () => {
  assert.deepEqual(archiveDateNeighbors("2024-03-01", "2026-09-06"), {
    previousDate: "2024-02-29",
    nextDate: "2024-03-02",
  });
  assert.deepEqual(archiveDateNeighbors("2026-09-06", "2026-09-06"), {
    previousDate: "2026-09-05",
    nextDate: null,
  });
  assert.equal(archiveUrl("2026-09-05"), "/day/2026-09-05");
});

test("current-day refresh merges one new capture exactly once and newest first", () => {
  const older = capture("older", "2026-09-06T12:00:00.000Z");
  const newer = capture("newer", "2026-09-06T12:15:00.000Z");
  const current = archive([older]);
  const incoming = archive([newer, older, newer]);
  const currentBefore = structuredClone(current);
  const incomingBefore = structuredClone(incoming);

  const merged = mergeCaptureArchives(current, incoming);

  assert.deepEqual(merged.captures.map(({ id }) => id), ["newer", "older"]);
  assert.equal(merged.captureCount, 2);
  assert.deepEqual(current, currentBefore);
  assert.deepEqual(incoming, incomingBefore);
  assert.equal(captureAdditionCount(current, incoming), 1);
  assert.equal(captureAdditionCount(merged, incoming), 0);
});
