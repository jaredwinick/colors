import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";

test("the production bundle and sky archive surface are present", async () => {
  await access(new URL("../dist/server/index.js", import.meta.url));

  const [
    page,
    archivePage,
    dayPage,
    timeline,
    imageOverlays,
    layout,
    packageJson,
    worker,
    styles,
  ] = await Promise.all([
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
    readFile(new URL("../worker/index.ts", import.meta.url), "utf8"),
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
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
  assert.match(timeline, /tabIndex=\{capture\.id === tabStopId \? 0 : -1\}/);
  assert.match(timeline, /aria-keyshortcuts="ArrowUp ArrowDown Home End Enter"/);
  assert.match(timeline, /aria-live="polite"/);
  assert.match(timeline, /aria-atomic="true"/);
  assert.match(timeline, /<ol className="palette-timeline">/);
  assert.match(timeline, /<li className="palette-row"/);
  assert.match(timeline, /<time/);
  assert.match(timeline, /onPointerEnter/);
  assert.match(timeline, /onFocus/);
  assert.match(timeline, /requestAnimationFrame\(\(\) => origin\?\.focus\(\)\)/);
  assert.match(imageOverlays, /if \(!preview\) return null/);
  assert.match(imageOverlays, /if \(!viewer\) return null/);
  assert.match(imageOverlays, /dialog\.showModal\(\)/);
  assert.match(imageOverlays, /onCancel/);
  assert.match(imageOverlays, /role=\{kind === "viewer" \? "alert" : undefined\}/);
  assert.match(imageOverlays, /aria-describedby="capture-dialog-description"/);
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
  assert.match(worker, /url\.pathname\.startsWith\("\/assets\/"\)/);
  assert.match(worker, /return env\.ASSETS\.fetch\(request\)/);
  assert.match(styles, /@media \(prefers-color-scheme: dark\)/);
  assert.match(styles, /@media \(prefers-reduced-motion: reduce\)/);
  assert.match(styles, /@media \(hover: none\), \(pointer: coarse\)/);
  assert.match(styles, /min-width: 0/);
  assert.match(
    styles,
    /\.capture-image-stage-preview\s*\{[^}]*aspect-ratio: 4 \/ 3/s,
  );
  assert.match(
    styles,
    /\.capture-image-stage-viewer\s*\{[^}]*height: min\(76dvh, 860px\)/s,
  );
  assert.doesNotMatch(
    `${page}\n${archivePage}\n${dayPage}\n${timeline}\n${imageOverlays}\n${layout}\n${packageJson}`,
    /codex-preview|react-loading-skeleton|Starter Project/i,
  );
});
