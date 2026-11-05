import { useEffect, useRef, useState } from "react";
import { bridge, type Source } from "../car/bridge";
import { carCommand, carEvent, openVolume, setBluetooth, shellAction, useEmulator, useNow } from "../car/store";
import { Clock } from "./Clock";
import { Drawer, useDrawerDrag } from "./Drawer";
import { NowPlaying } from "./NowPlaying";
import { CallOverlay, CameraOverlay } from "./Overlays";
import { VolumeHud } from "./VolumeHud";
import { MusicApp } from "./MusicApp";
import { Recents } from "./Recents";
import { EdgeBack, NavBar } from "./Gestures";
import { useCoverColors } from "./media";
import { useFingerMouse } from "./controls";

const THEME_FADE_MS = 750;

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
// ponytail: centres of the volume keys on this frame model, in screen pixels; another frame needs its own
// centres of the frame's volume keys, in canvas units (the screen is drawn at 0.8)
const KEY_CENTER = { VOL_UP: 520, VOL_DOWN: 645 } as const;
// leaves the edge along it, rises through a long S-curve, runs flat, and returns the same way
const BULGE_PATH = "M0 0C0 22 12 26 12 48V88C12 110 0 114 0 136Z";

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
  if (id === "music") {
    setBluetooth(true);
    carCommand({ type: "Audio.Source", source: "BT" });
    shellAction({ type: "OpenApp", app: "MUSIC" });
  }
  if (id === "radio") carCommand({ type: "Audio.Source", source: "RADIO" });
  if (id === "camera") carEvent({ type: "ReverseGear", on: !cameraOn });
}

export function Screen() {
  const finger = useFingerMouse<HTMLDivElement>();
  const emulator = useEmulator();
  const { car, shell } = emulator;
  const now = useNow(emulator.hour);
  const theme = bridge.effectiveTheme(shell, now.getHours());
  const light = theme === "LIGHT";
  const fading = useThemeFade(theme);
  const drawer = useRef<HTMLElement>(null);
  const { dragging, onPointerDown } = useDrawerDrag(drawer);
  const inApp = shell.app !== "HOME";
  const cover = useCoverColors(car.source === "BT" ? emulator.track.art : null);
  // while Bluetooth music plays, the home glow takes the cover's colours, softer by day
  const glow = car.source === "BT" && emulator.track.art
    ? ({ "--ambient-a": `color-mix(in srgb, ${cover.top} ${light ? 30 : 85}%, transparent)`, "--ambient-b": `color-mix(in srgb, ${cover.bottom} ${light ? 25 : 90}%, transparent)` } as React.CSSProperties)
    : undefined;
  const covered = shell.drawer || shell.recents || car.call !== "IDLE" || car.camera;
  const veil = bridge.veil(shell);
  // above neutral the whole screen goes through one colour filter; none at all otherwise
  const boost = veil.brightness > 1 ? { filter: `brightness(${veil.brightness.toFixed(3)}) saturate(${veil.saturation.toFixed(3)})` } : undefined;

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
    <div ref={finger} className={classes("screen", light && "light", shell.font === "geist" && "font-geist", fading && "theming", inApp && "in-app")} style={{ ...glow, ...boost }}>
      <div className="ambient" aria-hidden="true" />

      <header className="bar" inert={covered} onPointerDown={onPointerDown}>
        <div className="bar-group">
          <span
            className={classes("icon", car.source === "RADIO" ? "i-radio" : "i-bluetooth", car.source !== "NONE" && "is-on", car.source !== "RADIO" && !emulator.btOn && "is-off")}
            aria-hidden="true"
          />
          <span className="bar-text">{car.source === "NONE" && car.btConnected ? "Bluetooth" : SOURCE_LABEL[car.source]}</span>
        </div>
        <button className="handle" aria-label="Open quick settings" aria-controls="drawer" aria-expanded={shell.drawer} onClick={() => shellAction({ type: "OpenDrawer" })}>
          <span className="handle-line" />
        </button>
        <div className="bar-group">
          <span className="icon i-wifi-high" role="img" aria-label="Wi-Fi connected" />
          <button className="bar-button" aria-label={car.muted ? "Volume, muted" : "Volume"} onClick={openVolume}>
            <span className={`icon ${car.muted ? "i-speaker-slash-fill is-muted" : "i-speaker-high-fill"}`} aria-hidden="true" />
          </button>
          <span className={`icon ${light ? "i-sun-fill" : "i-moon-fill"}`} id="bar-theme" role="img" aria-label={light ? "Day theme" : "Night theme"} />
        </div>
      </header>

      <nav className="rail" aria-label="Functions" inert={covered || inApp}>
        {RAIL.map(({ id, label, icon }) => {
          const active = (car.source === "BT" && id === "music") || (car.source === "RADIO" && id === "radio");
          return (
            <button key={id} className={classes("rail-item", active && "is-active")} aria-current={active ? "page" : undefined} onClick={() => pressRail(id, car.camera)}>
              <span className={`icon ${icon}`} /><span>{label}</span>
            </button>
          );
        })}
      </nav>

      <section className="field" inert={covered || inApp}>
        <Clock now={now} face={shell.face} />
        <div className="apps" aria-label="Apps">
          {APPS.map(({ label, mark }) => (
            <button key={label} className="app" onClick={label === "Music" ? () => shellAction({ type: "OpenApp", app: "MUSIC" }) : undefined}>
              <span className={`app-mark ${mark}`} /><span>{label}</span>
            </button>
          ))}
        </div>
      </section>

      <section className="panel" aria-label="Now playing" inert={covered || inApp}>
        <NowPlaying emulator={emulator} />
      </section>

      <MusicApp open={shell.app === "MUSIC"} />
      <Recents emulator={emulator} />
      {/* always there, as on a phone, except under the drawer, a call or the camera */}
      {!shell.drawer && car.call === "IDLE" && !car.camera && (
        <>
          <NavBar />
          <EdgeBack side="left" />
          <EdgeBack side="right" />
        </>
      )}
      <div className={classes("scrim", shell.drawer && "is-open")} onClick={() => shellAction({ type: "CloseDrawer" })} />
      <Drawer emulator={emulator} panel={drawer} dragging={dragging} />
      {car.call !== "IDLE" && <CallOverlay emulator={emulator} />}
      {car.camera && <CameraOverlay />}
      {(Object.keys(KEY_CENTER) as (keyof typeof KEY_CENTER)[]).map((key) => (
        <svg key={key} className={classes("edge-bulge", emulator.pressedKey === key && "is-pressed")} style={{ top: KEY_CENTER[key] }} viewBox="0 0 12 136" preserveAspectRatio="none" aria-hidden="true">
          <path d={BULGE_PATH} />
        </svg>
      ))}
      <VolumeHud volume={car.volume} muted={car.muted} opened={emulator.volumeOpened} quiet={shell.app === "MUSIC" || shell.drawer} />
      <div className="dimmer" aria-hidden="true" style={{ opacity: veil.dark.toFixed(3) }} />
      {!shell.screenOn && <button className="screen-off" aria-label="Screen off, tap to wake" onClick={() => shellAction({ type: "Wake" })} />}
    </div>
  );
}
