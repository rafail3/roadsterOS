import { useEffect, useRef, useState } from "react";
import type { AnimationSpeed } from "../car/bridge";

/** The playback rate a speed gives every transition and animation on the screen; Off finishes them at once. */
const RATE: Record<AnimationSpeed, number> = { NORMAL: 1, FAST: 2, OFF: 1000 };

/**
 * The Developer section's tools on our screens: a dot under each finger, the frame rate in a corner, and every
 * animation sped up or skipped (Shell.animationScale on the unit). Listens on the screen it sits in.
 */
export function DevOverlays({ touches, fps, speed }: { touches: boolean; fps: boolean; speed: AnimationSpeed }) {
  const ref = useRef<HTMLDivElement>(null);
  const [dots, setDots] = useState<Record<number, { x: number; y: number }>>({});
  const [rate, setRate] = useState(0);

  useEffect(() => {
    const screen = ref.current?.parentElement;
    if (!touches || !screen) return;
    // the screen is drawn scaled: pointer pixels to the screen's own
    const at = (e: PointerEvent) => {
      const r = screen.getBoundingClientRect();
      const k = screen.offsetWidth / r.width;
      return { x: (e.clientX - r.left) * k, y: (e.clientY - r.top) * k };
    };
    const down = (e: PointerEvent) => setDots((d) => ({ ...d, [e.pointerId]: at(e) }));
    const move = (e: PointerEvent) => setDots((d) => (e.pointerId in d ? { ...d, [e.pointerId]: at(e) } : d));
    const up = (e: PointerEvent) => setDots((d) => {
      const rest = { ...d };
      delete rest[e.pointerId];
      return rest;
    });
    const events: [string, (e: PointerEvent) => void][] = [["pointerdown", down], ["pointermove", move], ["pointerup", up], ["pointercancel", up]];
    events.forEach(([name, fn]) => screen.addEventListener(name, fn as EventListener, true));
    return () => {
      events.forEach(([name, fn]) => screen.removeEventListener(name, fn as EventListener, true));
      setDots({});
    };
  }, [touches]);

  useEffect(() => {
    if (!fps) return;
    let frames = 0;
    let since = performance.now();
    let id = 0;
    const tick = (t: number) => {
      frames++;
      if (t - since >= 1000) {
        setRate(Math.round((frames * 1000) / (t - since)));
        frames = 0;
        since = t;
      }
      id = requestAnimationFrame(tick);
    };
    id = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(id);
  }, [fps]);

  useEffect(() => {
    const screen = ref.current?.parentElement;
    if (!screen || speed === "NORMAL") return;
    const faster = (e: Event) => (e.target as Element).getAnimations?.().forEach((a) => { a.playbackRate = RATE[speed]; });
    // the ones already running too, and back to normal when the speed changes again
    const running = (rate: number) => screen.getAnimations({ subtree: true }).forEach((a) => { a.playbackRate = rate; });
    running(RATE[speed]);
    screen.addEventListener("transitionrun", faster, true);
    screen.addEventListener("animationstart", faster, true);
    return () => {
      screen.removeEventListener("transitionrun", faster, true);
      screen.removeEventListener("animationstart", faster, true);
      running(1);
    };
  }, [speed]);

  return (
    <div ref={ref} className="dev-overlays" aria-hidden="true">
      {touches && Object.entries(dots).map(([id, p]) => <span key={id} className="dev-touch" style={{ left: p.x, top: p.y }} />)}
      {fps && <span className="dev-fps">{rate} fps</span>}
    </div>
  );
}
