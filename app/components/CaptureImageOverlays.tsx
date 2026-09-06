"use client";

import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type RefObject,
} from "react";
import type { CaptureView } from "../../db/capture-archive";

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
      {state === "loading" ? (
        <span className="capture-image-loading">Loading photograph…</span>
      ) : null}
      {state === "unavailable" ? (
        <div className="capture-image-unavailable" role="status">
          Photograph unavailable
          <small>Move away and try again.</small>
        </div>
      ) : null}
      {/* The private R2 route is loaded only after explicit user intent. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        key={capture.id}
        src={capture.imageUrl}
        alt={`Sky photograph captured ${timestamp}`}
        decoding="async"
        onLoad={() => setState("ready")}
        onError={() => setState("unavailable")}
      />
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
}: {
  viewer: CaptureViewer | null;
  dialogRef: RefObject<HTMLDialogElement | null>;
  onDismiss: () => void;
}) {
  const backdropPress = useRef(false);

  useEffect(() => {
    if (!viewer || !dialogRef.current) return;
    const dialog = dialogRef.current;
    const previousOverflow = document.body.style.overflow;
    if (!dialog.open) dialog.showModal();
    document.body.style.overflow = "hidden";

    return () => {
      document.body.style.overflow = previousOverflow;
      if (dialog.open) dialog.close();
    };
  }, [dialogRef, viewer]);

  if (!viewer) return null;

  const close = () => {
    if (dialogRef.current?.open) dialogRef.current.close();
  };

  return (
    <dialog
      className="capture-dialog"
      ref={dialogRef}
      aria-labelledby="capture-dialog-title"
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
        <OnDemandImage
          key={viewer.capture.id}
          capture={viewer.capture}
          timestamp={viewer.timestamp}
          kind="viewer"
        />
      </div>
    </dialog>
  );
}
