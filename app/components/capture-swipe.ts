export type CaptureNavigationDirection = "previous" | "next";

type Point = {
  x: number;
  y: number;
};

const MINIMUM_HORIZONTAL_DISTANCE = 48;
const HORIZONTAL_DOMINANCE_RATIO = 1.25;

export function captureNavigationIndex(
  currentIndex: number,
  captureCount: number,
  direction: CaptureNavigationDirection,
): number | null {
  if (currentIndex < 0 || currentIndex >= captureCount) return null;
  const destination = currentIndex + (direction === "previous" ? -1 : 1);
  return destination >= 0 && destination < captureCount ? destination : null;
}

export function captureSwipeDirection(
  start: Point,
  end: Point,
): CaptureNavigationDirection | null {
  const horizontalDistance = end.x - start.x;
  const verticalDistance = end.y - start.y;

  if (
    Math.abs(horizontalDistance) < MINIMUM_HORIZONTAL_DISTANCE ||
    Math.abs(horizontalDistance) <
      Math.abs(verticalDistance) * HORIZONTAL_DOMINANCE_RATIO
  ) {
    return null;
  }

  return horizontalDistance < 0 ? "next" : "previous";
}
