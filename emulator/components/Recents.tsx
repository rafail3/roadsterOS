import { useRef, useState, type PointerEvent } from "react";
import type { App } from "../car/bridge";
import { shellAction, type EmulatorState } from "../car/store";

/** Our own apps as the open-apps screen shows them; each new screen of the shell adds its entry here. */
const APP_META: Record<Exclude<App, "HOME">, { label: string; icon: string }> = {
  MUSIC: { label: "Music", icon: "i-music-notes" },
};
/** A card pushed up past this many pixels, or flicked up, is closed. */
const CLOSE_DISTANCE = 120;
const CLOSE_SPEED = 0.8;
const TAP_SLOP = 6;
/** The fly-up of a closed card, as in .recents-card.is-leaving. */
const LEAVE_MS = 220;

function Preview({ app, emulator }: { app: App; emulator: EmulatorState }) {
  if (app === "MUSIC") {
    const { track } = emulator;
    return (
      <span className="recents-preview recents-preview-music">
        {track.art
          ? <img src={track.art} alt="" />
          : <span className="recents-preview-empty"><span className="icon i-music-notes" /></span>}
        <span className="recents-preview-meta">
          <span className="recents-preview-title">{track.title}</span>
          <span className="recents-preview-sub">{track.artist}</span>
        </span>
      </span>
    );
  }
  return null;
}

function Card({ app, emulator }: { app: Exclude<App, "HOME">; emulator: EmulatorState }) {
  const meta = APP_META[app];
  const [dy, setDy] = useState(0);
  const [leaving, setLeaving] = useState(false);
  const drag = useRef<{ y: number; t: number; lastY: number; lastT: number } | null>(null);
  // the card flies up, then the app closes; a timer rather than transitionend, which never comes with reduced motion off-screen
  const close = () => {
    setDy(0);
    setLeaving(true);
    setTimeout(() => shellAction({ type: "CloseApp", app }), LEAVE_MS);
  };

  const down = (e: PointerEvent<HTMLButtonElement>) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = { y: e.clientY, t: e.timeStamp, lastY: e.clientY, lastT: e.timeStamp };
  };
  const move = (e: PointerEvent<HTMLButtonElement>) => {
    const d = drag.current;
    if (!d) return;
    d.lastY = e.clientY;
    d.lastT = e.timeStamp;
    // only upward travel moves the card; downward is resisted
    const travel = e.clientY - d.y;
    setDy(travel < 0 ? travel : travel / 6);
  };
  const up = (e: PointerEvent<HTMLButtonElement>) => {
    const d = drag.current;
    drag.current = null;
    if (!d) return;
    const travel = e.clientY - d.y;
    const speed = -travel / Math.max(1, e.timeStamp - d.t);
    if (travel < -CLOSE_DISTANCE || (travel < -TAP_SLOP * 4 && speed > CLOSE_SPEED)) return close();
    setDy(0);
    if (Math.abs(travel) < TAP_SLOP) shellAction({ type: "OpenApp", app });
  };

  return (
    <li
      className={`recents-card${leaving ? " is-leaving" : ""}${dy !== 0 ? " is-dragging" : ""}`}
      style={leaving ? undefined : { transform: `translateY(${dy}px)`, opacity: Math.max(0.3, 1 + dy / 400) }}
    >
      <div className="recents-card-head">
        <span className={`icon ${meta.icon}`} aria-hidden="true" />
        <span>{meta.label}</span>
        <button className="recents-card-close" aria-label={`Close ${meta.label}`} onClick={close}>
          <span className="icon i-x" aria-hidden="true" />
        </button>
      </div>
      <button
        className="recents-card-body"
        aria-label={`Open ${meta.label}`}
        onPointerDown={down}
        onPointerMove={move}
        onPointerUp={up}
        onPointerCancel={() => { drag.current = null; setDy(0); }}
      >
        <Preview app={app} emulator={emulator} />
      </button>
    </li>
  );
}

/** How far a finger travels up from the bottom edge before the open apps come up, in canvas pixels. */
const EDGE_OPEN_PX = 80;

/** The strip along the bottom edge: a swipe up from it opens the open apps over whatever is on screen. */
export function EdgeSwipe() {
  const start = useRef<{ y: number; scale: number } | null>(null);
  const end = () => { start.current = null; };
  return (
    <div
      className="edge-swipe"
      aria-hidden="true"
      onPointerDown={(e) => {
        e.currentTarget.setPointerCapture(e.pointerId);
        start.current = { y: e.clientY, scale: e.currentTarget.getBoundingClientRect().height / e.currentTarget.offsetHeight || 1 };
      }}
      onPointerMove={(e) => {
        const s = start.current;
        if (!s || (s.y - e.clientY) / s.scale < EDGE_OPEN_PX) return;
        end();
        shellAction({ type: "OpenRecents" });
      }}
      onPointerUp={end}
      onPointerCancel={end}
    />
  );
}

/** Our apps open right now, most recent first: tap one to return to it, push it up to close it. */
export function Recents({ emulator }: { emulator: EmulatorState }) {
  const { shell } = emulator;
  const apps = shell.running.filter((app): app is Exclude<App, "HOME"> => app !== "HOME");
  return (
    <section
      className={`recents${shell.recents ? " is-open" : ""}`}
      role="dialog"
      aria-modal="true"
      aria-label="Open apps"
      inert={!shell.recents}
      onClick={(e) => { if (e.target === e.currentTarget) shellAction({ type: "CloseRecents" }); }}
    >
      {apps.length > 0 ? (
        <>
          <ul className="recents-list" onClick={(e) => { if (e.target === e.currentTarget) shellAction({ type: "CloseRecents" }); }}>
            {apps.map((app) => <Card key={app} app={app} emulator={emulator} />)}
          </ul>
          <button className="recents-clear" onClick={() => shellAction({ type: "CloseAllApps" })}>Close all</button>
        </>
      ) : (
        <p className="recents-empty">No open apps</p>
      )}
    </section>
  );
}
