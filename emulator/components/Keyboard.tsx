import { useRef, useState } from "react";
import { holdToRepeat } from "./controls";

// digits stay on top of both layers, so a password with numbers never needs a layer switch
const DIGITS = "1234567890";
const LETTERS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"];
const SYMBOLS = ["-/:;()$&@\"", "[]{}#%^*+=", ".,?!'_~"];
type Layer = "letters" | "symbols";
/** Two Shift taps this close lock capitals on. */
const CAPS_LOCK_MS = 350;

function Key({ char, onPress, wide }: { char: string; onPress: (c: string) => void; wide?: boolean }) {
  const [down, setDown] = useState(false);
  return (
    <button
      type="button"
      className={`kb-key${wide ? " is-wide" : ""}${down ? " is-down" : ""}`}
      data-char={char}
      onPointerDown={() => setDown(true)}
      onPointerUp={() => setDown(false)}
      onPointerLeave={() => setDown(false)}
      onPointerCancel={() => setDown(false)}
      onClick={() => onPress(char)}
    >
      {char}
    </button>
  );
}

/**
 * Our own on-screen keyboard: letters with Shift and caps lock, numbers, symbols, a held backspace that repeats,
 * and a submit key that names the action. It types into whatever field the screen shows above it.
 */
export function Keyboard({ open, onKey, onBackspace, onSubmit, submitLabel, submitEnabled, symbols = true }: {
  open: boolean; onKey: (c: string) => void; onBackspace: () => void; onSubmit: () => void; submitLabel: string; submitEnabled: boolean;
  /** false: letters and digits only, no symbols layer (the car's name) */
  symbols?: boolean;
}) {
  const [layer, setLayer] = useState<Layer>("letters");
  const [shift, setShift] = useState<"off" | "once" | "lock">("off");
  const lastShift = useRef(0);
  const rows = layer === "letters" ? LETTERS : SYMBOLS;

  const type = (c: string) => {
    onKey(layer === "letters" && shift !== "off" ? c.toUpperCase() : c);
    if (shift === "once") setShift("off");
  };
  const pressShift = () => {
    const now = performance.now();
    if (shift === "lock") setShift("off");
    else if (shift === "once" && now - lastShift.current < CAPS_LOCK_MS) setShift("lock");
    else setShift(shift === "off" ? "once" : "off");
    lastShift.current = now;
  };
  const shown = (c: string) => (layer === "letters" && shift !== "off" ? c.toUpperCase() : c);

  return (
    <div className={`keyboard${open ? " is-open" : ""}`} aria-hidden={!open} inert={!open} role="group" aria-label="Keyboard">
      <div className="kb-row kb-row-digits">{[...DIGITS].map((c) => <Key key={c} char={c} onPress={onKey} />)}</div>
      <div className="kb-row">{[...rows[0]].map((c) => <Key key={c} char={shown(c)} onPress={type} />)}</div>
      <div className={`kb-row${layer === "letters" ? " kb-row-inset" : ""}`}>{[...rows[1]].map((c) => <Key key={c} char={shown(c)} onPress={type} />)}</div>
      <div className="kb-row">
        {layer === "letters" ? (
          <button type="button" className={`kb-key kb-fn${shift !== "off" ? " is-on" : ""}${shift === "lock" ? " is-locked" : ""}`} aria-label={shift === "lock" ? "Caps lock on" : "Shift"} aria-pressed={shift !== "off"} onClick={pressShift}>
            <span className="icon i-arrow-fat-up" aria-hidden="true" />
          </button>
        ) : (
          // the symbols layer has no Shift; the slot stays so the row keeps its shape
          <span className="kb-key kb-fn kb-spacer" aria-hidden="true" />
        )}
        <div className="kb-row-keys">{[...rows[2]].map((c) => <Key key={c} char={shown(c)} onPress={type} wide={layer !== "letters"} />)}</div>
        <button type="button" className="kb-key kb-fn" aria-label="Delete" {...holdToRepeat(onBackspace)}>
          <span className="icon i-backspace" aria-hidden="true" />
        </button>
      </div>
      <div className="kb-row">
        {symbols && <button type="button" className="kb-key kb-fn kb-layer" onClick={() => setLayer(layer === "letters" ? "symbols" : "letters")}>{layer === "letters" ? "#+=" : "ABC"}</button>}
        <button type="button" className="kb-key kb-space" onClick={() => onKey(" ")}>space</button>
        <button type="button" className="kb-key kb-fn kb-submit" disabled={!submitEnabled} onClick={onSubmit}>{submitLabel}</button>
      </div>
    </div>
  );
}
