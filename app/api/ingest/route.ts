import { env } from "cloudflare:workers";
import type { PaletteColor } from "../../../db/captures";

export const dynamic = "force-dynamic";

const HEX = /^#[0-9A-F]{6}$/i;
const ALLOWED_TYPES = new Map([
  ["image/jpeg", "jpg"],
  ["image/png", "png"],
  ["image/webp", "webp"],
]);

function readPalette(value: FormDataEntryValue | null): PaletteColor[] {
  if (typeof value !== "string") throw new Error("palette is required");
  const parsed = JSON.parse(value) as unknown;
  if (!Array.isArray(parsed) || parsed.length < 3 || parsed.length > 10) {
    throw new Error("palette must contain 3–10 colors");
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
      throw new Error("palette contains an invalid color");
    }
    return { hex: entry.hex.toUpperCase(), weight: entry.weight };
  });

  const total = colors.reduce((sum, color) => sum + color.weight, 0);
  return colors.map((color) => ({
    ...color,
    weight: Number((color.weight / total).toFixed(4)),
  }));
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
    const image = form.get("image");
    if (!(image instanceof File)) {
      return Response.json({ error: "image is required" }, { status: 400 });
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
    const capturedAtValue = String(form.get("captured_at") ?? "");
    const capturedAt = new Date(capturedAtValue);
    if (Number.isNaN(capturedAt.getTime())) {
      return Response.json(
        { error: "captured_at must be an ISO-8601 datetime" },
        { status: 400 },
      );
    }
    if (capturedAt.getTime() > Date.now() + 5 * 60 * 1000) {
      return Response.json(
        { error: "captured_at cannot be in the future" },
        { status: 400 },
      );
    }

    const id = crypto.randomUUID();
    const datePath = capturedAt.toISOString().slice(0, 10);
    const imageKey = `${datePath}/${id}.${extension}`;
    const receivedAt = new Date().toISOString();
    const deviceId = String(form.get("device_id") ?? "android-sky-camera").slice(
      0,
      100,
    );

    await env.SKY_IMAGES.put(imageKey, image.stream(), {
      httpMetadata: { contentType: image.type },
      customMetadata: {
        capturedAt: capturedAt.toISOString(),
        deviceId,
      },
    });

    try {
      await env.DB.prepare(
        `INSERT INTO captures
          (id, captured_at, received_at, image_key, image_type, image_bytes, palette_json, device_id)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      )
        .bind(
          id,
          capturedAt.toISOString(),
          receivedAt,
          imageKey,
          image.type,
          image.size,
          JSON.stringify(palette),
          deviceId,
        )
        .run();
    } catch (error) {
      await env.SKY_IMAGES.delete(imageKey);
      throw error;
    }

    return Response.json(
      {
        capture: {
          id,
          capturedAt: capturedAt.toISOString(),
          imageUrl: `/api/images/${imageKey}`,
          palette,
        },
      },
      { status: 201 },
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : "Upload failed";
    return Response.json({ error: message }, { status: 400 });
  }
}
