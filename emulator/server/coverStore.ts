// The emulator's copy of the unit's cover memory: one file per track under emulator/.covers, named by core-car's
// CoverIndex in the page. A file is replaced only by a larger image; past the limit, the cover of the track heard
// longest ago goes.
import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, statSync, unlinkSync, utimesSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

const DIR = resolve(".covers");
/** The same limit as the unit's. */
const MAX_FILES = 600;
const NAME = /^[^/\\]+\.jpg$/;

export const validName = (name: string) => NAME.test(name) && !name.includes("..");

/** Width times height from a JPEG or PNG header, 0 when unreadable. */
export function pixels(b: Buffer): number {
  if (b.length > 24 && b.readUInt32BE(0) === 0x89504e47) return b.readUInt32BE(16) * b.readUInt32BE(20);
  if (b.length < 4 || b[0] !== 0xff || b[1] !== 0xd8) return 0;
  let at = 2;
  while (at + 9 < b.length) {
    if (b[at] !== 0xff) return 0;
    const marker = b[at + 1];
    if (marker === 0xff) { at++; continue; }
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) return b.readUInt16BE(at + 5) * b.readUInt16BE(at + 7);
    at += 2 + b.readUInt16BE(at + 2);
  }
  return 0;
}

export function contentType(b: Buffer) {
  if (b.readUInt32BE(0) === 0x89504e47) return "image/png";
  if (b.subarray(8, 12).toString() === "WEBP") return "image/webp";
  return "image/jpeg";
}

const sizes = new Map<string, { mtime: number; pixels: number }>();

function dir() {
  if (!existsSync(DIR)) mkdirSync(DIR, { recursive: true });
  return DIR;
}

/** Every kept cover with its size, read from the headers once per file version. */
export function list(): { name: string; pixels: number }[] {
  return readdirSync(dir()).filter(validName).map((name) => {
    const mtime = statSync(join(DIR, name)).mtimeMs;
    const known = sizes.get(name);
    if (known && known.mtime === mtime) return { name, pixels: known.pixels };
    const px = pixels(readFileSync(join(DIR, name)));
    sizes.set(name, { mtime, pixels: px });
    return { name, pixels: px };
  });
}

/** A kept cover; reading it marks the track as heard now. */
export function read(name: string): Buffer | null {
  const file = join(dir(), name);
  if (!existsSync(file)) return null;
  const now = new Date();
  utimesSync(file, now, now);
  return readFileSync(file);
}

/** Keeps the image unless an equal or larger one is kept under that name. */
export function put(name: string, bytes: Buffer): { written: boolean; pixels: number } {
  const file = join(dir(), name);
  const incoming = pixels(bytes);
  if (existsSync(file)) {
    const kept = pixels(readFileSync(file));
    if (incoming <= kept) return { written: false, pixels: kept };
  }
  const isNew = !existsSync(file);
  writeFileSync(`${file}.part`, bytes);
  renameSync(`${file}.part`, file);
  if (isNew) trim();
  return { written: true, pixels: incoming };
}

function trim() {
  const files = readdirSync(DIR).filter(validName).map((name) => ({ name, mtime: statSync(join(DIR, name)).mtimeMs }));
  if (files.length <= MAX_FILES) return;
  files.sort((a, b) => a.mtime - b.mtime).slice(0, files.length - MAX_FILES).forEach((f) => unlinkSync(join(DIR, f.name)));
}
