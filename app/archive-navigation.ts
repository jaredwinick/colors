import { utcRangeForLocalDate } from "../db/capture-archive.ts";

export type ArchiveDateSelection = {
  date: string;
  isCanonical: boolean;
};

function shiftDate(date: string, days: number): string {
  const [year, month, day] = date.split("-").map(Number);
  const shifted = new Date(Date.UTC(year, month - 1, day + days));
  return `${shifted.getUTCFullYear().toString().padStart(4, "0")}-${(
    shifted.getUTCMonth() + 1
  )
    .toString()
    .padStart(2, "0")}-${shifted.getUTCDate().toString().padStart(2, "0")}`;
}

export function resolveArchiveDate(
  requested: string | string[] | undefined,
  currentDate: string,
  timeZone: string,
): ArchiveDateSelection {
  if (typeof requested !== "string") {
    return { date: currentDate, isCanonical: false };
  }

  try {
    utcRangeForLocalDate(requested, timeZone);
  } catch {
    return { date: currentDate, isCanonical: false };
  }

  if (requested > currentDate) {
    return { date: currentDate, isCanonical: false };
  }

  return { date: requested, isCanonical: true };
}

export function archiveUrl(date: string): string {
  return `/day/${encodeURIComponent(date)}`;
}

export function archiveDateNeighbors(date: string, currentDate: string) {
  return {
    previousDate: shiftDate(date, -1),
    nextDate: date < currentDate ? shiftDate(date, 1) : null,
  };
}
