// Browser emulator. All decisions go through the shared core-car bridge (Kotlin compiled to JavaScript);
// this file only draws the screen, binds the controls and talks to the bridge in JSON.

const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));
const screen = $("#screen");

// ----- bridge -----------------------------------------------------------------------------------
const bundle = window.rdstr || window["core-car"];
const K = (bundle && bundle.ro && bundle.ro.rdstr && bundle.ro.rdstr.car) || null;
if (!K) {
  const p = document.createElement("p");
  p.className = "state-note state-error";
  p.textContent = "Bridge missing: run ./gradlew :core-car:jsBrowserProductionWebpack";
  $(".state").prepend(p);
}
const bridge = {
  initial: () => JSON.parse(K.Bridge.initial()),
  apply: (state, event) => JSON.parse(K.Bridge.apply(JSON.stringify(state), JSON.stringify(event))),
  command: (state, cmd) => JSON.parse(K.Bridge.command(JSON.stringify(state), JSON.stringify(cmd))),
  echo: (cmd) => JSON.parse(K.Bridge.echo(JSON.stringify(cmd))),
  shellInitial: () => JSON.parse(K.Bridge.shellInitial()),
  shell: (state, action) => JSON.parse(K.Bridge.shell(JSON.stringify(state), JSON.stringify(action))),
  effectiveTheme: (state, hour) => K.Bridge.effectiveTheme(JSON.stringify(state), hour),
};

// ----- state ------------------------------------------------------------------------------------
const state = {
  car: K ? bridge.initial() : { source: "NONE", playing: false, muted: false, camera: false, call: "IDLE", btConnected: false, night: false },
  shell: K ? bridge.shellInitial() : { theme: "AUTO", drawer: false, face: "DIGITAL", font: "geist", brightness: 100 },
  // display data carried by events, not part of the rules state
  track: { title: "Night Transit", artist: "Vela Orsini", durationMs: 228000 },
  positionMs: 72000,
  radio: { khz: 101900, band: "FM", rds: "Radio Nord" },
  call: { name: "Ana Petrescu", number: "+40 7xx xxx xxx" },
  // preview only
  hour: null, // null = live clock, otherwise a simulated hour 0..23
};
const log = [];

function pad(n) { return String(n).padStart(2, "0"); }
function now() { return state.hour === null ? new Date() : new Date(2026, 9, 31, state.hour, 0); }
function addLog(dir, obj) {
  log.push({ t: new Date(), dir, text: JSON.stringify(obj) });
  if (log.length > 60) log.shift();
  renderLog();
}

// ----- car events and commands, all through the bridge -----------------------------------------
function carEvent(ev) {
  if (!K) return;
  if (ev.type === "Track") state.track = { title: ev.title, artist: ev.artist || "", durationMs: ev.durationMs || 0 };
  if (ev.type === "Playback" && ev.positionMs !== undefined) state.positionMs = ev.positionMs;
  if (ev.type === "Radio") state.radio = { khz: ev.khz, band: ev.band, rds: ev.rds || "" };
  if (ev.type === "Call" && ev.state === "RINGING") state.call = { name: ev.name || "", number: ev.number || "" };
  const step = bridge.apply(state.car, ev);
  state.car = step.state;
  addLog("in", ev);
  step.actions.forEach(runAction);
  render();
}

function runAction(a) {
  switch (a.type) {
    case "Send": carCommand(a.command); break;
    case "Night": shellAction({ type: "Headlights", on: a.on }); break;
    default: break; // ShowCamera, HideCamera, ShowCall, HideCall, SaveState, RestoreState: visible through the state
  }
}

function carCommand(cmd) {
  state.car = bridge.command(state.car, cmd);
  addLog("out", cmd);
  const echo = bridge.echo(cmd);
  if (echo) setTimeout(() => carEvent(echo), 120);
  render();
}

function shellAction(a) {
  state.shell = bridge.shell(state.shell, a);
  render();
}

// ----- digital face: each character is its own cell; a changed one counts in ------------------
let shownDigits = "";
function renderDigits(text) {
  const cells = $$("#clock .digit");
  text.split("").forEach((ch, i) => {
    const cell = cells[i];
    const current = cell.querySelector(".glyph:not(.out)");
    if (current && current.textContent === ch) return;
    if (current && shownDigits) {
      current.classList.remove("in");
      current.classList.add("out");
      const gone = () => current.remove();
      current.addEventListener("animationend", gone, { once: true });
      setTimeout(gone, 900);
    } else if (current) {
      current.remove();
    }
    const next = document.createElement("span");
    next.className = "glyph" + (shownDigits ? " in" : "");
    next.textContent = ch;
    next.addEventListener("animationend", () => next.classList.remove("in"), { once: true });
    cell.appendChild(next);
  });
  shownDigits = text;
}

// ----- analog face -------------------------------------------------------------------------------
function buildTicks() {
  const g = $("#ticks");
  if (g.childElementCount) return;
  const ns = "http://www.w3.org/2000/svg";
  for (let i = 0; i < 60; i++) {
    const a = (i / 60) * Math.PI * 2;
    const major = i % 5 === 0;
    const quarter = i % 15 === 0;
    const r1 = quarter ? 214 : major ? 212 : 216;
    const r2 = 226;
    const el = document.createElementNS(ns, quarter ? "circle" : "line");
    if (quarter) {
      el.setAttribute("cx", (240 + Math.sin(a) * 222).toFixed(2));
      el.setAttribute("cy", (240 - Math.cos(a) * 222).toFixed(2));
      el.setAttribute("r", "3.5");
    } else {
      el.setAttribute("x1", (240 + Math.sin(a) * r1).toFixed(2));
      el.setAttribute("y1", (240 - Math.cos(a) * r1).toFixed(2));
      el.setAttribute("x2", (240 + Math.sin(a) * r2).toFixed(2));
      el.setAttribute("y2", (240 - Math.cos(a) * r2).toFixed(2));
      if (major) el.setAttribute("class", "major");
    }
    g.appendChild(el);
  }
}
function renderHands(t) {
  const s = t.getSeconds();
  const m = t.getMinutes() + s / 60;
  const h = (t.getHours() % 12) + m / 60;
  $("#hand-second").style.transform = `rotate(${s * 6}deg)`;
  $("#hand-minute").style.transform = `rotate(${m * 6}deg)`;
  $("#hand-hour").style.transform = `rotate(${h * 30}deg)`;
}

let lastMinute = -1;
function renderClock() {
  const t = now();
  const analog = state.shell.face === "ANALOG";
  $("#digital").hidden = analog;
  $("#analog").hidden = !analog;
  if (analog) { buildTicks(); renderHands(t); }
  if (state.hour === null && t.getMinutes() === lastMinute) return;
  lastMinute = state.hour === null ? t.getMinutes() : -1;
  renderDigits(`${pad(t.getHours())}:${pad(t.getMinutes())}`);
  $("#clock").dateTime = t.toISOString();
  $("#dc-weekday").textContent = t.toLocaleDateString("en-GB", { weekday: "short" });
  $("#dc-day").textContent = String(t.getDate());
  $("#dc-month").textContent = t.toLocaleDateString("en-GB", { month: "long" });
}

// ----- theme and backlight -----------------------------------------------------------------------
const THEME_LABEL = { AUTO: "Auto", LIGHT: "Day", DARK: "Night" };
const THEME_ICON = { AUTO: "i-circle-half", LIGHT: "i-sun", DARK: "i-moon" };
let currentTheme = null;
let themingTimer = null;
function renderTheme() {
  const theme = K ? bridge.effectiveTheme(state.shell, now().getHours()) : "DARK";
  if (currentTheme !== null && theme !== currentTheme) {
    screen.classList.add("theming");
    clearTimeout(themingTimer);
    themingTimer = setTimeout(() => screen.classList.remove("theming"), 750);
  }
  currentTheme = theme;
  screen.classList.toggle("light", theme === "LIGHT");
  $("#bar-theme").className = `icon ${theme === "LIGHT" ? "i-sun" : "i-moon"}`;
  $("#bar-theme").setAttribute("aria-label", theme === "LIGHT" ? "Day theme" : "Night theme");
  $("#theme-icon").className = `icon ${THEME_ICON[state.shell.theme]}`;
  $("#theme-label").textContent = THEME_LABEL[state.shell.theme];
  $("#theme-toggle").setAttribute("aria-label", `Theme: ${THEME_LABEL[state.shell.theme]}, tap to change`);
  $("#theme-toggle").classList.toggle("is-on", state.shell.theme === "AUTO");
  $$("[data-theme]").forEach((b) => { const on = b.dataset.theme === state.shell.theme; b.classList.toggle("is-on", on); b.setAttribute("aria-pressed", String(on)); });
}
function renderBrightness() {
  const veil = Math.max(0, Math.min(0.78, (1 - state.shell.brightness / 100) * 0.85));
  $("#dimmer").style.opacity = veil.toFixed(3);
  $("#brightness").value = state.shell.brightness;
  $("#brightness").setAttribute("aria-valuetext", `${state.shell.brightness} percent`);
  $("#brightness-readout").textContent = `${state.shell.brightness}%`;
}

// ----- render ------------------------------------------------------------------------------------
function fmt(ms) { const s = Math.round(ms / 1000); return `${Math.floor(s / 60)}:${pad(s % 60)}`; }
function render() {
  const car = state.car, shell = state.shell;
  renderTheme();
  renderBrightness();
  screen.classList.toggle("font-geist", shell.font === "geist");
  $("#clock-toggle").setAttribute("aria-pressed", String(shell.face === "ANALOG"));

  // status bar
  const sourceText = { BT: "Bluetooth", RADIO: "Radio", NONE: "No source" }[car.source];
  $("#bar-source").textContent = car.source === "NONE" && car.btConnected ? "Bluetooth" : sourceText;
  $("#bar-bt").className = `icon ${car.source === "RADIO" ? "i-radio" : "i-bluetooth"}`;
  $("#bar-bt").classList.toggle("is-on", car.source !== "NONE");
  $("#bar-bt").classList.toggle("is-off", car.source !== "RADIO" && !car.btConnected);
  $("#bar-volume").className = `icon ${car.muted ? "i-speaker-slash is-off" : "i-speaker-high"}`;
  $("#bar-volume").setAttribute("aria-label", car.muted ? "Muted" : "Sound on");

  // panel
  $("#np-bt").hidden = car.source !== "BT";
  $("#np-radio").hidden = car.source !== "RADIO";
  $("#np-none").hidden = car.source !== "NONE";
  $("#np-title").textContent = state.track.title;
  $("#np-artist").textContent = state.track.artist;
  $("#np-pos").textContent = fmt(state.positionMs);
  $("#np-dur").textContent = fmt(state.track.durationMs);
  $("#np-progress").style.width = state.track.durationMs ? `${Math.min(100, (state.positionMs / state.track.durationMs) * 100)}%` : "0%";
  const play = $("#play");
  play.setAttribute("aria-label", car.playing ? "Pause" : "Play");
  play.firstElementChild.className = `icon ${car.playing ? "i-pause" : "i-play"}`;
  $(".freq-num").textContent = (state.radio.khz / 1000).toFixed(1);
  $(".freq-band").textContent = state.radio.band;
  $("#np-radio .np-title").textContent = state.radio.rds;

  // rail highlight follows the source
  $$(".rail-item").forEach((b) => {
    const on = (car.source === "BT" && b.dataset.fn === "music") || (car.source === "RADIO" && b.dataset.fn === "radio");
    b.classList.toggle("is-active", on);
    if (on) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
  });

  // drawer and overlays
  const drawer = $("#drawer");
  drawer.classList.toggle("is-open", shell.drawer);
  if (!drawer.classList.contains("is-dragging")) drawer.style.transform = "";
  drawer.inert = !shell.drawer;
  $("#handle").setAttribute("aria-expanded", String(shell.drawer));
  $("#scrim").classList.toggle("is-open", shell.drawer);
  $$(".toggle[data-toggle]").forEach((t) => {
    const on = t.dataset.toggle === "bt" ? car.btConnected : t.dataset.toggle === "mute" ? car.muted : true;
    t.classList.toggle("is-on", on); t.setAttribute("aria-pressed", String(on));
  });
  const covered = shell.drawer || car.call !== "IDLE" || car.camera;
  ["#bar", ".rail", ".field", "#panel"].forEach((sel) => { $(sel).inert = covered; });
  $("#camera").hidden = !car.camera;
  $("#call").hidden = car.call === "IDLE";
  $("#call-ringing").hidden = car.call !== "RINGING";
  $("#call-active").hidden = car.call !== "ACTIVE";
  $("#call-timer").hidden = car.call !== "ACTIVE";
  $("#call-number").hidden = car.call === "ACTIVE";
  $("#call-state").textContent = car.call === "RINGING" ? "Incoming call" : "On call";
  $("#call-name").textContent = state.call.name || state.call.number;
  $("#call-number").textContent = state.call.number;

  // state panel
  $$("[data-font]").forEach((b) => b.classList.toggle("is-on", b.dataset.font === shell.font));
  $$("[data-analog]").forEach((b) => b.classList.toggle("is-on", (b.dataset.analog === "1") === (shell.face === "ANALOG")));
  $$("[data-clock]").forEach((b) => b.classList.toggle("is-on", (b.dataset.clock === "live") === (state.hour === null)));
  $("[data-drawer]").classList.toggle("is-on", shell.drawer);
  $("#hour").value = state.hour === null ? new Date().getHours() : state.hour;
  $("#hour-readout").textContent = state.hour === null ? "live" : `${pad(state.hour)}:00`;
  $$(".seg button").forEach((b) => b.setAttribute("aria-pressed", String(b.classList.contains("is-on"))));
  if ($("#inspector-car")) {
    $("#inspector-car").textContent = JSON.stringify(car, null, 1);
    $("#inspector-shell").textContent = JSON.stringify(shell, null, 1);
  }
  renderClock();
}
function renderLog() {
  const el = $("#log");
  if (!el) return;
  el.innerHTML = "";
  log.slice().reverse().forEach((e) => {
    const row = document.createElement("div");
    row.className = `log-row log-${e.dir}`;
    row.textContent = `${pad(e.t.getHours())}:${pad(e.t.getMinutes())}:${pad(e.t.getSeconds())} ${e.dir === "in" ? "car >" : "< ui "} ${e.text}`;
    el.appendChild(row);
  });
}

// ----- controls: car ------------------------------------------------------------------------------
$$("[data-key]").forEach((b) => b.onclick = () => carEvent({ type: "SteeringKey", key: b.dataset.key }));
$$("[data-reverse]").forEach((b) => b.onclick = () => carEvent({ type: "ReverseGear", on: b.dataset.reverse === "1" }));
$$("[data-acc]").forEach((b) => b.onclick = () => carEvent({ type: "Power", acc: b.dataset.acc === "1" }));
$$("[data-lights]").forEach((b) => b.onclick = () => carEvent({ type: "Illumination", on: b.dataset.lights === "1" }));
$$("[data-btconn]").forEach((b) => b.onclick = () => carEvent({ type: "BtConnection", connected: b.dataset.btconn === "1", device: $("#bt-device").value || "Phone" }));
$$("[data-callev]").forEach((b) => b.onclick = () => carEvent({ type: "Call", state: b.dataset.callev, name: $("#call-name-in").value, number: $("#call-number-in").value }));
$("#radio-tune").onclick = () => carEvent({ type: "Radio", khz: Math.round(Number($("#radio-khz").value) * 1000), band: "FM", rds: $("#radio-rds").value, signal: 7 });
$("#track-send").onclick = () => carEvent({ type: "Track", title: $("#track-title").value, artist: $("#track-artist").value, album: "", durationMs: 228000 });
$$("[data-playev]").forEach((b) => b.onclick = () => carEvent({ type: "Playback", playing: b.dataset.playev === "1", positionMs: state.positionMs }));

// ----- controls: shell and preview ----------------------------------------------------------------
$$("[data-theme]").forEach((b) => b.onclick = () => shellAction({ type: "SetTheme", mode: b.dataset.theme }));
$("#theme-toggle").onclick = () => shellAction({ type: "CycleTheme" });
$$("[data-font]").forEach((b) => b.onclick = () => shellAction({ type: "SetFont", name: b.dataset.font }));
$$("[data-analog]").forEach((b) => b.onclick = () => shellAction({ type: "SetFace", face: b.dataset.analog === "1" ? "ANALOG" : "DIGITAL" }));
$("#clock-toggle").onclick = () => shellAction({ type: "ToggleFace" });
$("#brightness").oninput = (e) => shellAction({ type: "SetBrightness", value: Number(e.target.value) });
$$("[data-clock]").forEach((b) => b.onclick = () => { state.hour = b.dataset.clock === "live" ? null : 12; render(); });
$("#hour").oninput = (e) => { state.hour = Number(e.target.value); render(); };
$("[data-drawer]").onclick = () => shellAction({ type: state.shell.drawer ? "CloseDrawer" : "OpenDrawer" });
$("#handle").onclick = () => shellAction({ type: "OpenDrawer" });
$("#handle-close").onclick = () => shellAction({ type: "CloseDrawer" });
$("#scrim").onclick = () => shellAction({ type: "CloseDrawer" });
$$(".toggle[data-toggle]").forEach((t) => t.onclick = () => {
  if (t.dataset.toggle === "bt") carEvent({ type: "BtConnection", connected: !state.car.btConnected, device: "Phone" });
  if (t.dataset.toggle === "mute") carCommand({ type: "Audio.Mute", on: !state.car.muted });
});

// ----- controls: the screen itself, every tap is a car command ------------------------------------
$("#play").onclick = () => carCommand({ type: state.car.playing ? "Media.Pause" : "Media.Play" });
$$(".np .ctl[aria-label='Previous']").forEach((b) => b.onclick = () => carCommand({ type: "Media.Prev" }));
$$(".np .ctl[aria-label='Next']").forEach((b) => b.onclick = () => carCommand({ type: "Media.Next" }));
$$(".np .ctl[aria-label='Seek down']").forEach((b) => b.onclick = () => carCommand({ type: "Radio.Seek", up: false }));
$$(".np .ctl[aria-label='Seek up']").forEach((b) => b.onclick = () => carCommand({ type: "Radio.Seek", up: true }));
$$(".np .ctl[aria-label='Mute']").forEach((b) => b.onclick = () => carCommand({ type: "Audio.Mute", on: !state.car.muted }));
$$(".preset").forEach((b, i) => b.onclick = () => { $$(".preset").forEach((p) => p.classList.remove("is-active")); b.classList.add("is-active"); carCommand({ type: "Radio.Preset", index: i }); });
$$(".rail-item").forEach((b) => b.onclick = () => {
  if (b.dataset.fn === "music") carCommand({ type: "Audio.Source", source: "BT" });
  if (b.dataset.fn === "radio") carCommand({ type: "Audio.Source", source: "RADIO" });
  if (b.dataset.fn === "camera") carEvent({ type: "ReverseGear", on: !state.car.camera });
});
$$(".np-empty .ctl-wide").forEach((b) => b.onclick = () => carCommand({ type: "Audio.Source", source: b.textContent.trim() === "Radio" ? "RADIO" : "BT" }));
$("#call-ringing").children[0].onclick = () => carCommand({ type: "Phone.HangUp" });
$("#call-ringing").children[1].onclick = () => carCommand({ type: "Phone.Answer" });
$("#call-active").children[0].onclick = (e) => { const on = e.currentTarget.getAttribute("aria-pressed") !== "true"; e.currentTarget.setAttribute("aria-pressed", String(on)); carCommand({ type: "Phone.Mute", on }); };
$("#call-active").children[1].onclick = () => carCommand({ type: "Phone.HangUp" });
$("#camera-close").onclick = () => carEvent({ type: "ReverseGear", on: false });
$("[data-camera]").onclick = () => carEvent({ type: "ReverseGear", on: !state.car.camera });
window.addEventListener("keydown", (e) => { if (e.key === "Escape") { shellAction({ type: "CloseDrawer" }); if (state.car.camera) carEvent({ type: "ReverseGear", on: false }); } });

// pull the status bar down: the drawer follows the finger; the release rule lives in Shell
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
  shellAction({ type: "DrawerReleased", fraction: dy / drawerHeight() });
}
$("#bar").addEventListener("pointerup", endDrag);
$("#bar").addEventListener("pointercancel", endDrag);

// ----- scenarios: recorded journals replayed as car events ----------------------------------------
const RECORDINGS = ["boot-bt-music", "incoming-call", "reverse-while-navigating", "acc-off-on", "radio-steering-keys"];
let replayTimers = [];
function stopReplay() { replayTimers.forEach(clearTimeout); replayTimers = []; $("#replay-progress").style.width = "0"; }
async function replay(name) {
  stopReplay();
  try {
    const r = await fetch(`../sim/recordings/${name}.jsonl`);
    if (!r.ok) throw new Error(String(r.status));
    const lines = (await r.text()).split("\n").filter(Boolean).map((l) => JSON.parse(l));
    const total = lines[lines.length - 1].t || 1;
    lines.forEach((line) => replayTimers.push(setTimeout(() => { carEvent(line.event); $("#replay-progress").style.width = `${(line.t / total) * 100}%`; }, line.t)));
  } catch (err) {
    const p = document.createElement("p");
    p.className = "state-note state-error";
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
$("#replay-stop").onclick = stopReplay;

// ----- URL state for captures, e.g. ?bare&theme=light&hour=20&source=radio&call=ringing&drawer&camera&brightness=40&analog
const q = new URLSearchParams(location.search);
if (q.has("bare")) document.body.classList.add("bare");
if (K) {
  if (q.get("theme")) state.shell = bridge.shell(state.shell, { type: "SetTheme", mode: q.get("theme").toUpperCase() });
  if (q.has("night")) state.shell = bridge.shell(state.shell, { type: "SetTheme", mode: "DARK" });
  if (q.has("drawer")) state.shell = bridge.shell(state.shell, { type: "OpenDrawer" });
  if (q.has("analog")) state.shell = bridge.shell(state.shell, { type: "SetFace", face: "ANALOG" });
  if (q.get("font")) state.shell = bridge.shell(state.shell, { type: "SetFont", name: q.get("font") });
  if (q.get("brightness") !== null) state.shell = bridge.shell(state.shell, { type: "SetBrightness", value: Number(q.get("brightness")) });
  const source = (q.get("source") || "bt").toUpperCase();
  if (source === "BT") { state.car = bridge.apply(state.car, { type: "BtConnection", connected: true, device: "Phone" }).state; state.car = bridge.apply(state.car, { type: "Playback", playing: true, positionMs: 72000 }).state; }
  if (source === "RADIO") { state.car = bridge.command(state.car, { type: "Audio.Source", source: "RADIO" }); state.car = bridge.apply(state.car, { type: "Playback", playing: true }).state; }
  if (q.get("call") === "ringing") state.car = bridge.apply(state.car, { type: "Call", state: "RINGING", name: "Ana Petrescu", number: "+40 7xx xxx xxx" }).state;
  if (q.get("call") === "active") state.car = bridge.apply(bridge.apply(state.car, { type: "Call", state: "RINGING", name: "Ana Petrescu", number: "+40 7xx xxx xxx" }).state, { type: "Call", state: "ACTIVE" }).state;
  if (q.has("camera")) state.car = bridge.apply(state.car, { type: "ReverseGear", on: true }).state;
}
if (q.get("hour") !== null) state.hour = Number(q.get("hour"));
if (q.get("clock") === "fixed") state.hour = 12;
render();
setInterval(render, 1000);

// live reload for the design loop: poll the emulator files and reload when one changes, keeping the state in the URL
if (!q.has("bare") && location.protocol.startsWith("http")) {
  const files = ["index.html", "emulator.css", "tokens.css", "clock.css", "app.js", "../core-car/build/kotlin-webpack/js/productionExecutable/rdstr.js"];
  const stamps = {};
  async function stamp(f) {
    try { return (await fetch(f, { method: "HEAD", cache: "no-store" })).headers.get("last-modified"); } catch { return null; }
  }
  function stateUrl() {
    const p = new URLSearchParams();
    const sh = state.shell, car = state.car;
    if (sh.theme !== "AUTO") p.set("theme", sh.theme.toLowerCase());
    if (state.hour !== null) p.set("hour", String(state.hour));
    if (car.source !== "BT") p.set("source", car.source.toLowerCase());
    if (car.call !== "IDLE") p.set("call", car.call.toLowerCase());
    if (sh.drawer) p.set("drawer", "");
    if (car.camera) p.set("camera", "");
    if (sh.brightness !== 100) p.set("brightness", String(sh.brightness));
    if (sh.face === "ANALOG") p.set("analog", "");
    if (sh.font !== "geist") p.set("font", sh.font);
    const s = p.toString();
    return location.pathname + (s ? "?" + s : "");
  }
  (async () => {
    for (const f of files) stamps[f] = await stamp(f);
    setInterval(async () => {
      for (const f of files) {
        const s = await stamp(f);
        if (s && stamps[f] && s !== stamps[f]) { location.replace(stateUrl()); return; }
        if (s) stamps[f] = s;
      }
    }, 1000);
  })();
}
