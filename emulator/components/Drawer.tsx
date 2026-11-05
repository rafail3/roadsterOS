import { useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent, type ReactNode, type RefObject } from "react";
import { bridge } from "../car/bridge";
import { carCommand, setBluetooth, setLocation, setVolume, setWifi, shellAction, type EmulatorState } from "../car/store";
import { useMuteFill } from "./VolumeHud";

const THEME_LABEL = { AUTO: "Auto", LIGHT: "Day", DARK: "Night" };
const THEME_ICON = { AUTO: "i-circle-half", LIGHT: "i-sun-fill", DARK: "i-moon-fill" };
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

/** Held this long, a toggle opens its details instead of switching. */
const LONG_PRESS_MS = 500;

/** A quick toggle: an icon disc, its name and its state; the disc takes the function's colour while on. */
function Toggle({ kind, on, icon, title, state, onClick, onLongPress }: { kind: string; on: boolean; icon: string; title: string; state: string; onClick: () => void; onLongPress?: () => void }) {
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const held = useRef(false);
  const cancel = () => clearTimeout(timer.current);
  return (
    <button
      className={`toggle toggle-${kind}${on ? " is-on" : ""}`}
      aria-pressed={on}
      aria-description={onLongPress ? "Hold for details" : undefined}
      onPointerDown={() => {
        held.current = false;
        if (onLongPress) timer.current = setTimeout(() => { held.current = true; onLongPress(); }, LONG_PRESS_MS);
      }}
      onPointerUp={cancel}
      onPointerLeave={cancel}
      onPointerCancel={cancel}
      // the click that ends a long press is not a tap
      onClick={() => { if (held.current) held.current = false; else onClick(); }}
    >
      <span className="toggle-disc"><span className={`icon ${icon}`} aria-hidden="true" /></span>
      <span className="toggle-text">
        <span className="toggle-title">{title}</span>
        <span className="toggle-state">{state}</span>
      </span>
    </button>
  );
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
        <Toggle kind="bt" on={emulator.btOn} icon="i-bluetooth" title="Bluetooth" state={!emulator.btOn ? "Off" : car.btConnected ? emulator.device : "On"} onClick={() => setBluetooth(!emulator.btOn)} onLongPress={() => shellAction({ type: "OpenDevices" })} />
        <Toggle kind="wifi" on={emulator.wifiOn} icon="i-wifi-high" title="Wi-Fi" state={!emulator.wifiOn ? "Off" : emulator.wifi.connected ?? "On"} onClick={() => setWifi(!emulator.wifiOn)} onLongPress={() => shellAction({ type: "OpenNetworks" })} />
        <Toggle kind="location" on={emulator.locationOn} icon="i-navigation-arrow-fill" title="Location" state={emulator.locationOn ? "On" : "Off"} onClick={() => setLocation(!emulator.locationOn)} />
        <Toggle kind="mute" on={car.muted} icon="i-speaker-slash-fill" title="Mute" state={car.muted ? "On" : "Off"} onClick={() => carCommand({ type: "Audio.Mute", on: !car.muted })} />
        <Toggle kind="theme" on={shell.theme === "AUTO"} icon={THEME_ICON[shell.theme]} title="Theme" state={THEME_LABEL[shell.theme]} onClick={() => shellAction({ type: "CycleTheme" })} />
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
