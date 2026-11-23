#!/usr/bin/env bun
/**
 * Local multi-server OpenChamber mock fixture for Android emulator UI & Bubble QA.
 *
 * Mirrors OpenCode/OpenChamber `/auth/session`, `/api/session` (pagination,
 * directory scoping, parentID, unread/failed timestamps, markedUnread metadata),
 * `/api/session/:id/message` (OpenCode 2.x flat records, cursor pagination,
 * multi-step turns, async `openchamber-session` wakeups),
 * `/api/session/:id/prompt`, `WS /api/global/event/ws`, `/__mock/async-wakeup`,
 * and `/api/guests/gemini-tts-bridge/service/request`.
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
  --auto-wakeup-ms <ms>      Optional delay after WS /api/global/event/ws open to inject an async wakeup (0 = off)
  --log <path>               Optional file path to append request logs (truncates on start)
  -h, --help                 Show this help text

QA Endpoints:
  POST /__mock/async-wakeup  Injects a synthetic openchamber-session + assistant + idle turn and
                             broadcasts session.synthetic, session.execution.succeeded, and
                             openchamber:session-status (idle) over WS /api/global/event/ws.
                             Body: { sessionId?, title?, syntheticBody?, assistantReply?, workerSessionId? }

Examples:
  bun scripts/mock-openchamber-server.mjs
  bun scripts/mock-openchamber-server.mjs --ports 18401,18402 --page-size 3
  bun scripts/mock-openchamber-server.mjs --ports 18401,18402 --unauthorized-port 18402
  bun scripts/mock-openchamber-server.mjs --auto-wakeup-ms 2000

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
    "auto-wakeup-ms": { type: "string", default: "0" },
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
const autoWakeupMs = Math.max(0, Number.parseInt(values["auto-wakeup-ms"], 10) || 0);
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

function escapeXmlAttr(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function buildSingleTurnMessages(sid, title, directory, tBase) {
  const promptText = title
    ? `[voice] Summarize how ${title} handles draft staging and send grace.`
    : "[voice] Summarize the latest turn.";
  const replyText = title
    ? `**${title}** (\`${directory ?? ""}\`) keeps quick turns fast and drafts durable:\n\n- **Single-tap stop:** Enters a 2.5s undoable send grace window.\n- **Double-tap / Next:** Stages, smart-merges, and reads back your draft per session.`
    : "Latest mock reply.";
  return [
    {
      id: `msg_${sid}_idle_1`,
      type: "idle",
      outcome: "succeeded",
      time: { created: tBase + 3_000 },
    },
    {
      id: `msg_${sid}_assistant_1`,
      type: "assistant",
      finish: "stop",
      time: { created: tBase + 1_000, completed: tBase + 2_800 },
      parts: [{ type: "text", text: replyText }],
    },
    {
      id: `msg_${sid}_user_1`,
      type: "user",
      time: { created: tBase },
      parts: [{ type: "text", text: promptText }],
    },
  ];
}

function buildMultiResponseWithAsyncWakeupMessages(ses) {
  const sid = ses.id;
  const tBase = ses.time?.created ?? now - 300_000;
  const dir = ses.location?.directory ?? "";
  const isSco = sid === "ses_root_failed_2";
  const userPrompt = isSco
    ? "[voice] Diagnose why Bluetooth SCO disconnects after idle and start the audio benchmark worker."
    : "[voice] Inspect SessionRepository cursor pagination and start the audio benchmark worker.";
  const step1Text = isSco
    ? `Inspected \`BluetoothAudioRouter.kt\` in \`${dir}\` and traced the SCO route teardown to an unguarded \`stopBluetoothSco()\` call during idle handoff.`
    : `Inspected \`SessionRepository.kt\` in \`${dir}\` and verified cursor-based \`GET /api/session\` pagination loops across multi-page responses.`;
  const step2Text = isSco
    ? "Patched the SCO state guard to keep `MODE_IN_COMMUNICATION` pinned while `pinBluetoothSco` is enabled and dispatched `ses_worker_9` (`Run audio benchmark`) with `returnResult: true`."
    : "Confirmed deduplication by session ID and parent-child tree sorting remain stable across pages, and dispatched `ses_worker_9` (`Run audio benchmark`) with `returnResult: true`.";
  const syntheticBody = isSco
    ? "SCO handoff benchmark finished: 12/12 transitions succeeded with 0 dropped buffers and average reconnect latency of 42ms."
    : "Session & audio benchmark finished: paginated 24 sessions across 8 pages (0 duplicates) and verified 0 underruns during playback.";
  const asyncReply = isSco
    ? "**Run audio benchmark** finished cleanly: 12/12 Bluetooth SCO transitions passed with 0 dropped buffers (42ms avg reconnect)."
    : "**Run audio benchmark** completed: 24 sessions paginated cleanly across 8 pages with 0 duplicates and 0 audio underruns.";

  // Stored newest-first (desc) matching OpenCode 2.x GET /api/session/:id/message.
  return [
    {
      id: `msg_${sid}_idle_2`,
      type: "idle",
      outcome: "succeeded",
      time: { created: tBase + 14_500 },
    },
    {
      id: `msg_${sid}_asst_2b`,
      type: "assistant",
      finish: "stop",
      time: { created: tBase + 13_000, completed: tBase + 14_000 },
      parts: [{ type: "text", text: asyncReply }],
    },
    {
      id: `msg_${sid}_asst_2a`,
      type: "assistant",
      finish: "tool-calls",
      time: { created: tBase + 11_000, completed: tBase + 12_500 },
      parts: [
        {
          type: "text",
          text: isSco
            ? "Verified `ses_worker_9` benchmark logs against `BluetoothAudioRouter.kt` and confirmed zero audio focus drops across all 12 idle/SCO transitions."
            : "Cross-checked `ses_worker_9` pagination counters against `SessionRepository.kt` and confirmed all 8 cursors terminated cleanly.",
        },
      ],
    },
    {
      id: `msg_${sid}_syn_2`,
      type: "synthetic",
      time: { created: tBase + 10_000 },
      description: "Run audio benchmark",
      metadata: {
        source: "openchamber-session",
        sessionID: "ses_worker_9",
        title: "Run audio benchmark",
      },
      parts: [
        {
          type: "text",
          text: `<openchamber-session sessionId="ses_worker_9" title="Run audio benchmark">\n${syntheticBody}\n</openchamber-session>`,
        },
      ],
    },
    {
      id: `msg_${sid}_idle_1`,
      type: "idle",
      outcome: "succeeded",
      time: { created: tBase + 6_000 },
    },
    {
      id: `msg_${sid}_asst_1b`,
      type: "assistant",
      finish: "stop",
      time: { created: tBase + 4_000, completed: tBase + 5_500 },
      parts: [{ type: "text", text: step2Text }],
    },
    {
      id: `msg_${sid}_asst_1a`,
      type: "assistant",
      finish: "tool-calls",
      time: { created: tBase + 2_000, completed: tBase + 3_500 },
      parts: [{ type: "text", text: step1Text }],
    },
    {
      id: `msg_${sid}_user_1`,
      type: "user",
      time: { created: tBase + 1_000 },
      parts: [{ type: "text", text: userPrompt }],
    },
  ];
}

function buildInitialMessagesMap(sessions) {
  const map = new Map();
  for (const ses of sessions) {
    if (ses.id === "ses_root_failed_2" || ses.id === "ses_sub_1a") {
      map.set(ses.id, buildMultiResponseWithAsyncWakeupMessages(ses));
    } else {
      map.set(
        ses.id,
        buildSingleTurnMessages(
          ses.id,
          ses.title,
          ses.location?.directory ?? "",
          ses.time?.created ?? now - 60_000,
        ),
      );
    }
  }
  return map;
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
  const messagesBySessionId = buildInitialMessagesMap(sessions);
  const wsClients = new Set();
  const pendingTimers = new Set();
  let lastActiveSessionId = sessions[0]?.id ?? "ses_root_failed_2";
  let seqCounter = 0;

  function nextId(prefix) {
    seqCounter += 1;
    return `${prefix}_${Date.now()}_${seqCounter}`;
  }

  function ensureSessionMessages(sid) {
    let list = messagesBySessionId.get(sid);
    if (!list) {
      const ses = sessionsById.get(sid);
      list = buildSingleTurnMessages(
        sid,
        ses?.title ?? null,
        ses?.location?.directory ?? "",
        Date.now() - 10_000,
      );
      messagesBySessionId.set(sid, list);
    }
    return list;
  }

  function broadcastWs(payloadObj) {
    const encoded = JSON.stringify(payloadObj);
    let sent = 0;
    for (const ws of wsClients) {
      try {
        ws.send(encoded);
        sent += 1;
      } catch {
        wsClients.delete(ws);
      }
    }
    return sent;
  }

  function scheduleTimer(fn, delayMs) {
    const timer = setTimeout(() => {
      pendingTimers.delete(timer);
      fn();
    }, delayMs);
    pendingTimers.add(timer);
    return timer;
  }

  function injectAsyncWakeup(options = {}) {
    const sid =
      typeof options.sessionId === "string" && options.sessionId.trim()
        ? options.sessionId.trim()
        : lastActiveSessionId || "ses_root_failed_2";
    const title =
      typeof options.title === "string" && options.title.trim()
        ? options.title.trim()
        : "Run audio benchmark";
    const syntheticBody =
      typeof options.syntheticBody === "string" && options.syntheticBody.trim()
        ? options.syntheticBody.trim()
        : "Worker session completed audio routing verification with 0 underruns.";
    const assistantReply =
      typeof options.assistantReply === "string" && options.assistantReply.trim()
        ? options.assistantReply.trim()
        : `**${title}** finished cleanly with zero audio underruns.`;
    const workerSessionId =
      typeof options.workerSessionId === "string" && options.workerSessionId.trim()
        ? options.workerSessionId.trim()
        : "ses_worker_9";

    const list = ensureSessionMessages(sid);
    const t0 = Date.now();
    const synId = nextId(`msg_${sid}_syn`);
    const asstId = nextId(`msg_${sid}_asst`);
    const idleId = nextId(`msg_${sid}_idle`);

    const synRec = {
      id: synId,
      type: "synthetic",
      time: { created: t0 },
      description: title,
      metadata: {
        source: "openchamber-session",
        sessionID: workerSessionId,
        title,
      },
      parts: [
        {
          type: "text",
          text: `<openchamber-session sessionId="${escapeXmlAttr(workerSessionId)}" title="${escapeXmlAttr(title)}">\n${syntheticBody}\n</openchamber-session>`,
        },
      ],
    };
    const asstRec = {
      id: asstId,
      type: "assistant",
      finish: "stop",
      time: { created: t0 + 100, completed: t0 + 250 },
      parts: [{ type: "text", text: assistantReply }],
    };
    const idleRec = {
      id: idleId,
      type: "idle",
      outcome: "succeeded",
      time: { created: t0 + 300 },
    };

    // Prepend newest-first (idle, assistant, synthetic).
    list.unshift(idleRec, asstRec, synRec);

    const ses = sessionsById.get(sid);
    if (ses?.time) {
      ses.time.updated = t0 + 300;
      ses.time.idle = t0 + 300;
    }

    const evtSynId = nextId("evt");
    const evtDoneId = nextId("evt");
    const evtIdleId = nextId("evt");

    broadcastWs({
      type: "event",
      eventId: evtSynId,
      payload: {
        id: evtSynId,
        type: "session.synthetic",
        data: {
          sessionID: sid,
          description: title,
          text: syntheticBody,
          metadata: {
            source: "openchamber-session",
            sessionID: workerSessionId,
            title,
          },
        },
      },
    });
    broadcastWs({
      type: "event",
      eventId: evtDoneId,
      payload: {
        id: evtDoneId,
        type: "session.execution.succeeded",
        data: { sessionID: sid },
      },
    });
    const clientsNotified = broadcastWs({
      type: "event",
      eventId: evtIdleId,
      payload: {
        id: evtIdleId,
        type: "openchamber:session-status",
        properties: { sessionID: sid, status: "idle", timestamp: Date.now() },
      },
    });

    recordLine(`[${label}] ASYNC-WAKEUP session=${sid} title="${title}" clients=${clientsNotified}`);
    return {
      ok: true,
      sessionId: sid,
      messageIds: { synthetic: synId, assistant: asstId, idle: idleId },
      clientsNotified,
    };
  }

  return {
    pendingTimers,
    server: Bun.serve({
      hostname: values.host,
      port,
      async fetch(req, server) {
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

        if (url.pathname === "/api/global/event/ws") {
          if (server.upgrade(req, { data: { kind: "events", connectedAt: Date.now() } })) {
            return undefined;
          }
          return Response.json({ error: "WebSocket upgrade failed" }, { status: 400 });
        }

        if (url.pathname === "/api/dictation/ws") {
          if (server.upgrade(req, { data: { kind: "dictation", connectedAt: Date.now() } })) {
            return undefined;
          }
          return Response.json({ error: "Dictation WebSocket upgrade failed" }, { status: 400 });
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

        if (url.pathname === "/__mock/async-wakeup" && req.method === "POST") {
          const body = await req.json().catch(() => ({}));
          const result = injectAsyncWakeup(body && typeof body === "object" ? body : {});
          return Response.json(result);
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
          const hasOrder = url.searchParams.has("order");
          const hasCursor = url.searchParams.has("cursor");
          if (hasOrder && hasCursor) {
            return Response.json(
              { error: "Cannot combine 'order' and 'cursor' query parameters" },
              { status: 400 },
            );
          }

          const order = url.searchParams.get("order");
          if (order !== null && order !== "asc" && order !== "desc") {
            return Response.json({ error: "Invalid 'order' parameter" }, { status: 400 });
          }

          const cursorParam = url.searchParams.get("cursor");
          let offset = 0;
          if (cursorParam !== null && cursorParam !== "") {
            if (!/^\d+$/.test(cursorParam)) {
              return Response.json({ error: "Invalid 'cursor' parameter" }, { status: 400 });
            }
            offset = Number.parseInt(cursorParam, 10);
          }

          let limit = 0;
          if (url.searchParams.has("limit")) {
            const parsedLimit = Number.parseInt(url.searchParams.get("limit") ?? "", 10);
            if (!Number.isInteger(parsedLimit) || parsedLimit <= 0) {
              return Response.json({ error: "Invalid 'limit' parameter" }, { status: 400 });
            }
            limit = parsedLimit;
          }

          const sid = decodeURIComponent(msgMatch[1]);
          lastActiveSessionId = sid;
          const descMessages = ensureSessionMessages(sid);
          const ordered = order === "asc" ? [...descMessages].reverse() : descMessages;

          const slice = limit > 0 ? ordered.slice(offset, offset + limit) : ordered.slice(offset);
          const nextOffset = offset + slice.length;
          const nextCursor =
            limit > 0 && slice.length > 0 && nextOffset < ordered.length ? String(nextOffset) : null;

          return Response.json({
            data: slice,
            cursor: nextCursor ? { next: nextCursor } : {},
          });
        }

        const promptMatch = url.pathname.match(/^\/api\/session\/([^/]+)\/prompt$/);
        if (promptMatch && req.method === "POST") {
          const sid = decodeURIComponent(promptMatch[1]);
          lastActiveSessionId = sid;
          const body = await req.json().catch(() => ({}));
          const rawText =
            body && typeof body.text === "string" && body.text.trim()
              ? body.text.trim()
              : "[voice] Follow-up prompt";
          const cleanPreview = rawText.replace(/^\[voice\]\s*/i, "").trim() || rawText;

          const list = ensureSessionMessages(sid);
          const t0 = Date.now();
          const userMsgId = nextId(`msg_${sid}_user`);
          list.unshift({
            id: userMsgId,
            type: "user",
            time: { created: t0 },
            parts: [{ type: "text", text: rawText }],
          });

          scheduleTimer(() => {
            const tStep = Date.now();
            const asst1Id = nextId(`msg_${sid}_asst`);
            const asst2Id = nextId(`msg_${sid}_asst`);
            const idleId = nextId(`msg_${sid}_idle`);
            const asst1 = {
              id: asst1Id,
              type: "assistant",
              finish: "tool-calls",
              time: { created: tStep, completed: tStep + 80 },
              parts: [
                {
                  type: "text",
                  text: `Analyzing request: "${cleanPreview}".`,
                },
              ],
            };
            const asst2 = {
              id: asst2Id,
              type: "assistant",
              finish: "stop",
              time: { created: tStep + 100, completed: tStep + 200 },
              parts: [
                {
                  type: "text",
                  text: `Completed multi-step response for "${cleanPreview}".`,
                },
              ],
            };
            const idleRec = {
              id: idleId,
              type: "idle",
              outcome: "succeeded",
              time: { created: tStep + 220 },
            };
            list.unshift(idleRec, asst2, asst1);

            const ses = sessionsById.get(sid);
            if (ses?.time) {
              ses.time.updated = tStep + 220;
              ses.time.idle = tStep + 220;
            }

            const evtDoneId = nextId("evt");
            const evtIdleId = nextId("evt");
            broadcastWs({
              type: "event",
              eventId: evtDoneId,
              payload: {
                id: evtDoneId,
                type: "session.execution.succeeded",
                data: { sessionID: sid },
              },
            });
            broadcastWs({
              type: "event",
              eventId: evtIdleId,
              payload: {
                id: evtIdleId,
                type: "openchamber:session-status",
                properties: { sessionID: sid, status: "idle", timestamp: Date.now() },
              },
            });
          }, 300);

          return Response.json({ ok: true, id: userMsgId });
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
      websocket: {
        open(ws) {
          if (ws.data?.kind === "dictation") {
            ws.send(JSON.stringify({ type: "ready" }));
            return;
          }
          wsClients.add(ws);
          ws.send(JSON.stringify({ type: "ready", scope: "global" }));
          if (autoWakeupMs > 0) {
            const timer = scheduleTimer(() => {
              injectAsyncWakeup({ sessionId: lastActiveSessionId || "ses_root_failed_2" });
            }, autoWakeupMs);
            ws.data = { ...(ws.data || {}), autoWakeupTimer: timer };
          }
        },
        message(ws, message) {
          if (ws.data?.kind !== "dictation" || typeof message !== "string") {
            return;
          }
          let frame;
          try {
            frame = JSON.parse(message);
          } catch {
            return;
          }
          const dictationId = frame?.dictationId;
          if (frame?.type === "start") {
            ws.send(JSON.stringify({ type: "ack", dictationId, ackSeq: -1 }));
          } else if (frame?.type === "finish") {
            ws.send(JSON.stringify({ type: "finish_accepted", dictationId, timeoutMs: 5000 }));
            ws.send(
              JSON.stringify({
                type: "final",
                dictationId,
                text: "Verify async reply queue and stop controls",
              }),
            );
          }
        },
        close(ws) {
          wsClients.delete(ws);
          const timer = ws.data?.autoWakeupTimer;
          if (timer) {
            clearTimeout(timer);
            pendingTimers.delete(timer);
          }
        },
      },
    }),
  };
});

for (const { server: srv } of servers) {
  console.log(`Mock OpenChamber server listening on http://${values.host}:${srv.port}`);
}

const shutdown = () => {
  for (const { server: srv, pendingTimers } of servers) {
    for (const timer of pendingTimers) {
      clearTimeout(timer);
    }
    pendingTimers.clear();
    srv.stop(true);
  }
  process.exit(0);
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
await new Promise(() => {});
