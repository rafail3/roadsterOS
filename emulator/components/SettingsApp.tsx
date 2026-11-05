import { useMemo, useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from "react";
import { bridge, type FaceMode, type ThemeMode, type TimeSource } from "../car/bridge";
import { allTimeZones, removePack, setBluetooth, setLocation, setTimeSync, setTimeZone, setVolume, setWifi, shellAction, shiftClock, useEmulator, useNow, zoneOffsetMinutes, type EmulatorState } from "../car/store";
import { Keyboard } from "./Keyboard";
import { PillSlider } from "./Drawer";
import { useCloseOntoCard } from "./media";

type Page = "wifi" | "bluetooth" | "location" | "display" | "datetime" | "sound" | "phone" | "home" | "maps" | "about";

/** The level a start is capped at when the limit is first switched on. */
const START_VOLUME_DEFAULT = 12;
const BRIGHTNESS_KEY_STEP = 5;
const MB = 1024 * 1024;
// what the unit's About page reads (SystemInfo on Android); fixed values for the emulator
const ABOUT = { android: "8.1.0", model: "8227L_demo", screen: "1024 × 600", appMemory: 27 * MB, freeMemory: 214 * MB, totalMemory: 1003 * MB, freeStorage: 8.6 * 1024 * MB, totalStorage: 11.9 * 1024 * MB };
const VERSION = process.env.RDSTR_VERSION ?? "dev";

function Switch({ label, on, onChange }: { label: string; on: boolean; onChange: (on: boolean) => void }) {
  return (
    <button className={`switch${on ? " is-on" : ""}`} role="switch" aria-checked={on} aria-label={label} onClick={() => onChange(!on)}>
      <span className="switch-knob" aria-hidden="true" />
    </button>
  );
}

function Segmented<T extends string>({ label, options, value, onChange }: { label: string; options: [T, string][]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="set-seg" role="radiogroup" aria-label={label}>
      {options.map(([v, text]) => (
        <button key={v} role="radio" aria-checked={value === v} onClick={() => onChange(v)}>{text}</button>
      ))}
    </div>
  );
}

function Group({ title, footer, children }: { title?: string; footer?: ReactNode; children: ReactNode }) {
  return (
    <section className="set-group">
      {title && <h3 className="set-group-title">{title}</h3>}
      <div className="set-rows">{children}</div>
      {footer && <p className="set-footer">{footer}</p>}
    </section>
  );
}

/** One line of a group: its name, then a value, a control or a chevron when it opens something. */
function Row({ label, value, onPress, danger, children }: { label: ReactNode; value?: ReactNode; onPress?: () => void; danger?: boolean; children?: ReactNode }) {
  const body = (<>
    <span className="set-row-label">{label}</span>
    {value !== undefined && <span className="set-row-value">{value}</span>}
    {children}
    {onPress && !danger && <span className="icon i-caret-right set-chevron" aria-hidden="true" />}
  </>);
  return onPress
    ? <button className={`set-row is-button${danger ? " is-danger" : ""}`} onClick={onPress}>{body}</button>
    : <div className="set-row">{body}</div>;
}

function Wifi({ e }: { e: EmulatorState }) {
  return (
    <Group footer={!e.locationOn && e.wifiOn ? "Finding networks needs Location on." : undefined}>
      <Row label="Wi-Fi"><Switch label="Wi-Fi" on={e.wifiOn} onChange={setWifi} /></Row>
      {e.wifiOn && <Row label="Network" value={e.wifi.connected ?? "Not connected"} onPress={() => shellAction({ type: "OpenNetworks" })} />}
    </Group>
  );
}

function Bluetooth({ e }: { e: EmulatorState }) {
  const { car, btOn, device, saved } = e;
  return (
    <Group footer="Phones connect for music and calls. A tablet or a laptop plays music only.">
      <Row label="Bluetooth"><Switch label="Bluetooth" on={btOn} onChange={setBluetooth} /></Row>
      {btOn && <Row label="Connected" value={car.btConnected ? device : "None"} />}
      {btOn && <Row label="Devices" value={`${saved.length} saved`} onPress={() => shellAction({ type: "OpenDevices" })} />}
    </Group>
  );
}

function Location({ e }: { e: EmulatorState }) {
  return (
    <Group footer="Maps, the speed limits and the Wi-Fi search need the unit's location. It stays on the unit.">
      <Row label="Location"><Switch label="Location" on={e.locationOn} onChange={setLocation} /></Row>
    </Group>
  );
}

function Display({ e }: { e: EmulatorState }) {
  const { shell } = e;
  return (<>
    <Group title="Theme" footer="Auto is light from 05:00 to 19:00 and dark otherwise; with the headlights on, the screen dims a little.">
      <Row label="Appearance">
        <Segmented<ThemeMode> label="Appearance" options={[["AUTO", "Auto"], ["LIGHT", "Day"], ["DARK", "Night"]]} value={shell.theme} onChange={(mode) => shellAction({ type: "SetTheme", mode })} />
      </Row>
    </Group>
    <Group title="Brightness">
      <div className="set-row is-slider">
        <PillSlider
          label="Brightness" min={bridge.minBrightness()} max={bridge.maxBrightness()} step={BRIGHTNESS_KEY_STEP} value={shell.brightness}
          valueText={`${shell.brightness} percent`} onValue={(value) => shellAction({ type: "SetBrightness", value })}
          icon={<span className="pill-icon"><span className="icon i-sun-fill" aria-hidden="true" /></span>}
          readout={<span className="pill-readout" aria-hidden="true">{shell.brightness}%</span>}
        />
      </div>
    </Group>
    <Group title="Clock" footer={shell.faceMode === "BOTH" ? "A tap on the clock on home switches between the two faces." : undefined}>
      <Row label="Face">
        <Segmented<FaceMode> label="Clock face" options={[["BOTH", "Both"], ["DIGITAL", "Digital"], ["ANALOG", "Analog"]]} value={shell.faceMode} onChange={(mode) => shellAction({ type: "SetFaceMode", mode })} />
      </Row>
    </Group>
  </>);
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const SYNC_FOOTER: Record<TimeSource | "MANUAL", string> = {
  NETWORK: "The time comes from the internet whenever Wi-Fi is connected.",
  GPS: "The time comes from the GPS satellites whenever there is a fix, no internet needed.",
  MANUAL: "The clock keeps the time set above and is not corrected.",
};

/** − and + for one part of the date or time; the part's value between them, when given. */
function Stepper({ what, value, onStep }: { what: string; value?: string; onStep: (by: number) => void }) {
  return (
    <span className="set-stepper">
      <button aria-label={`Earlier ${what}`} onClick={() => onStep(-1)}><span className="icon i-minus" aria-hidden="true" /></button>
      {value !== undefined && <output aria-label={what}>{value}</output>}
      <button aria-label={`Later ${what}`} onClick={() => onStep(1)}><span className="icon i-plus" aria-hidden="true" /></button>
    </span>
  );
}

type DatePart = "day" | "month" | "year";

/** How far the clock moves for one step of a part of the date; a month or year keeps the day, or the month's last one. */
function dateStep(now: Date, part: DatePart, by: number): number {
  const d = new Date(now);
  if (part === "day") d.setDate(d.getDate() + by);
  else {
    const day = d.getDate();
    d.setDate(1);
    if (part === "month") d.setMonth(d.getMonth() + by); else d.setFullYear(d.getFullYear() + by);
    d.setDate(Math.min(day, new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()));
  }
  return d.getTime() - now.getTime();
}

/** Date and time: the clock's format, where it sets itself from or the time set by hand, and the zone (TimeKeeper on the unit). */
function DateTime({ e }: { e: EmulatorState }) {
  const [picking, setPicking] = useState(false);
  const [part, setPart] = useState<DatePart>("day");
  const now = useNow(e.hour);
  const { autoTime, timeSource, clock24h } = e.shell;
  if (picking) return <TimeZonePicker e={e} onDone={() => setPicking(false)} />;
  const date = now.toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
  const time = bridge.clockText(now.getHours(), now.getMinutes(), clock24h) + (clock24h ? "" : ` ${bridge.meridiem(now.getHours())}`);
  const offset = bridge.timeZoneOffset(zoneOffsetMinutes(e.timeZone, new Date()));
  return (<>
    <Group>
      {autoTime ? <Row label="Today" value={date} /> : (
        // tap the day, the month or the year, then step it
        <Row label="Today">
          <span className="set-date" role="radiogroup" aria-label="Part of the date to change">
            <span className="set-date-weekday">{now.toLocaleDateString("en-GB", { weekday: "long" })},</span>
            {([["day", String(now.getDate())], ["month", now.toLocaleDateString("en-GB", { month: "long" })], ["year", String(now.getFullYear())]] as [DatePart, string][]).map(([p, text]) => (
              <button key={p} role="radio" aria-checked={part === p} aria-label={`${p}, ${text}`} className="set-date-part" onClick={() => setPart(p)}>{text}</button>
            ))}
          </span>
          <Stepper what={part} onStep={(by) => shiftClock(dateStep(now, part, by))} />
        </Row>
      )}
      {autoTime ? <Row label="Time" value={time} /> : (
        <Row label="Time">
          <Stepper what="hour" value={bridge.clockText(now.getHours(), 0, clock24h).split(":")[0]} onStep={(by) => shiftClock(by * HOUR)} />
          <Stepper what="minute" value={String(now.getMinutes()).padStart(2, "0")} onStep={(by) => shiftClock(by * MINUTE)} />
        </Row>
      )}
    </Group>
    <Group>
      <Row label="24-Hour Time"><Switch label="24-Hour Time" on={clock24h} onChange={(on) => shellAction({ type: "SetClock24h", on })} /></Row>
    </Group>
    <Group footer={SYNC_FOOTER[autoTime ? timeSource : "MANUAL"]}>
      <Row label="Set Automatically"><Switch label="Set Automatically" on={autoTime} onChange={(on) => setTimeSync(on, timeSource)} /></Row>
      {autoTime && (
        <Row label="Sync With">
          <Segmented<TimeSource> label="Sync with" options={[["NETWORK", "Internet"], ["GPS", "GPS"]]} value={timeSource} onChange={(source) => setTimeSync(true, source)} />
        </Row>
      )}
    </Group>
    <Group>
      <Row label="Time Zone" value={`${bridge.timeZoneCity(e.timeZone)}, ${offset}`} onPress={() => setPicking(true)} />
    </Group>
  </>);
}

/** Every zone named after a place, by city, with a search on the on-screen keyboard. */
function TimeZonePicker({ e, onDone }: { e: EmulatorState; onDone: () => void }) {
  const [query, setQuery] = useState("");
  const [typing, setTyping] = useState(false);
  const zones = useMemo(() => {
    const at = new Date();
    return bridge.timeZones(allTimeZones()).map((id) => ({ id, offset: bridge.timeZoneOffset(zoneOffsetMinutes(id, at)) }));
  }, []);
  const ids = useMemo(() => zones.map((z) => z.id), [zones]);
  const shown = useMemo(() => {
    const found = new Set(bridge.timeZoneSearch(ids, query));
    return zones.filter((z) => found.has(z.id));
  }, [ids, zones, query]);
  const pick = (id: string) => { setTimeZone(id); onDone(); };
  return (<>
    <div className={`set-zones${typing ? " is-typing" : ""}`}>
      {/* back and the search stay at the top while the list scrolls */}
      <div className="set-zones-head">
      <button className="set-back" onClick={onDone}><span className="icon i-caret-left" aria-hidden="true" />Date &amp; Time</button>
      <div className="phone-search" role="search">
        <button className={`phone-search-field${typing ? " is-focused" : ""}`} aria-label={query ? `Search, ${query}` : "Search time zones"} onClick={() => setTyping(true)}>
          <span className="icon i-magnifying-glass" aria-hidden="true" />
          <span className={query ? "phone-search-text" : "phone-search-placeholder"}>{query || "Search a city"}</span>
          {typing && <span className="phone-search-caret" aria-hidden="true" />}
        </button>
        {(query || typing) && <button className="phone-search-cancel" onClick={() => { setQuery(""); setTyping(false); }}>Cancel</button>}
      </div>
      </div>
      <Group title="Time Zone" footer={shown.length ? `${shown.length} zones` : undefined}>
        {shown.map((z) => (
          // a list to pick from: no chevron, a check on the current one
          <button key={z.id} className="set-row is-button" aria-current={z.id === e.timeZone || undefined} onClick={() => pick(z.id)}>
            <span className="set-row-label">{bridge.timeZoneCity(z.id)}<span className="set-row-sub">{bridge.timeZoneRegion(z.id)}</span></span>
            <span className="set-row-value">{z.offset}</span>
            <span className={`icon i-check set-check${z.id === e.timeZone ? "" : " is-hidden"}`} aria-hidden="true" />
          </button>
        ))}
        {!shown.length && <Row label={`No time zone matches “${query}”`} />}
      </Group>
    </div>
    <Keyboard open={typing} onKey={(c) => setQuery((q) => q + c)} onBackspace={() => setQuery((q) => q.slice(0, -1))}
      onSubmit={() => setTyping(false)} submitLabel="Search" submitEnabled />
  </>);
}

function Sound({ e }: { e: EmulatorState }) {
  const { car, shell } = e;
  const limit = shell.startVolume > 0;
  const max = bridge.maxVolume();
  const step = (d: number) => shellAction({ type: "SetStartVolume", level: Math.max(1, shell.startVolume + d) });
  return (<>
    <Group title="Volume">
      <div className="set-row is-slider">
        <PillSlider
          className={car.muted ? "is-muted" : undefined} label="Volume" min={0} max={max} step={1} value={car.muted ? 0 : car.volume}
          valueText={car.muted ? "Muted" : String(car.volume)} onValue={setVolume}
          icon={<span className="pill-icon"><span className={`icon ${car.muted ? "i-speaker-slash-fill" : "i-speaker-high-fill"}`} aria-hidden="true" /></span>}
          readout={<span className="pill-readout" aria-hidden="true">{car.muted ? "Muted" : car.volume}</span>}
        />
      </div>
    </Group>
    <Group footer={limit ? `The car starts no louder than ${shell.startVolume} of ${max}; a quieter level stays as it was.` : "The car starts at the volume it was switched off with."}>
      <Row label="Limit Start Volume"><Switch label="Limit Start Volume" on={limit} onChange={(on) => shellAction({ type: "SetStartVolume", level: on ? START_VOLUME_DEFAULT : 0 })} /></Row>
      {limit && (
        <Row label="Start Volume">
          <span className="set-stepper">
            <button aria-label="Lower start volume" disabled={shell.startVolume <= 1} onClick={() => step(-1)}><span className="icon i-minus" aria-hidden="true" /></button>
            <output aria-live="polite" aria-label="Start volume">{shell.startVolume}</output>
            <button aria-label="Raise start volume" disabled={shell.startVolume >= max} onClick={() => step(1)}><span className="icon i-plus" aria-hidden="true" /></button>
          </span>
        </Row>
      )}
    </Group>
  </>);
}

function Phone({ e }: { e: EmulatorState }) {
  const on = e.shell.lockTypingWhileMoving;
  return (
    <Group title="Messages" footer="With this on, a message can be typed only while the car stands. Quick replies and calls work either way.">
      <Row label="Block Typing While Driving"><Switch label="Block Typing While Driving" on={on} onChange={(v) => shellAction({ type: "SetTypingLock", on: v })} /></Row>
    </Group>
  );
}

function Maps({ e }: { e: EmulatorState }) {
  const { installed, downloading } = e.maps;
  const [confirm, setConfirm] = useState<string | null>(null);
  return (<>
    <Group footer="Two short notes when the car goes over the speed limit. The limit sign and the red dial stay either way.">
      <Row label="Speed Limit Chime"><Switch label="Speed Limit Chime" on={e.shell.speedChime} onChange={(on) => shellAction({ type: "SetSpeedChime", on })} /></Row>
    </Group>
    <Group footer="The road's limit beside the speed on the map. The ring around the speed still turns orange near it and red over it.">
      <Row label="Speed Limit Sign"><Switch label="Speed Limit Sign" on={e.shell.speedLimitSign} onChange={(on) => shellAction({ type: "SetSpeedLimitSign", on })} /></Row>
    </Group>
    <Group title="Offline Maps" footer="Another country's map is offered when the car enters it, and downloads over Wi-Fi.">
      {installed.map((code) => (
        <Row key={code} label={bridge.countryName(code)} value={`${bridge.packMb(code) || "?"} MB`}>
          {confirm === code
            ? <button className="set-inline is-danger" onClick={() => { removePack(code); setConfirm(null); }}>Remove</button>
            : <button className="set-inline" aria-label={`Remove the ${bridge.countryName(code)} map`} onClick={() => setConfirm(code)}>Remove…</button>}
        </Row>
      ))}
      {downloading && <Row label={bridge.countryName(downloading.code)} value={`Downloading ${Math.round(downloading.progress * 100)}%`} />}
      {!installed.length && !downloading && <Row label="No maps on the unit" />}
    </Group>
  </>);
}

/** Which apps sit on home and in which order: ours and the other apps installed on the unit (core-car HomeApps). */
function HomeScreen({ e }: { e: EmulatorState }) {
  const chosen = bridge.homeApps(e.shell.homeApps, e.installed);
  const all = [...bridge.ownApps(), ...e.installed];
  const others = all.filter((a) => !e.shell.homeApps.includes(a.id));
  const max = bridge.homeAppsMax();
  const full = chosen.length >= max;
  const act = (a: { type: "ToggleHomeApp"; id: string } | { type: "MoveHomeApp"; id: string; by: number }) => shellAction(a);
  // a row dragged by its handle: the others make room, the drop moves it (core-car HomeApps.move)
  const [drag, setDrag] = useState<{ id: string; from: number; dy: number; h: number } | null>(null);
  const grab = useRef({ y: 0, scale: 1 });
  const to = drag ? Math.max(0, Math.min(chosen.length - 1, drag.from + Math.round(drag.dy / drag.h))) : -1;
  const shift = (i: number) => {
    if (!drag || i === drag.from) return drag ? drag.dy : 0;
    if (drag.from < i && i <= to) return -drag.h;
    if (to <= i && i < drag.from) return drag.h;
    return 0;
  };
  const down = (ev: PointerEvent<HTMLButtonElement>, id: string, from: number) => {
    const row = ev.currentTarget.closest(".set-row") as HTMLElement;
    ev.currentTarget.setPointerCapture(ev.pointerId);
    // the screen is drawn scaled: pointer pixels to layout pixels
    grab.current = { y: ev.clientY, scale: row.getBoundingClientRect().height / row.offsetHeight };
    setDrag({ id, from, dy: 0, h: row.offsetHeight });
  };
  const moveTo = (ev: PointerEvent<HTMLButtonElement>) => drag && setDrag({ ...drag, dy: (ev.clientY - grab.current.y) / grab.current.scale });
  const drop = () => {
    if (drag && to !== drag.from) act({ type: "MoveHomeApp", id: drag.id, by: to - drag.from });
    setDrag(null);
  };
  const key = (ev: KeyboardEvent<HTMLButtonElement>, id: string) => {
    const by = ev.key === "ArrowUp" ? -1 : ev.key === "ArrowDown" ? 1 : 0;
    if (by) { ev.preventDefault(); act({ type: "MoveHomeApp", id, by }); }
  };
  return (<>
    <Group title="On Home" footer={`Up to ${max} apps, in this order beside the card. Drag to reorder.`}>
      {chosen.map((a, i) => (
        <div key={a.id} className={`set-row set-reorder${drag?.id === a.id ? " is-dragged" : ""}`} style={drag ? { transform: `translateY(${shift(i)}px)` } : undefined}>
          <span className="set-row-label">{a.label}</span>
          <button className="set-inline is-remove" onClick={() => act({ type: "ToggleHomeApp", id: a.id })}>Remove</button>
          <button className="set-grip" aria-label={`Reorder ${a.label}, place ${i + 1} of ${chosen.length}`}
            onPointerDown={(ev) => down(ev, a.id, i)} onPointerMove={moveTo} onPointerUp={drop} onPointerCancel={() => setDrag(null)}
            onKeyDown={(ev) => key(ev, a.id)}>
            <span className="icon i-list" aria-hidden="true" />
          </button>
        </div>
      ))}
      {!chosen.length && <Row label="No apps on home" />}
    </Group>
    <Group title="More Apps" footer={full ? "Home is full: remove an app to add another." : "roadsterOS's own apps and the apps installed on the unit."}>
      {others.map((a) => (
        <Row key={a.id} label={a.label}>
          <button className="set-inline" disabled={full} onClick={() => act({ type: "ToggleHomeApp", id: a.id })}>Add</button>
        </Row>
      ))}
    </Group>
  </>);
}

function About({ e }: { e: EmulatorState }) {
  const [confirm, setConfirm] = useState(false);
  return (<>
    <Group>
      <Row label="roadsterOS" value={VERSION} />
      <Row label="Android" value={ABOUT.android} />
      <Row label="Platform" value={ABOUT.model} />
      <Row label="Screen" value={ABOUT.screen} />
    </Group>
    <Group title="Memory" footer="What roadsterOS itself uses, and what the unit has free for navigation and other apps.">
      <Row label="roadsterOS" value={bridge.size(ABOUT.appMemory)} />
      <Row label="Free" value={`${bridge.size(ABOUT.freeMemory)} of ${bridge.size(ABOUT.totalMemory)}`} />
    </Group>
    <Group title="Storage">
      <Row label="Free" value={`${bridge.size(ABOUT.freeStorage)} of ${bridge.size(ABOUT.totalStorage)}`} />
    </Group>
    <Group title="Troubleshooting" footer={e.shell.recordEvents ? `Recording: ${e.recorded} lines so far. Read the journal with adb.` : "Writes car events, commands, screen actions and errors to the unit, one file a day; older days are compressed and the journal never passes 10 MB."}>
      <Row label="Record Car Events"><Switch label="Record Car Events" on={e.shell.recordEvents} onChange={(on) => shellAction({ type: "SetRecording", on })} /></Row>
    </Group>
    <Group footer={confirm ? "Theme, brightness, clock and sound settings go back to their defaults. Phones, networks and maps stay." : undefined}>
      {confirm
        ? <Row label="Reset All Settings" danger onPress={() => { shellAction({ type: "ResetSettings" }); setConfirm(false); }} />
        : <Row label="Reset Settings…" onPress={() => setConfirm(true)} />}
    </Group>
  </>);
}

const PAGES: { id: Page; label: string; icon: string; tint: string }[] = [
  { id: "wifi", label: "Wi-Fi", icon: "i-wifi-high", tint: "#0a84ff" },
  { id: "bluetooth", label: "Bluetooth", icon: "i-bluetooth", tint: "#0a84ff" },
  { id: "location", label: "Location", icon: "i-navigation-arrow-fill", tint: "#0a84ff" },
  { id: "display", label: "Display", icon: "i-sun-fill", tint: "#5e5ce6" },
  { id: "datetime", label: "Date & Time", icon: "i-clock-fill", tint: "#8e8e93" },
  { id: "sound", label: "Sound", icon: "i-speaker-high-fill", tint: "#ff375f" },
  { id: "phone", label: "Phone", icon: "i-phone-fill", tint: "#17d847" },
  { id: "home", label: "Home Screen", icon: "i-house-fill", tint: "#5e5ce6" },
  { id: "maps", label: "Maps", icon: "i-map-trifold-fill", tint: "#30d158" },
  { id: "about", label: "About", icon: "i-info-fill", tint: "#8e8e93" },
];

/** What the list shows beside a section, so most states read without opening it. */
function summary(id: Page, e: EmulatorState): string {
  if (id === "wifi") return !e.wifiOn ? "Off" : e.wifi.connected ?? "On";
  if (id === "bluetooth") return !e.btOn ? "Off" : e.car.btConnected ? e.device : "On";
  if (id === "location") return e.locationOn ? "On" : "Off";
  return "";
}

/** Settings: the sections on the left, the chosen one on the right, as a tablet's settings in landscape. */
export function SettingsApp({ open }: { open: boolean }) {
  const e = useEmulator();
  const [page, setPage] = useState<Page>("wifi");
  const section = useCloseOntoCard(open);
  const current = PAGES.find((p) => p.id === page)!;
  return (
    <section ref={section} className={`music-app settings-app${open ? " is-open" : ""}`} aria-label="Settings" aria-hidden={!open} inert={!open}>
      <button className="glass-button music-close" aria-label="Close settings" onClick={() => shellAction({ type: "Home" })}>
        <span className="icon i-caret-down" aria-hidden="true" />
      </button>
      <nav className="set-nav" aria-label="Settings sections">
        <h2 className="set-title">Settings</h2>
        <ul>
          {PAGES.map((p) => (
            <li key={p.id}>
              <button className={`set-nav-item${page === p.id ? " is-current" : ""}`} aria-current={page === p.id ? "page" : undefined} onClick={() => setPage(p.id)}>
                <span className="set-tile" style={{ background: p.tint }} aria-hidden="true"><span className={`icon ${p.icon}`} /></span>
                <span className="set-nav-label">{p.label}</span>
                <span className="set-nav-value">{summary(p.id, e)}</span>
              </button>
            </li>
          ))}
        </ul>
      </nav>
      <div className="set-detail" key={page}>
        <h2 className="set-page-title">{current.label}</h2>
        {page === "wifi" && <Wifi e={e} />}
        {page === "bluetooth" && <Bluetooth e={e} />}
        {page === "location" && <Location e={e} />}
        {page === "display" && <Display e={e} />}
        {page === "datetime" && <DateTime e={e} />}
        {page === "sound" && <Sound e={e} />}
        {page === "phone" && <Phone e={e} />}
        {page === "maps" && <Maps e={e} />}
        {page === "home" && <HomeScreen e={e} />}
        {page === "about" && <About e={e} />}
      </div>
    </section>
  );
}
