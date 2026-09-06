declare namespace Cloudflare {
  interface Env {
    DB: D1Database;
    SKY_IMAGES: R2Bucket;
    INGEST_TOKEN?: string;
    DISPLAY_TIME_ZONE?: string;
  }
}
