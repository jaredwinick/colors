"use client";

import { useEffect, useState } from "react";
import type { CaptureArchive, CaptureView } from "../../db/capture-archive";
import { accentPreservingWidths } from "./palette-widths";

type Props = {
  initialArchive: CaptureArchive;
  initialIsLive: boolean;
};

function formatTime(value: string, timeZone: string) {
  return new Intl.DateTimeFormat("en-US", {
    timeZone,
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(new Date(value));
}

function formatDate(value: string) {
  const [year, month, day] = value.split("-").map(Number);
  return new Intl.DateTimeFormat("en-US", {
    timeZone: "UTC",
    weekday: "long",
    month: "long",
    day: "numeric",
    year: "numeric",
  }).format(new Date(Date.UTC(year, month - 1, day)));
}

function PaletteBand({ capture, newest }: { capture: CaptureView; newest: boolean }) {
  const widths = accentPreservingWidths(capture.palette);

  return (
    <div
      className={`palette-band${newest ? " palette-band-newest" : ""}`}
      role="img"
      aria-label={`${newest ? "Newest palette. " : ""}${capture.palette
        .map(
          (color) =>
            `${color.hex}, ${Math.round(color.weight * 100)} percent`,
        )
        .join("; ")}`}
    >
      {capture.palette.map((color, index) => (
        <span
          className="palette-swatch"
          key={`${capture.id}-${color.hex}-${index}`}
          style={{
            backgroundColor: color.hex,
            width: `${widths[index] * 100}%`,
          }}
          title={`${color.hex} · ${Math.round(color.weight * 100)}%`}
          aria-hidden="true"
        />
      ))}
    </div>
  );
}

export function SkyTimeline({ initialArchive, initialIsLive }: Props) {
  const [archive, setArchive] = useState(initialArchive);
  const [isLive, setIsLive] = useState(initialIsLive);
  const [isRefreshing, setIsRefreshing] = useState(false);

  useEffect(() => {
    if (!initialIsLive || !initialArchive.isCurrentDay) return;

    const refresh = async () => {
      setIsRefreshing(true);
      try {
        const response = await fetch(
          `/api/captures?date=${encodeURIComponent(initialArchive.date)}`,
          { cache: "no-store" },
        );
        if (!response.ok) return;
        const nextArchive = (await response.json()) as CaptureArchive;
        setArchive(nextArchive);
        setIsLive(true);
      } catch {
        // Preserve the last successful view through a temporary network loss.
      } finally {
        setIsRefreshing(false);
      }
    };

    const timer = window.setInterval(refresh, 60_000);
    return () => window.clearInterval(timer);
  }, [initialArchive.date, initialArchive.isCurrentDay, initialIsLive]);

  return (
    <main className="archive-shell">
      <header className="archive-header">
        <a className="wordmark" href="#timeline" aria-label="Colors archive home">
          colors
        </a>
        <div className="archive-title">
          <p className="archive-kicker">
            Atmospheric ribbon <span aria-hidden="true">·</span> accent-preserving
            widths
          </p>
          <h1>{formatDate(archive.date)}</h1>
        </div>
        <div className="archive-meta" aria-label="Archive summary">
          <span className="archive-status">
            <i
              className={isLive ? "status-live" : "status-sample"}
              aria-hidden="true"
            />
            {isLive ? "Live archive" : "Designed sample"}
          </span>
          <span>{archive.captureCount} palettes</span>
          <span>15 minute cadence</span>
        </div>
      </header>

      <section
        className="timeline-panel"
        id="timeline"
        aria-labelledby="timeline-title"
        aria-busy={isRefreshing}
      >
        <div className="timeline-heading">
          <span>Time</span>
          <h2 id="timeline-title">Palette · newest first</h2>
        </div>

        {archive.captures.length === 0 ? (
          <div className="empty-state">
            <p>No colors have arrived for this day yet.</p>
            <span>The next successful sky capture will appear here.</span>
          </div>
        ) : (
          <ol className="palette-timeline">
            {archive.captures.map((capture, index) => (
              <li className="palette-row" key={capture.id}>
                <time
                  dateTime={capture.capturedAt}
                  title={new Intl.DateTimeFormat("en-US", {
                    timeZone: archive.timeZone,
                    dateStyle: "full",
                    timeStyle: "short",
                  }).format(new Date(capture.capturedAt))}
                >
                  {formatTime(capture.capturedAt, archive.timeZone)}
                </time>
                <PaletteBand capture={capture} newest={index === 0} />
              </li>
            ))}
          </ol>
        )}

        {archive.invalidCaptureCount > 0 ? (
          <p className="record-note" role="status">
            {archive.invalidCaptureCount} malformed capture
            {archive.invalidCaptureCount === 1 ? " was" : "s were"} omitted.
          </p>
        ) : null}
      </section>

      <footer className="archive-footer">
        <p>
          Each ribbon distills one photograph. Rare colors retain a visible
          place while their original measured weights remain unchanged.
        </p>
        <a href="#timeline">Return to newest</a>
      </footer>
    </main>
  );
}
