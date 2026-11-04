import type { ReactNode } from "react";

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
