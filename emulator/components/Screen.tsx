import { useEffect, useRef, useState } from "react";
import { bridge, type Source } from "../car/bridge";
import { carCommand, carEvent, shellAction, useEmulator, useNow } from "../car/store";
import { Clock } from "./Clock";
import { Drawer, useDrawerDrag } from "./Drawer";
import { NowPlaying } from "./NowPlaying";
import { CallOverlay, CameraOverlay } from "./Overlays";
import { VolumeHud } from "./VolumeHud";

const THEME_FADE_MS = 750;
const MAX_VEIL = 0.78;
const VEIL_PER_DIM = 0.85;

const RAIL = [
  { id: "music", label: "Music", icon: "i-music-notes" },
  { id: "radio", label: "Radio", icon: "i-radio" },
  { id: "phone", label: "Phone", icon: "i-phone" },
  { id: "camera", label: "Camera", icon: "i-camera" },
  { id: "settings", label: "Settings", icon: "i-gear-six" },
];
const APPS = [
  { label: "Navigation", mark: "app-mark-nav" },
  { label: "Music", mark: "app-mark-music" },
  { label: "Podcasts", mark: "app-mark-pod" },
];
const SOURCE_LABEL: Record<Source, string> = { BT: "Bluetooth", RADIO: "Radio", NONE: "No source" };

function classes(...names: (string | false)[]) {
  return names.filter(Boolean).join(" ");
}

/** True for the length of the crossfade after the theme changes, set in the same commit as the new theme. */
function useThemeFade(theme: string) {
  const [shown, setShown] = useState(theme);
  const [fading, setFading] = useState(false);
  if (theme !== shown) {
    setShown(theme);
    setFading(true);
  }
  useEffect(() => {
    if (!fading) return;
    const timer = setTimeout(() => setFading(false), THEME_FADE_MS);
    return () => clearTimeout(timer);
  }, [fading, shown]);
  return fading;
}

function pressRail(id: string, cameraOn: boolean) {
  if (id === "music") carCommand({ type: "Audio.Source", source: "BT" });
  if (id === "radio") carCommand({ type: "Audio.Source", source: "RADIO" });
  if (id === "camera") carEvent({ type: "ReverseGear", on: !cameraOn });
}

export function Screen() {
  const emulator = useEmulator();
  const { car, shell } = emulator;
  const now = useNow(emulator.hour);
  const theme = bridge.effectiveTheme(shell, now.getHours());
  const light = theme === "LIGHT";
  const fading = useThemeFade(theme);
  const drawer = useRef<HTMLElement>(null);
  const { dragging, onPointerDown } = useDrawerDrag(drawer);
  const covered = shell.drawer || car.call !== "IDLE" || car.camera;
  const [volumeOpened, setVolumeOpened] = useState(0);
  const veil = Math.max(0, Math.min(MAX_VEIL, (1 - shell.brightness / 100) * VEIL_PER_DIM));

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      shellAction({ type: "CloseDrawer" });
      if (car.camera) carEvent({ type: "ReverseGear", on: false });
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [car.camera]);

  return (
    <div className={classes("screen", light && "light", shell.font === "geist" && "font-geist", fading && "theming")}>
      <div className="ambient" aria-hidden="true" />

      <header className="bar" inert={covered} onPointerDown={onPointerDown}>
        <div className="bar-group">
          <span
            className={classes("icon", car.source === "RADIO" ? "i-radio" : "i-bluetooth", car.source !== "NONE" && "is-on", car.source !== "RADIO" && !car.btConnected && "is-off")}
            aria-hidden="true"
          />
          <span className="bar-text">{car.source === "NONE" && car.btConnected ? "Bluetooth" : SOURCE_LABEL[car.source]}</span>
        </div>
        <button className="handle" aria-label="Open quick settings" aria-controls="drawer" aria-expanded={shell.drawer} onClick={() => shellAction({ type: "OpenDrawer" })}>
          <span className="handle-line" />
        </button>
        <div className="bar-group">
          <span className="icon i-wifi-high" role="img" aria-label="Wi-Fi connected" />
          <button className="bar-button" aria-label={car.muted ? "Volume, muted" : "Volume"} onClick={() => setVolumeOpened((n) => n + 1)}>
            <span className={`icon ${car.muted ? "i-speaker-slash is-off" : "i-speaker-high"}`} aria-hidden="true" />
          </button>
          <span className={`icon ${light ? "i-sun" : "i-moon"}`} id="bar-theme" role="img" aria-label={light ? "Day theme" : "Night theme"} />
        </div>
      </header>

      <nav className="rail" aria-label="Functions" inert={covered}>
        {RAIL.map(({ id, label, icon }) => {
          const active = (car.source === "BT" && id === "music") || (car.source === "RADIO" && id === "radio");
          return (
            <button key={id} className={classes("rail-item", active && "is-active")} aria-current={active ? "page" : undefined} onClick={() => pressRail(id, car.camera)}>
              <span className={`icon ${icon}`} /><span>{label}</span>
            </button>
          );
        })}
      </nav>

      <section className="field" inert={covered}>
        <Clock now={now} face={shell.face} />
        <div className="apps" aria-label="Apps">
          {APPS.map(({ label, mark }) => (
            <button key={label} className="app"><span className={`app-mark ${mark}`} /><span>{label}</span></button>
          ))}
        </div>
      </section>

      <section className="panel" aria-label="Now playing" inert={covered}>
        <NowPlaying emulator={emulator} />
      </section>

      <div className={classes("scrim", shell.drawer && "is-open")} onClick={() => shellAction({ type: "CloseDrawer" })} />
      <Drawer emulator={emulator} panel={drawer} dragging={dragging} />
      {car.call !== "IDLE" && <CallOverlay emulator={emulator} />}
      {car.camera && <CameraOverlay />}
      <VolumeHud volume={car.volume} muted={car.muted} opened={volumeOpened} />
      <div className="dimmer" aria-hidden="true" style={{ opacity: veil.toFixed(3) }} />
      {!shell.screenOn && <button className="screen-off" aria-label="Screen off, tap to wake" onClick={() => shellAction({ type: "Wake" })} />}
    </div>
  );
}
