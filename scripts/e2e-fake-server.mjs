#!/usr/bin/env node

import crypto from "node:crypto";
import http from "node:http";

const args = process.argv.slice(2);
const portIndex = args.indexOf("--port");
const port = Number(portIndex >= 0 ? args[portIndex + 1] : process.env.LIVETYPE_E2E_PORT || 8788);
const deviceSecret = "e2e-device-secret";
let nextItem = 1;
let reportedSessions = 0;
let reportedSeconds = 0;

function json(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json",
    "content-length": Buffer.byteLength(payload),
    "access-control-allow-origin": "*",
  });
  res.end(payload);
}

function authorized(req) {
  return req.headers["x-device-secret"] === deviceSecret;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function usageSummary() {
  const usdMicros = Math.round(reportedSeconds * 17000 / 60);
  const window = { seconds: reportedSeconds, usd_micros: usdMicros, sessions: reportedSessions };
  return {
    model: "gpt-live-transcribe",
    price: { usd_micros_per_minute: 17000, unit: "duration", estimated: false },
    windows: { today: window, last_7d: window, last_30d: window },
    tz_offset_minutes: 0,
    as_of: new Date().toISOString(),
    source: "device_reported",
  };
}

const server = http.createServer(async (req, res) => {
  if (req.url === "/token" && req.method === "POST") {
    if (!authorized(req)) {
      json(res, 401, { error: "Unauthorized" });
      return;
    }
    await readBody(req);
    console.log(JSON.stringify({ event: "token", ok: true }));
    json(res, 200, { value: "e2e-client-secret" });
    return;
  }

  if (req.url?.startsWith("/usage") && req.method === "GET") {
    if (!authorized(req)) {
      json(res, 401, { error: "Unauthorized" });
      return;
    }
    console.log(JSON.stringify({ event: "usage_get", ok: true }));
    json(res, 200, usageSummary());
    return;
  }

  if (req.url === "/usage" && req.method === "POST") {
    if (!authorized(req)) {
      json(res, 401, { error: "Unauthorized" });
      return;
    }
    try {
      const body = JSON.parse(await readBody(req));
      const seconds = Number(body?.usage?.seconds || 0);
      reportedSessions += 1;
      reportedSeconds += seconds;
      console.log(JSON.stringify({ event: "usage_post", item_id: body.item_id, seconds }));
      json(res, 202, { ok: true, duplicate: false });
    } catch {
      json(res, 400, { error: "Malformed JSON" });
    }
    return;
  }

  json(res, 404, { error: "Not found" });
});

function sendFrame(socket, payload, opcode = 0x1) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
  let header;
  if (data.length < 126) {
    header = Buffer.from([0x80 | opcode, data.length]);
  } else if (data.length < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(data.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(data.length), 2);
  }
  socket.write(Buffer.concat([header, data]));
}

function decodeFrames(socket, state) {
  while (state.buffer.length >= 2) {
    const first = state.buffer[0];
    const second = state.buffer[1];
    const masked = (second & 0x80) !== 0;
    let length = second & 0x7f;
    let offset = 2;
    if (length === 126) {
      if (state.buffer.length < 4) return;
      length = state.buffer.readUInt16BE(2);
      offset = 4;
    } else if (length === 127) {
      if (state.buffer.length < 10) return;
      length = Number(state.buffer.readBigUInt64BE(2));
      offset = 10;
    }
    const maskOffset = masked ? 4 : 0;
    const frameLength = offset + maskOffset + length;
    if (state.buffer.length < frameLength) return;
    const mask = masked ? state.buffer.subarray(offset, offset + 4) : null;
    const payloadStart = offset + maskOffset;
    const payload = Buffer.from(state.buffer.subarray(payloadStart, payloadStart + length));
    state.buffer = state.buffer.subarray(frameLength);
    if (mask) {
      for (let i = 0; i < payload.length; i += 1) payload[i] ^= mask[i % 4];
    }
    const opcode = first & 0x0f;
    if (opcode === 0x8) {
      socket.end();
      return;
    }
    if (opcode === 0x9) {
      sendFrame(socket, payload, 0xA);
      continue;
    }
    if (opcode !== 0x1) continue;
    let event;
    try {
      event = JSON.parse(payload.toString("utf8"));
    } catch {
      sendFrame(socket, JSON.stringify({ type: "error", error: { message: "Malformed JSON" } }));
      continue;
    }
    if (event.type === "session.update") {
      console.log(JSON.stringify({ event: "session_update", ok: true }));
      sendFrame(socket, JSON.stringify({ type: "session.updated" }));
    } else if (event.type === "input_audio_buffer.commit") {
      const itemId = `e2e-item-${nextItem++}`;
      console.log(JSON.stringify({ event: "ws_commit", item_id: itemId }));
      sendFrame(socket, JSON.stringify({
        type: "conversation.item.input_audio_transcription.delta",
        item_id: itemId,
        delta: "Hello from LiveType",
      }));
      sendFrame(socket, JSON.stringify({
        type: "conversation.item.input_audio_transcription.completed",
        item_id: itemId,
        transcript: "Hello from LiveType",
        usage: { type: "duration", seconds: 2 },
      }));
    }
  }
}

server.on("upgrade", (req, socket) => {
  if (req.url !== "/realtime" || req.headers.authorization !== "Bearer e2e-client-secret") {
    socket.end("HTTP/1.1 401 Unauthorized\r\n\r\n");
    return;
  }
  const key = req.headers["sec-websocket-key"];
  const accept = crypto.createHash("sha1")
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest("base64");
  socket.write([
    "HTTP/1.1 101 Switching Protocols",
    "Upgrade: websocket",
    "Connection: Upgrade",
    `Sec-WebSocket-Accept: ${accept}`,
    "\r\n",
  ].join("\r\n"));
  console.log(JSON.stringify({ event: "ws_open", ok: true }));
  const state = { buffer: Buffer.alloc(0) };
  socket.on("data", (chunk) => {
    state.buffer = Buffer.concat([state.buffer, chunk]);
    decodeFrames(socket, state);
  });
});

server.listen(port, "0.0.0.0", () => {
  console.log(`E2E_SERVER_READY ${port}`);
});

function shutdown() {
  server.close(() => process.exit(0));
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
