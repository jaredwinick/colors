import assert from "node:assert/strict";
import test from "node:test";

import { withArchivePageCache } from "../worker/archive-cache.ts";

const TIME_ZONE = "America/Denver";
const NOW = new Date("2026-09-06T18:00:00.000Z");

test("archive HTML uses the same current and completed-day cache policy as data", () => {
  const current = withArchivePageCache(
    new Request("https://colors.example/day/2026-09-06"),
    new Response("today"),
    TIME_ZONE,
    NOW,
  );
  const completed = withArchivePageCache(
    new Request("https://colors.example/day/2026-09-05"),
    new Response("history"),
    TIME_ZONE,
    NOW,
  );

  assert.equal(
    current.headers.get("Cache-Control"),
    "public, max-age=30, s-maxage=30, stale-while-revalidate=120",
  );
  assert.equal(
    completed.headers.get("Cache-Control"),
    "public, max-age=300, s-maxage=86400, stale-while-revalidate=604800",
  );
});

test("non-archive and unsuccessful responses retain their existing headers", () => {
  const response = new Response("missing", {
    status: 404,
    headers: { "Cache-Control": "private" },
  });
  assert.equal(
    withArchivePageCache(
      new Request("https://colors.example/api/captures"),
      response,
      TIME_ZONE,
      NOW,
    ),
    response,
  );
});
