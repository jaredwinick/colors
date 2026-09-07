import assert from "node:assert/strict";
import test from "node:test";

function luminance(hex: string): number {
  const channels = hex
    .slice(1)
    .match(/.{2}/g)!
    .map((value) => Number.parseInt(value, 16) / 255)
    .map((value) =>
      value <= 0.04045
        ? value / 12.92
        : ((value + 0.055) / 1.055) ** 2.4,
    );
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}

function contrast(foreground: string, background: string): number {
  const lighter = Math.max(luminance(foreground), luminance(background));
  const darker = Math.min(luminance(foreground), luminance(background));
  return (lighter + 0.05) / (darker + 0.05);
}

test("light and dark text tokens meet normal-text contrast", () => {
  const pairs = [
    ["#111a20", "#f5f7f8"],
    ["#587081", "#f5f7f8"],
    ["#175a7c", "#f5f7f8"],
    ["#eef3f5", "#12171b"],
    ["#91a2ad", "#12171b"],
    ["#8bc9e8", "#12171b"],
  ];

  for (const [foreground, background] of pairs) {
    assert.ok(
      contrast(foreground, background) >= 4.5,
      `${foreground} on ${background} does not meet 4.5:1`,
    );
  }
});
