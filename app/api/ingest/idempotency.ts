export const CAPTURE_UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export type StoredCapture = {
  id: string;
  capturedAt: string;
  receivedAt: string;
  imageKey: string;
  imageType: string;
  imageBytes: number;
  imageSha256: string | null;
  paletteJson: string;
  deviceId: string;
};

export type NewCapture = Omit<StoredCapture, "imageSha256"> & {
  imageSha256: string;
};

export type CaptureRepository = {
  findById(id: string): Promise<StoredCapture | null>;
  insert(capture: NewCapture): Promise<void>;
};

export type ImageObjectMetadata = {
  contentType: string;
  captureId: string;
  imageSha256: string;
};

export type CaptureObjectStore = {
  put(
    key: string,
    image: ArrayBuffer,
    metadata: ImageObjectMetadata,
  ): Promise<void>;
  delete(key: string): Promise<void>;
};

export type PersistCaptureResult = {
  capture: StoredCapture;
  created: boolean;
};

export class RequestValidationError extends Error {}

export class IdempotencyConflictError extends Error {
  constructor(captureId: string) {
    super(`capture_id ${captureId} was already used for different content`);
    this.name = "IdempotencyConflictError";
  }
}

export function readCaptureId(value: unknown): string {
  if (typeof value !== "string" || !CAPTURE_UUID_PATTERN.test(value)) {
    throw new RequestValidationError("capture_id must be a UUIDv4");
  }
  return value.toLowerCase();
}

export async function sha256Hex(image: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", image);
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

export function buildImageKey(
  capturedAt: string,
  captureId: string,
  imageSha256: string,
  extension: string,
): string {
  const datePath = capturedAt.slice(0, 10);
  return `${datePath}/${captureId}/${imageSha256}.${extension}`;
}

export function isExactRetry(
  existing: StoredCapture,
  candidate: NewCapture,
): boolean {
  return (
    existing.id === candidate.id &&
    existing.capturedAt === candidate.capturedAt &&
    existing.imageKey === candidate.imageKey &&
    existing.imageType === candidate.imageType &&
    existing.imageBytes === candidate.imageBytes &&
    existing.imageSha256 === candidate.imageSha256 &&
    existing.paletteJson === candidate.paletteJson &&
    existing.deviceId === candidate.deviceId
  );
}

export async function persistIdempotentCapture({
  repository,
  objects,
  candidate,
  image,
}: {
  repository: CaptureRepository;
  objects: CaptureObjectStore;
  candidate: NewCapture;
  image: ArrayBuffer;
}): Promise<PersistCaptureResult> {
  const existing = await repository.findById(candidate.id);
  if (existing) {
    if (isExactRetry(existing, candidate)) {
      return { capture: existing, created: false };
    }
    throw new IdempotencyConflictError(candidate.id);
  }

  await objects.put(candidate.imageKey, image, {
    contentType: candidate.imageType,
    captureId: candidate.id,
    imageSha256: candidate.imageSha256,
  });

  try {
    await repository.insert(candidate);
    return { capture: candidate, created: true };
  } catch (insertError) {
    const racedCapture = await repository.findById(candidate.id);
    if (racedCapture) {
      if (isExactRetry(racedCapture, candidate)) {
        return { capture: racedCapture, created: false };
      }
      if (racedCapture.imageKey !== candidate.imageKey) {
        await objects.delete(candidate.imageKey);
      }
      throw new IdempotencyConflictError(candidate.id);
    }

    await objects.delete(candidate.imageKey);
    throw insertError;
  }
}
