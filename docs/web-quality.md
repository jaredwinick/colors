# Web archive quality

The archive is designed to keep a complete 96-capture day readable, operable,
and quick on a small phone as well as a desktop. This document records the
release checks and the budgets that prevent those qualities from regressing.

## Enforced budgets

`npm test` builds the production site and fails when either compressed client
budget is exceeded:

| Resource | Budget | Issue #52 result |
| --- | ---: | ---: |
| All client JavaScript | 100 KiB gzip | 86.6 KiB (88,628 bytes) |
| All CSS | 8 KiB gzip | 4.7 KiB (4,788 bytes) |

The rendered-page test also enforces the following structural guarantees:

- source photographs are not rendered or downloaded until a visitor asks to
  see one;
- the image stages reserve their dimensions, avoiding a layout jump when an
  image loads;
- current-day HTML and JSON may be cached briefly, while completed-day HTML
  and JSON receive the longer immutable-archive policy;
- all eight palette colors and their measured percentages are represented in
  the accessible name even though only one palette row is in the Tab order;
- dark mode, reduced motion, and coarse-pointer adaptations remain present.

## Responsive browser matrix

A production Worker build was loaded with 96 captures and eight colors per
capture. The browser's vertical scrollbar accounts for the 15-pixel difference
between viewport and content width.

| Viewport | Content / scroll width | Rows | Smallest swatch | Initial images | Result |
| ---: | ---: | ---: | ---: | ---: | --- |
| 320 px | 305 / 305 px | 96 | 11.76 px | 0 | Pass |
| 736 px | 721 / 721 px | 96 | 32.10 px | 0 | Pass |
| 1024 px | 1009 / 1009 px | 96 | 49.39 px | 0 | Pass |
| 1440 px | 1425 / 1425 px | 96 | 57.98 px | 0 | Pass |

At each width the newest row was 23:45, the oldest was 00:00, every row
contained eight visible swatches, and no horizontal scrolling or clipping was
present. Essential labels were at least 11 pixels. The 320, 736, and 1440
layouts were also inspected visually.

Light-mode rendering was inspected in the browser. Light and dark foreground,
muted-text, and focus-accent token pairs are checked mathematically against the
WCAG 2.1 AA 4.5:1 contrast threshold. Reduced-motion and coarse-pointer rules
are asserted by the rendered-page test; the latter increases the palette target
height and disables hover-only presentation on touch-style devices.

## Keyboard and screen-reader behavior

The archive uses a real heading, navigation region, summary, named timeline
region, list, times, buttons, and dialog. A browser accessibility-tree review
confirmed that all 96 rows expose their timestamp and full eight-color palette.

Only the current palette is a Tab stop. Up Arrow, Down Arrow, Home, and End move
between palette rows, while Enter opens the source photograph. Escape closes
the dialog and restores focus to the originating row. The viewer has a named
close control and instructions; an image failure is announced as an alert.
Hover-preview failures remain silent. Refreshes announce only the number of
new captures added, so the one-minute poll does not create repetitive chatter.

## Mobile performance check

The 320-pixel, 96-row page was loaded through a local network profile with a
150 ms response delay and approximately 100 KiB/s per response. The practical
targets and observed results were:

| Check | Target | Issue #52 result |
| --- | ---: | ---: |
| Complete page load | no more than 3,000 ms | 1,816 ms |
| Palette click to visible dialog | no more than 350 ms | 288 ms |
| Initial source-image requests | 0 | 0 |
| Layout shift caused by images | 0 | 0 (images absent; stages reserved) |

These measurements are a reproducible smoke-test profile rather than a promise
about a visitor's network. The transfer budgets and structural checks are the
continuous safeguards that run on every change.

## Release checklist

1. Run `npm test` and the targeted linter for `app`, `db`, `tests`, and
   `worker`.
2. Load a 96-capture day at 320, 736, 1024, and 1440 pixels and confirm there
   is no horizontal scroll, clipping, or missing swatch.
3. Use only the keyboard to enter the timeline, move between rows, open the
   viewer, and close it with Escape.
4. Inspect both light and dark appearances, reduced-motion behavior, and a
   coarse-pointer layout before a release that materially changes the UI.

