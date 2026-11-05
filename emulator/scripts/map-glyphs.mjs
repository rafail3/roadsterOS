// The map labels' glyphs for the emulator, in the OS font: MapLibre draws text from signed distance field glyphs in
// protobuf ranges of 256 code points, so the font's TTF files (core-map, also used by the unit) are turned into those.
// Same conventions as node-fontnik: 24 px, 3 px buffer, radius 8, cutoff 0.25. The distance is exact, from each
// pixel centre to the glyph's outline flattened to segments.
//
//   node scripts/map-glyphs.mjs    (writes public/maps/fonts/<font name>/<start>-<end>.pbf)
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import opentype from "opentype.js";

const root = resolve(import.meta.dirname, "../..");
const fonts = join(root, "core-map/src/main/resources/ro/rdstr/map/fonts");
const out = resolve(import.meta.dirname, "../public/maps/fonts");
const FACES = [
  { file: "Geist-Regular.ttf", name: "Geist Regular" },
  { file: "Geist-SemiBold.ttf", name: "Geist SemiBold" },
];
// Latin, Latin Extended (Romanian ă â î ș ț and the neighbours' letters), general punctuation
const RANGES = [0, 256, 512, 8192];
const SIZE = 24, BUFFER = 3, RADIUS = 8, CUTOFF = 0.25;

// ----- protobuf writing -----
const varint = (n, bytes) => { n >>>= 0; while (n > 127) { bytes.push((n & 127) | 128); n >>>= 7; } bytes.push(n); };
const zigzag = (n) => (n << 1) ^ (n >> 31);
const key = (field, wire, bytes) => varint((field << 3) | wire, bytes);
const uint = (field, n, bytes) => { key(field, 0, bytes); varint(n, bytes); };
const sint = (field, n, bytes) => { key(field, 0, bytes); varint(zigzag(n), bytes); };
const blob = (field, payload, bytes) => { key(field, 2, bytes); varint(payload.length, bytes); for (const b of payload) bytes.push(b); };
const text = (field, s, bytes) => blob(field, [...Buffer.from(s, "utf8")], bytes);

/** The outline as closed polylines in pixels, y pointing down from the baseline; curves cut into short segments. */
function outline(path) {
  const rings = [];
  let ring = [], x = 0, y = 0, sx = 0, sy = 0;
  const steps = 8;
  for (const c of path.commands) {
    if (c.type === "M") { if (ring.length) rings.push(ring); ring = [[c.x, c.y]]; x = sx = c.x; y = sy = c.y; }
    else if (c.type === "L") { ring.push([c.x, c.y]); x = c.x; y = c.y; }
    else if (c.type === "Q") {
      for (let i = 1; i <= steps; i++) { const t = i / steps, u = 1 - t; ring.push([u * u * x + 2 * u * t * c.x1 + t * t * c.x, u * u * y + 2 * u * t * c.y1 + t * t * c.y]); }
      x = c.x; y = c.y;
    } else if (c.type === "C") {
      for (let i = 1; i <= steps; i++) {
        const t = i / steps, u = 1 - t;
        ring.push([u * u * u * x + 3 * u * u * t * c.x1 + 3 * u * t * t * c.x2 + t * t * t * c.x, u * u * u * y + 3 * u * u * t * c.y1 + 3 * u * t * t * c.y2 + t * t * t * c.y]);
      }
      x = c.x; y = c.y;
    } else if (c.type === "Z") { ring.push([sx, sy]); rings.push(ring); ring = []; }
  }
  if (ring.length) rings.push(ring);
  return rings;
}

/** Distance to the nearest edge, negative inside (non-zero winding), in pixels. */
function signedDistance(px, py, rings) {
  let best = Infinity, winding = 0;
  for (const r of rings) {
    for (let i = 1; i < r.length; i++) {
      const [ax, ay] = r[i - 1], [bx, by] = r[i];
      const dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
      const t = len ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len)) : 0;
      const ex = ax + t * dx - px, ey = ay + t * dy - py;
      best = Math.min(best, ex * ex + ey * ey);
      if (ay <= py) { if (by > py && dx * (py - ay) - (px - ax) * dy > 0) winding++; }
      else if (by <= py && dx * (py - ay) - (px - ax) * dy < 0) winding--;
    }
  }
  return winding !== 0 ? -Math.sqrt(best) : Math.sqrt(best);
}

function glyph(font, code, ascender) {
  const g = font.charToGlyph(String.fromCodePoint(code));
  if (!g || g.index === 0) return null;
  const advance = Math.round((g.advanceWidth * SIZE) / font.unitsPerEm);
  const path = g.getPath(0, 0, SIZE);
  const box = path.getBoundingBox();
  const bytes = [];
  uint(1, code, bytes);
  if (!path.commands.length || box.x2 <= box.x1) {
    blob(2, new Array(4 * BUFFER * BUFFER).fill(0), bytes);
    uint(3, 0, bytes); uint(4, 0, bytes); sint(5, 0, bytes); sint(6, -ascender, bytes); uint(7, advance, bytes);
    return bytes;
  }
  // the glyph's pixel box, y up from the baseline as the format counts it
  const xmin = Math.floor(box.x1), xmax = Math.ceil(box.x2);
  const ymin = Math.floor(-box.y2), ymax = Math.ceil(-box.y1);
  const w = xmax - xmin, h = ymax - ymin;
  const rings = outline(path);
  const bw = w + 2 * BUFFER, bh = h + 2 * BUFFER;
  const bitmap = new Array(bw * bh);
  for (let j = 0; j < bh; j++) {
    for (let i = 0; i < bw; i++) {
      const x = xmin - BUFFER + i + 0.5;
      const y = -(ymax + BUFFER - j - 0.5);
      const d = signedDistance(x, y, rings) / RADIUS;
      bitmap[j * bw + i] = Math.max(0, Math.min(255, Math.round(255 - 255 * (d + CUTOFF))));
    }
  }
  blob(2, bitmap, bytes);
  uint(3, w, bytes); uint(4, h, bytes); sint(5, xmin, bytes); sint(6, ymax - ascender, bytes); uint(7, advance, bytes);
  return bytes;
}

for (const face of FACES) {
  const font = opentype.parse(readFileSync(join(fonts, face.file)).buffer);
  const ascender = Math.round((font.ascender * SIZE) / font.unitsPerEm);
  mkdirSync(join(out, face.name), { recursive: true });
  for (const start of RANGES) {
    const range = `${start}-${start + 255}`;
    const stack = [];
    text(1, face.name, stack);
    text(2, range, stack);
    let count = 0;
    for (let code = start; code <= start + 255; code++) {
      const g = glyph(font, code, ascender);
      if (g) { blob(3, g, stack); count++; }
    }
    const file = [];
    blob(1, stack, file);
    writeFileSync(join(out, face.name, `${range}.pbf`), Buffer.from(file));
    console.log(`${face.name} ${range}: ${count} glyphs`);
  }
}
