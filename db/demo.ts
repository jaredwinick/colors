import type { CaptureView, PaletteColor } from "./capture-archive.ts";
import { utcRangeForLocalDate } from "./capture-archive.ts";

const weights = [0.24, 0.19, 0.16, 0.13, 0.1, 0.08, 0.06, 0.04];

const colorKeyframes = [
  ["#050A14", "#0A1224", "#111C34", "#1C2942", "#2D3950", "#465064", "#695F70", "#97776F"],
  ["#11192A", "#24334C", "#455878", "#756C86", "#A97883", "#D8846E", "#F1A16C", "#C8526B"],
  ["#2767A0", "#3980B9", "#5D9BC9", "#86B5D5", "#B3D0E0", "#DDE7E8", "#F1EBE0", "#8C919E"],
  ["#1D4E7C", "#3977A2", "#6694B1", "#9BAFC0", "#C7C5BE", "#E6BE9C", "#D97C63", "#A4475C"],
  ["#07101E", "#101B30", "#1D2942", "#303A51", "#4A485B", "#6B5662", "#8D6768", "#B07A6E"],
] as const;

function rgb(hex: string) {
  return [
    Number.parseInt(hex.slice(1, 3), 16),
    Number.parseInt(hex.slice(3, 5), 16),
    Number.parseInt(hex.slice(5, 7), 16),
  ];
}

function interpolateHex(from: string, to: string, amount: number) {
  const start = rgb(from);
  const end = rgb(to);
  const channels = start.map((value, index) =>
    Math.round(value + (end[index] - value) * amount),
  );
  return `#${channels
    .map((channel) => channel.toString(16).padStart(2, "0"))
    .join("")}`.toUpperCase();
}

function paletteAt(progress: number): PaletteColor[] {
  const keyframePosition = progress * (colorKeyframes.length - 1);
  const startIndex = Math.min(
    Math.floor(keyframePosition),
    colorKeyframes.length - 2,
  );
  const amount = keyframePosition - startIndex;

  return colorKeyframes[startIndex].map((color, index) => ({
    hex: interpolateHex(color, colorKeyframes[startIndex + 1][index], amount),
    weight: weights[index],
  }));
}

export function createDemoCaptures(
  date: string,
  timeZone: string,
): CaptureView[] {
  const { start, end } = utcRangeForLocalDate(date, timeZone);
  const first = Date.parse(start);
  const last = Date.parse(end);
  const interval = 15 * 60 * 1000;
  const captures: CaptureView[] = [];

  for (let instant = first; instant < last; instant += interval) {
    const progress = (instant - first) / (last - first);
    captures.push({
      id: `sample-${new Date(instant).toISOString()}`,
      capturedAt: new Date(instant).toISOString(),
      imageUrl: null,
      palette: paletteAt(progress),
    });
  }

  return captures.reverse();
}
