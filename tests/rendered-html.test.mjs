import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";

test("the production bundle and sky archive surface are present", async () => {
  await access(new URL("../dist/server/index.js", import.meta.url));

  const [page, archivePage, dayPage, timeline, imageOverlays, layout, packageJson] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/archive-page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/day/[date]/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/components/SkyTimeline.tsx", import.meta.url), "utf8"),
    readFile(
      new URL("../app/components/CaptureImageOverlays.tsx", import.meta.url),
      "utf8",
    ),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../package.json", import.meta.url), "utf8"),
  ]);

  assert.match(page, /redirect\(archiveUrl\(currentDate\)\)/);
  assert.match(archivePage, /getCaptureArchive\(/);
  assert.match(archivePage, /redirect\(archiveUrl\(selection\.date\)\)/);
  assert.match(dayPage, /params: Promise<\{ date: string \}>/);
  assert.match(dayPage, /renderArchivePage\(date\)/);
  assert.match(timeline, /Atmospheric ribbon/);
  assert.match(timeline, /Palette · newest first/);
  assert.match(timeline, /accentPreservingWidths/);
  assert.match(timeline, /archive\.timeZone/);
  assert.match(timeline, /aria-haspopup="dialog"/);
  assert.match(timeline, /onPointerEnter/);
  assert.match(timeline, /onFocus/);
  assert.match(timeline, /requestAnimationFrame\(\(\) => origin\?\.focus\(\)\)/);
  assert.match(imageOverlays, /if \(!preview\) return null/);
  assert.match(imageOverlays, /if \(!viewer\) return null/);
  assert.match(imageOverlays, /dialog\.showModal\(\)/);
  assert.match(imageOverlays, /onCancel/);
  assert.match(imageOverlays, /key=\{preview\.capture\.id\}/);
  assert.match(timeline, /archiveDateNeighbors/);
  assert.match(timeline, /aria-label="Archive day navigation"/);
  assert.match(timeline, /aria-disabled="true"/);
  assert.match(timeline, /document\.visibilityState/);
  assert.match(timeline, /visibilitychange/);
  assert.match(timeline, /mergeCaptureArchives/);
  assert.match(timeline, /if \(!initialIsLive \|\| !archive\.isCurrentDay\) return/);
  assert.match(timeline, /No captures were recorded for this day/);
  assert.doesNotMatch(timeline, /A day written|className="sky-frame"|>Source</);
  assert.match(layout, /Colors — a day written by the sky/);
  assert.match(layout, /og\.png/);
  assert.doesNotMatch(
    `${page}\n${archivePage}\n${dayPage}\n${timeline}\n${imageOverlays}\n${layout}\n${packageJson}`,
    /codex-preview|react-loading-skeleton|Starter Project/i,
  );
});
