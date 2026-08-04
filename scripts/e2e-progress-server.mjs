#!/usr/bin/env node

import { createReadStream } from "node:fs";
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";

function option(args, name, fallback = "") {
  const index = args.indexOf(name);
  return index >= 0 ? (args[index + 1] ?? fallback) : fallback;
}

const args = process.argv.slice(2);
const port = Number(option(args, "--port", "8790"));
const progressFile = option(args, "--progress");
const htmlFile = option(args, "--html");

if (!progressFile || !htmlFile) {
  console.error("Usage: e2e-progress-server.mjs --progress PATH --html PATH [--port PORT]");
  process.exit(2);
}

const server = createServer(async (request, response) => {
  try {
    if (request.url?.startsWith("/progress.json")) {
      const body = await readFile(progressFile);
      response.writeHead(200, {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "no-store",
      });
      response.end(body);
      return;
    }
    if (request.url === "/health") {
      response.writeHead(200, { "content-type": "text/plain; charset=utf-8" });
      response.end("ok\n");
      return;
    }
    response.writeHead(200, {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
    });
    createReadStream(htmlFile).pipe(response);
  } catch (error) {
    response.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    response.end(`${error.message}\n`);
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`E2E_PROGRESS_READY http://127.0.0.1:${port}/`);
});
