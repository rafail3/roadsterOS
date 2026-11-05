import { useEffect, useRef, useState, type PointerEvent } from "react";
import { shellAction } from "../car/store";

// distances in canvas pixels (the screen is drawn at 0.8), times in milliseconds
/** A flick up from the handle shorter than this does nothing. */
const HOME_PX = 40;
/** Past this height a finger that stops for a moment brings up the open apps instead of going home. */
const RECENTS_PX = 80;
const RECENTS_HOLD_MS = 150;
/** A pull in from a side edge past this goes back. */
const BACK_PX = 72;

/** The pointer's travel in canvas pixels, whatever scale the screen is drawn at. */
function scaleOf(el: HTMLElement) {
  return el.getBoundingClientRect().height / el.offsetHeight || 1;
}

/** The handle along the bottom edge, as on a phone: flick up for home, push up and hold for the open apps. */
export function NavBar() {
  const start = useRef<{ y: number; scale: number } | null>(null);
  const opened = useRef(false);
  const hold = useRef<ReturnType<typeof setTimeout>>(undefined);
  const [lift, setLift] = useState(0);
  useEffect(() => () => clearTimeout(hold.current), []);

  const travel = (e: PointerEvent<HTMLDivElement>) => {
    const s = start.current;
    return s ? (s.y - e.clientY) / s.scale : 0;
  };
  const end = (e: PointerEvent<HTMLDivElement>) => {
    clearTimeout(hold.current);
    if (start.current && !opened.current && travel(e) >= HOME_PX) shellAction({ type: "Home" });
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
        opened.current = false;
      }}
      onPointerMove={(e) => {
        if (!start.current) return;
        const t = travel(e);
        setLift(Math.max(0, Math.min(t, RECENTS_PX)));
        clearTimeout(hold.current);
        // pointer moves stop while the finger rests, so a timer that every move restarts fires on the pause
        if (t >= RECENTS_PX && !opened.current) {
          hold.current = setTimeout(() => { opened.current = true; shellAction({ type: "OpenRecents" }); }, RECENTS_HOLD_MS);
        }
      }}
      onPointerUp={end}
      onPointerCancel={() => { clearTimeout(hold.current); start.current = null; setLift(0); }}
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
