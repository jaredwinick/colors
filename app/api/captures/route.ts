import { env } from "cloudflare:workers";

import {
  ArchiveRequestError,
  cacheControlForArchive,
  localDateForInstant,
} from "../../../db/capture-archive";
import { getCaptureArchive } from "../../../db/captures";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const timeZone = env.DISPLAY_TIME_ZONE?.trim();
  if (!timeZone) {
    return Response.json(
      { error: "DISPLAY_TIME_ZONE is not configured" },
      { status: 500 },
    );
  }

  try {
    const now = new Date();
    const requestedDate = new URL(request.url).searchParams.get("date");
    const currentDate = localDateForInstant(now, timeZone);
    const date = requestedDate ?? currentDate;
    const archive = await getCaptureArchive(date, timeZone, now);
    const cacheControl = cacheControlForArchive(date < currentDate);

    return Response.json(
      archive,
      {
        headers: {
          "Cache-Control": cacheControl,
        },
      },
    );
  } catch (error) {
    if (error instanceof ArchiveRequestError) {
      return Response.json({ error: error.message }, { status: 400 });
    }
    const message = error instanceof Error ? error.message : "Unable to read captures";
    return Response.json({ error: message }, { status: 500 });
  }
}
