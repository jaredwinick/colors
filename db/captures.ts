import { env } from "cloudflare:workers";

import {
  localDateForInstant,
  queryCaptureArchive,
  utcRangeForLocalDate,
  type CaptureArchive,
  type CaptureRow,
  type CaptureView,
  type PaletteColor,
} from "./capture-archive";

export type { CaptureArchive, CaptureView, PaletteColor } from "./capture-archive";

export async function getCaptureArchive(
  date: string,
  timeZone: string,
  now = new Date(),
): Promise<CaptureArchive> {
  // Validate the request even in local/sample environments without D1.
  utcRangeForLocalDate(date, timeZone);

  if (!env.DB) {
    return {
      date,
      timeZone,
      captureCount: 0,
      invalidCaptureCount: 0,
      isCurrentDay: date === localDateForInstant(now, timeZone),
      captures: [],
    };
  }

  return queryCaptureArchive(env.DB, date, timeZone, now);
}

/** @deprecated The Concept C archive uses getCaptureArchive instead. */
export async function getRecentCaptures(hours = 24): Promise<CaptureView[]> {
  if (!env.DB) return [];

  const since = new Date(Date.now() - hours * 60 * 60 * 1000).toISOString();
  const result = await env.DB.prepare(
    `SELECT id, captured_at, image_key, palette_json
     FROM captures
     WHERE captured_at >= ?
     ORDER BY captured_at DESC
     LIMIT 200`,
  )
    .bind(since)
    .all<CaptureRow>();

  return (result.results ?? []).map((row) => ({
    id: row.id,
    capturedAt: row.captured_at,
    imageUrl: `/api/images/${encodeURIComponent(row.image_key)}`,
    palette: JSON.parse(row.palette_json) as PaletteColor[],
  }));
}
