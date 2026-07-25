CREATE TABLE `captures` (
	`id` text PRIMARY KEY NOT NULL,
	`captured_at` text NOT NULL,
	`received_at` text NOT NULL,
	`image_key` text NOT NULL,
	`image_type` text NOT NULL,
	`image_bytes` integer NOT NULL,
	`palette_json` text NOT NULL,
	`device_id` text
);
--> statement-breakpoint
CREATE UNIQUE INDEX `captures_image_key_unique` ON `captures` (`image_key`);--> statement-breakpoint
CREATE INDEX `captures_captured_at_idx` ON `captures` (`captured_at`);--> statement-breakpoint
CREATE INDEX `captures_device_captured_idx` ON `captures` (`device_id`,`captured_at`);