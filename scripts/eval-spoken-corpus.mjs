#!/usr/bin/env bun
/**
 * Live evaluator for the genericized SpokenDetailLevel test corpus against OpenChamber's
 * `/api/small-model/generate` endpoint.
 *
 * Usage:
 *   OPENCHAMBER_PASSWORD=... bun scripts/eval-spoken-corpus.mjs [baseUrl] [outJsonPath]
 */

import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const baseUrl = (process.argv[2] || "http://127.0.0.1:14096").replace(/\/+$/, "");
const outPath = process.argv[3] || "/tmp/opencode/spoken_corpus_eval.json";
const directory = resolve(".");
const password = process.env.OPENCHAMBER_PASSWORD;

if (!password) {
  console.error("Missing OPENCHAMBER_PASSWORD environment variable.");
  process.exit(1);
}

const corpusPath = resolve("app/src/test/resources/spoken_corpus/corpus.json");
const corpus = JSON.parse(readFileSync(corpusPath, "utf8"));

const ttsClientSource = readFileSync(
  resolve("app/src/main/java/com/openchamber/ptt/tts/TtsClient.kt"),
  "utf8",
);

function parseKotlinStringConcat(expr) {
  const literals = [];
  const tokenRegex = /"((?:\\.|[^"\\])*)"/g;
  let match;
  while ((match = tokenRegex.exec(expr)) !== null) {
    literals.push(JSON.parse(`"${match[1]}"`));
  }
  if (literals.length === 0) {
    throw new Error(`Failed to parse Kotlin string literal expression: ${expr}`);
  }
  return literals.join("");
}

function buildSystemPromptFromSource(level) {
  const fnMatch = ttsClientSource.match(
    /fun buildSpokenSystemPrompt\(detailLevel: SpokenDetailLevel\): String \{([\s\S]*?)\n\s{8}\}/,
  );
  if (!fnMatch) {
    throw new Error("Could not locate buildSpokenSystemPrompt in TtsClient.kt");
  }
  const fnBody = fnMatch[1];
  const levelMatch = fnBody.match(
    new RegExp(
      `SpokenDetailLevel\\.${level}\\s*->([\\s\\S]*?)(?=\\n\\s*SpokenDetailLevel\\.|\\n\\s*\\})`,
    ),
  );
  if (!levelMatch) {
    throw new Error(`Could not locate SpokenDetailLevel.${level} in TtsClient.kt`);
  }
  const returnMatch = fnBody.match(/return\s+([\s\S]*?)\s+abstractionGuidance\s*$/);
  if (!returnMatch) {
    throw new Error("Could not locate base prompt return expression in TtsClient.kt");
  }
  return parseKotlinStringConcat(returnMatch[1]) + parseKotlinStringConcat(levelMatch[1]);
}

function buildSystemPrompt(level) {
  return buildSystemPromptFromSource(level);
}

function normalizeSpokenOutput(text) {
  return text
    .replace(/\[([^\]]+)]\(([^)]+)\)/g, "$1")
    .replace(/\[\s*(?:short|medium|long)?\s*pause\s*]/gi, " ")
    .replace(/<\s*\/?\s*(?:(?:short|medium|long)?\s*pause|sigh|laugh|gasp|uhm|break\b[^>]*)\s*>/gi, " ")
    .replace(/https?:\/\//g, "")
    .replaceAll("```", " ")
    .replaceAll("`", "")
    .replaceAll("**", "")
    .replaceAll("__", "")
    .replace(/^\s{0,3}#{1,6}\s+/gm, "")
    .replace(/^\s*[-*+]\s+/gm, "")
    .replace(/(?<!\*)\*([^*\n]+?)\*(?!\*)/g, "$1")
    .replace(/(?<=[\p{L}\p{N}])_(?=[\p{L}\p{N}])/gu, " ")
    .replace(/(?<=[\p{L}\p{N})])[ \t]*\n+\s*/gu, ". ")
    .replace(/[ \t]+([.!?,;:])/g, "$1")
    .replace(/\s+/g, " ")
    .trim();
}

const authRes = await fetch(`${baseUrl}/auth/session`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    password,
    trustDevice: true,
    issueClientToken: true,
    clientLabel: "Spoken Corpus Eval",
    clientKind: "cli",
    devicePlatform: "linux",
    deviceName: "corpus-eval",
    dedupeKey: "openchamber-ptt-corpus-eval",
  }),
});
const authJson = await authRes.json();
const token = authJson.token || authJson.clientToken || authJson.data?.token || authJson.data?.clientToken;
if (!authRes.ok || !token) {
  console.error("Auth failed:", authRes.status, JSON.stringify(authJson));
  process.exit(1);
}

const headers = {
  "Content-Type": "application/json",
  Accept: "application/json",
  Authorization: `Bearer ${token}`,
  "x-opencode-directory": encodeURIComponent(directory),
  "x-opencode-directory-encoding": "uri",
};

const translatable = corpus.samples.filter((s) => s.expectTranslate);
const results = [];
for (const sample of translatable) {
  for (const level of ["ESSENTIALS", "BALANCED", "DETAILED"]) {
    const t0 = Date.now();
    const res = await fetch(`${baseUrl}/api/small-model/generate`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        prompt: `Session: ${sample.sessionLabel}\n\nAssistant reply:\n${sample.reply}`,
        system: buildSystemPrompt(level),
      }),
    });
    const elapsedMs = Date.now() - t0;
    const data = await res.json();
    const rawText = data.text ?? "";
    const normalizedText = rawText ? normalizeSpokenOutput(rawText) : "";
    results.push({
      id: sample.id,
      category: sample.category,
      level,
      status: res.status,
      elapsedMs,
      inputChars: sample.reply.length,
      rawChars: rawText.length,
      normalizedChars: normalizedText.length,
      model: data.modelID ? `${data.providerID}/${data.modelID}` : undefined,
      rawText,
      normalizedText,
    });
  }
}

writeFileSync(outPath, JSON.stringify({ count: results.length, results }, null, 2));
console.log(
  JSON.stringify({
    outPath,
    count: results.length,
    okCount: results.filter((r) => r.status === 200).length,
    avgMs: Math.round(results.reduce((a, b) => a + b.elapsedMs, 0) / Math.max(1, results.length)),
  }),
);
