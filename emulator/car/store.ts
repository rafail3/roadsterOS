// Emulator state outside React, so components and hot reload never lose the simulated car.

import { useEffect, useState, useSyncExternalStore } from "react";
import { createCoverDesk } from "./covers";
import { bridge, HFP, type Band, type LaunchableApp, type SoundSettings, type CameraSettings, type TimeSource, type CallRecord, type CarCommand, type CarEvent, type CarState, type Contact, type HfpCall, type Message, type OtherCall, type RuleAction, type ShellAction, type ShellState, type PanelKey } from "./bridge";
import { MAJOR_COMPUTER, MAJOR_PHONE, SAMPLE_CONTACTS, SAMPLE_PHONE, sampleCalls } from "./phone";
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
  /** a test drive whose speed climbs from 0 to SWEEP_TOP and back, over and over */
  sweep: boolean;
  /** the speed the map shows, worked out from the GPS fixes as on the unit */
  speed: { kmh: number; stopped: boolean };
  /** the country under the car, by ISO code; empty outside every known border or before the borders load */
  country: string;
  /** offline map packs: on the unit, the one downloading, and the offers turned down this trip */
  maps: { installed: string[]; downloading: { code: string; progress: number } | null; declined: string[] };
  /** the call on screen: who, since when it is answered (null before), where it is heard, the car's microphone */
  call: { name: string; number: string; label: string; incoming: boolean; since: number | null; inCar: boolean; micMuted: boolean; held: boolean; conference: boolean; other: (OtherCall & { name: string }) | null };
  /** the phone's voice assistant listens through the car's microphone */
  voiceAssistant: boolean;
  /** text messages since the phone connected, the ids read, the thread the phone app should open, the last one for the banner */
  messages: Message[];
  read: string[];
  thread: string | null;
  newMessage: Message | null;
  /** the unit's Bluetooth stack carries text messages (MAP client on); many units have it off */
  messagesSupported: boolean;
  /** other apps installed on the unit, as its launcher lists them */
  installed: LaunchableApp[];
  /** car events written to the unit's journal while recording is on */
  recorded: number;
  /** the phone book and history the connected phone shared (PBAP) */
  contacts: Contact[];
  calls: CallRecord[];
  /** what the phone reports over the hands-free link: battery and signal 0..5, the network */
  phoneStatus: { battery: number; signal: number; operator: string; roaming: boolean };
  /** the steering angle as the CAN box reports it, positive to the left, -1080..1080; only the camera's lines read it */
  eps: number;
  /** the phone whose screen is shown, while one is */
  castDevice: string;
  /** Bluetooth switching on, asked by the phone app */
  btTurningOn: boolean;
  /** the phone Bluetooth reports as connected */
  device: string;
  /** phones the unit has paired with, and whether each has Bluetooth on and in range right now */
  saved: { name: string; inRange: boolean; major: number }[];
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
  /** a newer version published on GitHub (simulated), or null when the unit runs the latest */
  releaseOut: string | null;
  /** the "restart the unit?" question is on screen */
  restartAsked: boolean;
  /** Settings > About > Software Update: the last check and what it found */
  updateCheck: { state: "IDLE" | "CHECKING" | "LATEST" | "AVAILABLE" | "FAILED"; at: number | null };
  /** Wi-Fi was asked to turn on while location is off: the screen asks to turn location on first */
  wifiNeedsLocation: boolean;
  acc: boolean;
  /** null = live clock, otherwise a simulated hour 0..23 */
  hour: number | null;
  /** the unit's time zone, and how far the clock was set by hand from the real time */
  timeZone: string;
  clockShiftMs: number;
  /** the car's dash display: how far its clock is from the browser's, and whether it shows the consumption */
  dash: { offsetMin: number; fuel: boolean };
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
/** Switching Bluetooth on takes the unit about this long. */
const BT_ON_MS = 900;
/** The other side picks up an outgoing call after this long. */
const ANSWER_MS = 3200;
/** The phone's own call list, as it would report it over HFP; the screen's call comes from it through core-car. */
let simCalls: HfpCall[] = [];
let simCallId = 0;
/** When each line of the current calls was answered, so a call swapped back keeps its own clock. */
const callStarts = new Map<string, number>();
/** Commands the simulated phone answers itself, through its call list, instead of the plain echo. */
const PHONE_CALLS = new Set(["Phone.Dial", "Phone.Redial", "Phone.Answer", "Phone.HangUp", "Phone.Hold", "Phone.Merge", "Phone.AnswerWaiting", "Phone.DeclineWaiting"]);
const PAIR_MS = 1200;
let scanTimers: ReturnType<typeof setTimeout>[] = [];
const ECHO_DELAY_MS = 120;
/** The caller of ?call=ringing: a number from the phone book; the phone sends no name, as over HFP. */
const CALLER_NUMBER = "+40721000101";
const NO_CALL = { name: "", number: "", label: "", incoming: false, since: null, inCar: true, micMuted: false, held: false, conference: false, other: null };
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

/** Where playback is now: the last reported position, advanced while playing (core-car PlaybackClock). */
export function elapsedMs(s: EmulatorState, now = Date.now()) {
  return bridge.elapsedMs(s.positionMs, s.positionAt, s.car.playing, s.track.durationMs, now);
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
    case "Call": {
      if (event.state === "IDLE") { callStarts.clear(); return state.car.call !== "IDLE" ? { calls: [finishedCall(state), ...state.calls] } : {}; }
      // calls are named from the phone book, as the unit does; a call taken over from the second line starts its own clock
      const prev = state.call;
      const number = event.number ?? "";
      const match = bridge.phoneFind(state.contacts, number);
      const same = state.car.call !== "IDLE" && number === prev.number;
      const other = event.other ? { ...event.other, name: event.other.name || bridge.phoneFind(state.contacts, event.other.number)?.name || "" } : null;
      if (event.state === "ACTIVE" && !callStarts.has(number)) callStarts.set(number, same && prev.since !== null ? prev.since : Date.now());
      const since = callStarts.get(number) ?? null;
      return {
        call: {
          ...(same ? prev : NO_CALL), number, name: event.name || match?.name || "", label: match?.label ?? "",
          incoming: same ? prev.incoming : event.state === "RINGING" || prev.other?.number === number,
          since, held: Boolean(event.held), conference: Boolean(event.conference), other,
          inCar: state.car.call === "IDLE" ? true : prev.inCar, micMuted: state.car.call === "IDLE" ? false : prev.micMuted,
        },
      };
    }
    case "VoiceAssistant":
      return { voiceAssistant: event.active };
    case "MessageSupport":
      return { messagesSupported: event.available };
    case "Message": {
      const { type: _type, ...message } = event;
      return { messages: [...state.messages, message], newMessage: message.sent ? state.newMessage : message, read: message.sent ? [...state.read, message.id] : state.read };
    }
    case "CallAudio":
      return { call: { ...state.call, inCar: event.inCar } };
    case "PhoneStatus":
      return { phoneStatus: { battery: event.battery ?? state.phoneStatus.battery, signal: event.signal ?? state.phoneStatus.signal, operator: event.operator ?? state.phoneStatus.operator, roaming: event.roaming ?? state.phoneStatus.roaming } };
    case "Power":
      return { acc: event.acc };
    case "BtConnection":
      if (!event.connected || !event.device) return {};
      // a phone that connects is a saved phone from then on
      return {
        device: event.device,
        saved: state.saved.some((d) => d.name === event.device) ? state.saved : [...state.saved, { name: event.device, inRange: true, major: MAJOR_PHONE }],
      };
    default:
      return {};
  }
}

/** The simulated phone reports its call list changed; the unit folds it with core-car Hfp.call. */
function publishCalls() {
  carEvent(bridge.hfpCall(simCalls));
}

const callIn = (...states: number[]) => simCalls.find((c) => states.includes(c.state));
const setState = (c: HfpCall | undefined, s: number) => { if (c) c.state = s; };
const drop = (c: HfpCall | undefined) => { simCalls = simCalls.filter((x) => x !== c); };

/** The phone's side of each call command, as AT+CHLD and the rest behave on a real phone. */
function phoneCalls(command: CarCommand) {
  const active = callIn(HFP.ACTIVE), held = callIn(HFP.HELD), ringing = callIn(HFP.INCOMING, HFP.WAITING);
  switch (command.type) {
    case "Phone.Dial":
    case "Phone.Redial": {
      // the phone's own last number; the emulator's is the last outgoing call in its history
      const number = command.type === "Phone.Dial" ? command.number : state.calls.find((c) => c.type === "OUTGOING")?.number;
      if (!number) return;
      setState(active, HFP.HELD);
      const call: HfpCall = { id: ++simCallId, state: HFP.ALERTING, number };
      simCalls = [...simCalls, call];
      setTimeout(() => { if (simCalls.includes(call) && call.state === HFP.ALERTING) { call.state = HFP.ACTIVE; publishCalls(); } }, ANSWER_MS);
      break;
    }
    case "Phone.Answer": setState(ringing, HFP.ACTIVE); break;
    case "Phone.HangUp":
      if (simCalls.length === 1 && ringing) drop(ringing);
      // a conference ends whole
      else if (active?.multiparty) simCalls = simCalls.filter((c) => !c.multiparty);
      else drop(callIn(HFP.ALERTING, HFP.DIALING) ?? active ?? held);
      break;
    case "Phone.Hold":
      if (active && !held) setState(active, HFP.HELD);
      else { setState(active, HFP.HELD); setState(held, HFP.ACTIVE); }
      break;
    case "Phone.Merge":
      simCalls.forEach((c) => { if (c.state === HFP.ACTIVE || c.state === HFP.HELD) { c.state = HFP.ACTIVE; c.multiparty = true; } });
      break;
    case "Phone.AnswerWaiting":
      if (command.keepCurrent) setState(active, HFP.HELD); else drop(active);
      setState(ringing, HFP.ACTIVE);
      break;
    case "Phone.DeclineWaiting": drop(ringing); break;
  }
  publishCalls();
}

/** The history line a call leaves on the phone: picked up or placed, else missed. */
function finishedCall(s: EmulatorState): CallRecord {
  const type = !s.call.incoming ? "OUTGOING" : s.call.since !== null ? "INCOMING" : "MISSED";
  return { number: s.call.number, name: "", type, atMs: Date.now() };
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
  if (action.type === "OpenApp" && action.app) shellAction({ type: "OpenApp", app: action.app });
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
 * On again, the last phone is asked for with Connect to Last Phone on; else the driver picks one from the list.
 */
export function setBluetooth(on: boolean) {
  // the phone it drops is the last one connected, asked for again when Bluetooth comes back on
  if (!on && state.car.btConnected) { lastPhone = state.device; carEvent({ type: "BtConnection", connected: false, device: state.device }); }
  if (!on && state.car.source === "BT") carCommand({ type: "Audio.Source", source: "NONE" });
  const was = state.btOn;
  set({ btOn: on });
  if (on && !was) connectLastPhone();
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
  if (PHONE_CALLS.has(command.type)) return void setTimeout(() => phoneCalls(command), ECHO_DELAY_MS);
  if (command.type === "Dash.Key") return pressDash(command.key);
  if (command.type === "Dsp") return;
  const echo = bridge.echo(command);
  if (echo) setTimeout(() => carEvent(echo), ECHO_DELAY_MS);
  if (command.type === "Phone.SendMessage") setTimeout(() => carEvent({ type: "Message", id: `sent-${Date.now()}`, number: command.number, text: command.text, atMs: Date.now(), sent: true }), ECHO_DELAY_MS);
  // with the laptop as the source its session plays the tracks: play and pause still answer at once as a phone
  // would, and the session's own report confirms or corrects them a moment later
  if (state.pcMedia && command.type in PC_COMMANDS) return void sendToLaptop(PC_COMMANDS[command.type]);
  if (command.type === "Media.Next" || command.type === "Media.Prev") setTimeout(() => phoneSkips(command.type === "Media.Next"), ECHO_DELAY_MS);
  if (command.type === "Radio.Tune") tune(state.radio.band, command.khz);
  if (command.type === "Radio.Seek") seek(command.up);
  if (command.type === "Radio.Scan") scanBand();
  if (command.type === "Radio.Band") tune(command.band, state.stations[command.band][0] ?? BANDS[command.band].min);
  if (command.type === "Audio.Source" && command.source === "RADIO") tune(state.radio.band, state.radio.khz);
  if (command.type === "Bt.Connect") { if (connecting) clearTimeout(connecting); connecting = null; tryConnect(command.device); }
  if (command.type === "Bt.Scan") scan();
  if (command.type === "Bt.Forget") forget(command.device);
  if (command.type === "Bt.Pair") set({ pairing: { name: command.device, code: String(100000 + Math.floor(Math.random() * 900000)) } });
  if (command.type === "Bt.ConfirmPairing") confirmPairing(command.accept);
  if (command.type === "Phone.Mute") set({ call: { ...state.call, micMuted: command.on } });
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
  // each position is a fix of the simulated chip, which reports its speed as a real one does: the set speed while
  // driving, nothing while parked
  const speed = bridge.speed(next.lon, next.lat, performance.now(), state.driving ? next.kmh : 0);
  set({ gps: next, speed, country: bordersReady ? bridge.countryAt(next.lon, next.lat) : state.country });
}

/** The speed test drive: up from 0 at SWEEP_KMH_PER_S, a pause at the top, down again, a pause standing. */
const SWEEP_TOP = 130;
const SWEEP_KMH_PER_S = 10;
const SWEEP_PAUSE_S = 2;
let sweepStart = 0;

function sweepKmh(seconds: number) {
  const climb = SWEEP_TOP / SWEEP_KMH_PER_S;
  const t = seconds % (2 * (climb + SWEEP_PAUSE_S));
  if (t < climb) return t * SWEEP_KMH_PER_S;
  if (t < climb + SWEEP_PAUSE_S) return SWEEP_TOP;
  if (t < 2 * climb + SWEEP_PAUSE_S) return SWEEP_TOP - (t - climb - SWEEP_PAUSE_S) * SWEEP_KMH_PER_S;
  return 0;
}

/** Drive straight on while the speed sweeps up and down, to watch the speed dial; off parks the car. */
export function setSpeedSweep(on: boolean) {
  sweepStart = performance.now();
  set({ sweep: on });
  setDriving(on, on ? 0 : state.gps.kmh);
}

/** Drive straight along the heading at the set speed. */
export function setDriving(on: boolean, kmh = state.gps.kmh || 90) {
  clearInterval(driveTimer);
  // the chosen speed stays set while parked, for the next drive
  set({ driving: on, sweep: on && state.sweep, gps: { ...state.gps, kmh } });
  if (!on) return setPosition({});
  driveTimer = setInterval(() => {
    if (state.sweep) set({ gps: { ...state.gps, kmh: Math.round(sweepKmh((performance.now() - sweepStart) / 1000)) } });
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

/** The setup from its first screen, as after a factory reset but keeping every setting; the unit starts again. */
export function restartSetup() {
  shellAction({ type: "RestartSetup" });
  window.location.reload();
}

/** A newer version on GitHub, or none (the control panel's switch). */
export function setReleaseOut(version: string | null) {
  set({ releaseOut: version });
}

/** Remove a pack from the unit, as from the maps settings; Romania can go too. */
export function removePack(code: string) {
  set({ maps: { ...state.maps, installed: state.maps.installed.filter((c) => c !== code), declined: state.maps.declined.filter((c) => c !== code) } });
}

/** Star or unstar a station in our radio app (core-car Tuner.toggleFavorite). */
export function toggleFavorite(khz: number) {
  set({ favorites: bridge.toggleFavorite(state.favorites, khz) });
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
    set({ pairing: null, nearby: state.nearby.filter((n) => n !== pairing.name), saved: [...state.saved, { name: pairing.name, inRange: true, major: MAJOR_PHONE }] });
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

let lastActivity = Date.now();

/** A touch on the screen or a key: the always-on display ends and its wait starts again. */
export function userActivity() {
  lastActivity = Date.now();
  if (state.shell.aod) shellAction({ type: "ExitAod" });
}

let aodTimer: ReturnType<typeof setInterval> | null = null;
/** Checks the wait of the always-on display once a second; a call, the camera or the map keep the screen as it is. */
export function watchIdle() {
  if (aodTimer || document.documentElement.classList.contains("freeze")) return;
  aodTimer = setInterval(() => {
    const busy = state.car.call !== "IDLE" || state.car.camera || state.shell.cameraOpen || state.shell.app === "MAPS";
    if (bridge.aodDue(state.shell, Date.now() - lastActivity, busy)) shellAction({ type: "EnterAod" });
  }, 1000);
}

/** The phone connected last; the unit asks it first when the car starts. */
let lastPhone: string | null = null;
let connecting: ReturnType<typeof setTimeout> | null = null;

/**
 * A few seconds after the car starts, or after Bluetooth is switched on, with no phone connected by then, the last phone
 * is asked for, with Connect to Last Phone on (core-car Startup; CarRuntime.connectLastPhone on the unit).
 */
function connectLastPhone() {
  if (connecting) clearTimeout(connecting);
  connecting = setTimeout(() => {
    connecting = null;
    const phone = bridge.reconnectPhone(state.shell.btAutoConnect, state.saved.map((d) => d.name), lastPhone);
    // a phone the driver is connecting by hand meanwhile wins: the last phone is asked only when nothing is going on
    if (phone && state.btOn && state.acc && !state.car.btConnected && !state.connecting) tryConnect(phone);
  }, bridge.startupConnectWaitMs());
}

export function carEvent(event: CarEvent) {
  if (bridge.wakesAod(event)) userActivity();
  const wasLive = state.car.cast === "LIVE";
  if (event.type === "BtConnection" && event.connected && event.device) lastPhone = event.device;
  // the car switched off: the unit sleeps and the phone's link drops with it, as on the unit
  if (event.type === "Power" && !event.acc) {
    if (connecting) clearTimeout(connecting);
    if (state.car.btConnected) {
      lastPhone = state.device;
      carEvent({ type: "BtConnection", connected: false, device: state.device });
    }
  }
  // the car starts: the call level back to its default, as each start of the unit does (core-car Sound.atStart)
  if (event.type === "Power" && event.acc) queueMicrotask(() => setSound({ ...state.shell.dsp, callVolume: CALL_VOLUME_DEFAULT }));
  const step = bridge.apply(state.car, event);
  set({ ...displayData(event), car: step.state, log: addLog("in", event), recorded: counted() });
  step.actions.forEach(runAction);
  // a phone brings its hands-free link up and down with it; a tablet or the laptop has none
  if (event.type === "BtConnection") {
    const major = state.saved.find((d) => d.name === event.device)?.major ?? MAJOR_COMPUTER;
    const calls = event.connected && bridge.callCapable(major);
    if (calls !== state.car.hfpConnected) carEvent({ type: "HfpConnection", connected: calls, device: event.device });
  }
  // a new call fills the screen, an ended one goes; Home folds it into its bar (core-car Shell)
  if (event.type === "Call") shellAction({ type: "CallChanged", state: event.state });
  // reverse gear takes the screen from a camera opened by hand, and closes it when it comes out
  if (event.type === "ReverseGear") shellAction({ type: "CloseCamera" });
  // the phone's screen opens over the shell when its picture plays, and leaves when the phone stops
  if (event.type === "PhoneCast") {
    set({ castDevice: event.state === "OFF" || event.state === "VISIBLE" ? "" : event.device ?? state.castDevice });
    if (wasLive !== (event.state === "LIVE")) shellAction({ type: "CastChanged", state: event.state });
  }
  if (event.type === "Power") castAvailability();
  // the screen follows the ignition: dark with the car off, back on when it starts
  if (event.type === "Power") shellAction({ type: event.acc ? "Wake" : "Sleep" });
  // a loud last level comes down to the start volume chosen in the settings (core-car Settings.startVolume)
  if (event.type === "Power" && event.acc) bridge.startVolume(state.car, state.shell.startVolume).forEach(carCommand);
  if (event.type === "Power" && event.acc) connectLastPhone();
}

/** A command from our own UI: the rules remember it, then it goes to the car. */
export function carCommand(command: CarCommand) {
  set({ car: bridge.command(state.car, command), recorded: counted() });
  sendToCar(command);
}

/** Opening the phone app: Bluetooth on first when it is off, since calls need a connected phone (core-car PhoneGate). */
export function openPhone() {
  shellAction({ type: "OpenApp", app: "PHONE" });
  if (!state.btOn) turnOnBluetooth();
}

/** Bluetooth on, after the moment the unit needs to bring it up. */
export function turnOnBluetooth() {
  if (state.btTurningOn) return;
  set({ btTurningOn: true });
  setTimeout(() => { set({ btTurningOn: false }); setBluetooth(true); }, BT_ON_MS);
}

/** The phone rings the unit, as a caller from the control panel. */
export function incomingCall(number: string) {
  if (!state.car.hfpConnected || callIn(HFP.INCOMING, HFP.WAITING)) return;
  // during a call the new one waits, as a second line
  simCalls = [...simCalls, { id: ++simCallId, state: simCalls.length ? HFP.WAITING : HFP.INCOMING, number }];
  publishCalls();
}

/** The other side ends the call talked, or stops ringing. */
export function remoteHangUp() {
  drop(callIn(HFP.ACTIVE, HFP.ALERTING, HFP.DIALING) ?? callIn(HFP.INCOMING, HFP.WAITING) ?? callIn(HFP.HELD));
  publishCalls();
}

/** Apps the control panel can put on the unit, as if installed with adb; none at start. */
export const UNIT_APPS: LaunchableApp[] = [{ id: "com.waze", label: "Waze" }, { id: "com.spotify.music", label: "Spotify" }, { id: "org.videolan.vlc", label: "VLC" }];

/** An app installed on the unit or removed from it; a removed one comes off home too. */
export function setInstalled(app: LaunchableApp, on: boolean) {
  const installed = on ? [...state.installed.filter((a) => a.id !== app.id), app] : state.installed.filter((a) => a.id !== app.id);
  set({ installed });
  shellAction({ type: "AppsInstalled", ids: installed.map((a) => a.id) });
}

/** An icon on home: our apps open in the shell, another app opens full screen on the unit (only logged here). */
/**
 * The Miracast receiver as the unit runs it: phones can find it while the car is on and the setting allows; here a
 * window of the laptop stands for the phone (CarRuntime and the receiver on the unit).
 */
let castStream: MediaStream | null = null;
export const castPicture = () => castStream;

function castAvailability() {
  const visible = state.acc && state.shell.castVisible;
  if (!visible && castStream) return stopLaptopWindow();
  const next = visible ? "VISIBLE" : "OFF";
  if (state.car.cast !== next && state.car.cast !== "LIVE" && state.car.cast !== "CONNECTING") carEvent({ type: "PhoneCast", state: next });
}
setTimeout(castAvailability, 0);

export function setCastVisible(on: boolean) {
  shellAction({ type: "SetCastVisible", on });
  castAvailability();
}

/** The laptop shares a window, as the phone would from Smart View: connecting while it is picked, then live. */
export async function shareLaptopWindow() {
  if (state.car.cast !== "VISIBLE") return;
  const device = "Laptop window";
  carEvent({ type: "PhoneCast", state: "CONNECTING", device });
  try {
    castStream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 30 }, audio: true });
  } catch {
    return carEvent({ type: "PhoneCast", state: state.acc && state.shell.castVisible ? "VISIBLE" : "OFF" });
  }
  castStream.getVideoTracks()[0].addEventListener("ended", stopLaptopWindow);
  carEvent({ type: "PhoneCast", state: "LIVE", device });
}

/** The phone stops sharing; the unit stays visible for the next time. */
export function stopLaptopWindow() {
  castStream?.getTracks().forEach((t) => t.stop());
  castStream = null;
  if (state.car.cast === "LIVE" || state.car.cast === "CONNECTING") carEvent({ type: "PhoneCast", state: state.acc && state.shell.castVisible ? "VISIBLE" : "OFF" });
}

/** A number as the screen shows it: grouped for its country, numbers without + by the Number Country setting. */
export function formatNumber(number: string) {
  return bridge.phoneFormat(number, state.shell.phoneRegion);
}

export function launchApp(id: string) {
  if (id === "PHONE") return openPhone();
  if (id === "PHONE_SCREEN") return shellAction({ type: "OpenApp", app: "PHONE_SCREEN" });
  if (id === "MAPS" || id === "MUSIC" || id === "RADIO" || id === "SETTINGS") return shellAction({ type: "OpenApp", app: id });
  set({ log: addLog("out", { type: "LaunchApp", package: id }) });
}

/** A text message reaches the phone, as the control panel sends one. */
export function incomingMessage(number: string, text: string) {
  if (!state.car.hfpConnected || !state.messagesSupported) return;
  carEvent({ type: "Message", id: `m-${Date.now()}`, number, text, atMs: Date.now() });
}

/** The phone app opens on a thread, from the banner; null back to the list. */
export function openThread(number: string | null) {
  set({ thread: number, newMessage: null });
  if (number) shellAction({ type: "OpenApp", app: "PHONE" });
}

export function markRead(ids: string[]) {
  const fresh = ids.filter((id) => !state.read.includes(id));
  if (fresh.length) set({ read: [...state.read, ...fresh] });
}

export function dismissMessage() {
  if (state.newMessage) set({ newMessage: null });
}

export function setSteering(eps: number) {
  set({ eps });
}

export function setPhoneStatus(status: { battery?: number; signal?: number; operator?: string; roaming?: boolean }) {
  carEvent({ type: "PhoneStatus", ...status });
}

/** A level picked on screen; the shared rules turn it into steps and mute at zero. */
export function setVolume(level: number) {
  bridge.setVolume(state.car, level).forEach(carCommand);
}

/** One level up or down from wherever the volume is now; safe to call again from a held key. */
export function stepVolume(delta: number) {
  setVolume(state.car.volume + delta);
}

/** A transport button on a sound page, its app or the map's strip: the unit's rule (core-car HomeCard.press). */
export function pressSound(page: "BLUETOOTH" | "RADIO", press: "PREV" | "PLAY" | "NEXT") {
  bridge.homeCardPress(state.car, page, press).forEach(carCommand);
}

/** One more line in the unit's journal when recording is on: an event, a command or a screen action. */
const counted = () => (state.shell.recordEvents ? state.recorded + 1 : state.recorded);

const CALL_VOLUME_DEFAULT = 20;

/** A change from the sound window: kept with the shell's settings, and the processor gets only the calls that moved it (CarRuntime on the unit). */
export function setSound(next: SoundSettings) {
  const writes = bridge.soundWrites(state.shell.dsp, next);
  shellAction({ type: "SetSound", settings: next });
  writes.forEach((write) => carCommand({ type: "Dsp", write }));
}

/** The camera settings: only the vendor values that moved are written (core-car Camera.writes). */
export function setCamera(next: CameraSettings) {
  const writes = bridge.cameraWrites(state.shell.camera, next);
  shellAction({ type: "SetCamera", settings: next });
  writes.forEach((write) => carCommand({ type: "Camera.Write", write }));
}

export function shellAction(action: ShellAction) {
  set({ shell: bridge.shell(state.shell, action), recorded: counted() });
  savePreferences();
}

// var: the store writes savedPrefs while building its first state, before this line runs
var PREFS_KEY = "rdstr.shell";
var savedPrefs = "";

/** A changed preference is written at once, as the unit does; the browser may refuse storage, the shell works on. */
function savePreferences() {
  const text = bridge.encodePreferences(state.shell);
  if (text === savedPrefs) return;
  savedPrefs = text;
  try { localStorage.setItem(PREFS_KEY, text); } catch { /* private window or storage off */ }
}

/** The preferences kept from the last run, or the defaults; ?fresh starts from the defaults. */
function storedShell(q: URLSearchParams) {
  if (q.has("fresh")) return bridge.shellInitial();
  try {
    const text = localStorage.getItem("rdstr.shell"); // PREFS_KEY is not set yet this early
    if (text) {
      savedPrefs = text;
      return bridge.decodePreferences(text);
    }
  } catch { /* storage off */ }
  return bridge.shellInitial();
}

/** Factory reset: every preference back to its default and setup to go through again, then a restart (CarRuntime.factoryReset). */
export function factoryReset() {
  try { localStorage.setItem(PREFS_KEY, bridge.factoryPreferences()); } catch { /* storage off: the reload starts from defaults anyway */ }
  window.location.reload();
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
      .map((line) => JSON.parse(line))
      // a unit's journal also holds commands, screen actions and errors: only the car events replay
      .filter((line) => line.event);
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

/** URL state for captures, e.g. ?bare&freeze&design=glassy&theme=light&hour=20&source=radio&call=ringing&drawer&camera&brightness=40&analog&app=music&track=1 */
function fromUrl(q: URLSearchParams): EmulatorState {
  let shell = storedShell(q);
  let car = bridge.initial();
  const applyEvent = (event: CarEvent) => { car = bridge.apply(car, event).state; };
  const theme = q.get("theme")?.toUpperCase();
  if (theme === "AUTO" || theme === "LIGHT" || theme === "DARK") shell = bridge.shell(shell, { type: "SetTheme", mode: theme });
  if (q.has("night")) shell = bridge.shell(shell, { type: "SetTheme", mode: "DARK" });
  const design = q.get("design")?.toUpperCase();
  if (design === "SOLID" || design === "GLASSY") shell = bridge.shell(shell, { type: "SetDesign", design });
  // ?freeze: a still picture for the parity captures: noon, no CSS animation or transition, no always-on display
  if (q.has("freeze")) document.documentElement.classList.add("freeze");
  if (q.has("drawer")) shell = bridge.shell(shell, { type: "OpenDrawer" });
  if (q.has("aod")) shell = bridge.shell(shell, { type: "EnterAod" });
  if (q.has("sound")) shell = bridge.shell(shell, { type: "OpenSound" });
  if (q.has("analog")) shell = bridge.shell(shell, { type: "SetFace", face: "ANALOG" });
  if (q.get("app") === "music") shell = bridge.shell(shell, { type: "OpenApp", app: "MUSIC" });
  if (q.get("app") === "radio") shell = bridge.shell(shell, { type: "OpenApp", app: "RADIO" });
  if (q.get("app") === "maps") shell = bridge.shell(shell, { type: "OpenApp", app: "MAPS" });
  if (q.get("app") === "phone") shell = bridge.shell(shell, { type: "OpenApp", app: "PHONE" });
  if (q.get("app") === "settings") shell = bridge.shell(shell, { type: "OpenApp", app: "SETTINGS" });
  if (q.has("devices")) shell = bridge.shell(shell, { type: "OpenDevices" });
  // ?setup=wifi: the setup from the start, moved on to that screen (every screen reachable: Wi-Fi and a release assumed)
  const setup = q.get("setup")?.toUpperCase();
  if (setup) {
    shell = bridge.shell(shell, { type: "RestartSetup" });
    while (!shell.setupDone && shell.setupStep !== setup) shell = bridge.shell(shell, { type: "SetupNext", wifi: true, updateAvailable: true });
  }
  const font = q.get("font");
  if (font) shell = bridge.shell(shell, { type: "SetFont", name: font });
  const brightness = q.get("brightness");
  if (brightness !== null) shell = bridge.shell(shell, { type: "SetBrightness", value: Number(brightness) });

  // ?btoff starts with Bluetooth off, so no phone; ?nophone with it on and no phone
  const source = (q.get("source") ?? (q.has("btoff") || q.has("nophone") ? "none" : "bt")).toUpperCase();
  if (source === "BT") {
    applyEvent({ type: "BtConnection", connected: true, device: SAMPLE_PHONE });
    applyEvent({ type: "HfpConnection", connected: true, device: SAMPLE_PHONE });
    applyEvent({ type: "Playback", playing: true, positionMs: 72000 });
  }
  if (source === "RADIO") {
    car = bridge.command(car, { type: "Audio.Source", source: "RADIO" });
    applyEvent({ type: "Playback", playing: true });
  }
  const call = q.get("call");
  if (call === "ringing" || call === "active") {
    simCalls = [{ id: ++simCallId, state: call === "active" ? HFP.ACTIVE : HFP.INCOMING, number: CALLER_NUMBER }];
    applyEvent(bridge.hfpCall(simCalls));
    shell = bridge.shell(shell, { type: "CallChanged", state: call === "active" ? "ACTIVE" : "RINGING" });
    if (q.has("callbar")) shell = bridge.shell(shell, { type: "Home" });
  }
  if (q.has("camera")) applyEvent({ type: "ReverseGear", on: true });

  const hour = q.get("hour");
  const caller = bridge.phoneFind(SAMPLE_CONTACTS, CALLER_NUMBER);
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
    sweep: false,
    speed: { kmh: 0, stopped: true },
    country: "",
    maps: { installed: ["RO"], downloading: null, declined: [] },
    call: call === "ringing" || call === "active"
      ? { ...NO_CALL, number: CALLER_NUMBER, name: caller?.name ?? "", label: caller?.label ?? "", incoming: true, since: call === "active" ? Date.now() - 83_000 : null }
      : NO_CALL,
    contacts: SAMPLE_CONTACTS,
    calls: sampleCalls(Date.now()),
    phoneStatus: { battery: 4, signal: 3, operator: "Vela Mobile", roaming: false },
    eps: 0,
    castDevice: "",
    btTurningOn: false,
    voiceAssistant: false,
    messages: [
      { id: "m-0", number: "0744 000 303", text: "Are you coming tonight?", atMs: Date.now() - 20 * 60_000 },
      { id: "m-1", number: "0721 000 111", text: "Did you eat? Call me when you get home.", atMs: Date.now() - 3 * 3_600_000 },
      { id: "m-2", number: "0721 000 111", text: "On my way, see you at 8.", atMs: Date.now() - 2.5 * 3_600_000, sent: true },
      { id: "m-3", number: "021 000 1414", text: "Your car is ready for pickup. We are open until 18:00.", atMs: Date.now() - 26 * 3_600_000 },
      { id: "m-4", number: "021 000 1414", text: "Thanks, I'll come tomorrow morning.", atMs: Date.now() - 25 * 3_600_000, sent: true },
    ],
    read: ["m-1", "m-3"],
    thread: null,
    newMessage: null,
    // ?nomap: a unit whose Bluetooth stack has no message client
    messagesSupported: !q.has("nomap"),
    installed: [],
    recorded: 0,
    device: SAMPLE_PHONE,
    // the names phones give themselves in their Bluetooth settings
    saved: [{ name: SAMPLE_PHONE, inRange: true, major: MAJOR_PHONE }, { name: "Galaxy S23", inRange: false, major: MAJOR_PHONE }, { name: "Galaxy Tab S9", inRange: true, major: MAJOR_COMPUTER }],
    connecting: null,
    failed: null,
    scanning: false,
    searched: false,
    nearby: [],
    pairing: null,
    pcMedia: false,
    btOn: !q.has("btoff"),
    wifiOn: true,
    wifi: { saved: [WIFI_NETWORKS[0].ssid], connected: WIFI_NETWORKS[0].ssid, connecting: null, wrongPassword: null, seen: [] },
    locationOn: true,
    // ?release=1.19.0: a newer version out
    releaseOut: q.get("release"),
    restartAsked: false,
    updateCheck: { state: "IDLE", at: null },
    wifiNeedsLocation: false,
    acc: true,
    hour: q.get("clock") === "fixed" ? 12 : hour !== null ? Number(hour) : q.has("freeze") ? 12 : null,
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    clockShiftMs: 0,
    // a car's clock is rarely right: a few minutes off at start
    dash: { offsetMin: 7, fuel: false },
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

/** The time the shell shows: live (as set by hand, in the unit's zone), or a fixed moment when an hour is simulated. */
export function useNow(hour: number | null) {
  const [live, setLive] = useState(() => new Date());
  useEffect(() => {
    const timer = setInterval(() => setLive(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);
  if (hour !== null) return new Date(2026, 9, 31, hour, 0);
  return shownNow(live);
}

/** The unit's local time: the browser's, as set by hand and in the unit's zone (the browser's getters read local time). */
function shownNow(live = new Date()) {
  const zoneShift = (zoneOffsetMinutes(state.timeZone, live) + live.getTimezoneOffset()) * 60_000;
  return new Date(live.getTime() + state.clockShiftMs + zoneShift);
}

const minutesOfDay = (d: Date) => d.getHours() * 60 + d.getMinutes();
const DAY_MIN = 24 * 60;

/** What the dash display shows now, in minutes of the day. */
export function dashMinutes(): number {
  return (((minutesOfDay(new Date()) + state.dash.offsetMin) % DAY_MIN) + DAY_MIN) % DAY_MIN;
}

/** A key reaching the dash display over CAN: hour and minute count up and wrap on their own, the clock key switches. */
function pressDash(key: "HOUR" | "MINUTE" | "CLOCK") {
  if (key === "CLOCK") return set({ dash: { ...state.dash, fuel: !state.dash.fuel } });
  const shown = dashMinutes();
  const h = Math.floor(shown / 60);
  const m = shown % 60;
  const next = key === "HOUR" ? ((h + 1) % 24) * 60 + m : h * 60 + ((m + 1) % 60);
  set({ dash: { ...state.dash, offsetMin: state.dash.offsetMin + next - shown } });
}

/** The car's battery was disconnected: the dash clock starts again from 00:00. */
export function resetDash() {
  set({ dash: { offsetMin: -minutesOfDay(new Date()), fuel: false } });
}

/** After a jump of the unit's local time, a matched display gets the keys that follow it (DashFollower on the unit). */
function followDash(change: () => void) {
  const before = minutesOfDay(shownNow());
  const beforeMs = shownNow().getTime();
  change();
  const jump = Math.round((shownNow().getTime() - beforeMs) / 60_000);
  if (!state.shell.dashMatched || jump === 0) return;
  for (const key of bridge.dashFollow(before, jump)) carCommand({ type: "Dash.Key", key });
}

const zoneFormats = new Map<string, Intl.DateTimeFormat>();
/** A zone's offset from GMT at a moment, in minutes, summer time included. */
export function zoneOffsetMinutes(zone: string, at: Date): number {
  let f = zoneFormats.get(zone);
  if (!f) {
    f = new Intl.DateTimeFormat("en-US", { timeZone: zone, hourCycle: "h23", year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "numeric" });
    zoneFormats.set(zone, f);
  }
  const p = Object.fromEntries(f.formatToParts(at).map((x) => [x.type, x.value]));
  const minute = Math.floor(at.getTime() / 60_000) * 60_000;
  return Math.round((Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute) - minute) / 60_000);
}

/** Every zone the browser knows, as Android's TimeZone.getAvailableIDs lists them. */
export function allTimeZones(): string[] {
  return (Intl as unknown as { supportedValuesOf(key: string): string[] }).supportedValuesOf("timeZone");
}

/** Automatic from the internet or GPS, or by hand; going back to automatic corrects a clock set by hand. */
export function setTimeSync(auto: boolean, source: TimeSource) {
  if (auto) followDash(() => set({ clockShiftMs: 0 }));
  shellAction({ type: "SetTimeSync", auto, source });
}

/** The date and time steppers: the clock moves by this much (AlarmManager.setTime on the unit). */
export function shiftClock(ms: number) {
  followDash(() => set({ clockShiftMs: state.clockShiftMs + ms }));
}

/** The unit's address on the hotspot, as WifiManager reports it; fixed in the emulator. */
export const UNIT_IP = "192.168.43.120";

/** Restart roadsterOS: the process ends and home opens again (DeveloperTools.restart); here, the page reloads. */
/** Looks for a newer version on GitHub, as the unit does on demand (Updates.check); Wi-Fi only. */
export function checkForUpdate() {
  if (!state.wifi.connected || state.updateCheck.state === "CHECKING") return;
  set({ updateCheck: { state: "CHECKING", at: state.updateCheck.at } });
  setTimeout(() => set({ updateCheck: { state: state.releaseOut ? "AVAILABLE" : "LATEST", at: Date.now() } }), 1200);
}

/** When this version went on the unit (on the unit: the package's last update time), kept from the first start. */
export function installedAt(): number {
  try {
    const kept = Number(localStorage.getItem("rdstr.installed"));
    if (kept) return kept;
    const now = Date.now();
    localStorage.setItem("rdstr.installed", String(now));
    return now;
  } catch { return Date.now(); }
}

/** The question before a restart, from About or a long press on Settings in the drawer (which closes). */
export function askRestart(on: boolean) {
  if (on) shellAction({ type: "CloseDrawer" });
  set({ restartAsked: on });
}

/** The whole unit starts again (CarRuntime.restartUnit): here the page loads again, from the boot screen. */
export function restartUnit() {
  window.location.reload();
}

/** Android's developer options open over the shell on the unit (DeveloperTools.openAndroidSettings); only logged here. */
export function openAndroidSettings() {
  set({ log: addLog("out", { type: "OpenAndroidSettings", action: "android.settings.APPLICATION_DEVELOPMENT_SETTINGS" }) });
}

export function setTimeZone(timeZone: string) {
  followDash(() => set({ timeZone }));
}
