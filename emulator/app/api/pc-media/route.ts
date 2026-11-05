// Laptop media source for the emulator: one PowerShell reader of the Windows media session, shared by every
// open emulator tab. GET streams its JSON lines as server-sent events; POST sends play, pause, toggle, next or prev.
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { resolve } from "node:path";

export const dynamic = "force-dynamic";

const COMMANDS = new Set(["play", "pause", "toggle", "next", "prev"]);
/** The reader stops this long after the last tab stops listening, so a reload does not restart it. */
const IDLE_STOP_MS = 15000;

let reader: ChildProcessWithoutNullStreams | null = null;
const listeners = new Set<(line: string) => void>();
// the latest line of each kind, replayed to a tab that starts listening
const latest = new Map<string, string>();
let idleTimer: ReturnType<typeof setTimeout> | undefined;

function start() {
  if (reader) return;
  const child = spawn("powershell", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", resolve("scripts/pc-media.ps1")]);
  reader = child;
  let buffered = "";
  child.stdout.setEncoding("utf8");
  child.stdout.on("data", (chunk: string) => {
    buffered += chunk;
    const lines = buffered.split(/\r?\n/);
    buffered = lines.pop() ?? "";
    for (const line of lines) {
      if (!line.startsWith("{")) continue;
      const kind = JSON.parse(line).type as string;
      if (kind === "session" && line.includes('"active":false')) latest.clear();
      latest.set(kind, line);
      listeners.forEach((send) => send(line));
    }
  });
  child.on("exit", () => {
    if (reader === child) reader = null;
    latest.clear();
  });
}

function stopWhenIdle() {
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => {
    if (listeners.size > 0 || !reader) return;
    reader.stdin.end();
    reader.kill();
    reader = null;
  }, IDLE_STOP_MS);
}

export async function GET(request: Request) {
  if (process.platform !== "win32") return new Response("The laptop media source reads the Windows media session.", { status: 501 });
  start();
  clearTimeout(idleTimer);
  const encoder = new TextEncoder();
  let send: (line: string) => void = () => {};
  const stream = new ReadableStream({
    start(controller) {
      send = (line) => controller.enqueue(encoder.encode(`data: ${line}\n\n`));
      ["session", "track", "cover", "playback"].forEach((kind) => { const line = latest.get(kind); if (line) send(line); });
      listeners.add(send);
      request.signal.addEventListener("abort", () => {
        listeners.delete(send);
        stopWhenIdle();
        try { controller.close(); } catch { /* already closed */ }
      });
    },
  });
  return new Response(stream, { headers: { "Content-Type": "text/event-stream", "Cache-Control": "no-store", Connection: "keep-alive" } });
}

export async function POST(request: Request) {
  const { command } = (await request.json()) as { command?: string };
  if (!command || !COMMANDS.has(command)) return new Response("Unknown command", { status: 400 });
  if (!reader) return new Response("The laptop media source is not running", { status: 409 });
  reader.stdin.write(`${command}\n`);
  return new Response(null, { status: 204 });
}
