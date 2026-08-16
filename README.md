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

Generate a long random token, configure it only in the hosting environment and
on the phone, and never commit it. The schema migration in `drizzle/` creates
the required indexes.

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
