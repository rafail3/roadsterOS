import { useEffect, useState, type ReactNode } from "react";
import type { CallState, Face, SteeringKey, ThemeMode } from "../car/bridge";
import { carEvent, replay, setHour, shellAction, stopReplay, useEmulator, useNow } from "../car/store";
import { Field, Section, Segmented, Switch } from "./controls";

const DEFAULT_TRACK_MS = 228000;
const FIXED_HOUR = 12;

const KEYS: { key: SteeringKey; label: string }[] = [
  { key: "VOL_DOWN", label: "Vol −" },
  { key: "VOL_UP", label: "Vol +" },
  { key: "MUTE", label: "Mute" },
  { key: "PHONE", label: "Phone" },
  { key: "PREV", label: "Prev" },
  { key: "NEXT", label: "Next" },
  { key: "MODE", label: "Mode" },
];
const CALL_STATES: { value: CallState; label: string }[] = [
  { value: "IDLE", label: "Idle" },
  { value: "RINGING", label: "Ringing" },
  { value: "ACTIVE", label: "Active" },
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
          <button key={key} className="button" onClick={() => carEvent({ type: "SteeringKey", key })}>{label}</button>
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
  const { car, positionMs } = useEmulator();
  const [device, setDevice] = useState("Phone");
  const [title, setTitle] = useState("Night Transit");
  const [artist, setArtist] = useState("Vela Orsini");
  return (
    <Section title="Bluetooth">
      <Field label="Device" value={device} onChange={setDevice} />
      <Switch label="Connected" checked={car.btConnected} onChange={(connected) => carEvent({ type: "BtConnection", connected, device: device || "Phone" })} />
      <Switch label="Playing" checked={car.playing} onChange={(playing) => carEvent({ type: "Playback", playing, positionMs })} />
      <div className="field-pair">
        <Field label="Title" value={title} onChange={setTitle} />
        <Field label="Artist" value={artist} onChange={setArtist} />
      </div>
      <button className="button" onClick={() => carEvent({ type: "Track", title, artist, album: "", durationMs: DEFAULT_TRACK_MS })}>Send track</button>
    </Section>
  );
}

function Phone() {
  const { car } = useEmulator();
  const [name, setName] = useState("Ana Petrescu");
  const [number, setNumber] = useState("+40 7xx xxx xxx");
  return (
    <Section title="Phone">
      <div className="field-pair">
        <Field label="Caller" value={name} onChange={setName} />
        <Field label="Number" value={number} onChange={setNumber} />
      </div>
      <Segmented label="Call state" options={CALL_STATES} value={car.call} onChange={(state) => carEvent({ type: "Call", state, name, number })} />
    </Section>
  );
}

function Radio() {
  const [mhz, setMhz] = useState("101.9");
  const [station, setStation] = useState("Radio Nord");
  const tune = () => carEvent({ type: "Radio", khz: Math.round(Number(mhz) * 1000), band: "FM", rds: station, signal: 7 });
  return (
    <Section title="Radio">
      <div className="field-pair">
        <Field label="Frequency (MHz)" type="number" step="0.1" value={mhz} onChange={setMhz} />
        <Field label="Station" value={station} onChange={setStation} />
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

export function ControlPanel() {
  return (
    <div className="controls-scroll">
      <DisplayControls />
      <SteeringWheel />
      <Signals />
      <Scenarios />
      <Bluetooth />
      <Phone />
      <Radio />
      <p className="note">Names, tracks and numbers are synthetic.</p>
    </div>
  );
}
