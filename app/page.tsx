import { getRecentCaptures } from "../db/captures";
import { demoCaptures } from "../db/demo";
import { SkyTimeline } from "./components/SkyTimeline";

export const dynamic = "force-dynamic";

export default async function Home() {
  let captures = demoCaptures;
  let isLive = false;

  try {
    const liveCaptures = await getRecentCaptures(24);
    if (liveCaptures.length > 0) {
      captures = liveCaptures;
      isLive = true;
    }
  } catch {
    // The designed sample keeps local previews and a fresh deployment useful
    // until the first migration and phone upload have completed.
  }

  return <SkyTimeline initialCaptures={captures} initialIsLive={isLive} />;
}
