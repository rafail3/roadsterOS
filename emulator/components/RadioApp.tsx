import { useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from "react";
import { bridge, type Band } from "../car/bridge";
import { BANDS, formatFreq, stationAt } from "../car/radio";
import { carCommand, shellAction, toggleFavorite, useEmulator } from "../car/store";
import { TransportRow } from "./media";

/** Each programme type family has its colour; the screen and the station dots take it. */
const GENRE_COLOR: [number[], string][] = [
  [[1, 2, 3, 16, 17, 21, 22, 29], "#0a84ff"],
  [[4], "#30d158"],
  [[10], "#ff375f"],
  [[11], "#ff9f0a"],
  [[13, 14], "#bf5af2"],
  [[24, 27], "#ffd60a"],
  [[25, 26, 28], "#40c8e0"],
  [[5, 6, 7, 8, 9, 12, 15, 18, 19, 20, 23], "#5e5ce6"],
];
export const genreColor = (pty: number) => GENRE_COLOR.find(([codes]) => codes.includes(pty))?.[1] ?? "#8e8e93";

/** Dial pixels per tuner step, and where the ticks and labels fall, per band. */
const DIAL: Record<Band, { px: number; minorEvery: number; majorEvery: number; firstMajor: number }> = {
  FM: { px: 6, minorEvery: 2, majorEvery: 20, firstMajor: 10 },
  AM: { px: 20, minorEvery: 1, majorEvery: 10, firstMajor: 0 },
};

/** The tuning scale under a fixed needle: drag it to tune, it snaps to the band's step; arrows step, page keys seek. */
/** `onPreview` reports the frequency under the needle while a finger drags it, null once it lets go. */
function Dial({ band, khz, stations, onPreview }: { band: Band; khz: number; stations: number[]; onPreview: (khz: number | null) => void }) {
  const { min, max, step } = BANDS[band];
  const dial = DIAL[band];
  const steps = (max - min) / step;
  const box = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<{ x: number; from: number; at: number; scale: number } | null>(null);
  const shown = drag?.at ?? khz;
  const offset = ((shown - min) / step) * dial.px;

  const down = (e: PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    e.currentTarget.setPointerCapture(e.pointerId);
    setDrag({ x: e.clientX, from: khz, at: khz, scale: r.width / e.currentTarget.offsetWidth });
  };
  const move = (e: PointerEvent<HTMLDivElement>) => {
    if (!drag) return;
    const moved = Math.round((drag.x - e.clientX) / drag.scale / dial.px);
    const at = Math.min(max, Math.max(min, drag.from + moved * step));
    setDrag({ ...drag, at });
    onPreview(at);
  };
  const up = () => {
    if (drag && drag.at !== khz) carCommand({ type: "Radio.Tune", khz: drag.at });
    setDrag(null);
    onPreview(null);
  };
  const key = (e: KeyboardEvent) => {
    const by = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1 }[e.key];
    if (by) { e.preventDefault(); carCommand({ type: "Radio.Tune", khz: Math.min(max, Math.max(min, khz + by * step)) }); }
    if (e.key === "PageUp" || e.key === "PageDown") { e.preventDefault(); carCommand({ type: "Radio.Seek", up: e.key === "PageUp" }); }
  };

  const labels = [];
  for (let i = dial.firstMajor; i <= steps; i += dial.majorEvery) labels.push(min + i * step);
  const strip = {
    width: steps * dial.px + 1,
    transform: `translateX(${-offset}px)`,
    "--minor": `${dial.minorEvery * dial.px}px`,
    "--major": `${dial.majorEvery * dial.px}px`,
    "--first-major": `${dial.firstMajor * dial.px}px`,
  } as CSSProperties;

  return (
    <div
      ref={box}
      className={`dial${drag ? " is-dragging" : ""}`}
      role="slider"
      tabIndex={0}
      aria-label="Tuning"
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={shown}
      aria-valuetext={`${formatFreq(band, shown)} ${band === "FM" ? "MHz" : "kHz"}`}
      onPointerDown={down}
      onPointerMove={move}
      onPointerUp={up}
      onPointerCancel={() => { setDrag(null); onPreview(null); }}
      onKeyDown={key}
    >
      <div className="dial-strip" style={strip}>
        {labels.map((f) => (
          <span key={f} className="dial-label" style={{ left: ((f - min) / step) * dial.px }}>{band === "FM" ? f / 1000 : f}</span>
        ))}
        {stations.map((f) => (
          <span key={f} className="dial-station" style={{ left: ((f - min) / step) * dial.px, background: genreColor(stationAt(band, f)?.pty ?? 0) }} />
        ))}
      </div>
      <span className="dial-needle" aria-hidden="true" />
    </div>
  );
}

function Star({ on, label, onToggle }: { on: boolean; label: string; onToggle: () => void }) {
  return (
    <button className={`radio-star${on ? " is-on" : ""}`} aria-label={label} aria-pressed={on} onClick={onToggle}>
      <span className={`icon ${on ? "i-star-fill" : "i-star"}`} aria-hidden="true" />
    </button>
  );
}

function StationRow({ band, khz, active, favorite }: { band: Band; khz: number; active: boolean; favorite: boolean }) {
  const station = stationAt(band, khz);
  const genre = bridge.ptyName(station?.pty ?? 0);
  const freq = formatFreq(band, khz);
  const name = station?.ps || freq;
  return (
    <li className={`radio-row${active ? " is-active" : ""}`} style={{ "--genre": genreColor(station?.pty ?? 0) } as CSSProperties}>
      <button className="radio-row-tune" aria-current={active ? "true" : undefined} onClick={() => carCommand({ type: "Radio.Tune", khz })}>
        {active ? <span className="radio-live" aria-hidden="true"><i /><i /><i /></span> : <span className="radio-row-dot" aria-hidden="true" />}
        <span className="radio-row-text">
          <span className="radio-row-name">{name}</span>
          <span className="radio-row-sub">{station?.ps ? [freq, genre].filter(Boolean).join(" · ") : genre || (band === "FM" ? "MHz" : "kHz")}</span>
        </span>
      </button>
      <Star on={favorite} label={favorite ? `Remove ${name} from favorites` : `Add ${name} to favorites`} onToggle={() => toggleFavorite(khz)} />
    </li>
  );
}

/** Full-screen radio: the station large over its genre's colour, the tuning dial, and the stations the last scan found. */
export function RadioApp({ open }: { open: boolean }) {
  const { car, radio, stations, favorites, radioScan } = useEmulator();
  const { band, khz } = radio;
  const genre = bridge.ptyName(radio.pty);
  const found = stations[band];
  // a starred frequency stays listed even when the last scan did not find it
  const mine = favorites.filter((f) => f >= BANDS[band].min && f <= BANDS[band].max);
  const others = found.filter((f) => !favorites.includes(f));
  const [preview, setPreview] = useState<number | null>(null);
  const scanning = radioScan !== null;
  const favorite = favorites.includes(khz);

  // leaving, the app's outline closes onto its card on home, as the music app does
  const section = useRef<HTMLElement>(null);
  useLayoutEffect(() => {
    const app = section.current;
    const card = app?.parentElement?.querySelector<HTMLElement>(".panel");
    if (!app || !card) return;
    const { offsetLeft: l, offsetTop: t, offsetWidth: w, offsetHeight: h } = card;
    app.style.setProperty("--to-t", `${t}px`);
    app.style.setProperty("--to-l", `${l}px`);
    app.style.setProperty("--to-r", `${app.offsetWidth - l - w}px`);
    app.style.setProperty("--to-b", `${app.offsetHeight - t - h}px`);
  }, [open]);

  const rows = (list: number[]) => list.map((f) => <StationRow key={f} band={band} khz={f} active={f === khz && !scanning} favorite={favorites.includes(f)} />);

  return (
    <section ref={section} className={`music-app radio-app${open ? " is-open" : ""}${car.muted ? " is-muted" : ""}`} aria-label="Radio" aria-hidden={!open} inert={!open} style={{ "--genre": genreColor(radio.pty) } as CSSProperties}>
      <button className="glass-button music-close" aria-label="Close radio" onClick={() => shellAction({ type: "Home" })}>
        <span className="icon i-caret-down" aria-hidden="true" />
      </button>

      <div className="radio-main">
        <div className="radio-hero">
          <p className="radio-tags">
            <span className="radio-tag is-band">{band}</span>
            {scanning ? <span className="radio-tag">Scanning</span> : radio.stereo && <span className="radio-tag">Stereo</span>}
          </p>
          <p className="radio-freq">
            <span className="radio-freq-num">{formatFreq(band, preview ?? khz)}</span>
            <span className="radio-freq-unit">{band === "FM" ? "MHz" : "kHz"}</span>
          </p>
          <div className="radio-station" aria-live="polite">
            {/* keyed by the name, so a new station's name fades in instead of jumping */}
            {/* stations without RDS, and every frequency a seek or scan passes, have no name: the row stays empty */}
            {radio.rds && <p className="radio-name" key={radio.rds}>{radio.rds}</p>}
            {genre && <span className="radio-genre" key={genre}>{genre}</span>}
          </div>
          <div className="radio-actions">
            <TransportRow
              playing={!car.muted}
              onPrev={() => carCommand({ type: "Radio.Seek", up: false })}
              onPlay={() => carCommand({ type: "Audio.Mute", on: !car.muted })}
              onNext={() => carCommand({ type: "Radio.Seek", up: true })}
            />
            <Star on={favorite} label={favorite ? "Remove from favorites" : "Add to favorites"} onToggle={() => toggleFavorite(khz)} />
          </div>
        </div>
        <Dial band={band} khz={khz} stations={found} onPreview={setPreview} />
      </div>

      <aside className="radio-list" aria-label="Stations">
        <header className="radio-list-head">
          <div className="radio-bands" role="group" aria-label="Band">
            {(["FM", "AM"] as Band[]).map((b) => (
              <button key={b} aria-pressed={b === band} onClick={() => b !== band && carCommand({ type: "Radio.Band", band: b })}>{b}</button>
            ))}
          </div>
          <button className="radio-scan" disabled={scanning} onClick={() => carCommand({ type: "Radio.Scan" })}>
            <span className={`icon i-magnifying-glass${scanning ? " is-searching" : ""}`} aria-hidden="true" />
            {scanning ? `${formatFreq(band, radioScan)}` : "Scan"}
          </button>
        </header>
        <div className="radio-list-scroll">
          {mine.length > 0 && (<>
            <h3 className="radio-section">Favorites</h3>
            <ul className="radio-rows">{rows(mine)}</ul>
          </>)}
          {(others.length > 0 || scanning) && (<>
            <h3 className="radio-section">{mine.length ? "Other stations" : "Stations"}</h3>
            <ul className="radio-rows">{rows(others)}</ul>
          </>)}
          {found.length === 0 && mine.length === 0 && !scanning && <p className="radio-empty">No stations found. Scan to look again.</p>}
        </div>
      </aside>
    </section>
  );
}
