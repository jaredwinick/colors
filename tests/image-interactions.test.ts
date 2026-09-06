import assert from "node:assert/strict";
import test from "node:test";

import { imagePreviewPosition } from "../app/components/image-preview-position.ts";

test("image previews prefer the space above the intended row", () => {
  assert.deepEqual(
    imagePreviewPosition(
      { top: 500, right: 900, bottom: 512 },
      { width: 1000, height: 800 },
    ),
    { left: 620, top: 234 },
  );
});

test("image previews move below top rows and stay inside the viewport", () => {
  assert.deepEqual(
    imagePreviewPosition(
      { top: 20, right: 190, bottom: 32 },
      { width: 320, height: 600 },
    ),
    { left: 16, top: 44 },
  );
  assert.deepEqual(
    imagePreviewPosition(
      { top: 590, right: 1000, bottom: 602 },
      { width: 1000, height: 620 },
    ),
    { left: 704, top: 324 },
  );
});
