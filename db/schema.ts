import { index, integer, sqliteTable, text } from "drizzle-orm/sqlite-core";

export const captures = sqliteTable(
  "captures",
  {
    id: text("id").primaryKey(),
    capturedAt: text("captured_at").notNull(),
    receivedAt: text("received_at").notNull(),
    imageKey: text("image_key").notNull().unique(),
    imageType: text("image_type").notNull(),
    imageBytes: integer("image_bytes").notNull(),
    paletteJson: text("palette_json").notNull(),
    deviceId: text("device_id"),
  },
  (table) => [
    index("captures_captured_at_idx").on(table.capturedAt),
    index("captures_device_captured_idx").on(table.deviceId, table.capturedAt),
  ],
);
