import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import { gzipSync } from "node:zlib";
import test from "node:test";

const CLIENT_ASSET_DIRECTORY = new URL("../dist/client/assets/", import.meta.url);
const MAXIMUM_GZIP_JAVASCRIPT_BYTES = 100 * 1024;
const MAXIMUM_GZIP_CSS_BYTES = 8 * 1024;

async function compressedBytes(extension) {
  const names = (await readdir(CLIENT_ASSET_DIRECTORY)).filter((name) =>
    name.endsWith(extension),
  );
  const files = await Promise.all(
    names.map((name) => readFile(new URL(name, CLIENT_ASSET_DIRECTORY))),
  );
  return files.reduce((total, file) => total + gzipSync(file).byteLength, 0);
}

test("the complete client asset set stays inside transfer budgets", async () => {
  const [javascriptBytes, cssBytes] = await Promise.all([
    compressedBytes(".js"),
    compressedBytes(".css"),
  ]);

  assert.ok(
    javascriptBytes <= MAXIMUM_GZIP_JAVASCRIPT_BYTES,
    `client JavaScript is ${javascriptBytes} compressed bytes`,
  );
  assert.ok(
    cssBytes <= MAXIMUM_GZIP_CSS_BYTES,
    `client CSS is ${cssBytes} compressed bytes`,
  );
});
