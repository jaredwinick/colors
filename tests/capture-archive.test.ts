import assert from "node:assert/strict";
import test from "node:test";

import {
  ArchiveRequestError,
  cacheControlForArchive,
  localDateForInstant,
  mapCaptureRows,
  queryCaptureArchive,
  readStoredPalette,
  utcRangeForLocalDate,
  type CaptureRow,
} from "../db/capture-archive.ts";

const TIME_ZONE = "America/Denver";
const PALETTE = JSON.stringify([
  { hex: "#123456", weight: 0.5 },
  { hex: "#ABCDEF", weight: 0.3 },
  { hex: "#FEDCBA", weight: 0.2 },
]);

function row(index: number, capturedAt: string): CaptureRow {
  return {
    id: `capture-${index}`,
    captured_at: capturedAt,
    image_key: `2026/08/16/capture-${index}.jpg`,
    palette_json: PALETTE,
  };
}

function fakeDatabase(rows: CaptureRow[]) {
  const observed = { query: "", bindings: [] as unknown[] };
  const statement = {
    bind(...values: unknown[]) {
      observed.bindings = values;
      return statement;
    },
    async all<T>() {
      return { results: rows as T[] };
    },
  };
  const database = {
    prepare(query: string) {
      observed.query = query;
      return statement;
    },
  } as unknown as D1Database;
  return { database, observed };
}

test("an ordinary local day returns all 96 quarter-hour captures newest first", async () => {
  const ascending = Array.from({ length: 96 }, (_, index) =>
    row(index, new Date(Date.UTC(2026, 7, 16, 6, index * 15)).toISOString()),
  );
  const rows = ascending.reverse();
  const { database, observed } = fakeDatabase(rows);

  const archive = await queryCaptureArchive(
    database,
    "2026-08-16",
    TIME_ZONE,
    new Date("2026-08-16T18:00:00.000Z"),
  );

  assert.equal(archive.captureCount, 96);
  assert.equal(archive.invalidCaptureCount, 0);
  assert.equal(archive.isCurrentDay, true);
  assert.equal(archive.captures[0].id, "capture-95");
  assert.equal(archive.captures.at(-1)?.id, "capture-0");
  assert.deepEqual(observed.bindings, [
    "2026-08-16T06:00:00.000Z",
    "2026-08-17T06:00:00.000Z",
  ]);
  assert.match(observed.query, /captured_at >= \? AND captured_at < \?/);
  assert.match(observed.query, /ORDER BY captured_at DESC/);
});

test("current partial and empty days return accurate metadata", async () => {
  const partial = fakeDatabase([
    row(1, "2026-08-16T15:15:00.000Z"),
    row(0, "2026-08-16T15:00:00.000Z"),
  ]);
  const current = await queryCaptureArchive(
    partial.database,
    "2026-08-16",
    TIME_ZONE,
    new Date("2026-08-16T15:30:00.000Z"),
  );
  assert.equal(current.captureCount, 2);
  assert.equal(current.isCurrentDay, true);

  const empty = await queryCaptureArchive(
    fakeDatabase([]).database,
    "2026-08-15",
    TIME_ZONE,
    new Date("2026-08-16T15:30:00.000Z"),
  );
  assert.equal(empty.captureCount, 0);
  assert.deepEqual(empty.captures, []);
  assert.equal(empty.isCurrentDay, false);
});

test("day boundaries account for both daylight-saving transitions", () => {
  const spring = utcRangeForLocalDate("2026-03-08", TIME_ZONE);
  const fall = utcRangeForLocalDate("2026-11-01", TIME_ZONE);

  assert.deepEqual(spring, {
    start: "2026-03-08T07:00:00.000Z",
    end: "2026-03-09T06:00:00.000Z",
  });
  assert.equal(
    Date.parse(spring.end) - Date.parse(spring.start),
    23 * 60 * 60 * 1000,
  );
  assert.deepEqual(fall, {
    start: "2026-11-01T06:00:00.000Z",
    end: "2026-11-02T07:00:00.000Z",
  });
  assert.equal(
    Date.parse(fall.end) - Date.parse(fall.start),
    25 * 60 * 60 * 1000,
  );
});

test("invalid archive dates produce request errors", () => {
  for (const value of ["2026-8-16", "2026-02-30", "not-a-date"]) {
    assert.throws(
      () => utcRangeForLocalDate(value, TIME_ZONE),
      ArchiveRequestError,
    );
  }
});

test("malformed stored palettes are skipped without hiding valid captures", () => {
  const malformed = row(1, "2026-08-16T15:15:00.000Z");
  malformed.palette_json = "not json";
  const invalidColor = row(2, "2026-08-16T15:30:00.000Z");
  invalidColor.palette_json = JSON.stringify([
    { hex: "blue", weight: 0.5 },
    { hex: "#ABCDEF", weight: 0.3 },
    { hex: "#FEDCBA", weight: 0.2 },
  ]);

  const mapped = mapCaptureRows([
    malformed,
    invalidColor,
    row(0, "2026-08-16T15:00:00.000Z"),
  ]);

  assert.equal(mapped.invalidCaptureCount, 2);
  assert.equal(mapped.captures.length, 1);
  assert.equal(mapped.captures[0].id, "capture-0");
  assert.equal(mapped.captures[0].palette[1].hex, "#ABCDEF");
  assert.equal(readStoredPalette("[]"), null);
});

test("current-day detection and cache policy use the configured zone", () => {
  const instant = new Date("2026-08-17T05:30:00.000Z");
  assert.equal(localDateForInstant(instant, TIME_ZONE), "2026-08-16");
  assert.equal(
    cacheControlForArchive(false),
    "public, max-age=30, s-maxage=30, stale-while-revalidate=120",
  );
  assert.equal(
    cacheControlForArchive(true),
    "public, max-age=300, s-maxage=86400, stale-while-revalidate=604800",
  );
});
