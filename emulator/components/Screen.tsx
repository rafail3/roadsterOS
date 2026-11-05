import { useEffect, useRef, useState } from "react";
import { bridge, type Source } from "../car/bridge";
import { carCommand, carEvent, openPhone, openVolume, setBluetooth, shellAction, useEmulator, useNow, WIFI_NETWORKS } from "../car/store";
import { Clock } from "./Clock";
import { Drawer, useDrawerDrag } from "./Drawer";
import { HomeCard } from "./HomeCard";
import { CallBar, CallOverlay, CameraOverlay, MessageBanner, VoiceOverlay } from "./Overlays";
import { VolumeHud } from "./VolumeHud";
import { ConnectionNotice } from "./ConnectionNotice";
import { MusicApp } from "./MusicApp";
import { RadioApp } from "./RadioApp";
import { MapsApp } from "./MapsApp";
import { PhoneApp, PhoneNetwork, useUnread } from "./PhoneApp";
import { SettingsApp } from "./SettingsApp";
import { MapOffer } from "./MapOffer";
import { DevicesSheet } from "./DevicesSheet";
import { NetworksSheet } from "./NetworksSheet";
import { LocationPrompt } from "./LocationPrompt";
import { WifiBars } from "./WifiBars";
import { Recents } from "./Recents";
import { EdgeBack, NavBar } from "./Gestures";
import { useCoverColors } from "./media";
import { useFingerMouse } from "./controls";

const THEME_FADE_MS = 750;

const RAIL = [
  { id: "music", label: "Music", icon: "i-music-notes" },
  { id: "radio", label: "Radio", icon: "i-radio" },
  { id: "phone", label: "Phone", icon: "i-phone" },
  { id: "maps", label: "Maps", icon: "i-navigation-arrow" },
  { id: "camera", label: "Camera", icon: "i-camera" },
];
/** The app icons on home and the app each opens. */
const APPS: { label: string; mark: string; app: "MAPS" | "MUSIC" | "PHONE" }[] = [
  { label: "Maps", mark: "app-mark-nav", app: "MAPS" },
  { label: "Music", mark: "app-mark-music", app: "MUSIC" },
  { label: "Phone", mark: "app-mark-phone", app: "PHONE" },
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

/** How long the screen takes to come on: the black lifts while the content settles from a slight zoom. */
const WAKE_MS = 650;

/** True while the screen is coming on: at start-up, and every time it is switched back on. */
function useWaking(on: boolean) {
  const [waking, setWaking] = useState(true);
  const [shown, setShown] = useState(on);
  if (on !== shown) {
    setShown(on);
    if (on) setWaking(true);
  }
  useEffect(() => {
    if (!waking) return;
    const timer = setTimeout(() => setWaking(false), WAKE_MS);
    return () => clearTimeout(timer);
  }, [waking]);
  return waking && on;
}

function pressRail(id: string, cameraOn: boolean) {
  if (id === "music") {
    setBluetooth(true);
    carCommand({ type: "Audio.Source", source: "BT" });
    shellAction({ type: "OpenApp", app: "MUSIC" });
  }
  if (id === "radio") {
    carCommand({ type: "Audio.Source", source: "RADIO" });
    shellAction({ type: "OpenApp", app: "RADIO" });
  }
  if (id === "maps") shellAction({ type: "OpenApp", app: "MAPS" });
  if (id === "phone") openPhone();
  if (id === "camera") carEvent({ type: "ReverseGear", on: !cameraOn });
}

export function Screen() {
  const finger = useFingerMouse<HTMLDivElement>();
  const emulator = useEmulator();
  const unread = useUnread(emulator);
  const { car, shell } = emulator;
  const now = useNow(emulator.hour);
  const theme = bridge.effectiveTheme(shell, now.getHours());
  const light = theme === "LIGHT";
  const fading = useThemeFade(theme);
  const waking = useWaking(shell.screenOn);
  const drawer = useRef<HTMLElement>(null);
  const { dragging, onPointerDown } = useDrawerDrag(drawer);
  const inApp = shell.app !== "HOME";
  const cover = useCoverColors(car.source === "BT" ? emulator.track.art : null);
  // while Bluetooth music plays, the home glow takes the cover's colours, softer by day
  const glow = car.source === "BT" && emulator.track.art
    ? ({ "--ambient-a": `color-mix(in srgb, ${cover.top} ${light ? 30 : 85}%, transparent)`, "--ambient-b": `color-mix(in srgb, ${cover.bottom} ${light ? 25 : 90}%, transparent)` } as React.CSSProperties)
    : undefined;
  // the call covers the screen only while it is shown whole; folded into its bar, the shell under it works
  const callFull = car.call !== "IDLE" && shell.callView === "FULL";
  const covered = shell.drawer || shell.recents || callFull || car.camera;
  const veil = bridge.veil(shell);
  const card = bridge.homeCard(car, emulator.btOn, shell.card);
  // a new source brings its page forward on the card
  const lastSource = useRef(car.source);
  useEffect(() => {
    if (lastSource.current === car.source) return;
    lastSource.current = car.source;
    if (card.forSource) shellAction({ type: "ShowCard", page: card.forSource });
  }, [car.source, card.forSource]);
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
    <div ref={finger} className={classes("screen", light && "light", shell.font === "geist" && "font-geist", fading && "theming", inApp && "in-app", shell.app === "MAPS" && "in-maps", waking && "waking")} style={{ ...glow, ...boost }}>
      <div className="ambient" aria-hidden="true" />

      <header className="bar" inert={covered} onPointerDown={onPointerDown}>
        <div className="bar-group">
          {/* first, the connected phone's network and battery, as it reports them over the hands-free link */}
          {car.hfpConnected && <PhoneNetwork status={emulator.phoneStatus} />}
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
          {/* the bars of the network the unit is on; all faint when on none */}
          {(() => {
            const network = WIFI_NETWORKS.find((n) => n.ssid === emulator.wifi.connected);
            return (
              <span role="img" aria-label={network ? `Wi-Fi, ${network.ssid}` : emulator.wifiOn ? "Wi-Fi, not connected" : "Wi-Fi off"} className={network ? undefined : "is-off"}>
                <WifiBars level={network?.signal ?? 0} className="bar-wifi" />
              </span>
            );
          })()}
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
        <Clock now={now} face={shell.face} h24={shell.clock24h} />
        <div className="apps" aria-label="Apps">
          {APPS.map(({ label, mark, app }) => {
            // the phone's icon counts its unread messages, as a phone's does
            const badge = app === "PHONE" ? unread : 0;
            return (
              <button key={label} className="app" aria-label={badge ? `${label}, ${badge} unread messages` : label}
                onClick={() => (app === "PHONE" ? openPhone() : shellAction({ type: "OpenApp", app }))}>
                <span className={`app-mark ${mark}`}>{badge > 0 && <span className="app-badge" aria-hidden="true">{badge}</span>}</span><span>{label}</span>
              </button>
            );
          })}
        </div>
      </section>

      <section className="panel" aria-label="Now playing and navigation" inert={covered || inApp}>
        <HomeCard emulator={emulator} card={card} />
      </section>

      <MusicApp open={shell.app === "MUSIC"} />
      <PhoneApp open={shell.app === "PHONE"} />
      <SettingsApp open={shell.app === "SETTINGS"} />
      <RadioApp open={shell.app === "RADIO"} />
      <MapsApp open={shell.app === "MAPS"} inCard={!inApp && card.shown === "NAVIGATION" && shell.screenOn} light={light} />
      {/* over any screen: opened from the music app or by a long press on the drawer's Bluetooth */}
      <DevicesSheet />
      <NetworksSheet />
      <LocationPrompt />
      <Recents emulator={emulator} />
      {/* always there, as on a phone, except under the drawer, a devices or networks panel (its keyboard reaches the edges), a call or the camera */}
      {/* the bottom handle stays over a call too, so it can be swiped home into its bar */}
      {!shell.drawer && !shell.devices && !shell.networks && !car.camera && (
        <>
          <NavBar />
          <EdgeBack side="left" />
          <EdgeBack side="right" />
        </>
      )}
      <div className={classes("scrim", shell.drawer && "is-open")} onClick={() => shellAction({ type: "CloseDrawer" })} />
      <Drawer emulator={emulator} panel={drawer} dragging={dragging} />
      {callFull && <CallOverlay emulator={emulator} />}
      {car.call !== "IDLE" && shell.callView === "BAR" && !car.camera && <CallBar emulator={emulator} />}
      {emulator.voiceAssistant && <VoiceOverlay />}
      {!callFull && !car.camera && <MessageBanner emulator={emulator} />}
      {car.camera && <CameraOverlay />}
      {(Object.keys(KEY_CENTER) as (keyof typeof KEY_CENTER)[]).map((key) => (
        <svg key={key} className={classes("edge-bulge", emulator.pressedKey === key && "is-pressed")} style={{ top: KEY_CENTER[key] }} viewBox="0 0 12 136" preserveAspectRatio="none" aria-hidden="true">
          <path d={BULGE_PATH} />
        </svg>
      ))}
      <ConnectionNotice notice={emulator.notice} hidden={callFull || car.camera} />
      <MapOffer hidden={callFull || car.camera || shell.drawer} />
      <VolumeHud volume={car.volume} muted={car.muted} opened={emulator.volumeOpened} quiet={shell.app === "MUSIC" || shell.drawer} />
      <div className="dimmer" aria-hidden="true" style={{ opacity: veil.dark.toFixed(3) }} />
      {/* always there: it fades in to switch the screen off and lifts to switch it on; a tap on it wakes the screen */}
      <button className={classes("screen-off", !shell.screenOn && "is-on", waking && "is-lifting")} aria-label="Screen off, tap to wake" tabIndex={shell.screenOn ? -1 : 0} aria-hidden={shell.screenOn} onClick={() => shellAction({ type: "Wake" })} />
    </div>
  );
}
