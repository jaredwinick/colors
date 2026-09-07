import type { CaptureView } from "../../db/capture-archive";
import { accentPreservingWidths } from "./palette-widths";

function paletteDescription(capture: CaptureView) {
  return capture.palette
    .map(
      (color) => `${color.hex}, ${Math.round(color.weight * 100)} percent`,
    )
    .join("; ");
}

export function CaptureMiniPalette({
  capture,
  timestamp,
}: {
  capture: CaptureView;
  timestamp: string;
}) {
  const widths = accentPreservingWidths(capture.palette);

  return (
    <div
      className="capture-mini-palette"
      role="img"
      aria-label={`Palette for ${timestamp}: ${paletteDescription(capture)}`}
    >
      {capture.palette.map((color, index) => (
        <span
          key={`${capture.id}-${color.hex}-${index}`}
          className="capture-mini-palette-swatch"
          style={{
            backgroundColor: color.hex,
            width: `${widths[index] * 100}%`,
          }}
          aria-hidden="true"
        />
      ))}
    </div>
  );
}
