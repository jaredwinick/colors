"use client";

import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type PointerEvent,
  type RefObject,
} from "react";
import type { CaptureView } from "../../db/capture-archive";
import { CaptureMiniPalette } from "./CaptureMiniPalette";
import {
  captureSwipeDirection,
  type CaptureNavigationDirection,
} from "./capture-swipe";

export type PreviewIntent = "pointer" | "focus";

export type CapturePreview = {
  capture: CaptureView;
  intent: PreviewIntent;
  position: CSSProperties;
  timestamp: string;
};

export type CaptureViewer = {
  capture: CaptureView;
  timestamp: string;
};

type ImageState = "loading" | "ready" | "unavailable";

function OnDemandImage({
  capture,
  timestamp,
  kind,
}: {
  capture: CaptureView;
  timestamp: string;
  kind: "preview" | "viewer";
}) {
  const [state, setState] = useState<ImageState>("loading");

  if (!capture.imageUrl) {
    return (
      <div className="capture-image-unavailable" role="status">
        Source photograph unavailable
      </div>
    );
  }

  return (
    <div
      className={`capture-image-stage capture-image-stage-${kind}`}
      data-image-state={state}
    >
      <div className="capture-image-frame">
        {state === "loading" ? (
          <span
            className="capture-image-loading"
            role={kind === "viewer" ? "status" : undefined}
          >
            Loading photograph…
          </span>
        ) : null}
        {state === "unavailable" ? (
          <div
            className="capture-image-unavailable"
            role={kind === "viewer" ? "alert" : undefined}
          >
            Photograph unavailable
            <small>Try this capture again later or continue browsing.</small>
          </div>
        ) : null}
        {/* The private R2 route is loaded only after explicit user intent. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          key={capture.id}
          src={capture.imageUrl}
          alt={`Sky photograph captured ${timestamp}`}
          decoding="async"
          draggable={false}
          onLoad={() => setState("ready")}
          onError={() => setState("unavailable")}
        />
      </div>
      <CaptureMiniPalette capture={capture} timestamp={timestamp} />
    </div>
  );
}

export function CaptureImagePreview({ preview }: { preview: CapturePreview | null }) {
  if (!preview) return null;

  return (
    <aside
      className="capture-preview"
      style={preview.position}
      aria-label={`Source photograph preview for ${preview.timestamp}`}
    >
      <OnDemandImage
        key={preview.capture.id}
        capture={preview.capture}
        timestamp={preview.timestamp}
        kind="preview"
      />
      <div className="capture-preview-caption">
        <span>Source photograph</span>
        <time dateTime={preview.capture.capturedAt}>{preview.timestamp}</time>
      </div>
    </aside>
  );
}

export function CaptureImageDialog({
  viewer,
  dialogRef,
  onDismiss,
  previousCapture,
  nextCapture,
  onNavigate,
}: {
  viewer: CaptureViewer | null;
  dialogRef: RefObject<HTMLDialogElement | null>;
  onDismiss: () => void;
  previousCapture: CaptureViewer | null;
  nextCapture: CaptureViewer | null;
  onNavigate: (direction: CaptureNavigationDirection) => void;
}) {
  const backdropPress = useRef(false);
  const swipeStart = useRef<{
    pointerId: number;
    x: number;
    y: number;
  } | null>(null);
  const isOpen = viewer !== null;

  useEffect(() => {
    if (!isOpen || !dialogRef.current) return;
    const dialog = dialogRef.current;
    const previousOverflow = document.body.style.overflow;
    if (!dialog.open) dialog.showModal();
    document.body.style.overflow = "hidden";

    return () => {
      document.body.style.overflow = previousOverflow;
      if (dialog.open) dialog.close();
    };
  }, [dialogRef, isOpen]);

  if (!viewer) return null;

  const close = () => {
    if (dialogRef.current?.open) dialogRef.current.close();
  };

  const navigate = (direction: CaptureNavigationDirection) => {
    const destination = direction === "previous" ? previousCapture : nextCapture;
    if (destination) onNavigate(direction);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDialogElement>) => {
    if (event.key === "ArrowLeft" && previousCapture) {
      event.preventDefault();
      navigate("previous");
    } else if (event.key === "ArrowRight" && nextCapture) {
      event.preventDefault();
      navigate("next");
    }
  };

  const startSwipe = (event: PointerEvent<HTMLDivElement>) => {
    if (event.pointerType !== "touch") return;
    swipeStart.current = {
      pointerId: event.pointerId,
      x: event.clientX,
      y: event.clientY,
    };
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  const finishSwipe = (event: PointerEvent<HTMLDivElement>) => {
    const start = swipeStart.current;
    swipeStart.current = null;
    if (!start || start.pointerId !== event.pointerId) return;

    const direction = captureSwipeDirection(start, {
      x: event.clientX,
      y: event.clientY,
    });
    if (direction) navigate(direction);
  };

  return (
    <dialog
      className="capture-dialog"
      ref={dialogRef}
      aria-labelledby="capture-dialog-title"
      aria-describedby="capture-dialog-description"
      onKeyDown={handleKeyDown}
      onClose={onDismiss}
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      onPointerDown={(event) => {
        backdropPress.current = event.target === event.currentTarget;
      }}
      onClick={(event) => {
        if (backdropPress.current && event.target === event.currentTarget) close();
        backdropPress.current = false;
      }}
    >
      <div className="capture-dialog-card">
        <header className="capture-dialog-header">
          <div>
            <p>Source photograph</p>
            <h2 id="capture-dialog-title">{viewer.timestamp}</h2>
          </div>
          <button type="button" className="dialog-close" onClick={close} autoFocus>
            <span aria-hidden="true">×</span>
            <span>Close photograph</span>
          </button>
        </header>
        <p className="sr-only" id="capture-dialog-description">
          Swipe left or press Right Arrow for the next, older capture. Swipe
          right or press Left Arrow for the previous, newer capture. Press
          Escape or use the Close photograph button to return to the selected
          palette.
        </p>
        <p className="sr-only" aria-live="polite" aria-atomic="true">
          Showing sky photograph captured {viewer.timestamp}
        </p>
        <div
          className="capture-dialog-swipe-area"
          onPointerDown={startSwipe}
          onPointerUp={finishSwipe}
          onPointerCancel={() => {
            swipeStart.current = null;
          }}
        >
          <OnDemandImage
            key={viewer.capture.id}
            capture={viewer.capture}
            timestamp={viewer.timestamp}
            kind="viewer"
          />
        </div>
        <nav className="capture-dialog-navigation" aria-label="Capture navigation">
          <button
            type="button"
            className="capture-navigation-button"
            disabled={!previousCapture}
            aria-label={
              previousCapture
                ? `Previous, newer capture from ${previousCapture.timestamp}`
                : "No previous, newer capture"
            }
            aria-keyshortcuts="ArrowLeft"
            onClick={() => navigate("previous")}
          >
            <span aria-hidden="true">←</span>
            <span>Newer</span>
          </button>
          <span className="capture-navigation-position" aria-hidden="true">
            Swipe to browse
          </span>
          <button
            type="button"
            className="capture-navigation-button capture-navigation-button-next"
            disabled={!nextCapture}
            aria-label={
              nextCapture
                ? `Next, older capture from ${nextCapture.timestamp}`
                : "No next, older capture"
            }
            aria-keyshortcuts="ArrowRight"
            onClick={() => navigate("next")}
          >
            <span>Older</span>
            <span aria-hidden="true">→</span>
          </button>
        </nav>
      </div>
    </dialog>
  );
}
