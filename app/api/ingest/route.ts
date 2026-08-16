import { env } from "cloudflare:workers";
import type { PaletteColor } from "../../../db/captures";
import {
  buildImageKey,
  IdempotencyConflictError,
  persistIdempotentCapture,
  readCaptureId,
  RequestValidationError,
  sha256Hex,
  type CaptureObjectStore,
  type CaptureRepository,
  type NewCapture,
  type StoredCapture,
} from "./idempotency";

export const dynamic = "force-dynamic";

const HEX = /^#[0-9A-F]{6}$/i;
const ALLOWED_TYPES = new Map([
  ["image/jpeg", "jpg"],
  ["image/png", "png"],
  ["image/webp", "webp"],
]);

type CaptureRow = {
  id: string;
  captured_at: string;
  received_at: string;
  image_key: string;
  image_type: string;
  image_bytes: number;
  image_sha256: string | null;
  palette_json: string;
  device_id: string | null;
};

function readPalette(value: FormDataEntryValue | null): PaletteColor[] {
  if (typeof value !== "string") {
    throw new RequestValidationError("palette is required");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(value) as unknown;
  } catch {
    throw new RequestValidationError("palette must be valid JSON");
  }
  if (!Array.isArray(parsed) || parsed.length < 3 || parsed.length > 10) {
    throw new RequestValidationError("palette must contain 3-10 colors");
  }

  const colors = parsed.map((entry) => {
    if (
      typeof entry !== "object" ||
      entry === null ||
      !("hex" in entry) ||
      !("weight" in entry) ||
      typeof entry.hex !== "string" ||
      !HEX.test(entry.hex) ||
      typeof entry.weight !== "number" ||
      !Number.isFinite(entry.weight) ||
      entry.weight <= 0
    ) {
      throw new RequestValidationError("palette contains an invalid color");
    }
    return { hex: entry.hex.toUpperCase(), weight: entry.weight };
  });

  const total = colors.reduce((sum, color) => sum + color.weight, 0);
  return colors.map((color) => ({
    ...color,
    weight: Number((color.weight / total).toFixed(4)),
  }));
}

function readCapturedAt(value: FormDataEntryValue | null): string {
  const capturedAt = new Date(String(value ?? ""));
  if (Number.isNaN(capturedAt.getTime())) {
    throw new RequestValidationError(
      "captured_at must be an ISO-8601 datetime",
    );
  }
  if (capturedAt.getTime() > Date.now() + 5 * 60 * 1000) {
    throw new RequestValidationError("captured_at cannot be in the future");
  }
  return capturedAt.toISOString();
}

function mapCaptureRow(row: CaptureRow): StoredCapture {
  return {
    id: row.id,
    capturedAt: row.captured_at,
    receivedAt: row.received_at,
    imageKey: row.image_key,
    imageType: row.image_type,
    imageBytes: row.image_bytes,
    imageSha256: row.image_sha256,
    paletteJson: row.palette_json,
    deviceId: row.device_id ?? "",
  };
}

function createCaptureRepository(database: typeof env.DB): CaptureRepository {
  return {
    async findById(id) {
      const row = await database
        .prepare(
          `SELECT id, captured_at, received_at, image_key, image_type,
                  image_bytes, image_sha256, palette_json, device_id
           FROM captures
           WHERE id = ?`,
        )
        .bind(id)
        .first<CaptureRow>();
      return row ? mapCaptureRow(row) : null;
    },
    async insert(capture) {
      await database
        .prepare(
          `INSERT INTO captures
            (id, captured_at, received_at, image_key, image_type, image_bytes,
             image_sha256, palette_json, device_id)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        )
        .bind(
          capture.id,
          capture.capturedAt,
          capture.receivedAt,
          capture.imageKey,
          capture.imageType,
          capture.imageBytes,
          capture.imageSha256,
          capture.paletteJson,
          capture.deviceId,
        )
        .run();
    },
  };
}

function createCaptureObjectStore(
  bucket: typeof env.SKY_IMAGES,
): CaptureObjectStore {
  return {
    async put(key, image, metadata) {
      await bucket.put(key, image, {
        httpMetadata: { contentType: metadata.contentType },
        customMetadata: {
          captureId: metadata.captureId,
          imageSha256: metadata.imageSha256,
        },
      });
    },
    async delete(key) {
      await bucket.delete(key);
    },
  };
}

function captureResponse(
  capture: StoredCapture,
  created: boolean,
): Response {
  return Response.json(
    {
      capture: {
        id: capture.id,
        capturedAt: capture.capturedAt,
        imageUrl: `/api/images/${capture.imageKey}`,
        palette: JSON.parse(capture.paletteJson) as PaletteColor[],
      },
      idempotentReplay: !created,
    },
    { status: created ? 201 : 200 },
  );
}

export async function POST(request: Request) {
  const expectedToken = env.INGEST_TOKEN;
  const suppliedToken = request.headers.get("authorization");
  if (!expectedToken) {
    return Response.json(
      { error: "INGEST_TOKEN is not configured" },
      { status: 503 },
    );
  }
  if (suppliedToken !== `Bearer ${expectedToken}`) {
    return Response.json({ error: "Unauthorized" }, { status: 401 });
  }

  if (!env.DB || !env.SKY_IMAGES) {
    return Response.json({ error: "Storage is unavailable" }, { status: 503 });
  }

  try {
    const form = await request.formData();
    const captureId = readCaptureId(form.get("capture_id"));
    const image = form.get("image");
    if (!(image instanceof File)) {
      throw new RequestValidationError("image is required");
    }

    const extension = ALLOWED_TYPES.get(image.type);
    if (!extension) {
      return Response.json(
        { error: "image must be JPEG, PNG, or WebP" },
        { status: 415 },
      );
    }
    if (image.size <= 0 || image.size > 12 * 1024 * 1024) {
      return Response.json(
        { error: "image must be between 1 byte and 12 MB" },
        { status: 413 },
      );
    }

    const palette = readPalette(form.get("palette"));
    const capturedAt = readCapturedAt(form.get("captured_at"));
    const deviceId = String(
      form.get("device_id") ?? "android-sky-camera",
    ).slice(0, 100);
    const imageData = await image.arrayBuffer();
    const imageSha256 = await sha256Hex(imageData);
    const imageKey = buildImageKey(
      capturedAt,
      captureId,
      imageSha256,
      extension,
    );

    const candidate: NewCapture = {
      id: captureId,
      capturedAt,
      receivedAt: new Date().toISOString(),
      imageKey,
      imageType: image.type,
      imageBytes: image.size,
      imageSha256,
      paletteJson: JSON.stringify(palette),
      deviceId,
    };
    const result = await persistIdempotentCapture({
      repository: createCaptureRepository(env.DB),
      objects: createCaptureObjectStore(env.SKY_IMAGES),
      candidate,
      image: imageData,
    });
    return captureResponse(result.capture, result.created);
  } catch (error) {
    if (error instanceof RequestValidationError) {
      return Response.json({ error: error.message }, { status: 400 });
    }
    if (error instanceof IdempotencyConflictError) {
      return Response.json({ error: error.message }, { status: 409 });
    }
    console.error("Ingest failed", error);
    return Response.json({ error: "Upload failed" }, { status: 500 });
  }
}
