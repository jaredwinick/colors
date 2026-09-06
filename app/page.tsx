import {
  localDateForInstant,
  type CaptureArchive,
} from "../db/capture-archive";
import { getCaptureArchive, getDisplayTimeZone } from "../db/captures";
import { createDemoCaptures } from "../db/demo";
import { SkyTimeline } from "./components/SkyTimeline";

export const dynamic = "force-dynamic";

export default async function Home() {
  const timeZone = getDisplayTimeZone();
  const now = new Date();
  const date = localDateForInstant(now, timeZone);
  const sampleCaptures = createDemoCaptures(date, timeZone);
  let archive: CaptureArchive = {
    date,
    timeZone,
    captureCount: sampleCaptures.length,
    invalidCaptureCount: 0,
    isCurrentDay: true,
    captures: sampleCaptures,
  };
  let isLive = false;

  try {
    const liveArchive = await getCaptureArchive(date, timeZone, now);
    if (liveArchive.captureCount > 0) {
      archive = liveArchive;
      isLive = true;
    }
  } catch {
    // Keep a complete designed day visible in local development and before the
    // first production capture arrives.
  }

  return <SkyTimeline initialArchive={archive} initialIsLive={isLive} />;
}
