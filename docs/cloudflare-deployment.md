# Cloudflare production deployment

The production application is the `colors-sky-archive` Cloudflare Worker at
<https://colors-sky-archive.jaredwinick.workers.dev>. It uses the
`colors-production` D1 database and the `colors-sky-images` R2 bucket through
the `DB` and `SKY_IMAGES` bindings declared in `wrangler.jsonc`.

## Concept C cutover

The redesigned archive was deployed on September 7, 2026 from Git commit
`034539f9cf6be94267980bc9d9bdad1e9c95a399`.

- Deployment ID: `6f111180-b1d9-4aac-970d-b24552ca0e2f`
- Worker version: `409865e7-7630-40bb-a0a1-28ba9e36f5a2` (version 9)
- Release tag: `issue-53-concept-c`
- Previous deployment ID: `33193b05-fc13-40f1-b936-88430d84a2ae`
- Previous Worker version: `8e260680-7a39-4e45-ac9a-3c18014ffd8b` (version 8)

The production build and automated tests passed before deployment. A Cloudflare
dry run confirmed the D1, R2, compiled-asset, and display-time-zone bindings.
The live checks after deployment confirmed:

- the root redirects to the current `America/Denver` archive day;
- the current day is returned newest first with valid eight-color palettes;
- September 6 returns all 96 expected quarter-hour captures;
- initial archive HTML contains no source-image elements;
- current and historical HTML/JSON use their intended cache policies;
- an R2-backed JPEG is readable through `/api/images` with immutable caching;
- the unchanged Android client successfully uploaded a new capture at
  `2026-09-07T14:15:01.355Z`, increasing the current day from 33 to 34 rows;
- the new capture's eight-color palette and 182,041-byte JPEG were readable;
- the live Worker log showed successful page, asset, API, image, and ingest
  requests with no exceptions during the observation window.

## Normal deployment

From a clean, reviewed `main` checkout using the repository's pinned tools:

```powershell
pnpm test
.\node_modules\.bin\wrangler.cmd deploy --dry-run --keep-vars
.\node_modules\.bin\wrangler.cmd deploy --keep-vars
```

`--keep-vars` preserves values configured in the Cloudflare dashboard. Worker
secrets such as `INGEST_TOKEN` are retained by Cloudflare and must never be
committed to the repository.

After publishing, verify `/`, the current `/api/captures` response, and one
returned `/api/images` URL. Then watch the next scheduled Android upload before
declaring the release complete.

## Exact rollback for the Concept C cutover

If version 9 must be withdrawn, route production back to the recorded version 8
with:

```powershell
.\node_modules\.bin\wrangler.cmd rollback 8e260680-7a39-4e45-ac9a-3c18014ffd8b --name colors-sky-archive --message "Rollback Concept C archive deployment" --yes
```

Confirm the command reports a successful deployment, then repeat the live
archive, image-route, and Android-ingest checks. A rollback changes the Worker
code and static assets; it does not delete D1 rows or R2 objects created after
the original version was deployed.

To inspect the active and recent rollback points:

```powershell
.\node_modules\.bin\wrangler.cmd deployments list
.\node_modules\.bin\wrangler.cmd versions list
```

