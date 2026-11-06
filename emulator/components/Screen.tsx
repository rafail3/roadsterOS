import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from "react";
import { bridge, type Source } from "../car/bridge";
import { userActivity, watchIdle } from "../car/store";
import { Dial } from "./Clock";
import { DevOverlays } from "./DevOverlays";
import { carCommand, carEvent, launchApp, openPhone, openVolume, setBluetooth, shellAction, useEmulator, useNow, WIFI_NETWORKS } from "../car/store";
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
import { PhoneScreenApp } from "./PhoneScreenApp";
import { SoundApp } from "./SoundApp";
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

/** The dock down the left edge: the functions a driver reaches most, as app icons. */
const RAIL = [
  { id: "music", label: "Music", mark: "app-mark-music" },
  { id: "radio", label: "Radio", mark: "app-mark-radio" },
  { id: "phone", label: "Phone", mark: "app-mark-phone" },
  { id: "maps", label: "Maps", mark: "app-mark-nav" },
  { id: "camera", label: "Camera", mark: "app-mark-camera" },
];
/** The tile colour of each of our apps on home; other apps get one from their name. */
const MARKS: Record<string, string> = { MAPS: "app-mark-nav", MUSIC: "app-mark-music", PHONE: "app-mark-phone", RADIO: "app-mark-radio", SETTINGS: "app-mark-settings", PHONE_SCREEN: "app-mark-cast" };
const hue = (id: string) => [...id].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);
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

function pressRail(id: string) {
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
  // the picture without reverse gear: the music keeps its level
  if (id === "camera") shellAction({ type: "OpenCamera" });
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
  // reverse gear shows the camera; its icon opens it too
  const camera = car.camera || shell.cameraOpen;
  const covered = shell.drawer || shell.recents || callFull || camera;
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

  useEffect(watchIdle, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      shellAction({ type: "CloseDrawer" });
      shellAction({ type: "CloseCamera" });
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    // any touch restarts the always-on display's wait; while it shows, its own layer takes the touch
    <div ref={finger} onPointerDownCapture={() => { if (!shell.aod) userActivity(); }} className={classes("screen", light && "light", shell.font === "geist" && "font-geist", fading && "theming", inApp && "in-app", shell.app === "MAPS" && "in-maps", waking && "waking")} style={{ ...glow, ...boost }}
      data-design={shell.design === "GLASSY" ? "glassy" : "solid"} data-theme={light ? "light" : "dark"}>
      <div className="ambient" aria-hidden="true" />

      <header className="bar" inert={covered} onPointerDown={onPointerDown}>
        <div className="bar-group">
          {/* inside an app the big clock is gone: the time sits at the bar's left edge */}
          {inApp && (
            <time className="bar-clock" dateTime={now.toISOString()}>
              {bridge.clockText(now.getHours(), now.getMinutes(), shell.clock24h)}{!shell.clock24h && <span className="bar-meridiem">{bridge.meridiem(now.getHours())}</span>}
            </time>
          )}
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
        <div className="dock">
          {RAIL.map(({ id, label, mark }) => (
            <button key={id} className="dock-item" aria-label={label} onClick={() => pressRail(id)}>
              <span className={`app-mark ${mark}`} aria-hidden="true" />
            </button>
          ))}
        </div>
      </nav>

      <section className="field" inert={covered || inApp}>
        <Clock now={now} face={shell.face} h24={shell.clock24h} switchable={shell.faceMode === "BOTH"} />
        <HomeApps apps={bridge.homeApps(shell.homeApps, emulator.installed)} unread={unread} />
      </section>

      <section className="panel" aria-label="Now playing and navigation" inert={covered || inApp}>
        <HomeCard emulator={emulator} card={card} />
      </section>

      <MusicApp open={shell.app === "MUSIC"} />
      <PhoneApp open={shell.app === "PHONE"} />
      <SettingsApp open={shell.app === "SETTINGS"} />
      <PhoneScreenApp open={shell.app === "PHONE_SCREEN"} />
      <SoundApp open={shell.sound} />
      <RadioApp open={shell.app === "RADIO"} />
      <MapsApp open={shell.app === "MAPS"} inCard={!inApp && card.shown === "NAVIGATION" && shell.screenOn} light={light} />
      {/* over any screen: opened from the music app or by a long press on the drawer's Bluetooth */}
      <DevicesSheet />
      <NetworksSheet />
      <LocationPrompt />
      <Recents emulator={emulator} />
      {/* always there, as on a phone, except under the drawer, a devices or networks panel (its keyboard reaches the edges), a call or the camera */}
      {/* the bottom handle stays over a call too, so it can be swiped home into its bar */}
      {!shell.drawer && !shell.devices && !shell.networks && !camera && (
        <>
          <NavBar />
          <EdgeBack side="left" />
          <EdgeBack side="right" />
        </>
      )}
      <div className={classes("scrim", shell.drawer && "is-open")} onClick={() => shellAction({ type: "CloseDrawer" })} />
      <Drawer emulator={emulator} panel={drawer} dragging={dragging} />
      {callFull && <CallOverlay emulator={emulator} />}
      {car.call !== "IDLE" && shell.callView === "BAR" && !camera && <CallBar emulator={emulator} />}
      {emulator.voiceAssistant && <VoiceOverlay />}
      {!callFull && !camera && <MessageBanner emulator={emulator} />}
      {camera && <CameraOverlay />}
      {(Object.keys(KEY_CENTER) as (keyof typeof KEY_CENTER)[]).map((key) => (
        <svg key={key} className={classes("edge-bulge", emulator.pressedKey === key && "is-pressed")} style={{ top: KEY_CENTER[key] }} viewBox="0 0 12 136" preserveAspectRatio="none" aria-hidden="true">
          <path d={BULGE_PATH} />
        </svg>
      ))}
      <ConnectionNotice notice={emulator.notice} hidden={callFull || camera} />
      <MapOffer hidden={callFull || camera || shell.drawer} />
      <VolumeHud volume={car.volume} muted={car.muted} opened={emulator.volumeOpened} quiet={shell.app === "MUSIC" || shell.drawer} />
      <div className="dimmer" aria-hidden="true" style={{ opacity: veil.dark.toFixed(3) }} />
      {/* always there: it fades in to switch the screen off and lifts to switch it on; a tap on it wakes the screen */}
      <DevOverlays touches={shell.showTouches} fps={shell.showFrameRate} speed={shell.animationSpeed} />
      {!shell.setupDone && <Setup />}
      <AlwaysOn on={shell.aod} now={now} face={bridge.aodFace(shell)} h24={shell.clock24h} />
      <button className={classes("screen-off", !shell.screenOn && "is-on", waking && "is-lifting")} aria-label="Screen off, tap to wake" tabIndex={shell.screenOn ? -1 : 0} aria-hidden={shell.screenOn} onClick={() => shellAction({ type: "Wake" })} />
    </div>
  );
}

/** The always-on display: black, the clock and the date in a soft white; a touch brings the screen back and does nothing else. */
function AlwaysOn({ on, now, face, h24 }: { on: boolean; now: Date; face: "DIGITAL" | "ANALOG"; h24: boolean }) {
  const date = now.toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long" });
  return (
    <div className={classes("aod", on && "is-on")} aria-hidden={!on} role={on ? "button" : undefined} aria-label={on ? "Always on display, tap to wake" : undefined}
      onPointerDown={(e) => { if (!on) return; e.stopPropagation(); userActivity(); }}>
      {face === "ANALOG"
        ? <div className="aod-dial"><Dial now={now} /></div>
        : <time className="aod-time" dateTime={now.toISOString()}>{bridge.clockText(now.getHours(), now.getMinutes(), h24)}{!h24 && <span className="aod-meridiem">{bridge.meridiem(now.getHours())}</span>}</time>}
      <p className="aod-date">{date}</p>
    </div>
  );
}

/** After a factory reset the unit starts here until setup is finished. */
function Setup() {
  return (
    <section className="setup" aria-label="Setup">
      <h1 className="setup-title">Welcome to roadsterOS</h1>
      <p className="setup-text">Let's set up the unit.</p>
      <button className="setup-start" onClick={() => shellAction({ type: "FinishSetup" })}>Continue</button>
    </section>
  );
}

/**
 * The icons on home. A press held on one starts arranging them, as a phone's home screen does: they jiggle, the held
 * one follows the finger left or right while the others make room, and the drop moves it (core-car HomeApps.move).
 * A tap outside the row ends the arranging; while it lasts a tap opens nothing.
 */
function HomeApps({ apps, unread }: { apps: { id: string; label: string }[]; unread: number }) {
  const [arranging, setArranging] = useState(false);
  const [drag, setDrag] = useState<{ id: string; from: number; dx: number; slot: number; scale: number } | null>(null);
  // after a drop: the icon already in its new place, drawn where the finger left it, then sliding the rest of the way
  const [settle, setSettle] = useState<{ id: string; dx: number } | null>(null);
  const press = useRef<{ timer: ReturnType<typeof setTimeout> | null; x: number; held: boolean }>({ timer: null, x: 0, held: false });
  const row = useRef<HTMLDivElement>(null);
  // an icon taken off shrinks away first; then the row re-centres
  const [leaving, setLeaving] = useState<string | null>(null);
  const placed = useRef<Record<string, number>>({});
  const ids = apps.map((a) => a.id).join();

  // when icons come or go the row re-centres: each one glides from where it was to its new place, a new one grows in
  useLayoutEffect(() => {
    const els = row.current ? ([...row.current.children] as HTMLElement[]) : [];
    const now: Record<string, number> = Object.fromEntries(els.map((el, i) => [apps[i]?.id ?? "", el.offsetLeft]));
    const [, glideMs, growMs] = bridge.arrange.times();
    // a drop places the icons itself (settle); only additions and removals glide here (core-car HomeArrange.glides)
    if (!settle && !drag) for (const g of bridge.arrange.glides(placed.current, now)) {
      const el = els[apps.findIndex((a) => a.id === g.id)];
      if (!el) continue;
      if (g.added) el.animate([{ opacity: 0, transform: "scale(0.6)" }, { opacity: 1, transform: "none" }], { duration: growMs, easing: "cubic-bezier(0.2, 0, 0, 1)" });
      else el.animate([{ transform: `translateX(${g.fromDx ?? 0}px)` }, { transform: "none" }], { duration: glideMs, easing: "cubic-bezier(0.2, 0, 0, 1)" });
    }
    placed.current = now;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ids]);

  const remove = (id: string) => {
    setLeaving(id);
    setTimeout(() => { shellAction({ type: "ToggleHomeApp", id }); setLeaving(null); }, bridge.arrange.times()[0]);
  };
  // where the held icon would drop, never past the row's ends, and where each icon is drawn meanwhile (core-car HomeArrange)
  const count = apps.length;
  const to = drag ? bridge.arrange.dropIndex(drag.from, drag.dx, drag.slot, count) : -1;
  const shift = (i: number) => (drag ? bridge.arrange.offset(i, drag.from, drag.dx, drag.slot, count) : 0);

  useEffect(() => {
    if (!settle) return;
    // one frame drawn without transitions at the drop's offset, then the icon glides to its place
    let b = 0;
    const a = requestAnimationFrame(() => { b = requestAnimationFrame(() => setSettle(null)); });
    return () => { cancelAnimationFrame(a); cancelAnimationFrame(b); };
  }, [settle]);

  useEffect(() => {
    if (!arranging) return;
    const away = (e: PointerEvent) => { if (!row.current?.contains(e.target as Node)) setArranging(false); };
    window.addEventListener("pointerdown", away, true);
    return () => window.removeEventListener("pointerdown", away, true);
  }, [arranging]);

  const startDrag = (el: HTMLElement, id: string, from: number, x: number) => {
    const r = el.getBoundingClientRect();
    // the screen is drawn scaled: pointer pixels to layout pixels, and one icon's step to the next
    const scale = r.width / el.offsetWidth;
    const next = (el.nextElementSibling ?? el.previousElementSibling) as HTMLElement | null;
    const slot = next ? Math.abs(next.getBoundingClientRect().left - r.left) / scale : el.offsetWidth;
    press.current.x = x;
    // a press that picked an icon up never opens it, whatever the arranging does by the time its click comes
    press.current.held = true;
    setDrag({ id, from, dx: 0, slot, scale });
  };
  const down = (e: React.PointerEvent<HTMLButtonElement>, id: string, i: number) => {
    const el = e.currentTarget;
    el.setPointerCapture(e.pointerId);
    press.current = { timer: null, x: e.clientX, held: false };
    if (arranging) return startDrag(el, id, i, e.clientX);
    press.current.timer = setTimeout(() => {
      press.current.held = true;
      setArranging(true);
      startDrag(el, id, i, press.current.x);
    }, bridge.arrange.holdMs());
  };
  const move = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (drag) return setDrag({ ...drag, dx: (e.clientX - press.current.x) / drag.scale });
    if (press.current.timer && bridge.arrange.cancelsHold(e.clientX - press.current.x)) { clearTimeout(press.current.timer); press.current.timer = null; }
  };
  const up = () => {
    if (press.current.timer) { clearTimeout(press.current.timer); press.current.timer = null; }
    if (!drag) return;
    if (to !== drag.from) {
      shellAction({ type: "MoveHomeApp", id: drag.id, by: to - drag.from });
      // the icon is in its new slot now: what is left of the finger's offset from there
      setSettle({ id: drag.id, dx: bridge.arrange.settle(drag.from, drag.dx, drag.slot, count) });
    }
    setDrag(null);
    // a move ends the arranging; a hold without one keeps it, so an icon can be taken off with its badge
    if (!bridge.arrange.keeps(drag.from, to)) setArranging(false);
  };
  const open = (id: string) => {
    // a press that picked the icon up (the hold, or a grab while arranging), or a tap while arranging, opens nothing
    const picked = press.current.held;
    press.current.held = false;
    if (bridge.arrange.opens(arranging, picked)) launchApp(id);
  };
  const key = (e: React.KeyboardEvent<HTMLButtonElement>, id: string) => {
    const by = e.key === "ArrowLeft" ? -1 : e.key === "ArrowRight" ? 1 : 0;
    if (arranging && by) { e.preventDefault(); shellAction({ type: "MoveHomeApp", id, by }); }
    if (arranging && e.key === "Escape") setArranging(false);
  };

  return (
    <div ref={row} className={classes("apps", arranging && "is-arranging", settle !== null && "is-settling")} aria-label={arranging ? "Apps, arranging: drag an icon or use the arrow keys" : "Apps"}>
      {/* the icons chosen in the settings (core-car HomeApps) */}
      {apps.map(({ id, label }, i) => {
        // the phone's icon counts its unread messages, as a phone's does
        const badge = id === "PHONE" ? unread : 0;
        return (
          <button key={id} className={classes("app", drag?.id === id && "is-dragged", leaving === id && "is-leaving")}
            style={{ "--i": i, transform: drag ? `translateX(${shift(i)}px)` : settle?.id === id ? `translateX(${settle.dx}px)` : undefined } as CSSProperties}
            aria-label={badge ? `${label}, ${badge} unread messages` : label}
            onPointerDown={(e) => down(e, id, i)} onPointerMove={move} onPointerUp={up} onPointerCancel={up} onKeyDown={(e) => key(e, id)} onClick={() => open(id)}>
            <span className={`app-mark ${MARKS[id] ?? "app-mark-other"}`} style={MARKS[id] ? undefined : { "--mark-hue": hue(id) } as CSSProperties}>
              {badge > 0 && !arranging && <span className="app-badge" aria-hidden="true">{badge}</span>}
              {/* arranging: the badge that takes the icon off home (it stays in the settings' More Apps) */}
              {arranging && (
                <span className="app-remove" role="button" tabIndex={0} aria-label={`Remove ${label} from home`}
                  onPointerDown={(e) => e.stopPropagation()}
                  onClick={(e) => { e.stopPropagation(); remove(id); }}
                  onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); e.stopPropagation(); remove(id); } }} />
              )}
            </span>
            <span>{label}</span>
          </button>
        );
      })}
    </div>
  );
}
