// Emulator state outside React, so components and hot reload never lose the simulated car.

import { useEffect, useState, useSyncExternalStore } from "react";
import { bridge, type CarCommand, type CarEvent, type CarState, type RuleAction, type ShellAction, type ShellState, type PanelKey } from "./bridge";

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
  radio: { khz: number; band: string; rds: string };
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
}

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
const artFor = (title: string) => SAMPLE_TRACKS.find((t) => t.title === title)?.art ?? null;

/** Where playback is now: the last reported position, advanced while playing. */
export function elapsedMs(s: EmulatorState, now = Date.now()) {
  const moved = s.car.playing ? now - s.positionAt : 0;
  return Math.min(s.track.durationMs || Infinity, s.positionMs + moved);
}

let state: EmulatorState = fromUrl(new URLSearchParams(location.search));
const listeners = new Set<() => void>();
let logId = 0;
let replayTimers: ReturnType<typeof setTimeout>[] = [];

function set(patch: Partial<EmulatorState>) {
  state = { ...state, ...patch };
  listeners.forEach((listener) => listener());
}

function addLog(direction: LogEntry["direction"], value: unknown): LogEntry[] {
  return [...state.log, { id: logId++, time: new Date(), direction, text: JSON.stringify(value) }].slice(-LOG_SIZE);
}

function displayData(event: CarEvent): Partial<EmulatorState> {
  switch (event.type) {
    case "Track":
      return { track: { title: event.title, artist: event.artist ?? "", durationMs: event.durationMs ?? 0, art: artFor(event.title) } };
    case "Playback":
      // without a reported position, freeze or resume from where playback is now
      return { positionMs: event.positionMs ?? elapsedMs(state), positionAt: Date.now() };
    case "Radio":
      return { radio: { khz: event.khz, band: event.band, rds: event.rds ?? "" } };
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
  Home: { type: "Home" },
  Back: { type: "Back" },
};

function runAction(action: RuleAction) {
  // ShowCamera, ShowCall and the like are visible through the rules state
  if (action.type === "Send" && action.command) sendToCar(action.command);
  if (action.type === "Night") shellAction({ type: "Headlights", on: Boolean(action.on) });
  if (SHELL_ACTIONS[action.type]) shellAction(SHELL_ACTIONS[action.type]);
  if (action.type === "ShowVolume") openVolume();
}

/** What the bus does: the rules already accounted for the command, the car answers. */
function sendToCar(command: CarCommand) {
  set({ log: addLog("out", command) });
  const echo = bridge.echo(command);
  if (echo) setTimeout(() => carEvent(echo), ECHO_DELAY_MS);
  if (command.type === "Media.Next" || command.type === "Media.Prev") setTimeout(() => phoneSkips(command.type === "Media.Next"), ECHO_DELAY_MS);
  if (command.type === "Bt.Connect") tryConnect(command.device);
  if (command.type === "Bt.Scan") scan();
  if (command.type === "Bt.Pair") set({ pairing: { name: command.device, code: String(100000 + Math.floor(Math.random() * 900000)) } });
  if (command.type === "Bt.ConfirmPairing") confirmPairing(command.accept);
  if (command.type === "Bt.Disconnect" && state.car.btConnected) setTimeout(() => carEvent({ type: "BtConnection", connected: false, device: state.device }), ECHO_DELAY_MS);
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
    radio: { khz: 101900, band: "FM", rds: "Radio Nord" },
    call: CALLER,
    device: "Phone",
    saved: [{ name: "Phone", inRange: true }, { name: "Work phone", inRange: false }, { name: "Tablet", inRange: true }],
    connecting: null,
    failed: null,
    scanning: false,
    searched: false,
    nearby: [],
    pairing: null,
    acc: true,
    hour: q.get("clock") === "fixed" ? 12 : hour !== null ? Number(hour) : null,
    log: [],
    replay: null,
    replayError: null,
    volumeOpened: 0,
    pressedKey: null,
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
