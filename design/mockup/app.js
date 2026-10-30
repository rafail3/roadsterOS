// Preview state for the mockup. Mirrors the shape of RulesState in core-car.
const state = {
  source: "bt",
  playing: true,
  call: "idle",
  theme: "auto",      // auto | light | dark
  camera: false,
  drawer: false,
  bt: true,
  wifi: true,
  mute: false,
  brightness: 80,     // 0..100, what the backlight would be set to
  autoBrightness: true,
  hour: null,         // null = live clock, otherwise a simulated hour 0..23
};

const DAY_START = 5;   // light theme from 05:00
const DAY_END = 19;    // dark theme from 19:00
const DAY_BRIGHTNESS = 85;
const NIGHT_BRIGHTNESS = 40;

const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));
const screen = $("#screen");

function pad(n) { return String(n).padStart(2, "0"); }

function now() {
  if (state.hour === null) return new Date();
  return new Date(2026, 9, 31, state.hour, 0);
}

function effectiveTheme() {
  if (state.theme !== "auto") return state.theme;
  const h = now().getHours();
  return h >= DAY_START && h < DAY_END ? "light" : "dark";
}

let lastMinute = -1;
function renderClock() {
  const t = now();
  if (state.hour === null && t.getMinutes() === lastMinute) return;
  lastMinute = state.hour === null ? t.getMinutes() : -1;
  $("#clock").textContent = `${pad(t.getHours())}:${pad(t.getMinutes())}`;
  $("#clock").dateTime = t.toISOString();
  $("#date").textContent = t.toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" });
}

let currentTheme = null;
let themingTimer = null;
function renderTheme() {
  const theme = effectiveTheme();
  if (currentTheme !== null && theme !== currentTheme) {
    // crossfade every colour on the screen; the Android build animates the same tokens
    screen.classList.add("theming");
    clearTimeout(themingTimer);
    themingTimer = setTimeout(() => screen.classList.remove("theming"), 750);
  }
  currentTheme = theme;
  screen.classList.toggle("light", theme === "light");
  if (state.autoBrightness) state.brightness = theme === "light" ? DAY_BRIGHTNESS : NIGHT_BRIGHTNESS;
  $("#bar-theme").className = `icon ${theme === "light" ? "i-sun" : "i-moon"}`;
  $("#bar-theme").setAttribute("aria-label", theme === "light" ? "Day theme" : "Night theme");
  $$("[data-theme]").forEach((b) => { const on = b.dataset.theme === state.theme; b.classList.toggle("is-on", on); b.setAttribute("aria-pressed", String(on)); });
}

function renderBrightness() {
  // the backlight cannot be shown in a browser: a black veil stands in for a dimmer panel
  const veil = Math.max(0, Math.min(0.78, (1 - state.brightness / 100) * 0.85));
  $("#dimmer").style.opacity = veil.toFixed(3);
  $("#brightness").value = state.brightness;
  $("#brightness").setAttribute("aria-valuetext", `${state.brightness} percent`);
  $("#brightness-readout").textContent = `${state.brightness}%`;
}

function render() {
  renderTheme();
  renderBrightness();

  // status bar
  const sourceText = { bt: "Bluetooth", radio: "Radio", none: "No source" }[state.source];
  $("#bar-source").textContent = state.source === "bt" && !state.bt ? "Bluetooth off" : sourceText;
  $("#bar-bt").className = `icon ${state.source === "radio" ? "i-radio" : "i-bluetooth"}`;
  $("#bar-bt").classList.toggle("is-on", state.source !== "none" && (state.source !== "bt" || state.bt));
  $("#bar-bt").classList.toggle("is-off", state.source === "bt" && !state.bt);
  $("#bar-volume").className = `icon ${state.mute ? "i-speaker-slash is-off" : "i-speaker-high"}`;
  $("#bar-volume").setAttribute("aria-label", state.mute ? "Muted" : "Sound on");

  // panel
  $("#np-bt").hidden = state.source !== "bt";
  $("#np-radio").hidden = state.source !== "radio";
  $("#np-none").hidden = state.source !== "none";
  const play = $("#play");
  play.setAttribute("aria-label", state.playing ? "Pause" : "Play");
  play.firstElementChild.className = `icon ${state.playing ? "i-pause" : "i-play"}`;

  // rail highlight follows the source
  $$(".rail-item").forEach((b) => {
    const on = (state.source === "bt" && b.dataset.fn === "music") || (state.source === "radio" && b.dataset.fn === "radio");
    b.classList.toggle("is-active", on);
    if (on) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
  });

  // drawer and overlays
  const drawer = $("#drawer");
  drawer.classList.toggle("is-open", state.drawer);
  if (!drawer.classList.contains("is-dragging")) drawer.style.transform = "";
  drawer.inert = !state.drawer;
  $("#handle").setAttribute("aria-expanded", String(state.drawer));
  $("#scrim").classList.toggle("is-open", state.drawer);
  $$(".toggle[data-toggle]").forEach((t) => { const on = Boolean(state[t.dataset.toggle]); t.classList.toggle("is-on", on); t.setAttribute("aria-pressed", String(on)); });
  const covered = state.drawer || state.call !== "idle" || state.camera;
  ["#bar", ".rail", ".field", "#panel"].forEach((sel) => { $(sel).inert = covered; });
  $("#camera").hidden = !state.camera;
  $("#call").hidden = state.call === "idle";
  $("#call-ringing").hidden = state.call !== "ringing";
  $("#call-active").hidden = state.call !== "active";
  $("#call-timer").hidden = state.call !== "active";
  $("#call-number").hidden = state.call === "active";
  $("#call-state").textContent = state.call === "ringing" ? "Incoming call" : "On call";

  // state panel highlight
  $$("[data-source]").forEach((b) => b.classList.toggle("is-on", b.dataset.source === state.source));
  $$("[data-playing]").forEach((b) => b.classList.toggle("is-on", (b.dataset.playing === "1") === state.playing));
  $$("[data-call]").forEach((b) => b.classList.toggle("is-on", b.dataset.call === state.call));
  $$("[data-clock]").forEach((b) => b.classList.toggle("is-on", (b.dataset.clock === "live") === (state.hour === null)));
  $("[data-camera]").classList.toggle("is-on", state.camera);
  $("[data-drawer]").classList.toggle("is-on", state.drawer);
  $("#hour").value = state.hour === null ? new Date().getHours() : state.hour;
  $("#hour-readout").textContent = state.hour === null ? "live" : `${pad(state.hour)}:00`;
  $$(".seg button").forEach((b) => b.setAttribute("aria-pressed", String(b.classList.contains("is-on"))));
  renderClock();
}

// state panel
$$("[data-source]").forEach((b) => b.onclick = () => { state.source = b.dataset.source; render(); });
$$("[data-playing]").forEach((b) => b.onclick = () => { state.playing = b.dataset.playing === "1"; render(); });
$$("[data-call]").forEach((b) => b.onclick = () => { state.call = b.dataset.call; render(); });
$$("[data-clock]").forEach((b) => b.onclick = () => { state.hour = b.dataset.clock === "live" ? null : 12; render(); });
$("#hour").oninput = (e) => { state.hour = Number(e.target.value); render(); };
$("[data-camera]").onclick = () => { state.camera = !state.camera; render(); };
$("[data-drawer]").onclick = () => { state.drawer = !state.drawer; render(); };

// on-screen interactions
$("#handle").onclick = () => { state.drawer = true; render(); };
$("#handle-close").onclick = () => { state.drawer = false; render(); };
$("#scrim").onclick = () => { state.drawer = false; render(); };
$("#play").onclick = () => { state.playing = !state.playing; render(); };
$$(".toggle[data-toggle]").forEach((t) => t.onclick = () => { state[t.dataset.toggle] = !state[t.dataset.toggle]; render(); });
$$("[data-theme]").forEach((b) => b.onclick = () => { state.theme = b.dataset.theme; state.autoBrightness = true; render(); });
$("#brightness").oninput = (e) => { state.brightness = Number(e.target.value); state.autoBrightness = false; render(); };
$$(".rail-item").forEach((b) => b.onclick = () => {
  if (b.dataset.fn === "music") state.source = "bt";
  if (b.dataset.fn === "radio") state.source = "radio";
  if (b.dataset.fn === "camera") state.camera = !state.camera;
  render();
});
$("#call-ringing").children[0].onclick = () => { state.call = "idle"; render(); };
$("#call-ringing").children[1].onclick = () => { state.call = "active"; render(); };
$("#call-active").children[1].onclick = () => { state.call = "idle"; render(); };
$("#camera-close").onclick = () => { state.camera = false; render(); };
window.addEventListener("keydown", (e) => { if (e.key === "Escape") { state.drawer = false; state.camera = false; render(); } });

// pull the status bar down: the drawer follows the finger, opens past a third of its height, else snaps back
let dragStart = null;
const drawerEl = $("#drawer");
function drawerHeight() { return drawerEl.offsetHeight; }
$("#bar").addEventListener("pointerdown", (e) => {
  dragStart = e.clientY;
  drawerEl.classList.add("is-dragging");
  drawerEl.inert = false;
  $("#bar").setPointerCapture(e.pointerId);
});
$("#bar").addEventListener("pointermove", (e) => {
  if (dragStart === null) return;
  const dy = Math.max(0, Math.min(drawerHeight(), e.clientY - dragStart));
  drawerEl.style.transform = `translateY(${dy - drawerHeight()}px)`;
});
function endDrag(e) {
  if (dragStart === null) return;
  const dy = e.clientY - dragStart;
  dragStart = null;
  drawerEl.classList.remove("is-dragging");
  state.drawer = dy > drawerHeight() / 3;
  render();
}
$("#bar").addEventListener("pointerup", endDrag);
$("#bar").addEventListener("pointercancel", endDrag);

// replay a recorded scenario from sim/recordings (same event vocabulary as core-car)
const RECORDINGS = ["boot-bt-music", "incoming-call", "reverse-while-navigating", "acc-off-on", "radio-steering-keys"];
function applyEvent(ev) {
  switch (ev.type) {
    case "BtConnection": state.bt = ev.connected; if (ev.connected && state.source === "none") state.source = "bt"; break;
    case "Playback": state.playing = ev.playing; if (ev.playing && state.source === "none") state.source = "bt"; break;
    case "Track": $("#np-title").textContent = ev.title; $("#np-artist").textContent = ev.artist || ""; break;
    case "Call": state.call = ["RINGING", "ACTIVE", "IDLE"].includes(ev.state) ? ev.state.toLowerCase() : "idle"; if (ev.name) $("#call-name").textContent = ev.name; if (ev.number) $("#call-number").textContent = ev.number; break;
    case "ReverseGear": state.camera = ev.on; break;
    // headlights on: the backlight drops even while the clock says day
    case "Illumination": state.autoBrightness = false; state.brightness = ev.on ? NIGHT_BRIGHTNESS : DAY_BRIGHTNESS; break;
    case "Radio": state.source = "radio"; $(".freq-num").textContent = (ev.khz / 1000).toFixed(1); $("#np-radio .np-title").textContent = ev.rds || ""; break;
    case "SteeringKey": if (ev.key === "MUTE") state.mute = !state.mute; break;
    case "Power": if (!ev.acc) { state.source = "none"; state.playing = false; } break;
  }
  render();
}
let replayTimers = [];
async function replay(name) {
  replayTimers.forEach(clearTimeout); replayTimers = [];
  try {
    const r = await fetch(`../../sim/recordings/${name}.jsonl`);
    if (!r.ok) throw new Error(String(r.status));
    const lines = (await r.text()).split("\n").filter(Boolean).map((l) => JSON.parse(l));
    lines.forEach((line) => replayTimers.push(setTimeout(() => applyEvent(line.event), line.t)));
  } catch (err) {
    const p = document.createElement("p");
    p.className = "state-note";
    p.textContent = "Could not load " + name + ": " + err.message;
    $("#recordings").appendChild(p);
  }
}
const list = $("#recordings");
RECORDINGS.forEach((name) => {
  const b = document.createElement("button");
  b.textContent = name;
  b.onclick = () => replay(name);
  list.appendChild(b);
});

// URL state, e.g. ?bare&theme=light&hour=20&source=radio&call=ringing&drawer&camera&brightness=40
const q = new URLSearchParams(location.search);
if (q.has("bare")) document.body.classList.add("bare");
if (q.get("theme")) state.theme = q.get("theme");
if (q.has("night")) state.theme = "dark";
if (q.has("drawer")) state.drawer = true;
if (q.has("camera")) state.camera = true;
if (q.get("source")) state.source = q.get("source");
if (q.get("call")) state.call = q.get("call");
if (q.get("hour") !== null) state.hour = Number(q.get("hour"));
if (q.get("clock") === "fixed") state.hour = 12;
if (q.get("brightness") !== null) { state.brightness = Number(q.get("brightness")); state.autoBrightness = false; }
render();
setInterval(render, 1000);
