import { env } from "cloudflare:workers";

export type PaletteColor = {
  hex: string;
  weight: number;
};

export type CaptureView = {
  id: string;
  capturedAt: string;
  imageUrl: string | null;
  palette: PaletteColor[];
};

type CaptureRow = {
  id: string;
  captured_at: string;
  image_key: string;
  palette_json: string;
};

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
