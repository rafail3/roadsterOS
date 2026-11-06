import { useEffect, useRef } from "react";
import { bridge, type BootArtData } from "../car/bridge";

// The boot screen and the setup's start screen, drawn dot by dot on a 1024x600 canvas, exactly as the unit draws them.
// The dots, the font and the timeline come from core-car (BootArt); the drawing below repeats the unit's, number for number.

const BG = "#07080a";
const PHOSPHOR = "#fff1c9";
const CAPTION = "#ffb02e";
const BAR = "#ff8a1a";
const SUBTITLE = "#c9b9a6";
const CAPTION_TEXT = "ROADSTEROS";
const SUBTITLE_TEXT = "THE OS FOR YOUR CAR";
/** After the picture is complete the boot screen stays this long before what comes next. */
const HOLD_MS = 800;

type WordDot = { x: number; y: number; c: string; layer: number; li: number; th: number };
type CubeDot = { x: number; y: number; c: string; face: number; letter: boolean; th: number };

let art: { tiny: Record<string, string>; word: { w: number; dots: WordDot[] }; cube: CubeDot[] } | null = null;
/** core-car's dots, read once and kept. */
function boot() {
  if (art) return art;
  const a: BootArtData = bridge.bootArt();
  const word: WordDot[] = [];
  for (let i = 0; i < a.word.dots.length; i += 6) {
    const d = a.word.dots;
    word.push({ x: d[i], y: d[i + 1], c: a.colors[d[i + 2]], layer: d[i + 3], li: d[i + 4], th: d[i + 5] });
  }
  const cube: CubeDot[] = [];
  for (let i = 0; i < a.cube.length; i += 6) {
    const d = a.cube;
    cube.push({ x: d[i], y: d[i + 1], c: a.colors[d[i + 2]], face: d[i + 3], letter: d[i + 4] === 1, th: d[i + 5] });
  }
  art = { tiny: a.tiny, word: { w: a.word.width, dots: word }, cube };
  return art;
}

const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5].map((v) => (v + 0.5) / 16);
const bayer = (x: number, y: number) => BAYER[(y & 3) * 4 + (x & 3)];
const clamp = (v: number) => Math.max(0, Math.min(1, v));
const outCubic = (t: number) => 1 - Math.pow(1 - clamp(t), 3);
/** ease-out with a small overshoot, for things that land */
const outBack = (t: number) => { t = clamp(t); const c = 1.6; return 1 + (c + 1) * Math.pow(t - 1, 3) + c * Math.pow(t - 1, 2); };

function tinyText(ctx: CanvasRenderingContext2D, text: string, x: number, y: number, cell: number, color: string, dot = 0.8) {
  const tiny = boot().tiny;
  ctx.fillStyle = color;
  [...text].forEach((ch, k) => {
    const g = tiny[ch] ?? tiny[" "];
    for (let i = 0; i < 35; i++) if (g[i] === "#") ctx.fillRect(x + (k * 6 + i % 5) * cell, y + Math.floor(i / 5) * cell, cell * dot, cell * dot);
  });
  return text.length * 6 * cell;
}

/** the tube: scanlines every 3 px, the corners darker; drawn once, laid over every frame */
let glass: HTMLCanvasElement | null = null;
function tube() {
  if (glass) return glass;
  glass = document.createElement("canvas");
  glass.width = 1024; glass.height = 600;
  const g = glass.getContext("2d")!;
  g.fillStyle = "rgba(0,0,0,0.3)";
  for (let y = 0; y < 600; y += 3) g.fillRect(0, y, 1024, 1);
  const r = g.createRadialGradient(512, 300, 240, 512, 300, 660);
  r.addColorStop(0, "rgba(0,0,0,0)"); r.addColorStop(1, "rgba(0,0,0,0.6)");
  g.fillStyle = r; g.fillRect(0, 0, 1024, 600);
  return glass;
}

/** a soft brighter band rolling down the tube, as an old screen's refresh does */
function roll(ctx: CanvasRenderingContext2D, y: number) {
  const g = ctx.createLinearGradient(0, y - 60, 0, y + 60);
  g.addColorStop(0, "rgba(255,200,140,0)"); g.addColorStop(0.5, "rgba(255,200,140,0.045)"); g.addColorStop(1, "rgba(255,200,140,0)");
  ctx.fillStyle = g; ctx.fillRect(0, y - 60, 1024, 120);
}

/** the cube at progress p per face (top, left, right; 0 hidden .. 1 whole); dots dissolve in by their fixed order */
function drawCube(ctx: CanvasRenderingContext2D, cx: number, cy: number, pitch: number, p: number[] = [1, 1, 1], flash = false) {
  for (const d of boot().cube) {
    const k = p[d.face];
    if (d.th > k) continue;
    // a dot that just arrived burns bright for a moment, like phosphor
    const fresh = k < 1 && d.th > k - 0.18;
    ctx.fillStyle = fresh || (flash && d.letter) ? PHOSPHOR : d.c;
    ctx.fillRect(cx + d.x * pitch, cy + d.y * pitch, pitch * 0.72, pitch * 0.72);
  }
}

/** the wordmark: each letter dissolves in while it drops into place; the depth grows out behind the face */
function drawWord(ctx: CanvasRenderingContext2D, ox: number, oy: number, pitch: number, letter: (li: number) => number = () => 1, depth = 1) {
  for (const d of boot().word.dots) {
    const k = letter(d.li);
    if (k <= 0 || (d.layer && depth <= 0) || d.th > k * 1.15) continue;
    const drop = (1 - outBack(k)) * -18;
    const shift = d.layer ? -d.layer * 2 * (1 - outCubic(depth)) + d.layer * 2 : 0;
    ctx.fillStyle = d.layer === 0 && k < 1 && d.th > k * 1.15 - 0.2 ? PHOSPHOR : d.c;
    ctx.fillRect(ox + (d.x + (d.layer ? shift : 0)) * pitch, oy + (d.y + (d.layer ? shift : 0)) * pitch + drop, pitch * 0.78, pitch * 0.78);
  }
}

/** The boot picture t ms after the unit starts: the tube lights, the cube builds r, O, S, rdstr lands, the name is typed. */
export function drawBoot(c: CanvasRenderingContext2D, t: number, still: boolean) {
  const f = bridge.bootFrame(t);
  c.fillStyle = BG; c.fillRect(0, 0, 1024, 600);
  if (f.tube) { c.fillStyle = `rgba(255,241,201,${f.tube.alpha})`; c.fillRect(f.tube.x, f.tube.y, f.tube.w, f.tube.h); }
  if (f.cube) drawCube(c, 512, 168, 3.4, f.cube, f.flash);
  const word = boot().word;
  if (f.letters) drawWord(c, 512 - word.w * 4 / 2, 322, 4, (li) => f.letters![li], f.depth);
  if (f.typed >= 0) {
    const cell = 2, x = 512 - CAPTION_TEXT.length * 6 * cell / 2;
    const w = tinyText(c, CAPTION_TEXT.slice(0, f.typed), x, 492, cell, CAPTION);
    if (f.cursor) { c.fillStyle = CAPTION; c.fillRect(x + w + 2, 492, 5 * cell, 7 * cell); }
  }
  if (!still) roll(c, f.rollY);
  c.drawImage(tube(), 0, 0);
  // the picture flickers twice right after the tube lights
  if (f.flicker) { c.fillStyle = "rgba(7,8,10,0.35)"; c.fillRect(0, 0, 1024, 600); }
}

/** The setup's first screen: the version in a bar, the cube and the wordmark, "touch to start" blinking in a bar. */
export function drawStart(s: CanvasRenderingContext2D, t: number, version: string, still: boolean) {
  s.fillStyle = BG; s.fillRect(0, 0, 1024, 600);
  const bar = (y: number, label: string) => {
    s.strokeStyle = BAR; s.lineWidth = 2; s.strokeRect(72, y, 880, 40);
    tinyText(s, label, 100, y + 13, 2, CAPTION);
    for (let i = 0; i < 40; i++) for (let j = 0; j < 9; j++) {
      if (bayer(i, j) > 1 - i / 40) continue;
      s.fillStyle = BAR; s.fillRect(760 + i * 4.6, y + 4 + j * 3.6, 3, 2.6);
    }
  };
  bar(48, `ROADSTEROS ${version}`);
  drawCube(s, 262, 300, 4.2);
  drawWord(s, 420, 240, 4.6);
  tinyText(s, SUBTITLE_TEXT, 424, 412, 2, SUBTITLE);
  bar(512, "TOUCH TO START" + (Math.floor(t / 530) % 2 ? "_" : " "));
  if (!still) roll(s, bridge.bootFrame(t).rollY);
  s.drawImage(tube(), 0, 0);
}

const stillMotion = () => typeof matchMedia !== "undefined" && matchMedia("(prefers-reduced-motion: reduce)").matches;

/** A canvas redrawn every frame by [draw] with the time since it appeared. */
function useCanvas(draw: (c: CanvasRenderingContext2D, t: number, still: boolean) => boolean | void) {
  const ref = useRef<HTMLCanvasElement>(null);
  const drawRef = useRef(draw);
  drawRef.current = draw;
  useEffect(() => {
    const c = ref.current?.getContext("2d");
    if (!c) return;
    const start = performance.now();
    let frame = 0;
    const step = (now: number) => {
      const still = stillMotion();
      if (drawRef.current(c, still ? 9999 : now - start, still) === false) return;
      frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, []);
  return ref;
}

/** The boot screen; a touch skips it. Calls [onDone] once the picture is complete and has been seen. */
export function BootScreen({ onDone }: { onDone: () => void }) {
  const done = useRef(false);
  const finish = () => { if (!done.current) { done.current = true; onDone(); } };
  const ref = useCanvas((c, t, still) => {
    drawBoot(c, t, still);
    if (t >= bridge.bootDoneMs() + HOLD_MS) { finish(); return false; }
  });
  return (
    <div className="boot" role="img" aria-label="roadsterOS is starting" onPointerDown={finish}>
      <canvas ref={ref} className="boot-canvas" width={1024} height={600} />
    </div>
  );
}

/** The setup's start screen: a touch anywhere starts the setup. */
export function StartScreen({ version, onStart }: { version: string; onStart: () => void }) {
  const ref = useCanvas((c, t, still) => drawStart(c, t, version, still));
  return (
    <button className="boot" aria-label="Touch to start the setup" onClick={onStart}>
      <canvas ref={ref} className="boot-canvas" width={1024} height={600} />
    </button>
  );
}

// frame captures: the boot and start pictures at any moment, compared against the approved design's frames
if (typeof window !== "undefined") Object.assign(window, { rdstrBoot: { drawBoot, drawStart } });

/** The boot screen's 5x7 pixel font, for captions elsewhere in the setup. */
export const pixelFont = () => boot().tiny;

/** The symbol, small: the cube's dots at whatever size, drawn once. [size] in canvas pixels (the screen draws at 0.8). */
export function CubeMark({ size }: { size: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const px = Math.round(size * 0.8);
  useEffect(() => {
    const c = ref.current?.getContext("2d");
    if (!c) return;
    c.clearRect(0, 0, px, px);
    // the cube spans about 76 dots each way
    drawCube(c, px / 2, px / 2, px / 80);
  }, [px]);
  return <canvas ref={ref} className="cube-mark" width={px} height={px} style={{ width: size, height: size }} aria-hidden="true" />;
}
