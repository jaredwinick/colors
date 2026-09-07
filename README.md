# Colors

Colors is a living 24-hour archive of palettes extracted from regularly
scheduled sky photographs.

## Architecture

- **Website and API:** one Cloudflare-compatible vinext Worker
- **Metadata:** D1 (`captures` table)
- **Original photographs:** R2 (`SKY_IMAGES` bucket)
- **Capture:** a Termux script on Android using Termux:API, Pillow, and curl

The public page reads only the newest 24 hours and refreshes every minute. The
authenticated ingest endpoint writes the original image to R2 and its searchable
metadata and weighted palette to D1. If the database is empty, the site shows a
designed sample so a fresh deployment is not a blank page.

## Local development

Install dependencies, then run:

```sh
npm run dev
```

The local database and object bucket are simulated by the Cloudflare tooling.
Generate a migration after schema edits with:

```sh
npm run db:generate
```

## Hosted configuration

The deployment needs:

- D1 binding `DB`
- R2 binding `SKY_IMAGES`
- secret environment value `INGEST_TOKEN`
- plain-text environment value `DISPLAY_TIME_ZONE` containing an IANA zone
  (production currently uses `America/Denver`)

Generate a long random token, configure it only in the hosting environment and
on the phone, and never commit it. The schema migration in `drizzle/` creates
the required indexes.

The production release procedure, deployed version record, and rollback steps
are maintained in [docs/cloudflare-deployment.md](docs/cloudflare-deployment.md).

## Ingest contract

`POST /api/ingest` accepts `multipart/form-data` with:

- `capture_id`: client-generated UUIDv4, reused unchanged for every retry
- `image`: JPEG, PNG, or WebP, up to 12 MB
- `captured_at`: ISO-8601 datetime
- `device_id`: optional source identifier
- `palette`: JSON array of 3–10 `{ "hex": "#RRGGBB", "weight": 0.25 }`

Send the token as `Authorization: Bearer …`.

The first accepted request returns `201`. An exact retry with the same ID,
image, timestamp, palette, and device returns the existing capture with `200`
and `idempotentReplay: true`. Reusing an ID for different content returns `409`.

See [android/README.md](android/README.md) for the phone setup.

## Archive read contract

`GET /api/captures?date=YYYY-MM-DD` returns the captures belonging to one
calendar day in `DISPLAY_TIME_ZONE`. If `date` is omitted, the endpoint uses
the current date in that configured zone. The Worker never derives the archive
zone from its own runtime or from a visitor's browser.

The response has this shape:

```json
{
  "date": "2026-08-16",
  "timeZone": "America/Denver",
  "captureCount": 96,
  "invalidCaptureCount": 0,
  "isCurrentDay": false,
  "captures": [
    {
      "id": "capture UUID",
      "capturedAt": "2026-08-17T05:45:00.000Z",
      "imageUrl": "/api/images/encoded-object-key",
      "palette": [{ "hex": "#52739A", "weight": 0.28 }]
    }
  ]
}
```

Captures are ordered newest first. Invalid dates return `400`. A record with
malformed palette JSON is omitted while `invalidCaptureCount` records the
problem, allowing the rest of the day to render. Source images remain private
in R2 and are read through the same-origin `/api/images` route.

Local midnight boundaries are converted to UTC before querying D1. The query
uses the indexed half-open range `captured_at >= start AND captured_at < end`,
so daylight-saving days naturally span 23 or 25 hours. Current-day responses
have a 30-second shared cache lifetime with stale revalidation; completed days
have a one-day shared cache lifetime because their data changes infrequently.

The archive page keeps its selected day in a shareable `/day/YYYY-MM-DD` path.
A bare root request redirects to the current date in `DISPLAY_TIME_ZONE`;
invalid and future dates normalize to that same current-day URL. Previous-day
and next-day links use ordinary URLs, so direct links, reloads, and browser
back/forward navigation retain the selected day. The next-day control is
unavailable on the current day.

Only the current day polls for updates. Polling pauses while the page is hidden
and refreshes immediately when it becomes visible again. Missing scheduled
intervals are represented by the absence of a capture rather than placeholder
rows; the header reports the number of captures that actually exist and labels
15 minutes as the intended schedule.

Responsive, accessibility, and performance expectations are documented in
[docs/web-quality.md](docs/web-quality.md). The production test suite enforces
the client transfer budgets and the archive's key structural guarantees.
