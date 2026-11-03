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
  track: { title: string; artist: string; durationMs: number };
  positionMs: number;
  radio: { khz: number; band: string; rds: string };
  call: { name: string; number: string };
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
const ECHO_DELAY_MS = 120;
const CALLER = { name: "Ana Petrescu", number: "+40 7xx xxx xxx" };

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
      return { track: { title: event.title, artist: event.artist ?? "", durationMs: event.durationMs ?? 0 } };
    case "Playback":
      return event.positionMs === undefined ? {} : { positionMs: event.positionMs };
    case "Radio":
      return { radio: { khz: event.khz, band: event.band, rds: event.rds ?? "" } };
    case "Call":
      return event.state === "RINGING" ? { call: { name: event.name ?? "", number: event.number ?? "" } } : {};
    case "Power":
      return { acc: event.acc };
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

/** URL state for captures, e.g. ?bare&theme=light&hour=20&source=radio&call=ringing&drawer&camera&brightness=40&analog */
function fromUrl(q: URLSearchParams): EmulatorState {
  let shell = bridge.shellInitial();
  let car = bridge.initial();
  const applyEvent = (event: CarEvent) => { car = bridge.apply(car, event).state; };
  const theme = q.get("theme")?.toUpperCase();
  if (theme === "AUTO" || theme === "LIGHT" || theme === "DARK") shell = bridge.shell(shell, { type: "SetTheme", mode: theme });
  if (q.has("night")) shell = bridge.shell(shell, { type: "SetTheme", mode: "DARK" });
  if (q.has("drawer")) shell = bridge.shell(shell, { type: "OpenDrawer" });
  if (q.has("analog")) shell = bridge.shell(shell, { type: "SetFace", face: "ANALOG" });
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
    track: { title: "Night Transit", artist: "Vela Orsini", durationMs: 228000 },
    positionMs: 72000,
    radio: { khz: 101900, band: "FM", rds: "Radio Nord" },
    call: CALLER,
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
