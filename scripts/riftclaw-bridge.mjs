#!/usr/bin/env node
import http from "node:http";
import { timingSafeEqual } from "node:crypto";

const HOST = "127.0.0.1";
const PORT = Number.parseInt(process.env.RIFTCLAW_BRIDGE_PORT || "18790", 10);
const OPENCLAW_URL = (process.env.RIFTCLAW_OPENCLAW_URL || "http://127.0.0.1:18791").replace(/\/$/, "");
const OPENCLAW_TOKEN = process.env.RIFTCLAW_OPENCLAW_TOKEN || "";
const BRIDGE_TOKEN = process.env.RIFTCLAW_BRIDGE_TOKEN || "";
const PROTOCOL_VERSION = 1;
const MAX_REQUEST_BYTES = 16 * 1024;
const MAX_RESPONSE_TEXT = 1200;
const REQUEST_TIMEOUT_MS = 20_000;

if (!OPENCLAW_TOKEN) failStartup("RIFTCLAW_OPENCLAW_TOKEN is required");
if (!BRIDGE_TOKEN || BRIDGE_TOKEN.length < 24) failStartup("RIFTCLAW_BRIDGE_TOKEN (>=24 chars) is required");
if (!isLoopbackHttpUrl(OPENCLAW_URL)) failStartup("RIFTCLAW_OPENCLAW_URL must be loopback http(s)");
if (!Number.isInteger(PORT) || PORT < 1024 || PORT > 65535) failStartup("invalid bridge port");

const suspiciousPatterns = [
  /ignore\s+(all\s+)?previous\s+instructions/gi,
  /system\s*prompt/gi,
  /(execute|run)\s+(this\s+)?(command|shell|code)/gi,
  /\b(sudo|su|bash|zsh|powershell|adb|magisk|shizuku)\b/gi,
  /\b(rm\s+-rf|chmod|chown)\b/gi,
  /(read|upload|send|exfiltrate).{0,24}(secret|token|cookie|key|credential)/gi,
  /(install|enable).{0,24}(plugin|package|extension)/gi,
];

function failStartup(message) {
  console.error(`[RiftClaw] ${message}`);
  process.exit(1);
}

function isLoopbackHttpUrl(value) {
  try {
    const u = new URL(value);
    return ["http:", "https:"].includes(u.protocol) && ["127.0.0.1", "localhost", "::1", "[::1]"].includes(u.hostname);
  } catch {
    return false;
  }
}

function sendJson(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  });
  res.end(body);
}

function sameSecret(a, b) {
  const left = Buffer.from(a || "", "utf8");
  const right = Buffer.from(b || "", "utf8");
  return left.length === right.length && left.length > 0 && timingSafeEqual(left, right);
}

function bearer(req) {
  const raw = String(req.headers.authorization || "");
  return raw.startsWith("Bearer ") ? raw.slice(7) : "";
}

function validToken(value) {
  return /^[A-Za-z0-9._+-]{1,48}$/.test(value);
}

function validRequestId(value) {
  return typeof value === "string" && value.length >= 8 && value.length <= 96 && /^[A-Za-z0-9._:+-]+$/.test(value);
}

function validDate(value) {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [y, m, d] = value.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}

function validateSearch(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) return "body_invalid";
  const allowedKeys = new Set(["protocolVersion", "requestId", "matchDate", "league", "teamA", "teamB", "intent"]);
  if (Object.keys(input).some((k) => !allowedKeys.has(k))) return "unexpected_field";
  if (input.protocolVersion !== PROTOCOL_VERSION) return "protocol_mismatch";
  if (!validRequestId(input.requestId)) return "request_id_invalid";
  if (!validDate(input.matchDate)) return "date_invalid";
  if (![input.league, input.teamA, input.teamB].every((x) => typeof x === "string" && validToken(x))) return "token_invalid";
  if (input.teamA.toLowerCase() === input.teamB.toLowerCase()) return "teams_must_differ";
  if (input.intent !== "starting_roster") return "intent_not_allowed";
  return null;
}

function buildQueries(input) {
  const [, month, day] = input.matchDate.split("-");
  const md = `${Number(month)}月${Number(day)}日`;
  return [
    `${md} ${input.teamA}对战${input.teamB} 首发名单`,
    `${md} ${input.teamB}对战${input.teamA} 首发名单`,
  ];
}

function sanitizeText(input, max = MAX_RESPONSE_TEXT) {
  if (typeof input !== "string") return null;
  let value = input.replace(/\u0000/g, " ").replace(/[\u0001-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, " ").slice(0, max);
  for (const pattern of suspiciousPatterns) value = value.replace(pattern, "[blocked-untrusted-instruction]");
  value = value.trim();
  return value || null;
}

async function readBody(req) {
  return await new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > MAX_REQUEST_BYTES) {
        reject(new Error("request_too_large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function parseToolDetails(result) {
  if (!result || typeof result !== "object") return {};
  if (result.details && typeof result.details === "object") return result.details;
  const content = Array.isArray(result.content) ? result.content : [];
  const text = content.find((x) => x && x.type === "text" && typeof x.text === "string")?.text;
  if (text) {
    try { return JSON.parse(text); } catch { return { content: text }; }
  }
  if (result.result && typeof result.result === "object") return parseToolDetails(result.result);
  return result;
}

async function invokeWeiboSearch(query) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(`${OPENCLAW_URL}/tools/invoke`, {
      method: "POST",
      redirect: "error",
      signal: controller.signal,
      headers: {
        "authorization": `Bearer ${OPENCLAW_TOKEN}`,
        "content-type": "application/json",
        "accept": "application/json",
      },
      body: JSON.stringify({ tool: "weibo_search", args: { query } }),
    });
    const raw = await response.text();
    if (raw.length > 512 * 1024) throw new Error("openclaw_response_too_large");
    let envelope;
    try { envelope = JSON.parse(raw); } catch { throw new Error("openclaw_invalid_json"); }
    if (!response.ok || envelope?.ok === false) {
      const message = sanitizeText(envelope?.error?.message || `http_${response.status}`, 160) || "openclaw_tool_error";
      throw new Error(message);
    }
    return parseToolDetails(envelope?.result ?? envelope);
  } finally {
    clearTimeout(timer);
  }
}

function normalizeHit(query, details) {
  if (!details || details.success === false || details.noContent === true) return null;
  const text = sanitizeText(details.content ?? details.message ?? null);
  if (!text) return null;
  return {
    title: sanitizeText(`微博智搜 · ${query}`, 180),
    text,
    source: sanitizeText(details.source ?? "微博智搜", 180),
    scheme: sanitizeText(details.scheme ?? null, 500),
    publishedAt: sanitizeText(details.callTime ?? null, 80),
  };
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === "GET" && req.url === "/v1/status") {
      return sendJson(res, 200, {
        service: "riftclaw",
        protocolVersion: PROTOCOL_VERSION,
        ready: true,
        capabilities: ["weibo_search"],
        detail: "bridge_ready",
      });
    }

    if (req.method !== "POST" || req.url !== "/v1/weibo/search") {
      return sendJson(res, 404, { error: "not_found" });
    }
    if (!sameSecret(bearer(req), BRIDGE_TOKEN)) {
      return sendJson(res, 401, { error: "unauthorized" });
    }
    if (!String(req.headers["content-type"] || "").toLowerCase().startsWith("application/json")) {
      return sendJson(res, 415, { error: "json_required" });
    }

    let input;
    try { input = JSON.parse(await readBody(req)); }
    catch (error) { return sendJson(res, error?.message === "request_too_large" ? 413 : 400, { error: "invalid_json" }); }

    const invalid = validateSearch(input);
    if (invalid) return sendJson(res, 400, { error: invalid });

    const hits = [];
    for (const query of buildQueries(input)) {
      try {
        const details = await invokeWeiboSearch(query);
        const hit = normalizeHit(query, details);
        if (hit) hits.push(hit);
      } catch (error) {
        console.error(`[RiftClaw] weibo_search failed: ${sanitizeText(error?.message || "error", 160)}`);
      }
    }

    return sendJson(res, 200, {
      protocolVersion: PROTOCOL_VERSION,
      requestId: input.requestId,
      source: "riftclaw-weibo",
      hits,
    });
  } catch (error) {
    console.error(`[RiftClaw] request failed: ${sanitizeText(error?.message || "error", 160)}`);
    return sendJson(res, 500, { error: "internal_error" });
  }
});

server.on("clientError", (error, socket) => {
  socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
});

server.listen(PORT, HOST, () => {
  console.log(`[RiftClaw] bridge listening on http://${HOST}:${PORT}`);
  console.log(`[RiftClaw] upstream OpenClaw: ${OPENCLAW_URL}`);
  console.log("[RiftClaw] exported capability: weibo_search only");
});
