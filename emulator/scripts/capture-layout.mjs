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

const looks = [["dark", "solid"], ["light", "solid"], ["dark", "glassy"], ["light", "glassy"]];
/** Every scene: a name and the emulator's URL parameters; `root` makes it a component scene. */
const SCENES = [
  ...looks.map(([theme, design]) => ({ name: `home-${theme}-${design}`, query: `theme=${theme}&design=${design}` })),
  ...looks.map(([theme, design]) => ({ name: `drawer-${theme}-${design}`, query: `theme=${theme}&design=${design}&drawer` })),
  ...looks.map(([theme, design]) => ({ name: `settings-${theme}-${design}`, query: `theme=${theme}&design=${design}&app=settings` })),
  // the status bar's Wi-Fi bars, connected to the strongest network (level 3)
  ...looks.map(([theme, design]) => ({ name: `wifibars-${theme}-${design}`, query: `theme=${theme}&design=${design}`, root: "bar/bar-group:1/span/wifi-bars.bar-wifi" })),
  // --- drawer, sheets, prompts, map offer (core-ui DrawerParity) ---
  ...looks.flatMap(([theme, design]) => {
    const q = `theme=${theme}&design=${design}`;
    const look = `${theme}-${design}`;
    const click = (...selectors) => async (page) => { for (const s of selectors) await page.click(s); };
    const wifiSheet = async (page) => { await click(".toggle-wifi", ".toggle-wifi")(page); await page.waitForTimeout(2600); };
    return [
      { name: `drawerpanel-${look}`, query: `${q}&drawer`, root: "drawer" },
      { name: `drawerpanel-off-${look}`, query: `${q}&drawer`, root: "drawer", act: click(".toggle-bt", ".toggle-wifi", ".toggle-location", ".toggle-mute") },
      { name: `devices-${look}`, query: `${q}&devices`, root: "devices" },
      { name: `devices-off-${look}`, query: `${q}&devices&btoff`, root: "devices" },
      { name: `networks-${look}`, query: `${q}&drawer`, root: "devices.networks", act: wifiSheet },
      { name: `networks-join-${look}`, query: `${q}&drawer`, root: "devices.networks", act: async (page) => { await wifiSheet(page); await page.click(".networks .saved-device.is-found .device-main"); } },
      { name: `prompt-location-${look}`, query: `${q}&drawer`, root: "alert:0", act: click(".toggle-location", ".toggle-wifi", ".toggle-wifi") },
      { name: `prompt-restart-${look}`, query: `${q}&drawer`, root: "alert:1", act: async (page) => { await page.hover(".toggle-settings"); await page.mouse.down(); await page.waitForTimeout(650); await page.mouse.up(); } },
      { name: `mapoffer-${look}`, query: `${q}&place=4`, root: "map-offer", act: (page) => page.waitForSelector(".map-offer.is-shown") },
    ];
  }),
  // --- end drawer, sheets ---
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
  // the dev server's own badge sits over the page, outside the screen
  await page.addStyleTag({ content: "nextjs-portal { display: none !important; }" });
  await page.evaluate(() => document.fonts.ready);
  // two frames for layout effects that measure and set state once
  await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));
  // a scene may click its way to the state it shows
  if (scene.act) {
    await scene.act(page);
    await page.mouse.move(0, 0);
    await page.evaluate(() => new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done))));
  }
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
