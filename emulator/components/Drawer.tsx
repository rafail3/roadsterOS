import { useState, type PointerEvent as ReactPointerEvent, type RefObject } from "react";
import { bridge } from "../car/bridge";
import { carCommand, carEvent, setVolume, shellAction, type EmulatorState } from "../car/store";

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

export function Drawer({ emulator, panel, dragging }: { emulator: EmulatorState; panel: RefObject<HTMLElement | null>; dragging: boolean }) {
  const { car, shell } = emulator;
  const open = shell.drawer;
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
      {/* not a label: pressing the reset button inside one would count as holding the slider */}
      <div className="slider slider-light">
        <span className="icon i-sun-dim" />
        <input type="range" min={bridge.minBrightness()} max={bridge.maxBrightness()} value={shell.brightness} aria-label="Brightness" aria-valuetext={`${shell.brightness} percent`} onChange={(e) => shellAction({ type: "SetBrightness", value: Number(e.target.value) })} />
        <button className="slider-readout" aria-label={`Brightness ${shell.brightness} percent, tap to reset to 100`} onClick={() => shellAction({ type: "SetBrightness", value: 100 })}>{shell.brightness}%</button>
      </div>
      <label className="slider">
        <span className="icon i-speaker-high" />
        <input type="range" min="0" max={bridge.maxVolume()} value={car.volume} aria-label="Volume" aria-valuetext={`${car.volume}`} onChange={(e) => setVolume(Number(e.target.value))} />
        <span className="slider-readout">{car.volume}</span>
      </label>
      <button className="handle handle-bottom" aria-label="Close quick settings" onClick={() => shellAction({ type: "CloseDrawer" })}><span className="handle-line" /></button>
    </section>
  );
}
