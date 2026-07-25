"use client";

import { useEffect, useMemo, useState } from "react";
import type { CaptureView } from "../../db/captures";

type Props = {
  initialCaptures: CaptureView[];
  initialIsLive: boolean;
};

function formatTime(value: string) {
  return new Intl.DateTimeFormat("en-US", {
    hour: "numeric",
    minute: "2-digit",
  }).format(new Date(value));
}

function formatDate(value: string) {
  const date = new Date(value);
  const today = new Date();
  if (date.toDateString() === today.toDateString()) return "Today";
  return new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "numeric",
  }).format(date);
}

export function SkyTimeline({ initialCaptures, initialIsLive }: Props) {
  const [captures, setCaptures] = useState(initialCaptures);
  const [isLive, setIsLive] = useState(initialIsLive);

  useEffect(() => {
    if (!initialIsLive) return;

    const refresh = async () => {
      try {
        const response = await fetch("/api/captures", { cache: "no-store" });
        if (!response.ok) return;
        const data = (await response.json()) as { captures: CaptureView[] };
        setCaptures(data.captures);
        setIsLive(true);
      } catch {
        // Preserve the last successful view through a temporary network loss.
      }
    };

    const timer = window.setInterval(refresh, 60_000);
    return () => window.clearInterval(timer);
  }, [initialIsLive]);

  const newest = captures[0];
  const dateLabel = useMemo(
    () =>
      newest
        ? new Intl.DateTimeFormat("en-US", {
            weekday: "long",
            month: "long",
            day: "numeric",
            year: "numeric",
          }).format(new Date(newest.capturedAt))
        : "Today",
    [newest],
  );

  return (
    <main className="site-shell">
      <header className="masthead">
        <div className="wordmark">Colors</div>
        <div className="masthead-center">An observation of light</div>
        <div className="live-mark">
          <span className={`live-dot ${isLive ? "" : "sample"}`} />
          {isLive ? "Live archive" : "Designed sample"}
        </div>
      </header>

      <section className="intro" aria-labelledby="page-title">
        <div>
          <p className="eyebrow">{dateLabel} · last 24 hours</p>
          <h1 id="page-title">
            A day written
            <br />
            by the <em>sky.</em>
          </h1>
        </div>
        <p className="intro-copy">
          A quiet record of the colors above us, gathered at regular intervals.
          The newest light arrives at the top.
        </p>
      </section>

      <section aria-label="Sky color timeline">
        <div className="timeline-head" aria-hidden="true">
          <span>Time</span>
          <span>Source</span>
          <span>
            <b>Extracted palette</b>
            <b>{captures.length} observations</b>
          </span>
        </div>
        <div className="timeline">
          {captures.length === 0 ? (
            <p className="empty-state">
              The sky is waiting. The first capture will appear here.
            </p>
          ) : (
            captures.map((capture) => (
              <article className="capture-row" key={capture.id}>
                <time
                  className="capture-time"
                  dateTime={capture.capturedAt}
                  title={new Date(capture.capturedAt).toLocaleString()}
                >
                  {formatTime(capture.capturedAt)}
                  <small>{formatDate(capture.capturedAt)}</small>
                </time>
                <div
                  className="sky-frame"
                  style={
                    capture.imageUrl
                      ? undefined
                      : {
                          background: `linear-gradient(145deg, ${capture.palette
                            .map((color) => color.hex)
                            .join(", ")})`,
                        }
                  }
                >
                  {capture.imageUrl ? (
                    // The upload endpoint controls this same-origin URL.
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      src={capture.imageUrl}
                      alt={`Sky at ${formatTime(capture.capturedAt)}`}
                      loading="lazy"
                    />
                  ) : null}
                </div>
                <div
                  className="palette"
                  aria-label={`Palette captured at ${formatTime(capture.capturedAt)}`}
                >
                  {capture.palette.map((color, index) => (
                    <div
                      className="swatch"
                      key={`${capture.id}-${color.hex}-${index}`}
                      style={{
                        backgroundColor: color.hex,
                        flexGrow: Math.max(1, color.weight * 100),
                      }}
                      title={`${color.hex} · ${Math.round(color.weight * 100)}%`}
                    >
                      <span className="swatch-label">{color.hex}</span>
                    </div>
                  ))}
                </div>
              </article>
            ))
          )}
        </div>
      </section>

      <footer className="footer">
        <p>
          Each band is distilled from one photograph. Width shows how much of
          the image each color occupies; together, the rows become a portrait of
          the day.
        </p>
        <a href="#page-title">Return to the newest light ↑</a>
      </footer>
    </main>
  );
}
