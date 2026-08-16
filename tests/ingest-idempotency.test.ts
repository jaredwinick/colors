import assert from "node:assert/strict";
import test from "node:test";

import {
  buildImageKey,
  IdempotencyConflictError,
  persistIdempotentCapture,
  readCaptureId,
  sha256Hex,
  type CaptureObjectStore,
  type CaptureRepository,
  type ImageObjectMetadata,
  type NewCapture,
  type StoredCapture,
} from "../app/api/ingest/idempotency.ts";

const CAPTURE_ID = "6fa459ea-ee8a-4ca4-894e-db77e160355e";
const IMAGE_HASH = "a".repeat(64);

function capture(overrides: Partial<NewCapture> = {}): NewCapture {
  const capturedAt = overrides.capturedAt ?? "2026-08-16T17:00:00.000Z";
  const id = overrides.id ?? CAPTURE_ID;
  const imageSha256 = overrides.imageSha256 ?? IMAGE_HASH;
  return {
    id,
    capturedAt,
    receivedAt: overrides.receivedAt ?? "2026-08-16T17:00:01.000Z",
    imageKey:
      overrides.imageKey ??
      buildImageKey(capturedAt, id, imageSha256, "jpg"),
    imageType: overrides.imageType ?? "image/jpeg",
    imageBytes: overrides.imageBytes ?? 3,
    imageSha256,
    paletteJson:
      overrides.paletteJson ??
      '[{"hex":"#112233","weight":0.5},{"hex":"#445566","weight":0.3},{"hex":"#778899","weight":0.2}]',
    deviceId: overrides.deviceId ?? "android-sky-camera",
  };
}

class MemoryCaptureRepository implements CaptureRepository {
  readonly captures = new Map<string, StoredCapture>();

  async findById(id: string): Promise<StoredCapture | null> {
    await Promise.resolve();
    return this.captures.get(id) ?? null;
  }

  async insert(value: NewCapture): Promise<void> {
    await Promise.resolve();
    if (this.captures.has(value.id)) {
      throw new Error("UNIQUE constraint failed: captures.id");
    }
    this.captures.set(value.id, { ...value });
  }
}

class MemoryObjectStore implements CaptureObjectStore {
  readonly objects = new Map<string, Uint8Array>();
  putCalls = 0;
  deleteCalls = 0;

  async put(
    key: string,
    image: ArrayBuffer,
    metadata: ImageObjectMetadata,
  ): Promise<void> {
    void metadata;
    this.putCalls += 1;
    this.objects.set(key, new Uint8Array(image).slice());
  }

  async delete(key: string): Promise<void> {
    this.deleteCalls += 1;
    this.objects.delete(key);
  }
}

class TwoPutBarrierObjectStore extends MemoryObjectStore {
  private putCount = 0;
  private readonly bothPuts: Promise<void>;
  private releaseBothPuts: () => void = () => undefined;

  constructor() {
    super();
    this.bothPuts = new Promise((resolve) => {
      this.releaseBothPuts = resolve;
    });
  }

  override async put(
    key: string,
    image: ArrayBuffer,
    metadata: ImageObjectMetadata,
  ): Promise<void> {
    await super.put(key, image, metadata);
    this.putCount += 1;
    if (this.putCount === 2) this.releaseBothPuts();
    await this.bothPuts;
  }
}

const image = Uint8Array.from([1, 2, 3]).buffer;

test("validates and normalizes client capture UUIDs", () => {
  assert.equal(readCaptureId(CAPTURE_ID.toUpperCase()), CAPTURE_ID);
  assert.throws(() => readCaptureId("not-a-uuid"), /UUIDv4/);
});

test("builds a deterministic hash-qualified R2 key", async () => {
  const hash = await sha256Hex(image);
  assert.equal(hash.length, 64);
  assert.equal(
    buildImageKey("2026-08-16T17:00:00.000Z", CAPTURE_ID, hash, "jpg"),
    `2026-08-16/${CAPTURE_ID}/${hash}.jpg`,
  );
});

test("stores the first upload once", async () => {
  const repository = new MemoryCaptureRepository();
  const objects = new MemoryObjectStore();
  const candidate = capture();

  const result = await persistIdempotentCapture({
    repository,
    objects,
    candidate,
    image,
  });

  assert.equal(result.created, true);
  assert.equal(repository.captures.size, 1);
  assert.equal(objects.objects.size, 1);
  assert.deepEqual(objects.objects.get(candidate.imageKey), new Uint8Array(image));
});

test("returns the existing capture after a response is lost", async () => {
  const repository = new MemoryCaptureRepository();
  const objects = new MemoryObjectStore();
  const firstAttempt = capture();

  await persistIdempotentCapture({
    repository,
    objects,
    candidate: firstAttempt,
    image,
  });
  const retry = await persistIdempotentCapture({
    repository,
    objects,
    candidate: capture({ receivedAt: "2026-08-16T17:00:05.000Z" }),
    image,
  });

  assert.equal(retry.created, false);
  assert.equal(retry.capture.receivedAt, firstAttempt.receivedAt);
  assert.equal(repository.captures.size, 1);
  assert.equal(objects.objects.size, 1);
  assert.equal(objects.putCalls, 1);
});

test("rejects conflicting reuse without changing stored data", async () => {
  const repository = new MemoryCaptureRepository();
  const objects = new MemoryObjectStore();
  const firstAttempt = capture();
  await persistIdempotentCapture({
    repository,
    objects,
    candidate: firstAttempt,
    image,
  });

  await assert.rejects(
    persistIdempotentCapture({
      repository,
      objects,
      candidate: capture({ deviceId: "different-device" }),
      image,
    }),
    IdempotencyConflictError,
  );
  assert.deepEqual(repository.captures.get(CAPTURE_ID), firstAttempt);
  assert.equal(repository.captures.size, 1);
  assert.equal(objects.objects.size, 1);
  assert.equal(objects.putCalls, 1);
});

test("concurrent exact retries converge on one row and object", async () => {
  const repository = new MemoryCaptureRepository();
  const objects = new TwoPutBarrierObjectStore();
  const attempts = await Promise.all([
    persistIdempotentCapture({
      repository,
      objects,
      candidate: capture(),
      image,
    }),
    persistIdempotentCapture({
      repository,
      objects,
      candidate: capture({ receivedAt: "2026-08-16T17:00:02.000Z" }),
      image,
    }),
  ]);

  assert.deepEqual(
    attempts.map((attempt) => attempt.created).sort(),
    [false, true],
  );
  assert.equal(repository.captures.size, 1);
  assert.equal(objects.objects.size, 1);
});

test("concurrent conflicting uploads remove the losing object", async () => {
  const repository = new MemoryCaptureRepository();
  const objects = new TwoPutBarrierObjectStore();
  const secondHash = "b".repeat(64);
  const second = capture({
    imageSha256: secondHash,
    imageKey: buildImageKey(
      "2026-08-16T17:00:00.000Z",
      CAPTURE_ID,
      secondHash,
      "jpg",
    ),
  });

  const attempts = await Promise.allSettled([
    persistIdempotentCapture({
      repository,
      objects,
      candidate: capture(),
      image,
    }),
    persistIdempotentCapture({
      repository,
      objects,
      candidate: second,
      image: Uint8Array.from([4, 5, 6]).buffer,
    }),
  ]);

  assert.equal(
    attempts.filter((attempt) => attempt.status === "fulfilled").length,
    1,
  );
  const rejection = attempts.find((attempt) => attempt.status === "rejected");
  assert(rejection && rejection.status === "rejected");
  assert(rejection.reason instanceof IdempotencyConflictError);
  assert.equal(repository.captures.size, 1);
  assert.equal(objects.objects.size, 1);
  const stored = repository.captures.get(CAPTURE_ID);
  assert(stored);
  assert(objects.objects.has(stored.imageKey));
  assert.equal(objects.deleteCalls, 1);
});

test("concurrent metadata conflicts sharing an image keep the winning object", async () => {
  const repository = new MemoryCaptureRepository();
  const objects = new TwoPutBarrierObjectStore();

  const attempts = await Promise.allSettled([
    persistIdempotentCapture({
      repository,
      objects,
      candidate: capture(),
      image,
    }),
    persistIdempotentCapture({
      repository,
      objects,
      candidate: capture({ deviceId: "different-device" }),
      image,
    }),
  ]);

  assert.equal(
    attempts.filter((attempt) => attempt.status === "fulfilled").length,
    1,
  );
  assert.equal(repository.captures.size, 1);
  assert.equal(objects.objects.size, 1);
  assert.equal(objects.deleteCalls, 0);
});
