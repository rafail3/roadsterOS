// Visual parity, browser side: opens each scene of the emulator at the bare 1024x600 screen in the installed Chrome
// (or Edge) and writes, per scene, <scene>.png and <scene>.json into build/parity/web/ at the repository root.
//
//   npm run parity:capture -w emulator [-- --base http://localhost:8787] [--only name,name] [--channel msedge]
//
// The emulator must be running (npm run dev). Every scene is loaded with ?bare&noboot&fresh&freeze, and the page clock
// is pinned (Date.now and new Date() return one fixed moment), so two runs give the same picture.
//
// JSON shape, shared with the Android capture (core-ui ParityCapture) and read by the compare (build-logic Parity.kt):
//   { scene, url, scale, canvas: { w, h }, root, elements: [ { key, tag, box: { x, y, w, h }, style: { ... }, text } ] }
// Boxes are in canvas px relative to `.screen`, which is dp in our app (the screen is drawn at --unit-scale). Colours
// are #AARRGGBB, as Android ints print. `root` is set on component scenes: only that element and its subtree are kept,
// and the PNG shows them alone on a transparent screen.
//
// Element keys, which an Android view declares through `View.uiKey` (core-ui UiKey.kt):
//   - an element's segment is its class list without state classes (is-*, has-*), joined with "."; with no class left,
//     its tag name: <svg class="wifi-bars bar-wifi"> is "wifi-bars.bar-wifi", <span role="img"> is "span";
//   - when the parent has more than one rendered child (display is not none) with the same segment, ":<index>" among
//     them is appended: the second .bar-group is "bar-group:1";
//   - the key is the path of segments from `.screen` (left out) down to the element, joined with "/":
//     "bar/bar-group:1/span/wifi-bars.bar-wifi".
// An <svg> is one element: what it draws is compared in the PNG. Pseudo-elements are not listed.
import { mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { parseArgs } from "node:util";
import { chromium } from "playwright-core";

const { values: args } = parseArgs({
  options: {
    base: { type: "string", default: "http://localhost:8787" },
    only: { type: "string" },
    channel: { type: "string" },
    out: { type: "string", default: resolve(import.meta.dirname, "../../build/parity/web") },
  },
});

/** A fixed moment for the page clock: Saturday 31 October 2026, 12:00 local time. */
const FROZEN = new Date(2026, 9, 31, 12, 0, 0);

// ----- maps scenes
const MAPS = "music-app.maps-app";
/** The street and the limit of the car's road found in the pack's tiles. */
const mapsLoaded = (page) => page.waitForSelector(".map-street-slot.is-shown ~ .map-speed .map-limit.is-shown", { timeout: 20000 });
/** As opened, then the map hidden. */
async function mapsStill(page) {
  await mapsLoaded(page);
  await page.addStyleTag({ content: ".maps-app .map-gl { display: none !important; }" });
}
/** Moved by a drag (no longer following), the credits open, then the map hidden. */
async function mapsPanned(page) {
  await mapsLoaded(page);
  await page.mouse.move(600, 300);
  await page.mouse.down();
  await page.mouse.move(560, 280, { steps: 8 });
  await page.mouse.up();
  await page.click(".map-credit .glass-button");
  await page.addStyleTag({ content: ".maps-app .map-gl { display: none !important; }" });
}

const looks = [["dark", "solid"], ["light", "solid"], ["dark", "glassy"], ["light", "glassy"]];
/** Every scene: a name and the emulator's URL parameters; `root` makes it a component scene. */
const SCENES = [
  ...looks.map(([theme, design]) => ({ name: `home-${theme}-${design}`, query: `theme=${theme}&design=${design}` })),
  ...looks.map(([theme, design]) => ({ name: `drawer-${theme}-${design}`, query: `theme=${theme}&design=${design}&drawer` })),
  ...looks.map(([theme, design]) => ({ name: `settings-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=settings` })),
  // the status bar's Wi-Fi bars, connected to the strongest network (level 3)
  ...looks.map(([theme, design]) => ({ name: `wifibars-${theme}-${design}`, query: `theme=${theme}&design=${design}`, root: "bar/bar-group:1/span/wifi-bars.bar-wifi" })),
  // --- sound window (core-ui SoundParity.kt): the section list and each section's own parts; `clicks` pick the section and the state
  ...looks.flatMap(([theme, design]) => {
    const S = "music-app.settings-app.sound-app";
    const nav = (n) => `.sound-app .set-nav li:nth-child(${n}) button`;
    const rock = ".sound-app .snd-presets >> text=Rock";
    return [
      { name: "nav", root: `${S}/set-nav`, clicks: [] },
      { name: "curve", root: `${S}/set-detail/snd-eq/snd-curve`, clicks: [rock] },
      { name: "bands", root: `${S}/set-detail/snd-eq/snd-bands`, clicks: [rock] },
      { name: "balance", root: `${S}/set-detail`, clicks: [nav(2)] },
      { name: "field", root: `${S}/set-detail/snd-stage`, clicks: [nav(3), '.snd-seat-hit[aria-label="Front Left"]'] },
      { name: "loudness", root: `${S}/set-detail/set-group:1`, clicks: [nav(4)] },
      { name: "bass", root: `${S}/set-detail/set-group:1`, clicks: [nav(5)] },
      { name: "filters", root: `${S}/set-detail/set-group:0/set-rows/snd-response`, clicks: [nav(6), '[aria-label="Front Speakers high-pass"]', ...Array(3).fill('[aria-label="Raise Front Speakers high-pass frequency"]')] },
    ].map((x) => ({ ...x, name: `sound-${x.name}-${theme}-${design}`, query: `theme=${theme}&design=${design}&sound` }));
  }),
  // --- end of the sound window
  // ----- media: the music app (playing, paused, no phone) and the radio app (FM, AM), each alone -----
  ...looks.map(([theme, design]) => ({ name: `music-playing-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=music`, root: "music-app" })),
  ...looks.map(([theme, design]) => ({ name: `music-paused-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=music&paused`, root: "music-app" })),
  ...looks.map(([theme, design]) => ({ name: `music-nophone-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=music&nophone`, root: "music-app" })),
  ...looks.map(([theme, design]) => ({ name: `radio-fm-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=radio&source=radio`, root: "music-app.radio-app" })),
  ...looks.map(([theme, design]) => ({ name: `radio-am-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=radio&source=radio&band=am`, root: "music-app.radio-app" })),
  // ----- end media -----
  // --- shell (Screen.tsx and its home): the open apps, the connection notice, the status bar, the dock, the card
  ...looks.map(([theme, design]) => ({ name: `recents-${theme}-${design}`, query: `theme=${theme}&design=${design}&recents=maps,radio,settings`, root: "recents" })),
  ...looks.map(([theme, design]) => ({ name: `notice-${theme}-${design}`, query: `theme=${theme}&design=${design}&notice=bt`, root: "notice" })),
  ...looks.map(([theme, design]) => ({ name: `bar-${theme}-${design}`, query: `theme=${theme}&design=${design}`, root: "bar" })),
  ...looks.map(([theme, design]) => ({ name: `rail-${theme}-${design}`, query: `theme=${theme}&design=${design}`, root: "rail" })),
  ...looks.map(([theme, design]) => ({ name: `card-nophone-${theme}-${design}`, query: `theme=${theme}&design=${design}&nophone`, root: "panel" })),
  // ---- Settings (SettingsApp.tsx, RestartPrompt.tsx): every section, the Software Update states and the restart prompt
  ...["wifi", "bluetooth", "location", "display", "datetime", "sound", "phone", "dash", "camera", "home", "maps", "about"].flatMap((id, i) =>
    looks.map(([theme, design]) => ({ name: `settings-${id}-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=settings`,
      root: "music-app.settings-app", clicks: i ? [`.set-nav-item >> nth=${i}`] : [] }))),
  ...looks.map(([theme, design]) => ({ name: `settings-developer-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=settings`,
    root: "music-app.settings-app", clicks: [".set-nav-item >> nth=11", "[aria-label='Developer Settings']", ".set-nav-item >> nth=12"] })),
  ...looks.map(([theme, design]) => ({ name: `settings-update-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=settings`,
    root: "music-app.settings-app", clicks: [".set-nav-item >> nth=11", ".set-row.is-button:has-text('roadsterOS')"] })),
  ...looks.map(([theme, design]) => ({ name: `settings-update-checked-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=settings`,
    root: "music-app.settings-app", clicks: [".set-nav-item >> nth=11", ".set-row.is-button:has-text('roadsterOS')", ".set-row.is-button:has-text('Check for Updates')"], wait: 1600 })),
  ...looks.map(([theme, design]) => ({ name: `restart-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=settings`,
    root: "alert:1", clicks: [".set-nav-item >> nth=11", ".set-row.is-button:has-text('Restart the Unit')"] })),
  // ---- end Settings
  // --- end shell
  // ----- maps (MapsApp.tsx): the screen's controls and status over the map; the map itself is hidden, its tiles are
  // drawn by MapLibre here and by our renderer on the unit. follow: as opened, with the street pill and the limit sign
  // of the car's road from the laptop's pack (MAP_PACKS); panned: moved by a drag, the credits open
  ...looks.map(([theme, design]) => ({ name: `maps-follow-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=maps`, root: MAPS, prepare: mapsStill })),
  ...looks.map(([theme, design]) => ({ name: `maps-panned-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=maps`, root: MAPS, prepare: mapsPanned })),
];

/** Runs in the page: the visible elements under .screen with their keys, boxes and computed styles. */
function collect(root) {
  const screen = document.querySelector(".screen");
  const rect = screen.getBoundingClientRect();
  const canvasW = parseFloat(getComputedStyle(screen).width);
  const scale = rect.width / canvasW;
  const paint = document.createElement("canvas").getContext("2d");
  // any CSS colour (rgb(), color(srgb …), color-mix results) as #AARRGGBB, through the canvas' own normalisation
  const hex = (css) => {
    if (!css || css === "none") return null;
    paint.fillStyle = "#00000000";
    paint.fillStyle = css;
    const v = paint.fillStyle;
    let r, g, b, a = 255;
    if (v.startsWith("#")) [r, g, b] = [1, 3, 5].map((i) => parseInt(v.slice(i, i + 2), 16));
    else {
      const n = v.match(/[\d.]+/g).map(Number);
      [r, g, b] = n;
      a = Math.round((n[3] ?? 1) * 255);
    }
    return "#" + [a, r, g, b].map((n) => n.toString(16).padStart(2, "0")).join("").toUpperCase();
  };
  const round = (v) => Math.round(v * 1000) / 1000;
  const px = (v) => (v === "normal" || v === "" ? null : round(parseFloat(v)));
  const segment = (el) => {
    const classes = [...el.classList].filter((c) => !c.startsWith("is-") && !c.startsWith("has-"));
    return classes.length ? classes.join(".") : el.tagName.toLowerCase();
  };
  const rendered = (el) => getComputedStyle(el).display !== "none";
  const out = [];
  const walk = (el, path, opacity) => {
    const cs = getComputedStyle(el);
    const seen = opacity * parseFloat(cs.opacity);
    const r = el.getBoundingClientRect();
    const box = { x: round((r.left - rect.left) / scale), y: round((r.top - rect.top) / scale), w: round(r.width / scale), h: round(r.height / scale) };
    const inside = r.right > rect.left && r.left < rect.right && r.bottom > rect.top && r.top < rect.bottom;
    const text = [...el.childNodes].filter((n) => n.nodeType === Node.TEXT_NODE).map((n) => n.textContent).join("").replace(/\s+/g, " ").trim();
    const keep = !root || path === root || path.startsWith(root + "/");
    if (root && path === root) el.setAttribute("data-parity-root", "");
    if (keep && cs.visibility === "visible" && seen > 0 && r.width > 0 && r.height > 0 && inside) {
      out.push({
        key: path,
        tag: el.tagName.toLowerCase(),
        box,
        style: {
          background: hex(cs.backgroundColor),
          backgroundImage: cs.backgroundImage === "none" ? null : cs.backgroundImage,
          color: hex(cs.color),
          fontFamily: cs.fontFamily,
          fontSize: px(cs.fontSize),
          fontWeight: Number(cs.fontWeight),
          letterSpacing: px(cs.letterSpacing),
          lineHeight: px(cs.lineHeight),
          borderRadius: cs.borderRadius,
          border: cs.borderStyle === "none" ? null : `${cs.borderWidth} ${cs.borderStyle} ${hex(cs.borderColor)}`,
          opacity: parseFloat(cs.opacity),
          boxShadow: cs.boxShadow === "none" ? null : cs.boxShadow,
        },
        text: text || null,
      });
    }
    if (el instanceof SVGSVGElement) return;
    const kids = [...el.children].filter(rendered);
    const counts = {};
    kids.forEach((k) => { const s = segment(k); counts[s] = (counts[s] ?? 0) + 1; });
    const index = {};
    for (const k of kids) {
      const s = segment(k);
      const i = index[s] = (index[s] ?? -1) + 1;
      walk(k, (path ? path + "/" : "") + s + (counts[s] > 1 ? `:${i}` : ""), seen);
    }
  };
  const kids = [...screen.children].filter(rendered);
  const counts = {};
  kids.forEach((k) => { const s = segment(k); counts[s] = (counts[s] ?? 0) + 1; });
  const index = {};
  for (const k of kids) {
    const s = segment(k);
    const i = index[s] = (index[s] ?? -1) + 1;
    walk(k, s + (counts[s] > 1 ? `:${i}` : ""), parseFloat(getComputedStyle(screen).opacity));
  }
  return { scale, canvas: { w: canvasW, h: parseFloat(getComputedStyle(screen).height) }, elements: out };
}

/** Runs in the page: everything but the component's subtree (marked by collect) drawn invisible, on a transparent page. */
function isolate() {
  const style = document.createElement("style");
  style.textContent = "html, body { background: transparent !important; } * { visibility: hidden !important; } [data-parity-root], [data-parity-root] * { visibility: visible !important; }";
  document.head.appendChild(style);
}

// software rasterising, as the Android side renders (layoutlib's CPU Skia), and the same on every machine whatever its GPU
const launch = (channel) => chromium.launch({ channel, args: ["--disable-gpu"] });
const browser = await launch(args.channel ?? "chrome").catch(() => launch("msedge"));
const context = await browser.newContext({ viewport: { width: 1024, height: 600 }, deviceScaleFactor: 1, timezoneId: "Europe/Bucharest", locale: "en-GB" });
mkdirSync(args.out, { recursive: true });
const wanted = args.only?.split(",");
let failed = 0;
for (const scene of SCENES.filter((s) => !wanted || wanted.includes(s.name))) {
  const page = await context.newPage();
  await page.clock.setFixedTime(FROZEN);
  const url = `${args.base}/?bare&noboot&fresh&freeze&${scene.query}`;
  page.on("pageerror", (e) => console.error(`${scene.name}: page error: ${e.message}`));
  await page.goto(url, { waitUntil: "networkidle" });
  await page.waitForSelector(".screen");
  for (const selector of scene.clicks ?? []) await page.click(selector);
  // the dev server's own badge sits over the page, outside the screen
  await page.addStyleTag({ content: "nextjs-portal { display: none !important; }" });
  await page.evaluate(() => document.fonts.ready);
  if (scene.prepare) await scene.prepare(page);
  // two frames for layout effects that measure and set state once
  await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));
  // scenes reached by taps (a settings section, a sub-page, a prompt): each selector clicked, then two frames
  for (const target of scene.clicks ?? []) {
    await page.click(target);
    await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));
  }
  if (scene.wait) await page.waitForTimeout(scene.wait);
  const layout = await page.evaluate(collect, scene.root ?? null);
  if (scene.root) {
    if (!layout.elements.some((e) => e.key === scene.root)) {
      console.error(`${scene.name}: no element ${scene.root}`);
      failed++;
    }
    await page.evaluate(isolate);
  }
  const box = await page.locator(".screen").boundingBox();
  await page.screenshot({ path: resolve(args.out, `${scene.name}.png`), clip: box, omitBackground: Boolean(scene.root), animations: "disabled" });
  writeFileSync(resolve(args.out, `${scene.name}.json`), JSON.stringify({ scene: scene.name, url, ...layout, root: scene.root ?? null }, null, 1));
  console.log(`${scene.name}: ${layout.elements.length} elements`);
  await page.close();
}
await browser.close();
console.log(`written to ${args.out}`);
process.exit(failed ? 1 : 0);
