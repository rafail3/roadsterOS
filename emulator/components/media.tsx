// Shared pieces of the Bluetooth music card and the music app: playback position, artwork crossfade,
// a scrolling title, a progress line and the transport buttons.

import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { bridge } from "../car/bridge";
import { elapsedMs, useEmulator } from "../car/store";

const TICK_MS = 250;
/** Older layers go once the newest has faded in; this only cleans up when no animation runs (reduced motion). */
const CROSSFADE_LIMIT_MS = 3000;
/** How long play keeps its glyph squeezed while waiting for the phone to confirm. */
const SWAP_WAIT_MS = 600;
const MARQUEE_PX_PER_S = 30;
const MARQUEE_GAP = 1 / 3;
/** The cover is sampled this small, as the unit decodes it, before its edge colours are taken. */
const COVER_SAMPLE = 48;

type CoverColors = { top: string; bottom: string };
const coverCache = new Map<string, CoverColors>();

/** The two background colours of a cover, computed by the shared rules from a small decode of it. */
export function useCoverColors(src: string | null): CoverColors {
  const fallback = () => bridge.coverColors([], COVER_SAMPLE);
  const [colors, setColors] = useState<CoverColors>(() => (src && coverCache.get(src)) || fallback());
  useEffect(() => {
    if (!src) return setColors(fallback());
    const known = coverCache.get(src);
    if (known) return setColors(known);
    let live = true;
    const img = new Image();
    img.onload = () => {
      const canvas = document.createElement("canvas");
      canvas.width = canvas.height = COVER_SAMPLE;
      const context = canvas.getContext("2d")!;
      context.drawImage(img, 0, 0, COVER_SAMPLE, COVER_SAMPLE);
      const rgba = context.getImageData(0, 0, COVER_SAMPLE, COVER_SAMPLE).data;
      const argb: number[] = [];
      for (let i = 0; i < rgba.length; i += 4) argb.push((rgba[i + 3] << 24) | (rgba[i] << 16) | (rgba[i + 1] << 8) | rgba[i + 2]);
      const next = bridge.coverColors(argb, COVER_SAMPLE);
      coverCache.set(src, next);
      if (live) setColors(next);
    };
    img.src = src;
    return () => { live = false; };
  }, [src]);
  return colors;
}

export function time(ms: number) {
  const seconds = Math.max(0, Math.round(ms / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

/** The playback position, advanced locally while playing; the phone reports it only on changes. */
export function usePosition() {
  const emulator = useEmulator();
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!emulator.car.playing) return;
    const timer = setInterval(() => setTick((n) => n + 1), TICK_MS);
    return () => clearInterval(timer);
  }, [emulator.car.playing]);
  return { elapsed: elapsedMs(emulator), duration: emulator.track.durationMs };
}

/**
 * The current image over the previous ones. A layer under the newest is removed only when the newest
 * reports the end of its fade (`settle`), so the hand-over never cuts a fade short.
 */
export function useCrossfade(src: string | null) {
  const [layers, setLayers] = useState<{ src: string | null; id: number }[]>([{ src, id: 0 }]);
  const id = useRef(0);
  useEffect(() => {
    setLayers((current) => (current[current.length - 1].src === src ? current : [...current, { src, id: ++id.current }]));
    const timer = setTimeout(() => setLayers((current) => current.slice(-1)), CROSSFADE_LIMIT_MS);
    return () => clearTimeout(timer);
  }, [src]);
  const settle = (settled: number) => setLayers((current) => current.filter((layer) => layer.id >= settled));
  return { layers, settle };
}

/** One line of text that scrolls slowly when it does not fit, after a pause. */
export function Marquee({ text, className }: { text: string; className: string }) {
  const box = useRef<HTMLParagraphElement>(null);
  const [run, setRun] = useState<{ distance: number; seconds: number } | null>(null);
  useLayoutEffect(() => {
    const el = box.current!;
    const width = (el.firstElementChild as HTMLElement).offsetWidth;
    if (width <= el.clientWidth) return setRun(null);
    const distance = width + el.clientWidth * MARQUEE_GAP;
    setRun({ distance, seconds: distance / MARQUEE_PX_PER_S });
  }, [text]);
  const style = run ? ({ "--marquee-distance": `-${run.distance}px`, "--marquee-duration": `${run.seconds}s`, "--marquee-gap": `${MARQUEE_GAP * 100}%` } as React.CSSProperties) : undefined;
  return (
    <p className={`${className} marquee${run ? " is-running" : ""}`} ref={box} title={text}>
      <span className="marquee-text" style={style}>{text}</span>
      {run && <span className="marquee-text" style={style} aria-hidden="true">{text}</span>}
    </p>
  );
}

export function Progress({ elapsed, duration, className = "" }: { elapsed: number; duration: number; className?: string }) {
  const fraction = duration ? Math.min(1, elapsed / duration) : 0;
  return (
    <div className={`progress-line ${className}`} role="progressbar" aria-label="Track position" aria-valuemin={0} aria-valuemax={Math.round(duration / 1000)} aria-valuenow={Math.round(elapsed / 1000)} aria-valuetext={`${time(elapsed)} of ${time(duration)}`}>
      <div className="progress-line-fill" style={{ transform: `scaleX(${fraction})` }} />
    </div>
  );
}

type Kind = "prev" | "play" | "next";
const LABEL: Record<Kind, string> = { prev: "Previous", play: "Play", next: "Next" };

/** A filled glyph with no plate; pressing blooms a soft disc behind it and squeezes the glyph. */
export function TransportButton({ kind, playing = false, onPress }: { kind: Kind; playing?: boolean; onPress: () => void }) {
  const [nudge, setNudge] = useState(false);
  // play stays squeezed after release until the other glyph replaces it, so the swap reads as one motion
  const [waitingOn, setWaitingOn] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  const icon = kind === "prev" ? "i-rewind-fill" : kind === "next" ? "i-fast-forward-fill" : playing ? "i-pause-fill" : "i-play-fill";
  const press = () => {
    onPress();
    if (kind !== "play") return setNudge(true);
    setWaitingOn(icon);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setWaitingOn(null), SWAP_WAIT_MS);
  };
  return (
    <button
      className={`tx tx-${kind}${nudge ? " is-nudging" : ""}${waitingOn === icon ? " is-waiting" : ""}`}
      aria-label={kind === "play" ? (playing ? "Pause" : "Play") : LABEL[kind]}
      onClick={press}
      onAnimationEnd={(e) => { if (e.animationName.startsWith("tx-nudge")) setNudge(false); }}
    >
      {/* keyed so a play/pause swap pops the new glyph in */}
      <span key={icon} className={`icon tx-glyph ${icon}${kind === "play" && playing ? " is-delayed" : ""}`} aria-hidden="true" />
    </button>
  );
}

export function TransportRow({ playing, onPrev, onPlay, onNext, children }: { playing: boolean; onPrev: () => void; onPlay: () => void; onNext: () => void; children?: ReactNode }) {
  return (
    <div className="transport">
      <TransportButton kind="prev" onPress={onPrev} />
      <TransportButton kind="play" playing={playing} onPress={onPlay} />
      <TransportButton kind="next" onPress={onNext} />
      {children}
    </div>
  );
}

/**
 * A full-screen app leaving closes its outline onto the card on home; the card's edges are measured from layout
 * (not from the screen), so home's settle zoom does not skew them. The app's CSS reads --to-t/-l/-r/-b.
 */
export function useCloseOntoCard(open: boolean) {
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
  return section;
}
