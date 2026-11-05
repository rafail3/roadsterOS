import { useState, type CSSProperties, type PointerEvent as ReactPointerEvent, type ReactNode, type RefObject } from "react";
import { bridge } from "../car/bridge";
import { carCommand, carEvent, setVolume, shellAction, type EmulatorState } from "../car/store";
import { useMuteFill } from "./VolumeHud";

const THEME_LABEL = { AUTO: "Auto", LIGHT: "Day", DARK: "Night" };
const THEME_ICON = { AUTO: "i-circle-half", LIGHT: "i-sun", DARK: "i-moon" };
/** A press that moves less than this stays a tap, so the handle keeps working as a button. */
const DRAG_THRESHOLD = 6;

/** Pull the status bar down: the drawer follows the pointer; the release rule lives in Shell. */
export function useDrawerDrag(drawer: RefObject<HTMLElement | null>) {
  const [dragging, setDragging] = useState(false);

  // on window, because a fast pull leaves the bar before its first move event
  const onPointerDown = (down: ReactPointerEvent) => {
    const el = drawer.current!;
    const height = el.offsetHeight;
    // the device frame may be scaled to fit the window
    const scale = el.getBoundingClientRect().height / height;
    const distance = (e: PointerEvent) => (e.clientY - down.clientY) / scale;

    const move = (e: PointerEvent) => {
      if (distance(e) < DRAG_THRESHOLD) return;
      setDragging(true);
      el.style.transform = `translateY(${Math.min(height, distance(e)) - height}px)`;
    };
    const finish = (e: PointerEvent) => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", finish);
      window.removeEventListener("pointercancel", finish);
      setDragging(false);
      el.style.transform = "";
      if (distance(e) >= DRAG_THRESHOLD) shellAction({ type: "DrawerReleased", fraction: distance(e) / height });
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", finish);
    window.addEventListener("pointercancel", finish);
  };

  return { dragging, onPointerDown };
}

const BRIGHTNESS_KEY_STEP = 5;
const KEY_DIRECTION: Record<string, number> = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1 };

/**
 * A thick track filled from the left, dragged anywhere along it. The icon sits inside the filled end,
 * so any value above the minimum keeps it on the fill; the readout stays outside, on the drawer.
 */
function PillSlider({ className, label, min, max, step, value, valueText, onValue, icon, readout, fillStyle }: {
  className?: string; label: string; min: number; max: number; step: number; value: number; valueText: string;
  onValue: (value: number) => void; icon: ReactNode; readout: ReactNode; fillStyle?: CSSProperties;
}) {
  const [held, setHeld] = useState(false);
  const fraction = (value - min) / (max - min);
  // the strip right of the icon maps the range, the same strip the fill grows along
  const valueAt = (e: ReactPointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    onValue(Math.round(min + Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)) * (max - min)));
  };
  const release = () => setHeld(false);
  return (
    <div className={`pill-slider${held ? " is-held" : ""}${fraction > 0 ? " is-filled" : ""}${className ? ` ${className}` : ""}`}>
      <div className="pill">
        <div className="pill-fill" style={{ "--fill": fraction.toFixed(4), ...fillStyle } as CSSProperties} />
        {icon}
        <div
          className="pill-track"
          role="slider"
          tabIndex={0}
          aria-label={label}
          aria-valuemin={min}
          aria-valuemax={max}
          aria-valuenow={value}
          aria-valuetext={valueText}
          onPointerDown={(e) => { setHeld(true); e.currentTarget.setPointerCapture(e.pointerId); valueAt(e); }}
          onPointerMove={(e) => { if (held) valueAt(e); }}
          onPointerUp={release}
          onPointerCancel={release}
          onKeyDown={(e) => {
            const direction = KEY_DIRECTION[e.key];
            if (!direction) return;
            e.preventDefault();
            onValue(Math.min(max, Math.max(min, value + direction * step)));
          }}
        />
      </div>
      {readout}
    </div>
  );
}

export function Drawer({ emulator, panel, dragging }: { emulator: EmulatorState; panel: RefObject<HTMLElement | null>; dragging: boolean }) {
  const { car, shell } = emulator;
  const open = shell.drawer;
  const muteFill = useMuteFill(car.muted);
  return (
    <section
      ref={panel}
      className={`drawer${open ? " is-open" : ""}${dragging ? " is-dragging" : ""}`}
      id="drawer"
      role="dialog"
      aria-modal="true"
      aria-label="Quick settings"
      inert={!open && !dragging}
    >
      <div className="drawer-grid">
        <button className={`toggle${car.btConnected ? " is-on" : ""}`} aria-pressed={car.btConnected} onClick={() => carEvent({ type: "BtConnection", connected: !car.btConnected, device: "Phone" })}>
          <span className="icon i-bluetooth" /><span>Bluetooth</span>
        </button>
        <button className="toggle is-on" aria-pressed="true"><span className="icon i-wifi-high" /><span>Wi-Fi</span></button>
        <button className={`toggle${car.muted ? " is-on" : ""}`} aria-pressed={car.muted} onClick={() => carCommand({ type: "Audio.Mute", on: !car.muted })}>
          <span className="icon i-speaker-slash" /><span>Mute</span>
        </button>
        <button className={`toggle${shell.theme === "AUTO" ? " is-on" : ""}`} aria-label={`Theme: ${THEME_LABEL[shell.theme]}, tap to change`} onClick={() => shellAction({ type: "CycleTheme" })}>
          <span className={`icon ${THEME_ICON[shell.theme]}`} /><span>{THEME_LABEL[shell.theme]}</span>
        </button>
      </div>
      <PillSlider
        className="slider-light"
        label="Brightness"
        min={bridge.minBrightness()}
        max={bridge.maxBrightness()}
        step={BRIGHTNESS_KEY_STEP}
        value={shell.brightness}
        valueText={`${shell.brightness} percent`}
        onValue={(value) => shellAction({ type: "SetBrightness", value })}
        icon={<span className="pill-icon"><span className="icon i-sun-fill" aria-hidden="true" /></span>}
        readout={<button className="pill-readout" aria-label={`Brightness ${shell.brightness} percent, tap to reset to 100`} onClick={() => shellAction({ type: "SetBrightness", value: 100 })}>{shell.brightness}%</button>}
      />
      <PillSlider
        className={car.muted ? "is-muted" : undefined}
        label="Volume"
        min={0}
        max={bridge.maxVolume()}
        step={1}
        value={car.muted ? 0 : car.volume}
        valueText={car.muted ? "Muted" : String(car.volume)}
        onValue={setVolume}
        fillStyle={muteFill}
        icon={
          <button className="pill-icon" aria-label={car.muted ? "Sound on" : "Mute"} aria-pressed={car.muted} onClick={() => carCommand({ type: "Audio.Mute", on: !car.muted })}>
            <span className={`icon ${car.muted ? "i-speaker-slash-fill" : "i-speaker-high-fill"}`} aria-hidden="true" />
          </button>
        }
        readout={<span className="pill-readout" aria-hidden="true">{car.muted ? "Muted" : car.volume}</span>}
      />
      <button className="handle handle-bottom" aria-label="Close quick settings" onClick={() => shellAction({ type: "CloseDrawer" })}><span className="handle-line" /></button>
    </section>
  );
}
