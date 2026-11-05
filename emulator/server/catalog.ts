// Large covers (1000 px) from two public music catalogs, Deezer first and Apple's second, as the unit and the
// phone app look them up. A result counts only when its title and artist match the track.

const LOOKUP_TIMEOUT_MS = 4000;

const fold = (s: string) =>
  s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/\(.*?\)|\[.*?\]/g, "").replace(/[^\p{L}\p{N}]+/gu, " ").trim();

const sameTrack = (title: string, artist: string, foundTitle: string, foundArtist: string) => {
  const t = fold(title), ft = fold(foundTitle), a = fold(artist), fa = fold(foundArtist);
  return t !== "" && (ft === t || ft.startsWith(t) || t.startsWith(ft)) && (!a || !fa || fa.includes(a) || a.includes(fa));
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

const found = new Map<string, Promise<Buffer | null>>();

/** The large cover's bytes, or null without network or without a match; each track is asked once per run. */
export function largeCover(title: string, artist: string): Promise<Buffer | null> {
  const key = `${title}|${artist}`;
  let result = found.get(key);
  if (!result) {
    result = (async () => {
      try {
        const url = await largeCoverUrl(title, artist);
        if (!url) return null;
        const image = await fetch(url, { signal: AbortSignal.timeout(LOOKUP_TIMEOUT_MS) });
        return image.ok ? Buffer.from(await image.arrayBuffer()) : null;
      } catch {
        return null;
      }
    })();
    found.set(key, result);
    // a miss may be the network: ask again next time
    result.then((bytes) => { if (!bytes) found.delete(key); });
  }
  return result;
}
