// Emulator state outside React, so components and hot reload never lose the simulated car.

import { useEffect, useState, useSyncExternalStore } from "react";
import { createCoverDesk } from "./covers";
import { bridge, type Band, type CarCommand, type CarEvent, type CarState, type RuleAction, type ShellAction, type ShellState, type PanelKey } from "./bridge";
import { BANDS, STATIONS, stationAt } from "./radio";
import { GPS_PLACES } from "./gps";
export { GPS_PLACES };

export interface LogEntry {
  id: number;
  time: Date;
  direction: "in" | "out";
  text: string;
}

export interface EmulatorState {
  car: CarState;
  shell: ShellState;
  // display data carried by events, not part of the rules state
  track: { title: string; artist: string; durationMs: number; art: string | null };
  /** position at positionAt (ms since epoch); the phone only reports it on changes */
  positionMs: number;
  positionAt: number;
  /** what the tuner plays; `rds` and `pty` stay empty until the station's RDS arrives */
  radio: { khz: number; band: Band; rds: string; pty: number; stereo: boolean };
  /** the stations the last scan found, per band, by frequency */
  stations: Record<Band, number[]>;
  /** frequencies starred in our radio app */
  favorites: number[];
  /** a scan running, at this frequency now */
  radioScan: number | null;
  /** the car's position from GPS, its heading in degrees from north, its speed */
  gps: { lon: number; lat: number; heading: number; kmh: number };
  driving: boolean;
  /** the country under the car, by ISO code; empty outside every known border or before the borders load */
  country: string;
  /** offline map packs: on the unit, the one downloading, and the offers turned down this trip */
  maps: { installed: string[]; downloading: { code: string; progress: number } | null; declined: string[] };
  call: { name: string; number: string };
  /** the phone Bluetooth reports as connected */
  device: string;
  /** phones the unit has paired with, and whether each has Bluetooth on and in range right now */
  saved: { name: string; inRange: boolean }[];
  /** the saved phone a connection is being tried with, and the last one that did not answer */
  connecting: string | null;
  failed: string | null;
  /** a search for new phones: running, what it found, and the pairing waiting for the code check */
  scanning: boolean;
  searched: boolean;
  nearby: string[];
  pairing: { name: string; code: string; confirmed?: boolean } | null;
  /** the music playing on this laptop stands in for the phone: its track, cover, playback and controls */
  pcMedia: boolean;
  /** the unit's radios, switched from the drawer; both on at every start, as the unit comes up */
  btOn: boolean;
  wifiOn: boolean;
  wifi: WifiState;
  /** location services; on at every start, as navigation and the Wi-Fi scan need it */
  locationOn: boolean;
  /** Wi-Fi was asked to turn on while location is off: the screen asks to turn location on first */
  wifiNeedsLocation: boolean;
  acc: boolean;
  /** null = live clock, otherwise a simulated hour 0..23 */
  hour: number | null;
  log: LogEntry[];
  replay: { name: string; progress: number } | null;
  replayError: string | null;
  /** bumped whenever the volume bar should come up, even with nothing changed */
  volumeOpened: number;
  /** the frame key held down right now, for the edge swell beside it */
  pressedKey: PanelKey | null;
  /** the last time Bluetooth or Wi-Fi connected or dropped, for the notice at the bottom of the screen */
  notice: ConnectionNotice | null;
}

export interface ConnectionNotice { id: number; kind: "bt" | "wifi"; connected: boolean; name: string }

/** A Wi-Fi network around the unit; `password` is the simulation's own, shown in the control panel for testing. */
export interface WifiNetwork { ssid: string; secure: boolean; signal: 1 | 2 | 3; password?: string }
export interface WifiState {
  saved: string[];
  connected: string | null;
  connecting: string | null;
  /** the network whose last join failed on the password */
  wrongPassword: string | null;
  /** networks the last scan has found so far */
  seen: string[];
}
export const WIFI_NETWORKS: WifiNetwork[] = [
  { ssid: "Phone hotspot", secure: true, signal: 3, password: "roadster1" },
  { ssid: "Home", secure: true, signal: 2, password: "home2026" },
  { ssid: "Cafe Free Wi-Fi", secure: false, signal: 2 },
  { ssid: "Office 5G", secure: true, signal: 1, password: "office-5g" },
];
/** A scan answers network by network; a join takes this long either way. */
const WIFI_SEEN_MS = [250, 700, 1200, 1900];
const WIFI_JOIN_MS = 1400;

const LOG_SIZE = 60;
/** How long the unit tries a saved phone before it gives up; a phone in range answers sooner. */
const CONNECT_MS = 1600;
const CONNECT_FAIL_MS = 4000;
/** A Bluetooth search runs this long; phones answer it one by one. */
const SCAN_MS = 8000;
const NEARBY = [{ name: "Friend's phone", afterMs: 900 }, { name: "Pixel 7", afterMs: 2300 }, { name: "Galaxy A54", afterMs: 4100 }];
const PAIR_MS = 1200;
let scanTimers: ReturnType<typeof setTimeout>[] = [];
const ECHO_DELAY_MS = 120;
const CALLER = { name: "Ana Petrescu", number: "+40 7xx xxx xxx" };
/** Prev restarts the track after this much playback, as phones do; earlier it goes back one track. */
const RESTART_AFTER_MS = 3000;

// The phone's queue as the emulator plays it: synthetic titles and covers.
export const SAMPLE_TRACKS = [
  { title: "Night Transit", artist: "Vela Orsini", durationMs: 228000, art: "/art/night-transit.svg" },
  { title: "Amber Coast", artist: "Lio Marren", durationMs: 201000, art: "/art/amber-coast.svg" },
  { title: "Glass Garden", artist: "Odessa Vane", durationMs: 254000, art: "/art/glass-garden.svg" },
];
// the synthetic queue carries its own covers; any other track's cover comes from the cover desk, as on the unit
const sampleArt = (title: string) => SAMPLE_TRACKS.find((t) => t.title === title)?.art;
const covers = createCoverDesk((title, art) => {
  if (state.track.title === title) set({ track: { ...state.track, art } });
});

/** Where playback is now: the last reported position, advanced while playing. */
export function elapsedMs(s: EmulatorState, now = Date.now()) {
  const moved = s.car.playing ? now - s.positionAt : 0;
  return Math.min(s.track.durationMs || Infinity, s.positionMs + moved);
}

let state: EmulatorState = fromUrl(new URLSearchParams(location.search));
// ?pc starts with the laptop's music as the source
if (new URLSearchParams(location.search).has("pc")) queueMicrotask(() => setPcMedia(true));
const listeners = new Set<() => void>();
let logId = 0;
let replayTimers: ReturnType<typeof setTimeout>[] = [];

function set(patch: Partial<EmulatorState>) {
  const before = state;
  state = { ...state, ...patch };
  // every change goes through here, so a connection made by hand, by itself or by a scenario is noticed the same way
  const notice = connectionNotice(before, state);
  if (notice) state = { ...state, notice };
  listeners.forEach((listener) => listener());
}

let noticeId = 0;
function connectionNotice(a: EmulatorState, b: EmulatorState): ConnectionNotice | null {
  if (a.car.btConnected !== b.car.btConnected) return { id: ++noticeId, kind: "bt", connected: b.car.btConnected, name: b.device };
  const network = b.wifi.connected ?? a.wifi.connected;
  if (a.wifi.connected !== b.wifi.connected && network) return { id: ++noticeId, kind: "wifi", connected: b.wifi.connected !== null, name: network };
  return null;
}

function addLog(direction: LogEntry["direction"], value: unknown): LogEntry[] {
  return [...state.log, { id: logId++, time: new Date(), direction, text: JSON.stringify(value) }].slice(-LOG_SIZE);
}

function displayData(event: CarEvent): Partial<EmulatorState> {
  switch (event.type) {
    case "Track": {
      const art = sampleArt(event.title) ?? null;
      if (!art && event.title !== state.track.title) queueMicrotask(() => covers.track({ title: event.title, artist: event.artist ?? "" }));
      const keep = event.title === state.track.title ? state.track.art : art;
      return { track: { title: event.title, artist: event.artist ?? "", durationMs: event.durationMs ?? 0, art: keep } };
    }
    case "Playback":
      // without a reported position, freeze or resume from where playback is now
      return { positionMs: event.positionMs ?? elapsedMs(state), positionAt: Date.now() };
    case "Radio":
      return { radio: { khz: event.khz, band: event.band, rds: event.rds ?? "", pty: event.pty ?? 0, stereo: event.stereo ?? false } };
    case "Call":
      return event.state === "RINGING" ? { call: { name: event.name ?? "", number: event.number ?? "" } } : {};
    case "Power":
      return { acc: event.acc };
    case "BtConnection":
      if (!event.connected || !event.device) return {};
      // a phone that connects is a saved phone from then on
      return {
        device: event.device,
        saved: state.saved.some((d) => d.name === event.device) ? state.saved : [...state.saved, { name: event.device, inRange: true }],
      };
    default:
      return {};
  }
}

const SHELL_ACTIONS: Record<string, ShellAction> = {
  ToggleScreen: { type: "ToggleScreen" },
  Back: { type: "Back" },
};

function runAction(action: RuleAction) {
  // ShowCamera, ShowCall and the like are visible through the rules state
  if (action.type === "Send" && action.command) sendToCar(action.command);
  if (action.type === "Night") shellAction({ type: "Headlights", on: Boolean(action.on) });
  if (SHELL_ACTIONS[action.type]) shellAction(SHELL_ACTIONS[action.type]);
  // the frame key: the shell tells a single press from a double press by the time between them
  if (action.type === "Home") shellAction({ type: "HomePressed", atMs: Date.now() });
  if (action.type === "ShowVolume") openVolume();
}

// The laptop source: the emulator's server reads the media session Windows shows (a music site in the browser)
// and streams it here; the transport keys go back to that session, which then reports the change.
const PC_COMMANDS: Record<string, string> = { "Media.Play": "play", "Media.Pause": "pause", "Media.Next": "next", "Media.Prev": "prev" };
const PC_DEVICE = "Laptop";
let pcStream: EventSource | null = null;

function sendToLaptop(command: string) {
  fetch("/api/pc-media", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ command }) }).catch(() => {});
}

function fromLaptop(line: string) {
  const m = JSON.parse(line) as { type: string; active?: boolean; title?: string; artist?: string; album?: string; durationMs?: number; url?: string; playing?: boolean; positionMs?: number };
  if (m.type === "session") carEvent({ type: "BtConnection", connected: Boolean(m.active), device: PC_DEVICE });
  if (m.type === "track" && m.title) carEvent({ type: "Track", title: m.title, artist: m.artist ?? "", album: m.album ?? "", durationMs: m.durationMs ?? 0 });
  if (m.type === "cover" && m.title && m.url) covers.fromSource(m.title, m.artist ?? (state.track.title === m.title ? state.track.artist : ""), m.url);
  if (m.type === "playback") carEvent({ type: "Playback", playing: Boolean(m.playing), positionMs: m.positionMs ?? 0 });
}

/**
 * Bluetooth off drops the connected phone, as on the unit, and a Bluetooth source with it: home then offers the sources.
 * On again, nothing switches by itself; a saved phone reconnects on its own later.
 */
export function setBluetooth(on: boolean) {
  if (!on && state.car.btConnected) carEvent({ type: "BtConnection", connected: false, device: state.device });
  if (!on && state.car.source === "BT") carCommand({ type: "Audio.Source", source: "NONE" });
  set({ btOn: on });
}

/** Wi-Fi off drops the network and forgets the scan; on again, the strongest saved network joins by itself. */
export function setWifi(on: boolean) {
  // on this Android a Wi-Fi scan needs location on: ask for it first instead of a Wi-Fi that finds nothing
  if (on && !state.locationOn) return set({ wifiNeedsLocation: true });
  clearWifiTimers();
  set({ wifiOn: on, wifi: { ...state.wifi, connected: null, connecting: null, wrongPassword: null, seen: [] } });
  if (!on) return;
  const best = WIFI_NETWORKS.filter((n) => state.wifi.saved.includes(n.ssid)).sort((a, b) => b.signal - a.signal)[0];
  if (best) wifiConnect(best.ssid);
}

let wifiTimers: ReturnType<typeof setTimeout>[] = [];
function clearWifiTimers() {
  wifiTimers.forEach(clearTimeout);
  wifiTimers = [];
}
const patchWifi = (patch: Partial<WifiState>) => set({ wifi: { ...state.wifi, ...patch } });

/** A scan: the networks in range answer one by one; a new scan starts the list again. */
/** Location off keeps Wi-Fi joined but stops finding networks; the list empties. */
export function setLocation(on: boolean) {
  set({ locationOn: on });
  if (!on) {
    clearWifiTimers();
    patchWifi({ seen: [] });
  }
}

/** The answer to "Wi-Fi needs location": yes turns location on, then Wi-Fi; no leaves both as they were. */
export function answerWifiNeedsLocation(turnOn: boolean) {
  set({ wifiNeedsLocation: false });
  if (!turnOn) return;
  setLocation(true);
  setWifi(true);
}

export function wifiScan() {
  if (!state.wifiOn || !state.locationOn) return;
  patchWifi({ seen: [] });
  WIFI_NETWORKS.forEach((n, i) => wifiTimers.push(setTimeout(() => {
    if (state.wifiOn && !state.wifi.seen.includes(n.ssid)) patchWifi({ seen: [...state.wifi.seen, n.ssid] });
  }, WIFI_SEEN_MS[i] ?? 2000)));
}

/** Join a network: a saved or open one needs nothing; a new secured one needs its password, and a wrong one fails. */
export function wifiConnect(ssid: string, password?: string) {
  const network = WIFI_NETWORKS.find((n) => n.ssid === ssid);
  if (!network || !state.wifiOn) return;
  patchWifi({ connecting: ssid, wrongPassword: null });
  wifiTimers.push(setTimeout(() => {
    if (state.wifi.connecting !== ssid) return;
    const known = state.wifi.saved.includes(ssid) || !network.secure;
    if (!known && password !== network.password) return patchWifi({ connecting: null, wrongPassword: ssid });
    patchWifi({ connecting: null, connected: ssid, saved: state.wifi.saved.includes(ssid) ? state.wifi.saved : [...state.wifi.saved, ssid] });
  }, WIFI_JOIN_MS));
}

/** Forget a saved network; the connected one drops with it. */
export function wifiForget(ssid: string) {
  patchWifi({ saved: state.wifi.saved.filter((s) => s !== ssid), connected: state.wifi.connected === ssid ? null : state.wifi.connected });
}

/** Turn the laptop source on or off; off leaves the last track on screen as the phone would after a disconnect. */
export function setPcMedia(on: boolean) {
  pcStream?.close();
  pcStream = null;
  set({ pcMedia: on });
  if (!on) {
    if (state.car.btConnected && state.device === PC_DEVICE) carEvent({ type: "BtConnection", connected: false, device: PC_DEVICE });
    return;
  }
  pcStream = new EventSource("/api/pc-media");
  pcStream.onmessage = (e) => fromLaptop(e.data);
}

/** What the bus does: the rules already accounted for the command, the car answers. */
function sendToCar(command: CarCommand) {
  set({ log: addLog("out", command) });
  const echo = bridge.echo(command);
  if (echo) setTimeout(() => carEvent(echo), ECHO_DELAY_MS);
  // with the laptop as the source its session plays the tracks: play and pause still answer at once as a phone
  // would, and the session's own report confirms or corrects them a moment later
  if (state.pcMedia && command.type in PC_COMMANDS) return void sendToLaptop(PC_COMMANDS[command.type]);
  if (command.type === "Media.Next" || command.type === "Media.Prev") setTimeout(() => phoneSkips(command.type === "Media.Next"), ECHO_DELAY_MS);
  if (command.type === "Radio.Tune") tune(state.radio.band, command.khz);
  if (command.type === "Radio.Seek") seek(command.up);
  if (command.type === "Radio.Scan") scanBand();
  if (command.type === "Radio.Band") tune(command.band, state.stations[command.band][0] ?? BANDS[command.band].min);
  if (command.type === "Audio.Source" && command.source === "RADIO") tune(state.radio.band, state.radio.khz);
  if (command.type === "Bt.Connect") tryConnect(command.device);
  if (command.type === "Bt.Scan") scan();
  if (command.type === "Bt.Forget") forget(command.device);
  if (command.type === "Bt.Pair") set({ pairing: { name: command.device, code: String(100000 + Math.floor(Math.random() * 900000)) } });
  if (command.type === "Bt.ConfirmPairing") confirmPairing(command.accept);
  if (command.type === "Bt.Disconnect" && state.car.btConnected) setTimeout(() => carEvent({ type: "BtConnection", connected: false, device: state.device }), ECHO_DELAY_MS);
}

// The tuner as the vendor library drives it: a station's name and programme type arrive a moment after it is tuned;
// a seek or a scan moves through the band one step at a time, and the screen follows it
const RDS_MS = 450;
/** The screen follows a seek or a scan in ticks of a few steps, so it keeps its frame rate. */
const TICK_MS = 32;
const SEEK_STEPS_PER_TICK = 3;
const SCAN_STEPS_PER_TICK = 4;
let tunerTimers: ReturnType<typeof setTimeout>[] = [];
function stopTuner() {
  tunerTimers.forEach(clearTimeout);
  tunerTimers = [];
  if (state.radioScan !== null) set({ radioScan: null });
}

/** Passing a frequency on the way: no station data yet. */
const sweepTo = (band: Band, khz: number) => set({ radio: { khz, band, rds: "", pty: 0, stereo: false } });

function tune(band: Band, khz: number) {
  stopTuner();
  const { min, max } = BANDS[band];
  const at = Math.min(max, Math.max(min, khz));
  const station = stationAt(band, at);
  carEvent({ type: "Radio", khz: at, band, signal: station?.signal ?? 0, stereo: band === "FM" && Boolean(station) });
  if (station?.ps) tunerTimers.push(setTimeout(() => carEvent({ type: "Radio", khz: at, band, rds: station.ps, pty: station.pty, signal: station.signal, stereo: true }), RDS_MS));
}

/** The next station up or down the band, around the end; the frequency runs there step by step. */
function seek(up: boolean) {
  const { band, khz } = state.radio;
  const { min, max, step } = BANDS[band];
  const on = STATIONS.filter((s) => s.band === band).map((s) => s.khz).sort((a, b) => a - b);
  if (!on.length) return;
  const target = up ? on.find((f) => f > khz) ?? on[0] : [...on].reverse().find((f) => f < khz) ?? on[on.length - 1];
  stopTuner();
  const span = max - min + step;
  const steps = ((up ? target - khz : khz - target) + span) % span / step;
  const ticks = Math.ceil(steps / SEEK_STEPS_PER_TICK);
  for (let k = 1; k < ticks; k++) {
    const i = k * SEEK_STEPS_PER_TICK;
    const f = min + (((khz - min + (up ? i : -i) * step) % span) + span) % span;
    tunerTimers.push(setTimeout(() => sweepTo(band, f), k * TICK_MS));
  }
  tunerTimers.push(setTimeout(() => tune(band, target), ticks * TICK_MS));
}

/** A whole-band scan: the list empties, fills with each station as the sweep passes it, then the last station plays again. */
function scanBand() {
  const { band, khz: was } = state.radio;
  const { min, max, step } = BANDS[band];
  stopTuner();
  set({ stations: { ...state.stations, [band]: [] }, radioScan: min });
  const count = (max - min) / step;
  const ticks = Math.ceil(count / SCAN_STEPS_PER_TICK);
  const onAir = STATIONS.filter((s) => s.band === band).map((s) => s.khz).sort((a, b) => a - b);
  for (let k = 1; k <= ticks; k++) {
    const f = min + Math.min(count, k * SCAN_STEPS_PER_TICK) * step;
    tunerTimers.push(setTimeout(() => set({ radio: { khz: f, band, rds: "", pty: 0, stereo: false }, radioScan: f, stations: { ...state.stations, [band]: onAir.filter((s) => s <= f) } }), k * TICK_MS));
  }
  tunerTimers.push(setTimeout(() => {
    set({ radioScan: null });
    const found = state.stations[band];
    tune(band, found.includes(was) ? was : found[0] ?? was);
  }, (ticks + 1) * TICK_MS));
}

// ----- GPS and offline maps -----

const DRIVE_TICK_MS = 250;
const KM_PER_DEG = 111.32;
/** The simulated download rate, MB per second, while Wi-Fi is connected. */
const DOWNLOAD_MBPS = 30;
let driveTimer: ReturnType<typeof setInterval> | undefined;
let downloadTimer: ReturnType<typeof setInterval> | undefined;

/** The borders load once; from then on every position is placed in its country. */
let bordersReady = false;
fetch("/maps/countries.geojson").then((r) => r.text()).then((geojson) => {
  bridge.loadBorders(geojson);
  bordersReady = true;
  setPosition(state.gps);
}).catch(() => {});

export function setPosition(gps: Partial<EmulatorState["gps"]>) {
  const next = { ...state.gps, ...gps };
  set({ gps: next, country: bordersReady ? bridge.countryAt(next.lon, next.lat) : state.country });
}

/** Drive straight along the heading at the set speed. */
export function setDriving(on: boolean, kmh = state.gps.kmh || 90) {
  clearInterval(driveTimer);
  // the chosen speed stays set while parked, for the next drive
  set({ driving: on, gps: { ...state.gps, kmh } });
  if (!on) return;
  driveTimer = setInterval(() => {
    const { lon, lat, heading, kmh: speed } = state.gps;
    const km = (speed * DRIVE_TICK_MS) / 3_600_000;
    const rad = (heading * Math.PI) / 180;
    setPosition({ lat: lat + (km * Math.cos(rad)) / KM_PER_DEG, lon: lon + (km * Math.sin(rad)) / (KM_PER_DEG * Math.cos((lat * Math.PI) / 180)) });
  }, DRIVE_TICK_MS);
}

/** What the screen offers for the country under the car: its pack, or Wi-Fi first. */
export function mapOffer(s: EmulatorState) {
  return bridge.mapOffer(s.country, s.maps.installed, s.wifi.connected !== null, s.maps.declined, s.maps.downloading?.code ?? null);
}

/** A pack downloads only over Wi-Fi: it waits while the unit is off Wi-Fi and goes on when it joins again. */
export function downloadPack(code: string) {
  clearInterval(downloadTimer);
  set({ maps: { ...state.maps, downloading: { code, progress: 0 } } });
  const perTick = (DOWNLOAD_MBPS * DRIVE_TICK_MS) / 1000 / (bridge.packMb(code) || 100);
  downloadTimer = setInterval(() => {
    const d = state.maps.downloading;
    if (!d || state.wifi.connected === null) return;
    const progress = Math.min(1, d.progress + perTick);
    if (progress < 1) return set({ maps: { ...state.maps, downloading: { code, progress } } });
    clearInterval(downloadTimer);
    set({ maps: { ...state.maps, downloading: null, installed: [...state.maps.installed, code] } });
  }, DRIVE_TICK_MS);
}

export function declinePack(code: string) {
  set({ maps: { ...state.maps, declined: [...state.maps.declined, code] } });
}

/** Remove a pack from the unit, as from the maps settings; Romania can go too. */
export function removePack(code: string) {
  set({ maps: { ...state.maps, installed: state.maps.installed.filter((c) => c !== code), declined: state.maps.declined.filter((c) => c !== code) } });
}

/** Star or unstar a station in our radio app. */
export function toggleFavorite(khz: number) {
  set({ favorites: state.favorites.includes(khz) ? state.favorites.filter((f) => f !== khz) : [...state.favorites, khz].sort((a, b) => a - b) });
}

/** The unit's side of a connect request: a phone with Bluetooth on answers, one that is off times out. */
function tryConnect(name: string) {
  set({ connecting: name, failed: null });
  const reachable = state.saved.find((d) => d.name === name)?.inRange ?? false;
  setTimeout(() => {
    if (state.connecting !== name) return;
    if (!reachable) return set({ connecting: null, failed: name });
    if (state.car.btConnected && state.device !== name) carEvent({ type: "BtConnection", connected: false, device: state.device });
    set({ connecting: null });
    carEvent({ type: "BtConnection", connected: true, device: name });
  }, reachable ? CONNECT_MS : CONNECT_FAIL_MS);
}

/** The unit forgets a saved phone; it drops the connection first when that phone is the connected one. */
function forget(name: string) {
  if (state.car.btConnected && state.device === name) carEvent({ type: "BtConnection", connected: false, device: name });
  set({
    saved: state.saved.filter((d) => d.name !== name),
    connecting: state.connecting === name ? null : state.connecting,
    failed: state.failed === name ? null : state.failed,
  });
}

/** A search: phones with Bluetooth on and not yet saved answer one by one, then it stops. */
function scan() {
  scanTimers.forEach(clearTimeout);
  set({ scanning: true, searched: true, nearby: [] });
  scanTimers = [
    ...NEARBY.filter((n) => !state.saved.some((d) => d.name === n.name)).map((n) =>
      setTimeout(() => set({ nearby: [...state.nearby, n.name] }), n.afterMs)),
    setTimeout(() => set({ scanning: false }), SCAN_MS),
  ];
}

/** Both screens showed the same code; on yes the phone is saved and connected. */
function confirmPairing(accept: boolean) {
  const pairing = state.pairing;
  if (!pairing) return;
  if (!accept) return set({ pairing: null });
  set({ pairing: { ...pairing, confirmed: true } });
  setTimeout(() => {
    set({ pairing: null, nearby: state.nearby.filter((n) => n !== pairing.name), saved: [...state.saved, { name: pairing.name, inRange: true }] });
    tryConnect(pairing.name);
  }, PAIR_MS);
}

/** A saved phone's Bluetooth turned on or off; the connected one drops when it goes. */
export function setInRange(name: string, inRange: boolean) {
  set({ saved: state.saved.map((d) => (d.name === name ? { ...d, inRange } : d)) });
  if (!inRange && state.car.btConnected && state.device === name) carEvent({ type: "BtConnection", connected: false, device: name });
}

/** The phone's side of next and previous: another track from its queue, or the same one from the start. */
function phoneSkips(forward: boolean) {
  const index = SAMPLE_TRACKS.findIndex((t) => t.title === state.track.title);
  if (!forward && elapsedMs(state) > RESTART_AFTER_MS) {
    carEvent({ type: "Playback", playing: state.car.playing, positionMs: 0 });
    return;
  }
  const next = SAMPLE_TRACKS[(index + (forward ? 1 : -1) + SAMPLE_TRACKS.length) % SAMPLE_TRACKS.length];
  carEvent({ type: "Track", title: next.title, artist: next.artist, album: "", durationMs: next.durationMs });
  carEvent({ type: "Playback", playing: state.car.playing, positionMs: 0 });
}

export function carEvent(event: CarEvent) {
  const step = bridge.apply(state.car, event);
  set({ ...displayData(event), car: step.state, log: addLog("in", event) });
  step.actions.forEach(runAction);
  // the screen follows the ignition: dark with the car off, back on when it starts
  if (event.type === "Power") shellAction({ type: event.acc ? "Wake" : "Sleep" });
}

/** A command from our own UI: the rules remember it, then it goes to the car. */
export function carCommand(command: CarCommand) {
  set({ car: bridge.command(state.car, command) });
  sendToCar(command);
}

/** A level picked on screen; the shared rules turn it into steps and mute at zero. */
export function setVolume(level: number) {
  bridge.setVolume(state.car, level).forEach(carCommand);
}

/** One level up or down from wherever the volume is now; safe to call again from a held key. */
export function stepVolume(delta: number) {
  setVolume(state.car.volume + delta);
}

export function shellAction(action: ShellAction) {
  set({ shell: bridge.shell(state.shell, action) });
}

export function pressKey(key: PanelKey | null) {
  if (state.pressedKey !== key) set({ pressedKey: key });
}

export function openVolume() {
  set({ volumeOpened: state.volumeOpened + 1 });
}

export function setHour(hour: number | null) {
  set({ hour });
}

export function clearLog() {
  set({ log: [] });
}

export function stopReplay() {
  replayTimers.forEach(clearTimeout);
  replayTimers = [];
  set({ replay: null });
}

export async function replay(name: string) {
  stopReplay();
  set({ replayError: null });
  try {
    const response = await fetch(`/recordings/${name}.jsonl`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const lines: { t: number; event: CarEvent }[] = (await response.text())
      .split("\n")
      .filter(Boolean)
      .map((line) => JSON.parse(line));
    const total = lines[lines.length - 1].t || 1;
    set({ replay: { name, progress: 0 } });
    replayTimers = lines.map((line) =>
      setTimeout(() => {
        carEvent(line.event);
        set({ replay: line.t >= total ? null : { name, progress: line.t / total } });
      }, line.t),
    );
  } catch (error) {
    set({ replayError: `Could not load ${name}: ${(error as Error).message}. Run npm run sync in emulator/, then try again.` });
  }
}

/** URL state for captures, e.g. ?bare&theme=light&hour=20&source=radio&call=ringing&drawer&camera&brightness=40&analog&app=music&track=1 */
function fromUrl(q: URLSearchParams): EmulatorState {
  let shell = bridge.shellInitial();
  let car = bridge.initial();
  const applyEvent = (event: CarEvent) => { car = bridge.apply(car, event).state; };
  const theme = q.get("theme")?.toUpperCase();
  if (theme === "AUTO" || theme === "LIGHT" || theme === "DARK") shell = bridge.shell(shell, { type: "SetTheme", mode: theme });
  if (q.has("night")) shell = bridge.shell(shell, { type: "SetTheme", mode: "DARK" });
  if (q.has("drawer")) shell = bridge.shell(shell, { type: "OpenDrawer" });
  if (q.has("analog")) shell = bridge.shell(shell, { type: "SetFace", face: "ANALOG" });
  if (q.get("app") === "music") shell = bridge.shell(shell, { type: "OpenApp", app: "MUSIC" });
  if (q.get("app") === "radio") shell = bridge.shell(shell, { type: "OpenApp", app: "RADIO" });
  if (q.get("app") === "maps") shell = bridge.shell(shell, { type: "OpenApp", app: "MAPS" });
  if (q.has("devices")) shell = bridge.shell(shell, { type: "OpenDevices" });
  const font = q.get("font");
  if (font) shell = bridge.shell(shell, { type: "SetFont", name: font });
  const brightness = q.get("brightness");
  if (brightness !== null) shell = bridge.shell(shell, { type: "SetBrightness", value: Number(brightness) });

  const source = (q.get("source") ?? "bt").toUpperCase();
  if (source === "BT") {
    applyEvent({ type: "BtConnection", connected: true, device: "Phone" });
    applyEvent({ type: "Playback", playing: true, positionMs: 72000 });
  }
  if (source === "RADIO") {
    car = bridge.command(car, { type: "Audio.Source", source: "RADIO" });
    applyEvent({ type: "Playback", playing: true });
  }
  const call = q.get("call");
  if (call === "ringing" || call === "active") applyEvent({ type: "Call", state: "RINGING", ...CALLER });
  if (call === "active") applyEvent({ type: "Call", state: "ACTIVE" });
  if (q.has("camera")) applyEvent({ type: "ReverseGear", on: true });

  const hour = q.get("hour");
  return {
    car,
    shell,
    track: { ...(SAMPLE_TRACKS[Number(q.get("track"))] ?? SAMPLE_TRACKS[0]) },
    positionMs: 72000,
    positionAt: Date.now(),
    radio: { khz: 101900, band: "FM", rds: "NORD FM", pty: 9, stereo: true },
    stations: { FM: STATIONS.filter((s) => s.band === "FM").map((s) => s.khz), AM: STATIONS.filter((s) => s.band === "AM").map((s) => s.khz) },
    favorites: [89200, 91400, 101900],
    radioScan: null,
    gps: GPS_PLACES[0],
    driving: false,
    country: "",
    maps: { installed: ["RO"], downloading: null, declined: [] },
    call: CALLER,
    device: "Phone",
    saved: [{ name: "Phone", inRange: true }, { name: "Work phone", inRange: false }, { name: "Tablet", inRange: true }],
    connecting: null,
    failed: null,
    scanning: false,
    searched: false,
    nearby: [],
    pairing: null,
    pcMedia: false,
    btOn: true,
    wifiOn: true,
    wifi: { saved: [WIFI_NETWORKS[0].ssid], connected: WIFI_NETWORKS[0].ssid, connecting: null, wrongPassword: null, seen: [] },
    locationOn: true,
    wifiNeedsLocation: false,
    acc: true,
    hour: q.get("clock") === "fixed" ? 12 : hour !== null ? Number(hour) : null,
    log: [],
    replay: null,
    replayError: null,
    volumeOpened: 0,
    pressedKey: null,
    notice: null,
  };
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function useEmulator() {
  return useSyncExternalStore(subscribe, () => state);
}

/** The time the shell shows: live, or a fixed moment when an hour is simulated. */
export function useNow(hour: number | null) {
  const [live, setLive] = useState(() => new Date());
  useEffect(() => {
    const timer = setInterval(() => setLive(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);
  return hour === null ? live : new Date(2026, 9, 31, hour, 0);
}
