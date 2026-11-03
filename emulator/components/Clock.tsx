import { useLayoutEffect, useRef } from "react";
import type { Face } from "../car/bridge";
import { shellAction } from "../car/store";

const pad = (n: number) => String(n).padStart(2, "0");

/** Each character is its own cell; a changed one rolls in. Glyphs are managed by hand so the leaving one can finish its animation. */
function Digits({ text, dateTime }: { text: string; dateTime: string }) {
  const clock = useRef<HTMLTimeElement>(null);
  const shown = useRef("");

  useLayoutEffect(() => {
    const cells = clock.current!.querySelectorAll<HTMLElement>(".digit");
    text.split("").forEach((ch, i) => {
      const cell = cells[i];
      const current = cell.querySelector(".glyph:not(.out)");
      if (current?.textContent === ch) return;
      if (current && shown.current) {
        current.classList.remove("in");
        current.classList.add("out");
        const gone = () => current.remove();
        current.addEventListener("animationend", gone, { once: true });
        setTimeout(gone, 900);
      } else {
        current?.remove();
      }
      const next = document.createElement("span");
      next.className = "glyph" + (shown.current ? " in" : "");
      next.textContent = ch;
      next.addEventListener("animationend", () => next.classList.remove("in"), { once: true });
      cell.appendChild(next);
    });
    shown.current = text;
  }, [text]);

  return (
    <time className="clock" ref={clock} dateTime={dateTime}>
      <span className="digit" /><span className="digit" /><span className="digit colon" /><span className="digit" /><span className="digit" />
    </time>
  );
}

const TICKS = Array.from({ length: 60 }, (_, i) => {
  const angle = (i / 60) * Math.PI * 2;
  const point = (r: number) => ({
    x: (240 + Math.sin(angle) * r).toFixed(2),
    y: (240 - Math.cos(angle) * r).toFixed(2),
  });
  if (i % 15 === 0) {
    const c = point(222);
    return <circle key={i} cx={c.x} cy={c.y} r="3.5" />;
  }
  const major = i % 5 === 0;
  const from = point(major ? 212 : 216);
  const to = point(226);
  return <line key={i} x1={from.x} y1={from.y} x2={to.x} y2={to.y} className={major ? "major" : undefined} />;
});

function Dial({ now }: { now: Date }) {
  const seconds = now.getSeconds();
  const minutes = now.getMinutes() + seconds / 60;
  const hours = (now.getHours() % 12) + minutes / 60;
  return (
    <svg className="dial" viewBox="0 0 480 480" aria-hidden="true">
      <defs>
        <radialGradient id="dial-shade" cx="50%" cy="42%" r="60%">
          <stop offset="0" stopColor="var(--dial-hi)" />
          <stop offset="1" stopColor="var(--dial-lo)" />
        </radialGradient>
      </defs>
      <circle className="dial-face" cx="240" cy="240" r="228" fill="url(#dial-shade)" />
      <circle className="dial-inner" cx="240" cy="240" r="221" />
      <circle className="dial-rim" cx="240" cy="240" r="228" />
      <g className="ticks">{TICKS}</g>
      <g className="numerals">
        <text x="240" y="76">12</text>
        <text x="406" y="252">3</text>
        <text x="240" y="428">6</text>
        <text x="74" y="252">9</text>
      </g>
      <g className="hand hand-hour" style={{ transform: `rotate(${hours * 30}deg)` }}><rect x="235" y="120" width="10" height="150" rx="5" /></g>
      <g className="hand hand-minute" style={{ transform: `rotate(${minutes * 6}deg)` }}><rect x="236" y="60" width="8" height="212" rx="4" /></g>
      <g className="hand hand-second" style={{ transform: `rotate(${seconds * 6}deg)` }}><rect x="238.75" y="44" width="2.5" height="246" rx="1.25" /></g>
      <circle className="cap" cx="240" cy="240" r="9" />
      <circle className="cap-dot" cx="240" cy="240" r="3.5" />
    </svg>
  );
}

export function Clock({ now, face }: { now: Date; face: Face }) {
  const analog = face === "ANALOG";
  return (
    <button className="clock-block" aria-label="Clock, tap to switch face" aria-pressed={analog} onClick={() => shellAction({ type: "ToggleFace" })}>
      <div className="digital" hidden={analog}>
        <Digits text={`${pad(now.getHours())}:${pad(now.getMinutes())}`} dateTime={now.toISOString()} />
        <div className="date-card">
          <span className="dc-weekday">{now.toLocaleDateString("en-GB", { weekday: "short" })}</span>
          <span className="dc-day">{now.getDate()}</span>
          <span className="dc-month">{now.toLocaleDateString("en-GB", { month: "long" })}</span>
        </div>
      </div>
      <div className="analog" id="analog" hidden={!analog}>
        {analog && <Dial now={now} />}
      </div>
    </button>
  );
}
