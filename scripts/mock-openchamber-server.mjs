#!/usr/bin/env bun
/**
 * Local multi-server OpenChamber mock fixture for Android emulator UI & Bubble QA.
 *
 * Mirrors OpenCode/OpenChamber `/auth/session`, `/api/session` (pagination,
 * directory scoping, parentID, unread/failed timestamps, markedUnread metadata),
 * `/api/session/:id/message`, and `/api/guests/gemini-tts-bridge/service/request`.
 * Logs all requests to stdout and warns loudly if any `/view` endpoint is called.
 */

import { appendFileSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";

function printHelp() {
  console.log(`Usage: bun scripts/mock-openchamber-server.mjs [options]

Options:
  --ports <list>             Comma-separated ports to listen on (default: "18401,18402")
  --host <addr>              Bind address (default: "127.0.0.1")
  --page-size <n>            Optional /api/session page size for cursor pagination testing (0 = disabled)
  --unauthorized-port <port> Port that returns HTTP 401 on /api/session for expired-auth UI testing
  --log <path>               Optional file path to append request logs (truncates on start)
  -h, --help                 Show this help text

Examples:
  bun scripts/mock-openchamber-server.mjs
  bun scripts/mock-openchamber-server.mjs --ports 18401,18402 --page-size 3
  bun scripts/mock-openchamber-server.mjs --ports 18401,18402 --unauthorized-port 18402

Emulator URLs (openchamber_api35):
  Primary host:   http://10.0.2.2:18401  (any non-empty password)
  Secondary host: http://10.0.2.2:18402  (any non-empty password)`);
}

const { values } = parseArgs({
  args: Bun.argv.slice(2),
  options: {
    ports: { type: "string", default: "18401,18402" },
    host: { type: "string", default: "127.0.0.1" },
    "page-size": { type: "string", default: "0" },
    "unauthorized-port": { type: "string" },
    log: { type: "string" },
    help: { type: "boolean", short: "h", default: false },
  },
  strict: true,
});

if (values.help) {
  printHelp();
  process.exit(0);
}

const ports = values.ports
  .split(",")
  .map((s) => Number.parseInt(s.trim(), 10))
  .filter((n) => Number.isInteger(n) && n > 0 && n <= 65535);

if (ports.length === 0) {
  console.error("Provide at least one valid port via --ports (e.g. 18401,18402).");
  process.exit(1);
}

const pageSize = Number.parseInt(values["page-size"], 10) || 0;
const unauthorizedPort = values["unauthorized-port"]
  ? Number.parseInt(values["unauthorized-port"], 10)
  : null;
const logPath = values.log?.trim() || null;

if (logPath) {
  writeFileSync(logPath, "");
}

const now = Date.now();

function buildPrimarySessions() {
  return [
    {
      id: "ses_root_unread_1",
      title: "Voice cockpit PR review",
      location: { directory: "/home/dev/Projects/openchamber-ptt-android" },
      outcome: "succeeded",
      time: {
        created: now - 3_600_000,
        updated: now - 30_000,
        idle: now - 30_000,
        viewed: now - 600_000,
      },
    },
    {
      id: "ses_sub_1a",
      parentID: "ses_root_unread_1",
      title: "Explore SessionRepository pagination",
      location: { directory: "/home/dev/Projects/openchamber-ptt-android" },
      time: { created: now - 900_000, updated: now - 45_000, idle: now - 45_000 },
    },
    {
      id: "ses_sub_1b",
      parentID: "ses_root_unread_1",
      title: "Audit Compose IME insets",
      location: { directory: "/home/dev/Projects/openchamber-ptt-android" },
      time: { created: now - 800_000, updated: now - 35_000, idle: now - 35_000 },
    },
    {
      id: "ses_root_failed_2",
      title: "Fix Bluetooth SCO disconnect",
      location: { directory: "/home/dev/Projects/audio-router" },
      outcome: "failed",
      time: {
        created: now - 7_200_000,
        updated: now - 120_000,
        idle: now - 120_000,
        viewed: now - 3_600_000,
      },
    },
    {
      id: "ses_sub_2a",
      parentID: "ses_root_failed_2",
      title: "Check Android 15 AudioManager",
      location: { directory: "/home/dev/Projects/audio-router" },
      time: { created: now - 300_000, updated: now - 150_000, idle: now - 150_000 },
    },
    {
      id: "ses_root_read_3",
      title: "CI release signing setup",
      location: { directory: "/home/dev/Projects/public/openchamber-ptt-android" },
      outcome: "succeeded",
      time: {
        created: now - 14_400_000,
        updated: now - 600_000,
        idle: now - 600_000,
        viewed: now - 500_000,
      },
    },
    {
      id: "ses_sub_3a",
      parentID: "ses_root_read_3",
      title: "Verify apksigner workflow",
      location: { directory: "/home/dev/Projects/public/openchamber-ptt-android" },
      time: { created: now - 1_200_000, updated: now - 700_000 },
    },
    {
      id: "ses_sub_3b_grand",
      parentID: "ses_sub_3a",
      title: "Check keystore permissions",
      location: { directory: "/home/dev/Projects/public/openchamber-ptt-android" },
      time: { created: now - 1_100_000, updated: now - 650_000 },
    },
    {
      id: "ses_root_read_4",
      title: "Refactor PttStateMachine",
      location: { directory: "/home/dev/Projects/voice-core" },
      outcome: "succeeded",
      time: {
        created: now - 20_000_000,
        updated: now - 1_800_000,
        idle: now - 1_800_000,
        viewed: now - 1_700_000,
      },
    },
    {
      id: "ses_archived_root",
      title: "Archived parent fixture",
      location: { directory: "/home/dev/Projects/legacy" },
      time: { created: now - 40_000_000, updated: now - 30_000_000, archived: now - 10_000_000 },
    },
    {
      id: "ses_archived_stray_child",
      parentID: "ses_archived_root",
      title: "Stray child of archived parent",
      location: { directory: "/home/dev/Projects/legacy" },
      time: { created: now - 35_000_000, updated: now - 29_000_000 },
    },
  ];
}

function buildSecondarySessions(index) {
  return [
    {
      id: `ses_srv${index}_marked`,
      title: "Build telemetry exporter",
      location: { directory: "/srv/telemetry-pipeline" },
      outcome: "succeeded",
      time: {
        created: now - 5_000_000,
        updated: now - 200_000,
        idle: now - 200_000,
        viewed: now - 100_000,
      },
      metadata: { openchamber: { markedUnread: { at: now - 50_000 } } },
    },
    {
      id: `ses_srv${index}_read`,
      title: "Inspect staging metrics",
      location: { directory: "/srv/metrics-dashboard" },
      outcome: "succeeded",
      time: {
        created: now - 9_000_000,
        updated: now - 900_000,
        idle: now - 900_000,
        viewed: now - 800_000,
      },
    },
  ];
}

function decodeDirectoryHeader(req, url) {
  const queryDir = url.searchParams.get("directory");
  if (queryDir) return queryDir;
  const raw = req.headers.get("x-opencode-directory");
  if (!raw) return null;
  if (req.headers.get("x-opencode-directory-encoding") === "uri" || raw.includes("%")) {
    try {
      return decodeURIComponent(raw);
    } catch {
      return raw;
    }
  }
  return raw;
}

function recordLine(line) {
  console.log(line);
  if (logPath) {
    appendFileSync(logPath, `${line}\n`);
  }
}

const servers = ports.map((port, idx) => {
  const label = `srv${idx + 1}`;
  const sessions = idx === 0 ? buildPrimarySessions() : buildSecondarySessions(idx + 1);
  const sessionsById = new Map(sessions.map((s) => [s.id, s]));

  return Bun.serve({
    hostname: values.host,
    port,
    async fetch(req) {
      const url = new URL(req.url);
      const scopedDir = decodeDirectoryHeader(req, url);
      recordLine(`[${label}] ${req.method} ${url.pathname}${url.search} dir=${scopedDir ?? "NONE"}`);

      if (
        req.method === "POST" &&
        (/^\/api\/session\/[^/]+\/view$/.test(url.pathname) ||
          /^\/api\/sessions\/[^/]+\/view$/.test(url.pathname))
      ) {
        console.error(`[WARN] Unexpected read-marking request on ${label}: ${url.pathname}`);
      }

      if (url.pathname === "/health") {
        return Response.json({ ok: true, server: label });
      }

      if (url.pathname === "/auth/session" && req.method === "POST") {
        const body = await req.json().catch(() => ({}));
        if (!body || typeof body.password !== "string" || !body.password.trim()) {
          return Response.json({ error: "Password is required" }, { status: 401 });
        }
        return Response.json({ authenticated: true, clientToken: `mock_tok_${label}` });
      }

      if (url.pathname === "/api/session" && req.method === "GET") {
        if (unauthorizedPort !== null && port === unauthorizedPort) {
          return Response.json({ error: "Unauthorized" }, { status: 401 });
        }

        const filtered = scopedDir
          ? sessions.filter((s) => s.location?.directory === scopedDir)
          : sessions;

        const requestedLimit = Number.parseInt(url.searchParams.get("limit") ?? "0", 10) || 0;
        const effectiveLimit =
          pageSize > 0
            ? requestedLimit > 0
              ? Math.min(pageSize, requestedLimit)
              : pageSize
            : requestedLimit;
        const offset = Number.parseInt(url.searchParams.get("cursor") ?? "0", 10) || 0;

        if (effectiveLimit > 0) {
          const slice = filtered.slice(offset, offset + effectiveLimit);
          const nextOffset = offset + slice.length;
          const nextCursor = slice.length > 0 ? String(nextOffset) : null;
          return Response.json({
            data: slice,
            cursor: nextCursor ? { next: nextCursor } : {},
          });
        }

        return Response.json({ data: filtered, cursor: {} });
      }

      const msgMatch = url.pathname.match(/^\/api\/session\/([^/]+)\/message$/);
      if (msgMatch && req.method === "GET") {
        const sid = decodeURIComponent(msgMatch[1]);
        const ses = sessionsById.get(sid);
        const promptText = ses
          ? `[voice] Summarize how ${ses.title} handles draft staging and send grace.`
          : "[voice] Summarize the latest turn.";
        const replyText = ses
          ? `**${ses.title}** (\`${ses.location?.directory ?? ""}\`) keeps quick turns fast and drafts durable:\n\n- **Single-tap stop:** Enters a 2.5s undoable send grace window.\n- **Double-tap / Next:** Stages, smart-merges, and reads back your draft per session.`
          : "Latest mock reply.";
        return Response.json([
          {
            info: { id: `msg_${sid}_assistant`, role: "assistant" },
            parts: [{ type: "text", text: replyText }],
          },
          {
            info: { id: `msg_${sid}_user`, role: "user" },
            parts: [{ type: "text", text: promptText }],
          },
        ]);
      }

      const promptMatch = url.pathname.match(/^\/api\/session\/([^/]+)\/prompt$/);
      if (promptMatch && req.method === "POST") {
        return Response.json({ ok: true, id: `msg_${Date.now()}` });
      }

      if (url.pathname === "/api/guests/gemini-tts-bridge/service/request") {
        return Response.json({
          status: 200,
          body: JSON.stringify({
            default_voice: "Kore",
            voices: [
              { id: "Kore", display_name: "Kore", style: "Clear" },
              { id: "Aoede", display_name: "Aoede", style: "Warm" },
            ],
          }),
        });
      }

      return Response.json({ ok: true });
    },
  });
});

for (const srv of servers) {
  console.log(`Mock OpenChamber server listening on http://${values.host}:${srv.port}`);
}

const shutdown = () => {
  for (const srv of servers) srv.stop(true);
  process.exit(0);
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
await new Promise(() => {});
