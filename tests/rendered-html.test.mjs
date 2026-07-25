import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";

test("the production bundle and sky archive surface are present", async () => {
  await access(new URL("../dist/server/index.js", import.meta.url));

  const [page, timeline, layout, packageJson] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/components/SkyTimeline.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../package.json", import.meta.url), "utf8"),
  ]);

  assert.match(page, /getRecentCaptures\(24\)/);
  assert.match(timeline, /A day written/);
  assert.match(timeline, /Live archive/);
  assert.match(layout, /Colors — a day written by the sky/);
  assert.match(layout, /og\.png/);
  assert.doesNotMatch(
    `${page}\n${timeline}\n${layout}\n${packageJson}`,
    /codex-preview|react-loading-skeleton|Starter Project/i,
  );
});
