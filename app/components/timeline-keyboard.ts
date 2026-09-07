export type TimelineNavigationKey =
  | "ArrowUp"
  | "ArrowDown"
  | "Home"
  | "End";

export function timelineNavigationIndex(
  currentIndex: number,
  itemCount: number,
  key: string,
): number | null {
  if (itemCount <= 0 || currentIndex < 0 || currentIndex >= itemCount) {
    return null;
  }

  switch (key as TimelineNavigationKey) {
    case "ArrowUp":
      return Math.max(0, currentIndex - 1);
    case "ArrowDown":
      return Math.min(itemCount - 1, currentIndex + 1);
    case "Home":
      return 0;
    case "End":
      return itemCount - 1;
    default:
      return null;
  }
}
