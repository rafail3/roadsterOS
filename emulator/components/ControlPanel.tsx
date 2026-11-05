import { useEffect, useState, type ReactNode } from "react";
import type { Face, SteeringKey, ThemeMode } from "../car/bridge";
import { SAMPLE_CALLERS } from "../car/phone";
import { carEvent, GPS_PLACES, incomingCall, remoteHangUp, removePack, setPhoneStatus, replay, setDriving, setSpeedSweep, setHour, setInRange, setPcMedia, setPosition, shellAction, stopReplay, useEmulator, useNow, WIFI_NETWORKS } from "../car/store";
import { Field, holdToRepeat, Section, Segmented, Switch } from "./controls";

const DEFAULT_TRACK_MS = 228000;
const FIXED_HOUR = 12;

const KEYS: { key: SteeringKey; label: string }[] = [
  { key: "VOL_DOWN", label: "Vol −" },
  { key: "VOL_UP", label: "Vol +" },
  { key: "MUTE", label: "Mute" },
  { key: "PREV", label: "Prev" },
  { key: "NEXT", label: "Next" },
  { key: "MODE", label: "Set" },
];
const THEMES: { value: ThemeMode; label: string }[] = [
  { value: "AUTO", label: "Auto" },
  { value: "LIGHT", label: "Day" },
  { value: "DARK", label: "Night" },
];
const FACES: { value: Face; label: string }[] = [
  { value: "DIGITAL", label: "Digital" },
  { value: "ANALOG", label: "Analog" },
];
const FONTS = [{ value: "geist", label: "Geist" }, { value: "inter", label: "Inter" }];
const CLOCKS = [{ value: "live", label: "Live" }, { value: "fixed", label: "12:00" }] as const;

function SteeringWheel() {
  return (
    <Section title="Steering wheel" open>
      <div className="keys">
        {KEYS.map(({ key, label }) => (
          key === "VOL_UP" || key === "VOL_DOWN"
            ? <button key={key} className="button" {...holdToRepeat(() => carEvent({ type: "SteeringKey", key }))}>{label}</button>
            : <button key={key} className="button" onClick={() => carEvent({ type: "SteeringKey", key })}>{label}</button>
        ))}
      </div>
    </Section>
  );
}

function Signals() {
  const { car, acc } = useEmulator();
  return (
    <Section title="Signals" open>
      <Switch label="Ignition (ACC)" checked={acc} onChange={(on) => carEvent({ type: "Power", acc: on })} />
      <Switch label="Reverse gear" checked={car.camera} onChange={(on) => carEvent({ type: "ReverseGear", on })} />
      <Switch label="Headlights" checked={car.night} onChange={(on) => carEvent({ type: "Illumination", on })} />
    </Section>
  );
}

function Bluetooth() {
  const { car, positionMs, saved, pcMedia } = useEmulator();
  const [device, setDevice] = useState("Phone");
  const [title, setTitle] = useState("Night Transit");
  const [artist, setArtist] = useState("Vela Orsini");
  return (
    <Section title="Bluetooth">
      <Switch label="Laptop media" checked={pcMedia} onChange={setPcMedia} />
      <p className="note">Plays what this Windows laptop plays, for example a music site in the browser: track, cover, position, and the transport keys control it.</p>
      <Field label="Device" value={device} onChange={setDevice} />
      <Switch label="Connected" checked={car.btConnected} onChange={(connected) => carEvent({ type: "BtConnection", connected, device: device || "Phone" })} />
      <Switch label="Playing" checked={car.playing} onChange={(playing) => carEvent({ type: "Playback", playing, positionMs })} />
      <div className="field-pair">
        <Field label="Title" value={title} onChange={setTitle} />
        <Field label="Artist" value={artist} onChange={setArtist} />
      </div>
      <button className="button" onClick={() => carEvent({ type: "Track", title, artist, album: "", durationMs: DEFAULT_TRACK_MS })}>Send track</button>
      <p className="note">Saved phones: Bluetooth on and in range</p>
      {saved.map((d) => <Switch key={d.name} label={d.name} checked={d.inRange} onChange={(on) => setInRange(d.name, on)} />)}
    </Section>
  );
}

/** The simulated networks around the unit, with the passwords that join them. */
function Wifi() {
  const { wifi } = useEmulator();
  return (
    <Section title="Wi-Fi">
      <p className="note">Networks in range and their passwords, for joining from the screen.</p>
      {WIFI_NETWORKS.map((n) => (
        <div key={n.ssid} className="row">
          <span>{n.ssid}{wifi.connected === n.ssid ? " · connected" : ""}</span>
          <code>{n.secure ? n.password : "open"}</code>
        </div>
      ))}
    </Section>
  );
}

/** The phone's side of a call: it rings the unit, the other side hangs up, its battery and signal change. */
function Phone() {
  const { car, phoneStatus } = useEmulator();
  const [number, setNumber] = useState(SAMPLE_CALLERS[0].number);
  const idle = car.call === "IDLE";
  return (
    <Section title="Phone">
      <p className="note">{car.hfpConnected ? "A phone is connected for calls." : "Connect a phone (not the tablet) to ring the unit."}</p>
      <div className="field-pair">
        {SAMPLE_CALLERS.map((c) => <button key={c.number} className="button" onClick={() => setNumber(c.number)}>{c.label}</button>)}
      </div>
      <Field label="Caller number" value={number} onChange={setNumber} />
      <div className="field-pair">
        <button className="button" disabled={!car.hfpConnected || !idle} onClick={() => incomingCall(number)}>Ring the unit</button>
        <button className="button" disabled={idle} onClick={remoteHangUp}>Other side hangs up</button>
      </div>
      <Row label={`Signal ${phoneStatus.signal}/5`}>
        <input type="range" min="0" max="5" value={phoneStatus.signal} onChange={(e) => setPhoneStatus({ signal: Number(e.target.value) })} />
      </Row>
      <Row label={`Battery ${phoneStatus.battery}/5`}>
        <input type="range" min="0" max="5" value={phoneStatus.battery} onChange={(e) => setPhoneStatus({ battery: Number(e.target.value) })} />
      </Row>
    </Section>
  );
}

// The unit's tuner: FM in 0.05 MHz steps, AM in 9 kHz steps (European region).
const BANDS = {
  FM: { label: "Frequency (MHz)", min: 87.5, max: 108, step: 0.05, toKhz: (v: number) => Math.round(v * 1000), start: "101.9" },
  AM: { label: "Frequency (kHz)", min: 522, max: 1620, step: 9, toKhz: (v: number) => Math.round(v), start: "1404" },
} as const;
const BAND_OPTIONS: { value: keyof typeof BANDS; label: string }[] = [{ value: "FM", label: "FM" }, { value: "AM", label: "AM" }];

function Radio() {
  const [band, setBand] = useState<keyof typeof BANDS>("FM");
  const [value, setValue] = useState<string>(BANDS.FM.start);
  const [station, setStation] = useState("Radio Nord");
  const range = BANDS[band];
  const tune = () => {
    const clamped = Math.min(range.max, Math.max(range.min, Number(value) || range.min));
    carEvent({ type: "Radio", khz: range.toKhz(clamped), band, rds: band === "FM" ? station : "", signal: 7 });
  };
  return (
    <Section title="Radio">
      <Row label="Band"><Segmented label="Band" options={BAND_OPTIONS} value={band} onChange={(b) => { setBand(b); setValue(BANDS[b].start); }} /></Row>
      <div className="field-pair">
        <Field label={range.label} type="number" step={String(range.step)} min={String(range.min)} max={String(range.max)} value={value} onChange={setValue} />
        <Field label="Station (RDS)" value={station} onChange={setStation} />
      </div>
      <button className="button" onClick={tune}>Tune</button>
    </Section>
  );
}

function Scenarios() {
  const { replay: running, replayError } = useEmulator();
  const [names, setNames] = useState<string[]>([]);
  const [listError, setListError] = useState(false);
  useEffect(() => {
    fetch("/recordings/index.json").then((r) => r.json()).then(setNames).catch(() => setListError(true));
  }, []);
  return (
    <Section title="Scenarios" open>
      <div className="scenarios">
        {names.map((name) => (
          <button key={name} className="button scenario" aria-pressed={running?.name === name} onClick={() => replay(name)}>
            <span className="icon i-play" aria-hidden="true" />{name}
          </button>
        ))}
      </div>
      {running && (
        <div className="replay">
          <progress value={running.progress} aria-label={`Replaying ${running.name}`} />
          <button className="button" onClick={stopReplay}>Stop</button>
        </div>
      )}
      {listError && <p className="note error" role="alert">Could not list scenarios. Run npm run sync in emulator/, then reload.</p>}
      {replayError && <p className="note error" role="alert">{replayError}</p>}
    </Section>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return <div className="row"><span>{label}</span>{children}</div>;
}

function DisplayControls() {
  const { shell, hour } = useEmulator();
  const now = useNow(hour);
  return (
    <Section title="Display" open>
      <Row label="Theme"><Segmented label="Theme" options={THEMES} value={shell.theme} onChange={(mode) => shellAction({ type: "SetTheme", mode })} /></Row>
      <Row label="Clock"><Segmented label="Clock face" options={FACES} value={shell.face} onChange={(face) => shellAction({ type: "SetFace", face })} /></Row>
      <Row label="Time"><Segmented label="Clock source" options={CLOCKS} value={hour === null ? "live" : "fixed"} onChange={(v) => setHour(v === "live" ? null : FIXED_HOUR)} /></Row>
      <label className="range">
        <span>Simulated hour (auto theme: day from 05:00 to 19:00)</span>
        <input type="range" min="0" max="23" value={now.getHours()} onChange={(e) => setHour(Number(e.target.value))} />
        <output>{hour === null ? "live" : `${String(hour).padStart(2, "0")}:00`}</output>
      </label>
      <Row label="Font"><Segmented label="Font" options={FONTS} value={shell.font} onChange={(name) => shellAction({ type: "SetFont", name })} /></Row>
      <Switch label="Drawer open" checked={shell.drawer} onChange={(on) => shellAction({ type: on ? "OpenDrawer" : "CloseDrawer" })} />
    </Section>
  );
}

const SPEEDS = [{ value: "90", label: "90" }, { value: "400", label: "400" }, { value: "2000", label: "2000" }];

/** The simulated GPS: where the car is, which way it drives and how fast, and the map packs on the unit. */
function Gps() {
  const { gps, driving, sweep, country, maps } = useEmulator();
  return (
    <Section title="GPS and maps">
      <div className="keys">
        {GPS_PLACES.map((p) => <button key={p.name} className="button" onClick={() => setPosition(p)}>{p.name}</button>)}
      </div>
      <label className="range">
        <span>Heading</span>
        <input type="range" min="0" max="359" value={Math.round(gps.heading)} onChange={(e) => setPosition({ heading: Number(e.target.value) })} />
        <output>{Math.round(gps.heading)}°</output>
      </label>
      <Row label="Speed, km/h"><Segmented label="Speed" options={SPEEDS} value={String(gps.kmh || 90) as "90"} onChange={(v) => setDriving(driving, Number(v))} /></Row>
      <label className="range">
        <span>Speed</span>
        <input type="range" min="0" max="180" value={Math.round(gps.kmh)} disabled={sweep} onChange={(e) => setDriving(driving, Number(e.target.value))} />
        <output>{Math.round(gps.kmh)} km/h</output>
      </label>
      <Switch label="Driving" checked={driving} onChange={(on) => setDriving(on)} />
      <Switch label="Speed test: up to 130 and down, on repeat" checked={sweep} onChange={setSpeedSweep} />
      <p className="note">In {country || "no known country"} at {gps.lat.toFixed(4)}, {gps.lon.toFixed(4)}. Map packs on the unit:</p>
      {maps.installed.map((code) => (
        <div key={code} className="row"><span>{code}</span><button className="button" onClick={() => removePack(code)}>Remove</button></div>
      ))}
    </Section>
  );
}

export function ControlPanel() {
  return (
    <div className="controls-scroll">
      <DisplayControls />
      <SteeringWheel />
      <Signals />
      <Scenarios />
      <Bluetooth />
      <Wifi />
      <Phone />
      <Radio />
      <Gps />
      <p className="note">Names, tracks and numbers are synthetic.</p>
    </div>
  );
}
