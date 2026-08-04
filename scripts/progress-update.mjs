#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";

function readOption(args, name, fallback = "") {
  const index = args.indexOf(name);
  return index >= 0 ? (args[index + 1] ?? fallback) : fallback;
}

const args = process.argv.slice(2);
const file = readOption(args, "--file");
const section = readOption(args, "--section", "current");
const platform = readOption(args, "--platform", "shared");
const id = readOption(args, "--id");
const title = readOption(args, "--title");
const status = readOption(args, "--status", "in_progress");
const result = readOption(args, "--result", status);
const message = readOption(args, "--message");
const details = readOption(args, "--details");
const runId = readOption(args, "--run-id");
const clearHistory = args.includes("--clear-history");

if (!file) {
  console.error("Usage: progress-update.mjs --file PATH [--section current|tried|succeeded]");
  process.exit(2);
}

function newState(run = runId || `run-${Date.now()}`) {
  const now = new Date().toISOString();
  return {
    version: 2,
    run_id: run,
    run_status: "running",
    started_at: now,
    updated_at: now,
    current: null,
    cards: [],
  };
}

function migrate(loaded) {
  const state = loaded && typeof loaded === "object" ? loaded : newState();
  state.version = 2;
  state.cards ??= [];

  // Preserve the first-generation viewer's arrays when upgrading in place.
  // They are intentionally converted once and then never deleted from the
  // user's visible history.
  for (const [legacyKey, kind, legacyResult] of [
    ["succeeded", "success", "pass"],
    ["tried", "attempt", "fail"],
  ]) {
    for (const item of state[legacyKey] ?? []) {
      if (!state.cards.some((card) => card.id === item.id && card.updated_at === item.updated_at)) {
        state.cards.push({
          ...item,
          platform: item.platform || "shared",
          kind,
          result: item.result || legacyResult,
          run_id: item.run_id || state.run_id || "legacy",
        });
      }
    }
  }
  delete state.succeeded;
  delete state.tried;
  return state;
}

let state;
try {
  state = migrate(JSON.parse(await fs.readFile(file, "utf8")));
} catch {
  state = newState();
}

const now = new Date().toISOString();
state.updated_at = now;
state.cards ??= [];
if (runId) state.run_id = runId;

if (section === "reset") {
  // `reset` starts a new run; it no longer erases the history. Use the explicit
  // --clear-history switch only when a human deliberately wants a blank board.
  if (clearHistory) state.cards = [];
  state.run_id = runId || `run-${Date.now()}`;
  state.run_status = "running";
  state.started_at = now;
  state.current = null;
} else if (section === "current") {
  state.current = {
    id: id || "current",
    platform,
    title: title || "Current work",
    status,
    message,
    details,
    updated_at: now,
  };
  // A platform step can finish while the other platform is still running.
  // Only the shared runner card owns the aggregate status; otherwise an
  // Android stop tap would make the whole iOS + Android run look complete.
  if (platform === "shared" && id === "runner" && (status === "passed" || status === "failed")) {
    state.run_status = status;
  } else if (status === "in_progress") {
    state.run_status = "running";
  }
} else if (section === "run") {
  state.run_status = status;
  state.current = {
    id: id || "runner",
    platform,
    title: title || "End-to-end runner",
    status,
    message,
    details,
    updated_at: now,
  };
} else if (section === "tried" || section === "succeeded") {
  if (!id || !title) {
    console.error(`--id and --title are required for --section ${section}`);
    process.exit(2);
  }
  state.cards.push({
    id,
    platform,
    kind: section === "succeeded" ? "success" : "attempt",
    title,
    result,
    message,
    details,
    run_id: state.run_id,
    updated_at: now,
  });
}

await fs.mkdir(path.dirname(file), { recursive: true });
const temporary = `${file}.${process.pid}.tmp`;
await fs.writeFile(temporary, `${JSON.stringify(state, null, 2)}\n`, "utf8");
await fs.rename(temporary, file);
