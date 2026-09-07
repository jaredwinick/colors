import {
  cacheControlForArchive,
  localDateForInstant,
} from "../db/capture-archive.ts";

const ARCHIVE_DAY_PATH = /^\/day\/(\d{4}-\d{2}-\d{2})$/;

export function withArchivePageCache(
  request: Request,
  response: Response,
  timeZone: string | undefined,
  now = new Date(),
): Response {
  if (
    !timeZone ||
    (request.method !== "GET" && request.method !== "HEAD") ||
    response.status !== 200
  ) {
    return response;
  }

  const date = new URL(request.url).pathname.match(ARCHIVE_DAY_PATH)?.[1];
  if (!date) return response;

  const currentDate = localDateForInstant(now, timeZone);
  const headers = new Headers(response.headers);
  headers.set("Cache-Control", cacheControlForArchive(date < currentDate));

  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}
