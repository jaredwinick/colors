export type PreviewAnchor = {
  top: number;
  right: number;
  bottom: number;
};

export type PreviewViewport = {
  width: number;
  height: number;
};

export function imagePreviewPosition(
  anchor: PreviewAnchor,
  viewport: PreviewViewport,
  preview = { width: 280, height: 254 },
) {
  const margin = 16;
  const gap = 12;
  const maximumLeft = Math.max(margin, viewport.width - preview.width - margin);
  const left = Math.min(Math.max(margin, anchor.right - preview.width), maximumLeft);
  const above = anchor.top - preview.height - gap;
  const below = anchor.bottom + gap;
  const maximumTop = Math.max(margin, viewport.height - preview.height - margin);
  const top = Math.min(
    Math.max(margin, above >= margin ? above : below),
    maximumTop,
  );

  return { left, top };
}
