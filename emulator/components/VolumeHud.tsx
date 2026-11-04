import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { bridge } from "../car/bridge";
import { carCommand, setVolume } from "../car/store";

const VISIBLE_MS = 1500;
/** Muting drains the bar and unmuting refills it to the old level, slower than a single step. */
const MUTE_FILL_MS = 420;
const reducedMotion = () => matchMedia("(prefers-reduced-motion: reduce)").matches;
const KEY_STEP: Record<string, number> = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1 };

/** Shown after every volume change, or when opened from the status bar; stays while a finger holds it. */
/** `quiet` while a screen shows its own volume control, which then moves instead. */
export function VolumeHud({ volume, muted, opened, quiet = false }: { volume: number; muted: boolean; opened: number; quiet?: boolean }) {
  const max = bridge.maxVolume();
  const [shown, setShown] = useState(false);
  const holding = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  // compared by value: a first render, or a repeated mount in development, is not a change
  const last = useRef({ volume, muted, opened });
  const [shaking, setShaking] = useState(false);
  const [muteChange, setMuteChange] = useState(false);
  const muteTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const wasMuted = useRef(muted);

  const poke = () => {
    setShown(true);
    clearTimeout(timer.current);
    if (!holding.current) timer.current = setTimeout(() => setShown(false), VISIBLE_MS);
  };

  useEffect(() => {
    const before = last.current;
    last.current = { volume, muted, opened };
    if (before.volume === volume && before.muted === muted && before.opened === opened) return;
    if (before.muted !== muted) {
      setMuteChange(true);
      clearTimeout(muteTimer.current);
      muteTimer.current = setTimeout(() => setMuteChange(false), MUTE_FILL_MS);
    }
    if (!quiet) poke();
  }, [volume, muted, opened]);
  useEffect(() => () => { clearTimeout(timer.current); clearTimeout(muteTimer.current); }, []);
  useEffect(() => {
    if (muted && !wasMuted.current) setShaking(true);
    wasMuted.current = muted;
  }, [muted]);

  // the whole strip between the icon and the number is the touch target, not only the thin track
  const dragTo = (e: PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    setVolume(Math.round(Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)) * max));
  };
  const release = () => { holding.current = false; poke(); };
  const onKey = (e: KeyboardEvent) => {
    if (!KEY_STEP[e.key]) return;
    e.preventDefault();
    setVolume(volume + KEY_STEP[e.key]);
  };

  return (
    <div className={`volume-hud${shown ? " is-shown" : ""}${muted ? " is-muted" : ""}${shaking ? " is-shaking" : ""}`} inert={!shown} onAnimationEnd={(e) => { if (e.target === e.currentTarget) setShaking(false); }}>
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
        <div className="volume-track"><div className="volume-fill" style={{ width: `${muted ? 0 : (volume / max) * 100}%`, transitionDuration: muteChange && !reducedMotion() ? `${MUTE_FILL_MS}ms` : undefined }} /></div>
      </div>
      <span className="volume-num" aria-hidden="true">{muted ? "Muted" : volume}</span>
    </div>
  );
}
