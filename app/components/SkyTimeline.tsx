"use client";

import { useEffect, useRef, useState } from "react";
import {
  localDateForInstant,
  type CaptureArchive,
  type CaptureView,
} from "../../db/capture-archive";
import { archiveDateNeighbors, archiveUrl } from "../archive-navigation";
import {
  CaptureImageDialog,
  CaptureImagePreview,
  type CapturePreview,
  type CaptureViewer,
  type PreviewIntent,
} from "./CaptureImageOverlays";
import { imagePreviewPosition } from "./image-preview-position";
import { accentPreservingWidths } from "./palette-widths";
import { mergeCaptureArchives } from "./archive-refresh";

type Props = {
  initialArchive: CaptureArchive;
  initialCurrentDate: string;
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

function formatTimestamp(value: string, timeZone: string) {
  return new Intl.DateTimeFormat("en-US", {
    timeZone,
    dateStyle: "full",
    timeStyle: "short",
  }).format(new Date(value));
}

function paletteDescription(capture: CaptureView) {
  return capture.palette
    .map(
      (color) => `${color.hex}, ${Math.round(color.weight * 100)} percent`,
    )
    .join("; ");
}

function PaletteBand({ capture, newest }: { capture: CaptureView; newest: boolean }) {
  const widths = accentPreservingWidths(capture.palette);

  return (
    <div
      className={`palette-band${newest ? " palette-band-newest" : ""}`}
      role="img"
      aria-label={`${newest ? "Newest palette. " : ""}${paletteDescription(capture)}`}
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

export function SkyTimeline({
  initialArchive,
  initialCurrentDate,
  initialIsLive,
}: Props) {
  const [archive, setArchive] = useState(initialArchive);
  const [currentDate, setCurrentDate] = useState(initialCurrentDate);
  const [isLive, setIsLive] = useState(initialIsLive);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [preview, setPreview] = useState<CapturePreview | null>(null);
  const [viewer, setViewer] = useState<CaptureViewer | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const viewerOrigin = useRef<HTMLButtonElement | null>(null);

  const showPreview = (
    capture: CaptureView,
    element: HTMLElement,
    intent: PreviewIntent,
  ) => {
    if (!capture.imageUrl) return;
    if (
      intent === "pointer" &&
      !window.matchMedia("(hover: hover) and (pointer: fine)").matches
    ) {
      return;
    }
    const rect = element.getBoundingClientRect();
    const position = imagePreviewPosition(rect, {
      width: window.innerWidth,
      height: window.innerHeight,
    });
    setPreview({
      capture,
      intent,
      position: { left: position.left, top: position.top },
      timestamp: formatTimestamp(capture.capturedAt, archive.timeZone),
    });
  };

  const hidePreview = (captureId: string, intent: PreviewIntent) => {
    setPreview((current) =>
      current?.capture.id === captureId && current.intent === intent
        ? null
        : current,
    );
  };

  const openViewer = (
    capture: CaptureView,
    origin: HTMLButtonElement,
  ) => {
    if (!capture.imageUrl) return;
    viewerOrigin.current = origin;
    setPreview(null);
    setViewer({
      capture,
      timestamp: formatTimestamp(capture.capturedAt, archive.timeZone),
    });
  };

  const dismissViewer = () => {
    setViewer(null);
    const origin = viewerOrigin.current;
    viewerOrigin.current = null;
    window.requestAnimationFrame(() => origin?.focus());
  };

  useEffect(() => {
    if (!initialIsLive || !archive.isCurrentDay) return;

    let interval: number | null = null;
    let request: AbortController | null = null;
    let inFlight = false;
    let disposed = false;

    const refresh = async () => {
      if (inFlight || document.visibilityState !== "visible") return;
      inFlight = true;
      const controller = new AbortController();
      request = controller;
      setIsRefreshing(true);
      try {
        const response = await fetch(
          `/api/captures?date=${encodeURIComponent(archive.date)}`,
          { cache: "no-store", signal: controller.signal },
        );
        if (!response.ok) return;
        const nextArchive = (await response.json()) as CaptureArchive;
        if (disposed || request !== controller) return;
        setArchive((current) => mergeCaptureArchives(current, nextArchive));
        setCurrentDate(
          localDateForInstant(new Date(), nextArchive.timeZone),
        );
        setIsLive(true);
      } catch {
        // Preserve the last successful view through a temporary network loss.
      } finally {
        if (request === controller) {
          inFlight = false;
          request = null;
          if (!disposed) setIsRefreshing(false);
        }
      }
    };

    const stopPolling = () => {
      if (interval !== null) window.clearInterval(interval);
      interval = null;
    };

    const startPolling = () => {
      if (interval === null && document.visibilityState === "visible") {
        interval = window.setInterval(() => void refresh(), 60_000);
      }
    };

    const handleVisibilityChange = () => {
      if (document.visibilityState === "hidden") {
        stopPolling();
        request?.abort();
        request = null;
        inFlight = false;
        setIsRefreshing(false);
        return;
      }
      void refresh();
      startPolling();
    };

    startPolling();
    document.addEventListener("visibilitychange", handleVisibilityChange);
    return () => {
      disposed = true;
      stopPolling();
      request?.abort();
      document.removeEventListener("visibilitychange", handleVisibilityChange);
    };
  }, [archive.date, archive.isCurrentDay, initialIsLive]);

  const { previousDate, nextDate } = archiveDateNeighbors(
    archive.date,
    currentDate,
  );
  const statusLabel = !isLive
    ? "Designed sample"
    : archive.isCurrentDay
      ? "Live archive"
      : "Historical archive";

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
          <nav className="day-navigation" aria-label="Archive day navigation">
            <a
              href={archiveUrl(previousDate)}
              rel="prev"
              aria-label={`View ${formatDate(previousDate)}`}
            >
              <span aria-hidden="true">←</span> Previous day
            </a>
            {nextDate ? (
              <a
                href={archiveUrl(nextDate)}
                rel="next"
                aria-label={`View ${formatDate(nextDate)}`}
              >
                Next day <span aria-hidden="true">→</span>
              </a>
            ) : (
              <span className="day-navigation-disabled" aria-disabled="true">
                Next day <span aria-hidden="true">→</span>
              </span>
            )}
          </nav>
        </div>
        <div className="archive-meta" aria-label="Archive summary">
          <span className="archive-status">
            <i
              className={
                isLive && archive.isCurrentDay
                  ? "status-live"
                  : isLive
                    ? "status-history"
                    : "status-sample"
              }
              aria-hidden="true"
            />
            {statusLabel}
          </span>
          <span>
            {archive.captureCount} capture{archive.captureCount === 1 ? "" : "s"}
          </span>
          <span>Scheduled every 15 minutes</span>
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
            <p>
              {archive.isCurrentDay
                ? "No colors have arrived for today yet."
                : "No captures were recorded for this day."}
            </p>
            <span>
              {archive.isCurrentDay
                ? "The next successful sky capture will appear here."
                : "Choose the previous or next available day to continue browsing."}
            </span>
          </div>
        ) : (
          <ol className="palette-timeline">
            {archive.captures.map((capture, index) => (
              <li className="palette-row" key={capture.id}>
                <time
                  dateTime={capture.capturedAt}
                  title={formatTimestamp(capture.capturedAt, archive.timeZone)}
                >
                  {formatTime(capture.capturedAt, archive.timeZone)}
                </time>
                {capture.imageUrl ? (
                  <button
                    type="button"
                    className="palette-trigger"
                    aria-haspopup="dialog"
                    aria-label={`View source photograph captured ${formatTimestamp(
                      capture.capturedAt,
                      archive.timeZone,
                    )}. Palette: ${paletteDescription(capture)}`}
                    onPointerEnter={(event) =>
                      showPreview(capture, event.currentTarget, "pointer")
                    }
                    onPointerLeave={() => hidePreview(capture.id, "pointer")}
                    onFocus={(event) =>
                      showPreview(capture, event.currentTarget, "focus")
                    }
                    onBlur={() => hidePreview(capture.id, "focus")}
                    onClick={(event) => openViewer(capture, event.currentTarget)}
                  >
                    <PaletteBand capture={capture} newest={index === 0} />
                  </button>
                ) : (
                  <PaletteBand capture={capture} newest={index === 0} />
                )}
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

      <CaptureImagePreview preview={preview} />
      <CaptureImageDialog
        viewer={viewer}
        dialogRef={dialogRef}
        onDismiss={dismissViewer}
      />
    </main>
  );
}
