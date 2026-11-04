import type { MouseEvent, PointerEvent, ReactNode } from "react";

// A held volume key repeats as the unit's MCU repeats it: again after about 270 ms, then every 210 ms (measured).
const FIRST_REPEAT_MS = 270;
const REPEAT_MS = 210;
// ponytail: one timer for the whole page, one finger holds one key at a time
let repeatTimer: ReturnType<typeof setTimeout> | undefined;

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
