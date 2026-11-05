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

// Windows passes on only a small cover (about 120 px), so a large one is looked up by title and artist in a public
// music catalog, Deezer first, then Apple's. A result counts only when its title and artist match the track.
const LOOKUP_TIMEOUT_MS = 4000;
const hqCovers = new Map<string, string | null>();
const fold = (s: string) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/\(.*?\)|\[.*?\]/g, "").replace(/[^a-z0-9]+/g, " ").trim();
const sameTrack = (title: string, artist: string, foundTitle: string, foundArtist: string) => {
  const t = fold(title), ft = fold(foundTitle), a = fold(artist), fa = fold(foundArtist);
  return (ft === t || ft.startsWith(t) || t.startsWith(ft)) && (!a || fa.includes(a) || a.includes(fa));
};

async function json(url: string) {
  const response = await fetch(url, { signal: AbortSignal.timeout(LOOKUP_TIMEOUT_MS) });
  return response.ok ? response.json() : null;
}

async function largeCoverUrl(title: string, artist: string): Promise<string | null> {
  const q = encodeURIComponent(`${title} ${artist}`);
  const deezer = await json(`https://api.deezer.com/search?q=${q}&limit=5`).catch(() => null);
  const dz = deezer?.data?.find((d: { title: string; artist: { name: string } }) => sameTrack(title, artist, d.title, d.artist.name));
  if (dz?.album?.cover_xl) return dz.album.cover_xl;
  const apple = await json(`https://itunes.apple.com/search?term=${q}&entity=song&limit=5`).catch(() => null);
  const ap = apple?.results?.find((r: { trackName: string; artistName: string }) => sameTrack(title, artist, r.trackName, r.artistName));
  return ap?.artworkUrl100 ? ap.artworkUrl100.replace("100x100bb", "1000x1000bb") : null;
}

/** The large cover as a data URL, so the page can read its pixels for the background colours. */
async function largeCover(title: string, artist: string): Promise<string | null> {
  const key = `${title}|${artist}`;
  if (hqCovers.has(key)) return hqCovers.get(key)!;
  let result: string | null = null;
  try {
    const url = await largeCoverUrl(title, artist);
    if (url) {
      const image = await fetch(url, { signal: AbortSignal.timeout(LOOKUP_TIMEOUT_MS) });
      if (image.ok) result = `data:${image.headers.get("content-type") ?? "image/jpeg"};base64,${Buffer.from(await image.arrayBuffer()).toString("base64")}`;
    }
  } catch { /* no network or no match: the small cover stays */ }
  hqCovers.set(key, result);
  return result;
}

function broadcast(kind: string, line: string) {
  latest.set(kind, line);
  listeners.forEach((send) => send(line));
}

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
      const message = JSON.parse(line) as { type: string; title?: string; artist?: string };
      if (message.type === "session" && line.includes('"active":false')) latest.clear();
      // once a large cover is known for the track, the small one from Windows is dropped
      if (message.type === "cover" && [...hqCovers.entries()].some(([key, url]) => url && key.startsWith(`${message.title}|`))) continue;
      broadcast(message.type, line);
      if (message.type === "track" && message.title) {
        const { title, artist = "" } = message;
        largeCover(title, artist).then((url) => { if (url) broadcast("cover", JSON.stringify({ type: "cover", title, url })); });
      }
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
