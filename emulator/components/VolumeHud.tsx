import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { bridge } from "../car/bridge";
import { carCommand, setVolume } from "../car/store";

const VISIBLE_MS = 1500;
const KEY_STEP: Record<string, number> = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1 };

/** Shown after every volume change, or when opened from the status bar; stays while a finger holds it. */
export function VolumeHud({ volume, muted, opened }: { volume: number; muted: boolean; opened: number }) {
  const max = bridge.maxVolume();
  const [shown, setShown] = useState(false);
  const holding = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const first = useRef(true);

  const poke = () => {
    setShown(true);
    clearTimeout(timer.current);
    if (!holding.current) timer.current = setTimeout(() => setShown(false), VISIBLE_MS);
  };

  useEffect(() => {
    if (first.current) { first.current = false; return; }
    poke();
  }, [volume, muted, opened]);
  useEffect(() => () => clearTimeout(timer.current), []);

  // the whole strip between the icon and the number is the touch target, not only the thin track
  const dragTo = (e: PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    if (muted) carCommand({ type: "Audio.Mute", on: false });
    setVolume(Math.round(Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)) * max));
  };
  const release = () => { holding.current = false; poke(); };
  const onKey = (e: KeyboardEvent) => {
    if (!KEY_STEP[e.key]) return;
    e.preventDefault();
    setVolume(volume + KEY_STEP[e.key]);
  };

  return (
    <div className={`volume-hud${shown ? " is-shown" : ""}`} inert={!shown}>
      <button className="volume-mute" aria-label={muted ? "Sound on" : "Mute"} aria-pressed={muted} onClick={() => carCommand({ type: "Audio.Mute", on: !muted })}>
        <span className={`icon ${muted ? "i-speaker-slash" : "i-speaker-high"}`} aria-hidden="true" />
      </button>
      <div
        className="volume-slider"
        role="slider"
        tabIndex={0}
        aria-label="Volume"
        aria-valuemin={0}
        aria-valuemax={max}
        aria-valuenow={volume}
        aria-valuetext={muted ? "Muted" : String(volume)}
        onPointerDown={(e) => { holding.current = true; e.currentTarget.setPointerCapture(e.pointerId); dragTo(e); poke(); }}
        onPointerMove={(e) => { if (holding.current) dragTo(e); }}
        onPointerUp={release}
        onPointerCancel={release}
        onKeyDown={onKey}
      >
        <div className="volume-track"><div className="volume-fill" style={{ width: `${muted ? 0 : (volume / max) * 100}%` }} /></div>
      </div>
      <span className="volume-num" aria-hidden="true">{muted ? "Muted" : volume}</span>
    </div>
  );
}
