import { useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { bridge, type EqPreset, type ListeningSeat, type SoundSettings, type SpeakerFilter } from "../car/bridge";
import { setSound, shellAction, useEmulator } from "../car/store";
import { CAR_SEATS, CAR_VIEW, CarTop, speakerLevels } from "./CarTop";
import { useCloseOntoCard } from "./media";
import { Group, Row, Segmented, Switch } from "./SettingsApp";

type Section = "eq" | "balance" | "field" | "loudness" | "bass" | "filters";

const SECTIONS: { id: Section; label: string; icon: string; tint: string }[] = [
  { id: "eq", label: "Equalizer", icon: "i-snd-faders", tint: "#0a84ff" },
  { id: "balance", label: "Balance", icon: "i-snd-balance", tint: "#5e5ce6" },
  { id: "field", label: "Listening Position", icon: "i-snd-field", tint: "#0071a4" },
  { id: "loudness", label: "Loudness", icon: "i-snd-loudness", tint: "#c93400" },
  { id: "bass", label: "Smart Bass", icon: "i-snd-bass", tint: "#d30f45" },
  { id: "filters", label: "Speaker Filters", icon: "i-snd-filter", tint: "#248a3d" },
];

// the processor's ranges (core-car Sound)
const BAND_DB = 12;
const BALANCE = 7;
const BANDS_PER_PAGE = 10;
const PRESETS: [EqPreset, string][] = [["FLAT", "Flat"], ["POP", "Pop"], ["ROCK", "Rock"], ["JAZZ", "Jazz"], ["CLASSIC", "Classic"], ["USER", "Custom"]];
const SEATS: { id: ListeningSeat; label: string }[] = [
  { id: "FRONT_LEFT", label: "Front Left" }, { id: "FRONT_RIGHT", label: "Front Right" },
  { id: "REAR_LEFT", label: "Rear Left" }, { id: "REAR_RIGHT", label: "Rear Right" },
];
const SPEAKERS = ["Front Left", "Front Right", "Rear Left", "Rear Right"];
const range = (from: number, to: number, step: number) => Array.from({ length: Math.floor((to - from) / step) + 1 }, (_, i) => from + i * step);
const LOW_HZ = range(50, 1000, 50);
const HIGH_HZ = range(2000, 10000, 500);
const GAINS = range(0, 12, 1);
const SUB_LEVELS = range(0, 15, 1);
const SUB_HZ = range(80, 240, 10);
const DELAYS = range(0, 200, 1);

const hz = (v: number) => (v >= 1000 ? `${+(v / 1000).toFixed(2)} kHz` : `${v} Hz`);
const signed = (v: number) => (v > 0 ? `+${v}` : `${v}`);
const ms = (tenths: number) => `${(tenths / 10).toFixed(1)} ms`;

/** What the list shows beside each section, so the setup reads without opening it. */
function summary(id: Section, s: SoundSettings): string {
  switch (id) {
    case "eq": return PRESETS.find(([p]) => p === s.preset)![1];
    case "balance": return s.balance === 0 && s.fader === 0 ? "Centered"
      : [s.balance && `${s.balance < 0 ? "Left" : "Right"} ${Math.abs(s.balance)}`, s.fader && `${s.fader > 0 ? "Front" : "Rear"} ${Math.abs(s.fader)}`].filter(Boolean).join(", ");
    case "field": return ({ ALL: "Center", FRONT: "Front Row", REAR: "Rear Row", CUSTOM: "Custom" } as Record<string, string>)[s.seat] ?? SEATS.find((x) => x.id === s.seat)!.label;
    case "loudness": return s.loudness ? "On" : "Off";
    case "bass": return s.bass ? `+${s.bassGain} dB` : "Off";
    case "filters": {
      const on = [s.front.highPass, s.front.lowPass, s.rear.highPass, s.rear.lowPass].filter(Boolean).length;
      return on ? `${on} On` : "Off";
    }
  }
}

/** A value from a list with − and +, the value between them. */
function Steps({ what, values, value, format, onChange }: { what: string; values: number[]; value: number; format: (v: number) => string; onChange: (v: number) => void }) {
  // a stored value off the list (an older version's step) sits at its nearest neighbour
  const i = values.reduce((best, v, k) => (Math.abs(v - value) < Math.abs(values[best] - value) ? k : best), 0);
  return (
    <span className="set-stepper">
      <button aria-label={`Lower ${what}`} disabled={i === 0} onClick={() => onChange(values[i - 1])}><span className="icon i-minus" aria-hidden="true" /></button>
      <output aria-label={what} className="snd-step-value">{format(value)}</output>
      <button aria-label={`Raise ${what}`} disabled={i === values.length - 1} onClick={() => onChange(values[i + 1])}><span className="icon i-plus" aria-hidden="true" /></button>
    </span>
  );
}

/** A smooth line through the bands; the page of bands on screen is lit. */
function Curve({ values, max, page }: { values: number[]; max: number; page?: number }) {
  const w = 1000, h = 160;
  const pts = values.map((v, i) => [(i + 0.5) * (w / values.length), h / 2 - (v / max) * (h / 2 - 10)]);
  let d = `M0 ${pts[0][1]} L${pts[0][0]} ${pts[0][1]}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const [x0, y0] = pts[i], [x1, y1] = pts[i + 1];
    const cx = (x0 + x1) / 2;
    d += ` C${cx} ${y0} ${cx} ${y1} ${x1} ${y1}`;
  }
  d += ` L${w} ${pts[pts.length - 1][1]}`;
  const span = w / Math.ceil(values.length / BANDS_PER_PAGE);
  return (
    <svg className="snd-curve" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" aria-hidden="true">
      {page !== undefined && <rect x={page * span} y="0" width={span} height={h} className="snd-curve-page" />}
      <line x1="0" y1={h / 2} x2={w} y2={h / 2} className="snd-curve-zero" />
      {/* the lift or cut, filled between the curve and flat */}
      <path d={`${d} L${w} ${h / 2} L0 ${h / 2} Z`} className="snd-curve-fill" />
      <path d={d} className="snd-curve-line" />
    </svg>
  );
}

/** One band's gain: drag the knob, or the arrow keys. */
function BandSlider({ label, value, max, onChange }: { label: string; value: number; max: number; onChange: (v: number) => void }) {
  const track = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState(false);
  const at = (y: number) => {
    // the screen is drawn scaled: a fraction of the track's own box needs no scale
    const r = track.current!.getBoundingClientRect();
    const f = Math.min(1, Math.max(0, (r.bottom - y) / r.height));
    return Math.round(f * 2 * max - max);
  };
  const down = (e: PointerEvent<HTMLDivElement>) => { e.currentTarget.setPointerCapture(e.pointerId); setDragging(true); const v = at(e.clientY); if (v !== value) onChange(v); };
  const move = (e: PointerEvent<HTMLDivElement>) => { if (dragging) { const v = at(e.clientY); if (v !== value) onChange(v); } };
  const key = (e: KeyboardEvent<HTMLDivElement>) => {
    const by = e.key === "ArrowUp" ? 1 : e.key === "ArrowDown" ? -1 : 0;
    if (by) { e.preventDefault(); onChange(Math.min(max, Math.max(-max, value + by))); }
  };
  const pos = (value + max) / (2 * max);
  return (
    <div className="snd-band">
      <output className={`snd-band-value${value === 0 ? " is-flat" : ""}`}>{signed(value)}</output>
      <div ref={track} className={`snd-band-track${dragging ? " is-dragging" : ""}`} role="slider" tabIndex={0} aria-label={`${label} Hz`} aria-valuemin={-max} aria-valuemax={max} aria-valuenow={value} aria-valuetext={`${signed(value)} dB`}
        onPointerDown={down} onPointerMove={move} onPointerUp={() => setDragging(false)} onPointerCancel={() => setDragging(false)} onKeyDown={key}>
        <span className="snd-band-fill" style={{ bottom: `${Math.min(pos, 0.5) * 100}%`, top: `${(1 - Math.max(pos, 0.5)) * 100}%` }} />
        <span className="snd-band-knob" style={{ bottom: `${pos * 100}%` }} />
      </div>
      <span className="snd-band-label">{label}</span>
    </div>
  );
}

/** Thirty bands, ten at a time; the curve above shows all of them. */
function Equalizer({ s }: { s: SoundSettings }) {
  const [page, setPage] = useState(0);
  const labels = useMemo(() => bridge.soundBands(), []);
  const pages = Math.ceil(labels.length / BANDS_PER_PAGE);
  const first = page * BANDS_PER_PAGE;
  const ranges: [string, string][] = Array.from({ length: pages }, (_, p) => [String(p), `${labels[p * BANDS_PER_PAGE]}–${labels[Math.min(labels.length, (p + 1) * BANDS_PER_PAGE) - 1]} Hz`]);
  return (<>
    <div className="snd-presets">
      <Segmented<EqPreset> label="Equalizer preset" options={PRESETS} value={s.preset} onChange={(p) => setSound(bridge.soundPreset(s, p))} />
    </div>
    <section className="snd-eq" aria-label="Bands">
      <Curve values={s.bands} max={BAND_DB} page={page} />
      <div className="snd-eq-pages">
        <Segmented<string> label="Bands shown" options={ranges} value={String(page)} onChange={(p) => setPage(Number(p))} />
      </div>
      <div className="snd-bands">
        {labels.slice(first, first + BANDS_PER_PAGE).map((b, i) => (
          <BandSlider key={b} label={b} value={s.bands[first + i]} max={BAND_DB} onChange={(v) => setSound(bridge.soundBand(s, first + i, v))} />
        ))}
      </div>
    </section>
    <p className="set-footer">dB per band, from −{BAND_DB} to +{BAND_DB}. Moving a band makes the preset Custom; Flat sets every band back to 0.</p>
  </>);
}

/** Where the sound is centred between the four speakers: drag the dot over the cabin, or the arrow keys. */
function Balance({ s }: { s: SoundSettings }) {
  const pad = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState(false);
  const at = (x: number, y: number) => {
    const r = pad.current!.getBoundingClientRect();
    const step = (f: number) => Math.round((Math.min(1, Math.max(0, f)) * 2 - 1) * BALANCE);
    return { balance: step((x - r.left) / r.width), fader: -step((y - r.top) / r.height) };
  };
  const place = (next: { balance: number; fader: number }) => { if (next.balance !== s.balance || next.fader !== s.fader) setSound({ ...s, ...next }); };
  const down = (e: PointerEvent<HTMLDivElement>) => { e.currentTarget.setPointerCapture(e.pointerId); setDragging(true); place(at(e.clientX, e.clientY)); };
  const move = (e: PointerEvent<HTMLDivElement>) => { if (dragging) place(at(e.clientX, e.clientY)); };
  const key = (e: KeyboardEvent<HTMLDivElement>) => {
    const d = ({ ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, 1], ArrowDown: [0, -1] } as Record<string, number[]>)[e.key];
    if (!d) return;
    e.preventDefault();
    const c = (v: number) => Math.min(BALANCE, Math.max(-BALANCE, v));
    place({ balance: c(s.balance + d[0]), fader: c(s.fader + d[1]) });
  };
  const x = ((s.balance + BALANCE) / (2 * BALANCE)) * 100;
  const y = ((BALANCE - s.fader) / (2 * BALANCE)) * 100;
  return (
    <div className="snd-stage">
      <div className="snd-cabin is-static">
        <CarTop levels={speakerLevels(s.balance, s.fader)} />
        {/* the pad covers the cabin, so the centre falls between the two rows of seats */}
        <div ref={pad} className={`snd-pad${dragging ? " is-dragging" : ""}`} role="slider" tabIndex={0} aria-label="Balance and fader" aria-valuetext={summary("balance", s)}
          onPointerDown={down} onPointerMove={move} onPointerUp={() => setDragging(false)} onPointerCancel={() => setDragging(false)} onKeyDown={key}>
          <span className="snd-axis is-x" style={{ top: `${y}%` }} />
          <span className="snd-axis is-y" style={{ left: `${x}%` }} />
          <span className="snd-dot" style={{ left: `${x}%`, top: `${y}%` }} />
        </div>
      </div>
      <div className="snd-readout">
        <p><span>Left and right</span><strong>{s.balance === 0 ? "Center" : `${s.balance < 0 ? "Left" : "Right"} ${Math.abs(s.balance)}`}</strong></p>
        <p><span>Front and rear</span><strong>{s.fader === 0 ? "Center" : `${s.fader > 0 ? "Front" : "Rear"} ${Math.abs(s.fader)}`}</strong></p>
        <button className="set-inline" disabled={s.balance === 0 && s.fader === 0} onClick={() => setSound({ ...s, balance: 0, fader: 0 })}>Center</button>
      </div>
    </div>
  );
}

/** Where the delays focus the sound: one seat, a row, the middle of the car, or a delay per speaker. */
const FOCUS: { id: ListeningSeat; label: string; seats: ListeningSeat[] }[] = [
  { id: "ALL", label: "Center", seats: ["FRONT_LEFT", "FRONT_RIGHT", "REAR_LEFT", "REAR_RIGHT"] },
  { id: "FRONT", label: "Front", seats: ["FRONT_LEFT", "FRONT_RIGHT"] },
  { id: "REAR", label: "Rear", seats: ["REAR_LEFT", "REAR_RIGHT"] },
  { id: "CUSTOM", label: "Custom", seats: [] },
];

function Field({ s }: { s: SoundSettings }) {
  const row = FOCUS.find((f) => f.id === s.seat);
  const lit = (seat: ListeningSeat) => (row ? row.seats.includes(seat) : s.seat === seat);
  return (<>
    <div className="snd-stage">
      <div className="snd-cabin is-static">
        <CarTop lit={lit} />
        {CAR_SEATS.map((seat, i) => (
          <button key={seat.id} className="snd-seat-hit" aria-pressed={s.seat === seat.id} aria-label={SEATS[i].label} onClick={() => setSound({ ...s, seat: seat.id })}
            style={{ left: `${(seat.x / CAR_VIEW.w) * 100}%`, top: `${(seat.y / CAR_VIEW.h) * 100}%`, width: `${(seat.w / CAR_VIEW.w) * 100}%`, height: `${(seat.h / CAR_VIEW.h) * 100}%` }} />
        ))}
      </div>
      <div className="snd-readout">
        <p><span>Focused on</span><strong>{summary("field", s)}</strong></p>
        <div className="snd-readout-actions" role="radiogroup" aria-label="Listening position">
          {FOCUS.map((f) => (
            <button key={f.id} role="radio" aria-checked={s.seat === f.id} className="snd-focus" onClick={() => setSound({ ...s, seat: f.id })}>{f.label}</button>
          ))}
        </div>
        <p className="snd-hint">Tap a seat to focus the sound there.</p>
      </div>
    </div>
    {s.seat === "CUSTOM" ? (
      <Group title="Delay per Speaker" footer="Each speaker plays later by this much, so the sound of all four reaches you together; 0.1 ms is about 3.4 cm.">
        {SPEAKERS.map((name, i) => (
          <Row key={name} label={name} value={`${Math.round(s.delays[i] * 3.4)} cm`}>
            <Steps what={`${name} delay`} values={DELAYS} value={s.delays[i]} format={ms} onChange={(v) => setSound({ ...s, delays: s.delays.map((d, j) => (j === i ? v : d)) })} />
          </Row>
        ))}
      </Group>
    ) : <p className="set-footer">Center plays every speaker at once. Front and Rear delay the nearer speakers so the sound meets between the two seats of that row.</p>}
  </>);
}

function Loudness({ s }: { s: SoundSettings }) {
  return (<>
    <Group footer="Lifts the lows and the highs at low volume, where the ear hears them less.">
      <Row label="Loudness"><Switch label="Loudness" on={s.loudness} onChange={(loudness) => setSound({ ...s, loudness })} /></Row>
    </Group>
    {s.loudness && (<>
      <Group title="Low">
        <Row label="Frequency"><Steps what="low frequency" values={LOW_HZ} value={s.loudLowHz} format={hz} onChange={(loudLowHz) => setSound({ ...s, loudLowHz })} /></Row>
        <Row label="Gain"><Steps what="low gain" values={GAINS} value={s.loudLowGain} format={(v) => `${v} dB`} onChange={(loudLowGain) => setSound({ ...s, loudLowGain })} /></Row>
      </Group>
      <Group title="High">
        <Row label="Frequency"><Steps what="high frequency" values={HIGH_HZ} value={s.loudHighHz} format={hz} onChange={(loudHighHz) => setSound({ ...s, loudHighHz })} /></Row>
        <Row label="Gain"><Steps what="high gain" values={GAINS} value={s.loudHighGain} format={(v) => `${v} dB`} onChange={(loudHighGain) => setSound({ ...s, loudHighGain })} /></Row>
      </Group>
    </>)}
    <Group footer="600 Hz and 2 kHz, each lifted 4 dB.">
      <Row label="Reset Loudness" onPress={() => setSound(bridge.soundResetLoudness(s))} />
    </Group>
  </>);
}

function Bass({ s }: { s: SoundSettings }) {
  return (<>
    <Group footer="Adds the low notes door speakers cannot play, as harmonics the ear hears as bass.">
      <Row label="Smart Bass"><Switch label="Smart Bass" on={s.bass} onChange={(bass) => setSound({ ...s, bass })} /></Row>
      {s.bass && <Row label="Gain"><Steps what="bass gain" values={GAINS} value={s.bassGain} format={(v) => `${v} dB`} onChange={(bassGain) => setSound({ ...s, bassGain })} /></Row>}
    </Group>
    {s.bass && (
      <Group title="Subwoofer Output" footer="For a subwoofer on the unit's sub out: its level, and the frequency above which it stays quiet.">
        <Row label="Level"><Steps what="subwoofer level" values={SUB_LEVELS} value={s.subLevel} format={(v) => `${v} dB`} onChange={(subLevel) => setSound({ ...s, subLevel })} /></Row>
        <Row label="Cut Above"><Steps what="subwoofer cut-off" values={SUB_HZ} value={s.subHz} format={hz} onChange={(subHz) => setSound({ ...s, subHz })} /></Row>
      </Group>
    )}
    <Group footer="On, 6 dB, subwoofer 10 dB up to 240 Hz.">
      <Row label="Reset Smart Bass" onPress={() => setSound(bridge.soundResetBass(s))} />
    </Group>
  </>);
}

/** The response a pair of speakers gets from its filters, on a log axis from 20 Hz to 20 kHz. */
function Response({ f }: { f: SpeakerFilter }) {
  const x = (v: number) => (Math.log10(v / 20) / 3) * 1000;
  const lo = f.highPass ? x(f.highPassHz) : -400;
  const hi = f.lowPass ? x(f.lowPassHz) : 1400;
  const d = `M${lo - 140} 150 C${lo - 50} 150 ${lo - 50} 30 ${lo + 40} 30 L${hi - 40} 30 C${hi + 50} 30 ${hi + 50} 150 ${hi + 140} 150`;
  return (
    <svg className="snd-curve is-small" viewBox="0 0 1000 160" preserveAspectRatio="none" aria-hidden="true">
      <path d={`${d} L1400 160 L-400 160 Z`} className="snd-curve-fill" />
      <path d={d} className="snd-curve-line" />
    </svg>
  );
}

function FilterPair({ title, footer, f, onChange }: { title: string; footer: string; f: SpeakerFilter; onChange: (f: SpeakerFilter) => void }) {
  const hpf = useMemo(() => bridge.soundHpf(), []);
  const lpf = useMemo(() => bridge.soundLpf(), []);
  return (
    <Group title={title} footer={footer}>
      <div className="snd-response">
        <Response f={f} />
        <div className="snd-scale" aria-hidden="true">{["20", "100", "500", "2k", "8k", "20k"].map((t) => <span key={t}>{t}</span>)}</div>
      </div>
      <Row label="High-Pass"><Switch label={`${title} high-pass`} on={f.highPass} onChange={(highPass) => onChange({ ...f, highPass })} /></Row>
      {f.highPass && <Row label="Cut Below"><Steps what={`${title} high-pass frequency`} values={hpf} value={f.highPassHz} format={hz} onChange={(highPassHz) => onChange({ ...f, highPassHz })} /></Row>}
      <Row label="Low-Pass"><Switch label={`${title} low-pass`} on={f.lowPass} onChange={(lowPass) => onChange({ ...f, lowPass })} /></Row>
      {f.lowPass && <Row label="Cut Above"><Steps what={`${title} low-pass frequency`} values={lpf} value={f.lowPassHz} format={hz} onChange={(lowPassHz) => onChange({ ...f, lowPassHz })} /></Row>}
    </Group>
  );
}

function Filters({ s }: { s: SoundSettings }) {
  return (<>
    <FilterPair title="Front Speakers" footer="A high-pass keeps the lows a door speaker cannot play out of it, so it plays louder without distorting." f={s.front} onChange={(front) => setSound({ ...s, front })} />
    <FilterPair title="Rear Speakers" footer="A low-pass on the rear keeps the highs in front, where the stereo image is." f={s.rear} onChange={(rear) => setSound({ ...s, rear })} />
  </>);
}

/** The sound window: every sound setting of the unit, one section at a time, over the app it was opened from. */
export function SoundApp({ open }: { open: boolean }) {
  const e = useEmulator();
  const [section, setSection] = useState<Section>("eq");
  const ref = useCloseOntoCard(open);
  const current = SECTIONS.find((x) => x.id === section)!;
  const s = e.shell.dsp;
  return (
    <section ref={ref} className={`music-app settings-app sound-app${open ? " is-open" : ""}`} aria-label="Sound" aria-hidden={!open} inert={!open}>
      <button className="glass-button music-close" aria-label="Close sound" onClick={() => shellAction({ type: "CloseSound" })}>
        <span className="icon i-caret-down" aria-hidden="true" />
      </button>
      <nav className="set-nav" aria-label="Sound sections">
        <h2 className="set-title">Sound</h2>
        <ul>
          {SECTIONS.map((x) => (
            <li key={x.id}>
              <button className={`set-nav-item${section === x.id ? " is-current" : ""}`} aria-current={section === x.id ? "page" : undefined} onClick={() => setSection(x.id)}>
                <span className="set-tile" style={{ background: x.tint }} aria-hidden="true"><span className={`icon ${x.icon}`} /></span>
                <span className="set-nav-label">{x.label}</span>
                <span className="set-nav-value">{summary(x.id, s)}</span>
              </button>
            </li>
          ))}
        </ul>
      </nav>
      <div className={`set-detail${section === "eq" ? " is-fit" : ""}`} key={section}>
        <h2 className="set-page-title">{current.label}</h2>
        {section === "eq" && <Equalizer s={s} />}
        {section === "balance" && <Balance s={s} />}
        {section === "field" && <Field s={s} />}
        {section === "loudness" && <Loudness s={s} />}
        {section === "bass" && <Bass s={s} />}
        {section === "filters" && <Filters s={s} />}
      </div>
    </section>
  );
}
