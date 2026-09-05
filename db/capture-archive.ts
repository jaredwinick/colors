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

export type CaptureArchive = {
  date: string;
  timeZone: string;
  captureCount: number;
  invalidCaptureCount: number;
  isCurrentDay: boolean;
  captures: CaptureView[];
};

export type CaptureRow = {
  id: string;
  captured_at: string;
  image_key: string;
  palette_json: string;
};

export class ArchiveRequestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ArchiveRequestError";
  }
}

const ARCHIVE_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const HEX_COLOR = /^#[0-9A-F]{6}$/i;

type CalendarDate = {
  year: number;
  month: number;
  day: number;
};

function readCalendarDate(value: string): CalendarDate {
  const match = ARCHIVE_DATE.exec(value);
  if (!match) {
    throw new ArchiveRequestError("date must use YYYY-MM-DD format");
  }

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const candidate = new Date(Date.UTC(year, month - 1, day));

  if (
    candidate.getUTCFullYear() !== year ||
    candidate.getUTCMonth() !== month - 1 ||
    candidate.getUTCDate() !== day
  ) {
    throw new ArchiveRequestError("date must be a valid calendar date");
  }

  return { year, month, day };
}

function dateString({ year, month, day }: CalendarDate): string {
  return `${year.toString().padStart(4, "0")}-${month
    .toString()
    .padStart(2, "0")}-${day.toString().padStart(2, "0")}`;
}

function nextDate(value: CalendarDate): CalendarDate {
  const next = new Date(Date.UTC(value.year, value.month - 1, value.day + 1));
  return {
    year: next.getUTCFullYear(),
    month: next.getUTCMonth() + 1,
    day: next.getUTCDate(),
  };
}

function formatterFor(timeZone: string) {
  try {
    return new Intl.DateTimeFormat("en-US", {
      timeZone,
      calendar: "gregory",
      numberingSystem: "latn",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23",
    });
  } catch {
    throw new Error(`DISPLAY_TIME_ZONE is not a valid IANA time zone: ${timeZone}`);
  }
}

function zonedParts(instant: Date, timeZone: string) {
  const values = new Map(
    formatterFor(timeZone)
      .formatToParts(instant)
      .filter((part) => part.type !== "literal")
      .map((part) => [part.type, Number(part.value)]),
  );

  return {
    year: values.get("year")!,
    month: values.get("month")!,
    day: values.get("day")!,
    hour: values.get("hour")!,
    minute: values.get("minute")!,
    second: values.get("second")!,
  };
}

function utcForZonedMidnight(value: CalendarDate, timeZone: string): Date {
  const localAsUtc = Date.UTC(value.year, value.month - 1, value.day);
  let candidate = localAsUtc;

  // Resolve the zone offset at the requested local midnight. Repeating the
  // calculation handles an offset transition between the initial guess and
  // the final instant.
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const parts = zonedParts(new Date(candidate), timeZone);
    const representedAsUtc = Date.UTC(
      parts.year,
      parts.month - 1,
      parts.day,
      parts.hour,
      parts.minute,
      parts.second,
    );
    const nextCandidate = localAsUtc - (representedAsUtc - candidate);
    if (nextCandidate === candidate) break;
    candidate = nextCandidate;
  }

  const resolved = zonedParts(new Date(candidate), timeZone);
  if (
    resolved.year !== value.year ||
    resolved.month !== value.month ||
    resolved.day !== value.day ||
    resolved.hour !== 0 ||
    resolved.minute !== 0 ||
    resolved.second !== 0
  ) {
    throw new Error(
      `Unable to resolve local midnight for ${dateString(value)} in ${timeZone}`,
    );
  }

  return new Date(candidate);
}

export function localDateForInstant(instant: Date, timeZone: string): string {
  const parts = zonedParts(instant, timeZone);
  return dateString(parts);
}

export function utcRangeForLocalDate(date: string, timeZone: string) {
  const localDate = readCalendarDate(date);
  return {
    start: utcForZonedMidnight(localDate, timeZone).toISOString(),
    end: utcForZonedMidnight(nextDate(localDate), timeZone).toISOString(),
  };
}

export function readStoredPalette(value: string): PaletteColor[] | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value) as unknown;
  } catch {
    return null;
  }

  if (!Array.isArray(parsed) || parsed.length < 3 || parsed.length > 10) {
    return null;
  }

  const palette: PaletteColor[] = [];
  for (const entry of parsed) {
    if (
      typeof entry !== "object" ||
      entry === null ||
      !("hex" in entry) ||
      !("weight" in entry) ||
      typeof entry.hex !== "string" ||
      !HEX_COLOR.test(entry.hex) ||
      typeof entry.weight !== "number" ||
      !Number.isFinite(entry.weight) ||
      entry.weight <= 0
    ) {
      return null;
    }
    palette.push({ hex: entry.hex.toUpperCase(), weight: entry.weight });
  }

  return palette;
}

export function mapCaptureRows(rows: CaptureRow[]) {
  const captures: CaptureView[] = [];
  let invalidCaptureCount = 0;

  for (const row of rows) {
    const palette = readStoredPalette(row.palette_json);
    if (!palette) {
      invalidCaptureCount += 1;
      continue;
    }

    captures.push({
      id: row.id,
      capturedAt: row.captured_at,
      imageUrl: `/api/images/${encodeURIComponent(row.image_key)}`,
      palette,
    });
  }

  return { captures, invalidCaptureCount };
}

export function cacheControlForArchive(isCompletedDay: boolean): string {
  return isCompletedDay
    ? "public, max-age=300, s-maxage=86400, stale-while-revalidate=604800"
    : "public, max-age=30, s-maxage=30, stale-while-revalidate=120";
}

export async function queryCaptureArchive(
  database: D1Database,
  date: string,
  timeZone: string,
  now = new Date(),
): Promise<CaptureArchive> {
  const range = utcRangeForLocalDate(date, timeZone);
  const result = await database
    .prepare(
      `SELECT id, captured_at, image_key, palette_json
       FROM captures
       WHERE captured_at >= ? AND captured_at < ?
       ORDER BY captured_at DESC`,
    )
    .bind(range.start, range.end)
    .all<CaptureRow>();
  const { captures, invalidCaptureCount } = mapCaptureRows(result.results ?? []);

  return {
    date,
    timeZone,
    captureCount: captures.length,
    invalidCaptureCount,
    isCurrentDay: date === localDateForInstant(now, timeZone),
    captures,
  };
}
