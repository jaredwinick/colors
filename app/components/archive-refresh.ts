import type { CaptureArchive, CaptureView } from "../../db/capture-archive.ts";

function newestFirst(captures: CaptureView[]): CaptureView[] {
  return captures.sort((left, right) =>
    right.capturedAt.localeCompare(left.capturedAt),
  );
}

function uniqueCaptures(captures: CaptureView[]): CaptureView[] {
  return [...new Map(captures.map((capture) => [capture.id, capture])).values()];
}

export function mergeCaptureArchives(
  current: CaptureArchive,
  incoming: CaptureArchive,
): CaptureArchive {
  const captures = newestFirst(
    uniqueCaptures(
      current.date === incoming.date
        ? [...current.captures, ...incoming.captures]
        : [...incoming.captures],
    ),
  );

  return {
    ...incoming,
    captureCount: captures.length,
    captures,
  };
}

export function captureAdditionCount(
  current: CaptureArchive,
  incoming: CaptureArchive,
): number {
  if (current.date !== incoming.date) return incoming.captures.length;

  const existingIds = new Set(current.captures.map(({ id }) => id));
  return new Set(
    incoming.captures
      .filter(({ id }) => !existingIds.has(id))
      .map(({ id }) => id),
  ).size;
}
