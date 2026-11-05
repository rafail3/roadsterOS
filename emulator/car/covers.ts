// The unit's cover desk, as the emulator runs it: the disk first, then the source's own cover (the laptop plays the
// phone app's part), then the catalogs once a short grace passed without one, and a stock cover when nothing exists.
// Names and matching come from core-car; the emulator server keeps the files.
import { bridge } from "./bridge";

/** The same set and order as the unit's stock covers. */
export const STOCK_COVERS = ["/art/amber-coast.svg", "/art/glass-garden.svg", "/art/night-transit.svg"];
/** As on the unit: the source sends within a second of the track change. */
const PHONE_GRACE_MS = 1500;

type Kept = { name: string; pixels: number };
type Track = { title: string; artist: string };

export function createCoverDesk(show: (title: string, art: string | null) => void) {
  let current: Track | null = null;
  let real = false;
  let shownPixels = 0;
  let turn = 0;
  let grace: ReturnType<typeof setTimeout> | undefined;
  const sharp = () => bridge.sharpPx() ** 2;

  const kept = (): Promise<Kept[]> => fetch("/api/covers").then((r) => r.json()).catch(() => []);
  const post = (body: object) =>
    fetch("/api/covers", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) })
      .then((r) => r.json())
      .catch(() => null) as Promise<{ written?: boolean; pixels?: number; found?: boolean } | null>;

  // the kept file of a track, or the name a new one gets
  async function fileFor(title: string, artist: string) {
    const files = await kept();
    const name = bridge.coverFind(files.map((f) => f.name), title, artist);
    return { name: name ?? bridge.coverFileName(title, artist), pixels: files.find((f) => f.name === name)?.pixels ?? 0, isNew: !name };
  }

  const isCurrent = (title: string, artist: string) => current !== null && bridge.coverFind([bridge.coverFileName(title, artist)], current.title, current.artist) !== null;

  function showFile(track: Track, name: string, pixels: number) {
    real = true;
    shownPixels = pixels;
    show(track.title, `/api/covers?name=${encodeURIComponent(name)}&v=${Date.now()}`);
  }

  async function fromCatalog(track: Track, my: number) {
    if (!navigator.onLine) return; // the online event tries again
    const { name } = await fileFor(track.title, track.artist);
    const result = await post({ name, title: track.title, artist: track.artist });
    if (my === turn && result?.found && (result.written || !real)) showFile(track, name, result.pixels ?? 0);
  }

  async function track(next: Track) {
    current = next;
    real = false;
    shownPixels = 0;
    clearTimeout(grace);
    const my = ++turn;
    const file = await fileFor(next.title, next.artist);
    if (my !== turn) return;
    if (!file.isNew) {
      showFile(next, file.name, file.pixels);
      if (file.pixels < sharp()) grace = setTimeout(() => fromCatalog(next, my), PHONE_GRACE_MS);
      return;
    }
    show(next.title, null); // the previous track's cover must not stay up
    grace = setTimeout(async () => {
      await fromCatalog(next, my);
      if (my === turn && !real) show(next.title, STOCK_COVERS[bridge.stockCover(next.title, next.artist, STOCK_COVERS.length)]);
    }, PHONE_GRACE_MS);
  }

  /** A cover from the source for this track, as a data URL: kept, and shown when it is new or larger. */
  async function fromSource(title: string, artist: string, data: string) {
    const { name } = await fileFor(title, artist);
    const result = await post({ name, data });
    if (!result || !current || !isCurrent(title, artist)) return;
    if ((result.pixels ?? 0) >= sharp()) clearTimeout(grace);
    if (result.written || !real) showFile(current, name, result.pixels ?? 0);
  }

  window.addEventListener("online", () => {
    if (current && (!real || shownPixels < sharp())) fromCatalog(current, turn);
  });

  return { track, fromSource };
}
