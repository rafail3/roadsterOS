// The country map packs this laptop has built (map-build/packs/<CODE>.mbtiles), read the way the unit reads them:
// straight from the MBTiles file, with no network.
import { DatabaseSync } from "node:sqlite";
import { existsSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";

const DIR = process.env.MAP_PACKS ?? resolve(process.cwd(), "../../map-build/packs");
const open = new Map<string, DatabaseSync>();

export function packs(): string[] {
  if (!existsSync(DIR)) return [];
  return readdirSync(DIR).filter((f) => f.endsWith(".mbtiles")).map((f) => f.slice(0, -".mbtiles".length));
}

function db(code: string) {
  let d = open.get(code);
  if (!d) {
    d = new DatabaseSync(join(DIR, `${code}.mbtiles`), { readOnly: true });
    open.set(code, d);
  }
  return d;
}

/** One vector tile, gzipped as stored, from the first pack that has it; MBTiles counts rows from the bottom. */
export function tile(z: number, x: number, y: number): Uint8Array | null {
  const row = (1 << z) - 1 - y;
  for (const code of packs()) {
    const hit = db(code).prepare("SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?").get(z, x, row) as { tile_data: Uint8Array } | undefined;
    if (hit) return hit.tile_data;
  }
  return null;
}
