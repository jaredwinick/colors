import {
  localDateForInstant,
  type CaptureArchive,
} from "../db/capture-archive";
import { getCaptureArchive, getDisplayTimeZone } from "../db/captures";
import { createDemoCaptures } from "../db/demo";
import { redirect } from "next/navigation";
import { archiveUrl, resolveArchiveDate } from "./archive-navigation";
import { SkyTimeline } from "./components/SkyTimeline";

export async function renderArchivePage(requestedDate: string) {
  const timeZone = getDisplayTimeZone();
  const now = new Date();
  const currentDate = localDateForInstant(now, timeZone);
  const selection = resolveArchiveDate(requestedDate, currentDate, timeZone);
  if (!selection.isCanonical) {
    redirect(archiveUrl(selection.date));
  }

  let archive: CaptureArchive = await getCaptureArchive(
    selection.date,
    timeZone,
    now,
  );
  let isLive = true;

  if (
    process.env.NODE_ENV === "development" &&
    archive.isCurrentDay &&
    archive.captureCount === 0
  ) {
    const sampleCaptures = createDemoCaptures(selection.date, timeZone);
    archive = {
      ...archive,
      captureCount: sampleCaptures.length,
      captures: sampleCaptures,
    };
    isLive = false;
  }

  return (
    <SkyTimeline
      initialArchive={archive}
      initialCurrentDate={currentDate}
      initialIsLive={isLive}
    />
  );
}
