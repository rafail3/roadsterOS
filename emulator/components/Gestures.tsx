import { useEffect, useRef, useState, type PointerEvent } from "react";
import { shellAction } from "../car/store";

// distances in canvas pixels (the screen is drawn at 0.8), times in milliseconds
/** A flick up from the handle shorter than this does nothing. */
const HOME_PX = 40;
/** Past this height a finger that stops for a moment brings up the open apps instead of going home. */
const RECENTS_PX = 80;
const RECENTS_HOLD_MS = 150;
/** A resting finger still drifts a little; drift under this does not restart the pause. */
const HOLD_SLOP_PX = 8;
/** Released past RECENTS_PX slower than this (canvas pixels per millisecond) is a slow push: open apps, not home. */
const FLICK_SPEED = 0.5;
/** A pull in from a side edge past this goes back. */
const BACK_PX = 72;

/** The pointer's travel in canvas pixels, whatever scale the screen is drawn at. */
function scaleOf(el: HTMLElement) {
  return el.getBoundingClientRect().height / el.offsetHeight || 1;
}

/** The handle along the bottom edge, as on a phone: flick up for home, push up and hold for the open apps. */
export function NavBar() {
  const start = useRef<{ y: number; scale: number } | null>(null);
  // where the finger was when the pause timer last restarted, and the last two moves for the release speed
  const anchor = useRef(0);
  const last = useRef<{ t: number; travel: number }[]>([]);
  const opened = useRef(false);
  const hold = useRef<ReturnType<typeof setTimeout>>(undefined);
  const [lift, setLift] = useState(0);
  useEffect(() => () => clearTimeout(hold.current), []);

  const travel = (e: PointerEvent<HTMLDivElement>) => {
    const s = start.current;
    return s ? (s.y - e.clientY) / s.scale : 0;
  };
  const openRecents = () => { opened.current = true; shellAction({ type: "OpenRecents" }); };
  const end = (e: PointerEvent<HTMLDivElement>) => {
    clearTimeout(hold.current);
    hold.current = undefined;
    if (start.current && !opened.current) {
      const t = travel(e);
      const [a, b] = last.current;
      const speed = a && b ? (b.travel - a.travel) / Math.max(1, b.t - a.t) : Infinity;
      if (t >= RECENTS_PX && speed < FLICK_SPEED) openRecents();
      else if (t >= HOME_PX) shellAction({ type: "Home" });
    }
    start.current = null;
    setLift(0);
  };

  return (
    <div
      className="nav-bar"
      aria-hidden="true"
      onPointerDown={(e) => {
        e.currentTarget.setPointerCapture(e.pointerId);
        start.current = { y: e.clientY, scale: scaleOf(e.currentTarget) };
        anchor.current = 0;
        last.current = [];
        opened.current = false;
      }}
      onPointerMove={(e) => {
        if (!start.current || opened.current) return;
        const t = travel(e);
        last.current = [...last.current, { t: e.timeStamp, travel: t }].slice(-2);
        setLift(Math.max(0, Math.min(t, RECENTS_PX)));
        if (t < RECENTS_PX) { clearTimeout(hold.current); hold.current = undefined; return; }
        // the timer fires once the finger rests; only real movement, not drift, restarts it
        if (hold.current === undefined || Math.abs(t - anchor.current) > HOLD_SLOP_PX) {
          clearTimeout(hold.current);
          anchor.current = t;
          hold.current = setTimeout(openRecents, RECENTS_HOLD_MS);
        }
      }}
      onPointerUp={end}
      onPointerCancel={() => { clearTimeout(hold.current); hold.current = undefined; start.current = null; setLift(0); }}
    >
      <span className="nav-handle" style={{ transform: `translateY(${-lift * 0.25}px)` }} />
    </div>
  );
}

/** A strip along one side edge: pulling in from it goes back, with the arrow phones show while the finger moves. */
export function EdgeBack({ side }: { side: "left" | "right" }) {
  const start = useRef<{ x: number; top: number; scale: number } | null>(null);
  const [pull, setPull] = useState<{ y: number; d: number } | null>(null);
  const armed = pull !== null && pull.d >= BACK_PX;

  const end = () => {
    if (armed) shellAction({ type: "Back" });
    start.current = null;
    setPull(null);
  };

  return (
    <div
      className={`edge-back edge-back-${side}`}
      aria-hidden="true"
      onPointerDown={(e) => {
        e.currentTarget.setPointerCapture(e.pointerId);
        const r = e.currentTarget.getBoundingClientRect();
        start.current = { x: e.clientX, top: r.top, scale: scaleOf(e.currentTarget) };
      }}
      onPointerMove={(e) => {
        const s = start.current;
        if (!s) return;
        const d = ((side === "left" ? e.clientX - s.x : s.x - e.clientX)) / s.scale;
        setPull({ y: (e.clientY - s.top) / s.scale, d: Math.max(0, d) });
      }}
      onPointerUp={end}
      onPointerCancel={() => { start.current = null; setPull(null); }}
    >
      {pull && (
        <span
          className={`edge-back-arrow${armed ? " is-armed" : ""}`}
          style={{ top: pull.y, transform: `translateX(${(side === "left" ? 1 : -1) * Math.min(pull.d, BACK_PX) * 0.5}px)`, opacity: Math.min(1, pull.d / BACK_PX) }}
        >
          <span className="icon i-caret-left" />
        </span>
      )}
    </div>
  );
}
