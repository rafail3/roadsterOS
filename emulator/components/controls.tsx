import { useEffect, useRef, type MouseEvent, type PointerEvent, type ReactNode } from "react";

// A held volume key repeats as the unit's MCU repeats it: again after about 270 ms, then every 210 ms (measured).
const FIRST_REPEAT_MS = 270;
const REPEAT_MS = 210;
// ponytail: one timer for the whole page, one finger holds one key at a time
let repeatTimer: ReturnType<typeof setTimeout> | undefined;

// The mouse stands in for a finger on the unit's touchscreen: a drag scrolls whatever list it starts on and glides
// out after release, images are not dragged, and there is no context menu. Touch already behaves so natively.
const SCROLL_INTENT_PX = 8;
const GLIDE_DECAY = 0.94;
const GLIDE_STOP = 0.2;

/** The nearest element between the press and the screen root that can scroll vertically right now. */
function scrollerAt(from: EventTarget | null, root: HTMLElement): HTMLElement | null {
  for (let el = from instanceof HTMLElement ? from : null; el && el !== root; el = el.parentElement) {
    const { overflowY } = getComputedStyle(el);
    if ((overflowY === "auto" || overflowY === "scroll") && el.scrollHeight > el.clientHeight) return el;
  }
  return null;
}

export function useFingerMouse<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  useEffect(() => {
    const root = ref.current;
    if (!root) return;
    let drag: { el: HTMLElement; x: number; y: number; top: number; vertical: boolean | null; lastY: number; lastT: number; speed: number; scale: number } | null = null;
    let frame = 0;
    let swallow = false;
    const glide = (el: HTMLElement, speed: number) => {
      // speed in list pixels per frame, slowing a little each frame until it settles
      const step = () => {
        el.scrollTop -= speed;
        speed *= GLIDE_DECAY;
        if (Math.abs(speed) > GLIDE_STOP) frame = requestAnimationFrame(step);
      };
      frame = requestAnimationFrame(step);
    };
    const down = (e: globalThis.PointerEvent) => {
      if (e.pointerType !== "mouse" || e.button !== 0) return;
      cancelAnimationFrame(frame);
      swallow = false;
      const el = scrollerAt(e.target, root);
      drag = el && { el, x: e.clientX, y: e.clientY, top: el.scrollTop, vertical: null, lastY: e.clientY, lastT: e.timeStamp, speed: 0, scale: el.getBoundingClientRect().height / el.offsetHeight || 1 };
    };
    const move = (e: globalThis.PointerEvent) => {
      if (!drag) return;
      if (e.buttons === 0) return up();
      const dx = e.clientX - drag.x;
      const dy = e.clientY - drag.y;
      if (drag.vertical === null) {
        if (Math.abs(dy) > SCROLL_INTENT_PX && Math.abs(dy) > Math.abs(dx)) drag.vertical = true;
        else if (Math.abs(dx) > SCROLL_INTENT_PX) drag.vertical = false;
      }
      if (!drag.vertical) return;
      drag.el.scrollTop = drag.top - dy / drag.scale;
      const dt = Math.max(1, e.timeStamp - drag.lastT);
      drag.speed = ((e.clientY - drag.lastY) / drag.scale / dt) * 16;
      drag.lastY = e.clientY;
      drag.lastT = e.timeStamp;
    };
    const up = () => {
      if (drag?.vertical) {
        swallow = true;
        glide(drag.el, drag.speed);
      }
      drag = null;
    };
    // the click at the end of a scroll drag is not a tap on what lies under the pointer
    const click = (e: globalThis.MouseEvent) => {
      if (!swallow) return;
      swallow = false;
      e.preventDefault();
      e.stopPropagation();
    };
    const block = (e: Event) => e.preventDefault();
    root.addEventListener("pointerdown", down);
    root.addEventListener("click", click, true);
    root.addEventListener("dragstart", block);
    root.addEventListener("contextmenu", block);
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", up);
    return () => {
      cancelAnimationFrame(frame);
      root.removeEventListener("pointerdown", down);
      root.removeEventListener("click", click, true);
      root.removeEventListener("dragstart", block);
      root.removeEventListener("contextmenu", block);
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", up);
    };
  }, []);
  return ref;
}

/** Press handlers that send once on press and keep sending while the pointer stays down. */
export function holdToRepeat(send: () => void, onHold?: (down: boolean) => void) {
  const stop = () => { clearTimeout(repeatTimer); onHold?.(false); };
  const tick = (delay: number) => { repeatTimer = setTimeout(() => { send(); tick(REPEAT_MS); }, delay); };
  return {
    onPointerDown: (e: PointerEvent) => { if (e.button !== 0) return; clearTimeout(repeatTimer); onHold?.(true); send(); tick(FIRST_REPEAT_MS); },
    onPointerUp: stop,
    onPointerLeave: stop,
    onPointerCancel: stop,
    // Enter and Space still press the key once; a pointer press already sent on pointer down
    onClick: (e: MouseEvent) => { if (e.detail === 0) send(); },
  };
}

export function Section({ title, open, children }: { title: string; open?: boolean; children: ReactNode }) {
  return (
    <details className="section" open={open}>
      <summary><h2>{title}</h2><span className="icon i-caret-down" aria-hidden="true" /></summary>
      <div className="section-body">{children}</div>
    </details>
  );
}

export function Switch({ label, checked, onChange }: { label: string; checked: boolean; onChange: (on: boolean) => void }) {
  return (
    <label className="switch">
      <span>{label}</span>
      <input type="checkbox" role="switch" checked={checked} onChange={(e) => onChange(e.target.checked)} />
    </label>
  );
}

export function Field({ label, value, onChange, type = "text", step, min, max }: { label: string; value: string; onChange: (value: string) => void; type?: string; step?: string; min?: string; max?: string }) {
  return (
    <label className="field-input">
      <span>{label}</span>
      <input type={type} step={step} min={min} max={max} value={value} spellCheck={false} autoComplete="off" onChange={(e) => onChange(e.target.value)} />
    </label>
  );
}

interface Option<T> {
  value: T;
  label: string;
}

export function Segmented<T extends string>({ label, options, value, onChange }: { label: string; options: readonly Option<T>[]; value: T; onChange: (value: T) => void }) {
  return (
    <div className="segmented" role="group" aria-label={label}>
      {options.map((option) => (
        <button key={option.value} aria-pressed={option.value === value} onClick={() => onChange(option.value)}>{option.label}</button>
      ))}
    </div>
  );
}
