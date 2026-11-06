import { useMemo, useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent, type ReactNode } from "react";
import { bridge, type AnimationSpeed, type AodFace, type CameraSettings, type FaceMode, type Guidelines, type ReverseDip, type ThemeMode, type TimeSource } from "../car/bridge";
import { allTimeZones, carCommand, factoryReset, setCamera, setCastVisible, setSound, openAndroidSettings, restartShell, UNIT_IP, removePack, setBluetooth, setLocation, setTimeSync, setTimeZone, setVolume, setWifi, shellAction, shiftClock, useEmulator, useNow, zoneOffsetMinutes, type EmulatorState } from "../car/store";
import { Keyboard } from "./Keyboard";
import { PillSlider } from "./Drawer";
import { useCloseOntoCard } from "./media";
import { GuideLines } from "./Overlays";

type Page = "wifi" | "bluetooth" | "location" | "display" | "datetime" | "dash" | "camera" | "sound" | "phone" | "home" | "maps" | "about" | "developer";

/** The level a start is capped at when the limit is first switched on. */
const START_VOLUME_DEFAULT = 12;
const BRIGHTNESS_KEY_STEP = 5;
const MB = 1024 * 1024;
// the vendor's range for the lines' height, in pixels of the 1024x600 screen (core-car Camera)
const LINE_POS_MIN = -200;
const LINE_POS_MAX = 70;
const LINE_POS_STEP = 10;
const GUIDE_FOOTERS: Record<Guidelines, string> = {
  OFF: "No lines over the picture.",
  FIXED: "Two lines show the car's width and how close things are: red is nearest, then yellow and green.",
  STEERING: "The lines bend as the steering wheel turns, from the angle the car's CAN box sends. Red is nearest.",
};
// what the unit's About page reads (SystemInfo on Android); fixed values for the emulator
const ABOUT = { android: "8.1.0", model: "8227L_demo", screen: "1024 × 600", appMemory: 27 * MB, freeMemory: 214 * MB, totalMemory: 1003 * MB, freeStorage: 8.6 * 1024 * MB, totalStorage: 11.9 * 1024 * MB };
const VERSION = process.env.RDSTR_VERSION ?? "dev";

export function Switch({ label, on, onChange }: { label: string; on: boolean; onChange: (on: boolean) => void }) {
  return (
    <button className={`switch${on ? " is-on" : ""}`} role="switch" aria-checked={on} aria-label={label} onClick={() => onChange(!on)}>
      <span className="switch-knob" aria-hidden="true" />
    </button>
  );
}

export function Segmented<T extends string>({ label, options, value, onChange }: { label: string; options: [T, string][]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="set-seg" role="radiogroup" aria-label={label}>
      {options.map(([v, text]) => (
        <button key={v} role="radio" aria-checked={value === v} onClick={() => onChange(v)}>{text}</button>
      ))}
    </div>
  );
}

export function Group({ title, footer, children }: { title?: string; footer?: ReactNode; children: ReactNode }) {
  return (
    <section className="set-group">
      {title && <h3 className="set-group-title">{title}</h3>}
      <div className="set-rows">{children}</div>
      {footer && <p className="set-footer">{footer}</p>}
    </section>
  );
}

/** One line of a group: its name, then a value, a control or a chevron when it opens something. */
export function Row({ label, value, onPress, danger, children }: { label: ReactNode; value?: ReactNode; onPress?: () => void; danger?: boolean; children?: ReactNode }) {
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
    <Group title="Always On Display" footer={shell.aodEnabled
      ? `Left alone ${shell.aodAfterMin} min, the screen goes black with only the clock and the date. A touch, a key, a call or reverse gear brings it back; it waits during a call, the camera and the map.`
      : "The screen stays as it is when left alone."}>
      <Row label="Always On Display"><Switch label="Always On Display" on={shell.aodEnabled} onChange={(on) => shellAction({ type: "SetAod", on })} /></Row>
      {shell.aodEnabled && (<>
        <Row label="After">
          <Segmented<string> label="Always on display after" options={bridge.aodMinutes().map((m) => [String(m), `${m} min`] as [string, string])} value={String(shell.aodAfterMin)} onChange={(v) => shellAction({ type: "SetAodAfter", minutes: Number(v) })} />
        </Row>
        <Row label="Clock">
          <Segmented<AodFace> label="Always on display clock" options={[["HOME", "As Home"], ["DIGITAL", "Digital"], ["ANALOG", "Analog"]]} value={shell.aodFace} onChange={(face) => shellAction({ type: "SetAodFace", face })} />
        </Row>
      </>)}
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

/**
 * The car's small dash display: switch it between the clock and the consumption, and match its clock once; it follows
 * the unit's clock after that (DashFollower on the unit). Its keys only count up and nothing is read back.
 */
function CarDisplay({ e }: { e: EmulatorState }) {
  const now = useNow(e.hour);
  const unit = bridge.clockText(now.getHours(), now.getMinutes(), true);
  const press = (key: "HOUR" | "MINUTE" | "CLOCK") => carCommand({ type: "Dash.Key", key });
  return (<>
    <Group footer="Switches the car's small display between the clock and the instant fuel consumption.">
      <Row label="Clock or Fuel"><button className="set-inline" onClick={() => press("CLOCK")}>Switch</button></Row>
    </Group>
    {e.shell.dashMatched ? (
      <Group title="Clock" footer="Changes of the unit's time, its zone and summer time go to the display. Match again after the car's battery was disconnected, or when the display has drifted.">
        <Row label="Follows the Unit's Clock" value={unit} />
        <Row label="Match Again" onPress={() => shellAction({ type: "SetDashMatched", on: false })} />
      </Group>
    ) : (
      <Group title="Match the Clock" footer="The display only counts up. Step it until it shows the unit's time, then tap Done; from then on it follows the unit's clock.">
        <Row label="Unit Time" value={unit} />
        <Row label="Display Hour"><button className="set-inline" onClick={() => press("HOUR")}>+1 Hour</button></Row>
        <Row label="Display Minute"><button className="set-inline" onClick={() => press("MINUTE")}>+1 Minute</button></Row>
        <Row label="The Display Shows This Time"><button className="set-inline" onClick={() => shellAction({ type: "SetDashMatched", on: true })}>Done</button></Row>
      </Group>
    )}
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
    <Group footer="Equalizer, balance, the listening position, loudness, bass and the speakers' filters.">
      <Row label="Sound Effects" onPress={() => shellAction({ type: "OpenSound" })} />
    </Group>
    <Group title="Calls" footer="How loud the other side of a call is in the car, for this drive: each start of the unit goes back to 20. Music keeps its own level.">
      <div className="set-row is-slider">
        <PillSlider label="Call volume" min={0} max={30} step={1} value={e.shell.dsp.callVolume} valueText={String(e.shell.dsp.callVolume)}
          onValue={(callVolume) => setSound({ ...e.shell.dsp, callVolume })}
          icon={<span className="pill-icon"><span className="icon i-phone-fill" aria-hidden="true" /></span>}
          readout={<span className="pill-readout" aria-hidden="true">{e.shell.dsp.callVolume}</span>} />
      </div>
    </Group>
    <Group title="Radio" footer="Powers an amplified antenna through the antenna lead. Off for a passive antenna.">
      <Row label="Antenna Power"><Switch label="Antenna Power" on={e.shell.dsp.antennaPower} onChange={(antennaPower) => setSound({ ...e.shell.dsp, antennaPower })} /></Row>
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

function ReverseCamera({ e }: { e: EmulatorState }) {
  const camera = e.shell.camera;
  const put = (patch: Partial<CameraSettings>) => setCamera({ ...camera, ...patch });
  // the screen's y grows downwards: the lines go up with a smaller value, shown as a higher number
  const height = -camera.linePos;
  return (<>
    <Group title="Picture" footer="Flips the picture left to right. Turn it on when things on the screen sit on the other side than in the car's mirrors.">
      <Row label="Mirror Image"><Switch label="Mirror Image" on={camera.mirror} onChange={(mirror) => put({ mirror })} /></Row>
    </Group>
    <Group title="Guidelines" footer={GUIDE_FOOTERS[camera.guidelines]}>
      <Row label="Lines">
        <Segmented<Guidelines> label="Guidelines" options={[["OFF", "Off"], ["FIXED", "Fixed"], ["STEERING", "Steering"]]} value={camera.guidelines} onChange={(guidelines) => put({ guidelines })} />
      </Row>
      {camera.guidelines !== "OFF" && (<>
        <div className="set-row cam-preview-row">
          <div className="camera-feed cam-preview" role="img" aria-label="Preview of the guidelines">
            <div className={`camera-scene${camera.mirror ? " is-mirrored" : ""}`} aria-hidden="true" />
            <GuideLines camera={camera} eps={e.eps} />
          </div>
        </div>
        <Row label="Height">
          <span className="set-stepper">
            <button aria-label="Move the lines down" disabled={camera.linePos >= LINE_POS_MAX} onClick={() => put({ linePos: Math.min(LINE_POS_MAX, camera.linePos + LINE_POS_STEP) })}><span className="icon i-caret-down" aria-hidden="true" /></button>
            <output aria-live="polite" aria-label="Guideline height">{height > 0 ? `+${height}` : height}</output>
            <button aria-label="Move the lines up" disabled={camera.linePos <= LINE_POS_MIN} onClick={() => put({ linePos: Math.max(LINE_POS_MIN, camera.linePos - LINE_POS_STEP) })}><span className="icon i-caret-up" aria-hidden="true" /></button>
          </span>
        </Row>
      </>)}
    </Group>
    <Group title="Music" footer="While reverse gear is in, music and the radio play quieter; they go back to their level when it comes out.">
      <Row label="Lower the Music">
        <Segmented<ReverseDip> label="Lower the music while reversing" options={[["NONE", "Off"], ["WEAK", "A Little"], ["MEDIUM", "Medium"], ["STRONG", "A Lot"]]} value={camera.dip} onChange={(dip) => put({ dip })} />
      </Row>
    </Group>
  </>);
}

/** The country names as the screen writes them; the unit takes them from Android's locale. */
const regionNames = new Intl.DisplayNames(["en"], { type: "region" });
const regionName = (code: string) => { try { return regionNames.of(code) ?? code; } catch { return code; } };

/** The countries to group numbers by, searchable by name, code or calling code ("+40"). */
function RegionPicker({ e, onDone }: { e: EmulatorState; onDone: () => void }) {
  const [query, setQuery] = useState("");
  const [typing, setTyping] = useState(false);
  const regions = useMemo(() => bridge.phoneRegions()
    .map((id) => ({ id, name: regionName(id), code: bridge.phoneCallingCode(id) }))
    .sort((a, b) => a.name.localeCompare(b.name)), []);
  const q = query.trim().toLowerCase().replace(/^\+/, "");
  const shown = q ? regions.filter((r) => r.name.toLowerCase().includes(q) || r.id.toLowerCase() === q || String(r.code).startsWith(q)) : regions;
  const pick = (region: string) => { shellAction({ type: "SetPhoneRegion", region }); onDone(); };
  return (<>
    <div className={`set-zones${typing ? " is-typing" : ""}`}>
      <div className="set-zones-head">
      <button className="set-back" onClick={onDone}><span className="icon i-caret-left" aria-hidden="true" />Phone</button>
      <div className="phone-search" role="search">
        <button className={`phone-search-field${typing ? " is-focused" : ""}`} aria-label={query ? `Search, ${query}` : "Search countries"} onClick={() => setTyping(true)}>
          <span className="icon i-magnifying-glass" aria-hidden="true" />
          <span className={query ? "phone-search-text" : "phone-search-placeholder"}>{query || "Search a country or +code"}</span>
          {typing && <span className="phone-search-caret" aria-hidden="true" />}
        </button>
        {(query || typing) && <button className="phone-search-cancel" onClick={() => { setQuery(""); setTyping(false); }}>Cancel</button>}
      </div>
      </div>
      <Group title="Number Country" footer={shown.length ? `${shown.length} countries` : undefined}>
        {shown.map((r) => (
          <button key={r.id} className="set-row is-button" aria-current={r.id === e.shell.phoneRegion || undefined} onClick={() => pick(r.id)}>
            <span className="set-row-label">{r.name}</span>
            <span className="set-row-value">+{r.code}</span>
            <span className={`icon i-check set-check${r.id === e.shell.phoneRegion ? "" : " is-hidden"}`} aria-hidden="true" />
          </button>
        ))}
        {!shown.length && <Row label={`No country matches “${query}”`} />}
      </Group>
    </div>
    <Keyboard open={typing} onKey={(c) => setQuery((v) => v + c)} onBackspace={() => setQuery((v) => v.slice(0, -1))}
      onSubmit={() => setTyping(false)} submitLabel="Search" submitEnabled />
  </>);
}

function Phone({ e }: { e: EmulatorState }) {
  const on = e.shell.lockTypingWhileMoving;
  const [picking, setPicking] = useState(false);
  if (picking) return <RegionPicker e={e} onDone={() => setPicking(false)} />;
  const region = e.shell.phoneRegion;
  return (<>
    <Group title="Numbers" footer={`Numbers written without + are grouped as in ${regionName(region)}. Calls dial the digits as they are.`}>
      <Row label="Number Country" value={`${regionName(region)} (+${bridge.phoneCallingCode(region)})`} onPress={() => setPicking(true)} />
    </Group>
    <Group title="Messages" footer="With this on, a message can be typed only while the car stands. Quick replies and calls work either way.">
      <Row label="Block Typing While Driving"><Switch label="Block Typing While Driving" on={on} onChange={(v) => shellAction({ type: "SetTypingLock", on: v })} /></Row>
    </Group>
    <Group title="Phone Screen" footer={e.shell.castVisible
      ? `While the car is on, phones find the unit as ${bridge.castName()} in Smart View or Screen Mirroring. Sharing pauses the music and the radio; they play again after.`
      : "Phones cannot find the unit to share their screen."}>
      <Row label="Visible to Phones"><Switch label="Visible to Phones" on={e.shell.castVisible} onChange={setCastVisible} /></Row>
    </Group>
  </>);
}

function Maps({ e }: { e: EmulatorState }) {
  const { installed, downloading } = e.maps;
  const [confirm, setConfirm] = useState<string | null>(null);
  return (<>
    <Group title="Voice Guidance" footer={`The spoken directions of a navigation app, through the unit. ${e.shell.dsp.musicUnderNav === 0
      ? "The music stops while a direction is spoken and comes back after."
      : e.shell.dsp.musicUnderNav === 100 ? "The music keeps its level under the voice."
      : `The music plays at ${e.shell.dsp.musicUnderNav}% of its level while a direction is spoken.`}`}>
      <div className="set-row is-slider">
        <PillSlider label="Voice guidance volume" min={0} max={100} step={5} value={e.shell.dsp.navVolume} valueText={`${e.shell.dsp.navVolume}%`}
          onValue={(navVolume) => setSound({ ...e.shell.dsp, navVolume })}
          icon={<span className="pill-icon"><span className="icon i-navigation-arrow-fill" aria-hidden="true" /></span>}
          readout={<span className="pill-readout" aria-hidden="true">{e.shell.dsp.navVolume}%</span>} />
      </div>
      <Row label="Music Under the Voice">
        <span className="set-stepper">
          <button aria-label="Quieter music under the navigation voice" disabled={e.shell.dsp.musicUnderNav <= 0} onClick={() => setSound({ ...e.shell.dsp, musicUnderNav: Math.max(0, e.shell.dsp.musicUnderNav - 10) })}><span className="icon i-minus" aria-hidden="true" /></button>
          <output aria-label="Music under the navigation voice" className="set-mix">{e.shell.dsp.musicUnderNav === 0 ? "Off" : `${e.shell.dsp.musicUnderNav}%`}</output>
          <button aria-label="Louder music under the navigation voice" disabled={e.shell.dsp.musicUnderNav >= 100} onClick={() => setSound({ ...e.shell.dsp, musicUnderNav: Math.min(100, e.shell.dsp.musicUnderNav + 10) })}><span className="icon i-plus" aria-hidden="true" /></button>
        </span>
      </Row>
    </Group>
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

/** Our apps' tiles by id; another app gets a tile coloured from its package, with its initial. */
const APP_MARK: Record<string, string> = { MAPS: "app-mark-nav", MUSIC: "app-mark-music", PHONE: "app-mark-phone", RADIO: "app-mark-radio", SETTINGS: "app-mark-settings", PHONE_SCREEN: "app-mark-cast" };
const markHue = (id: string) => [...id].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);

function AppName({ id, label }: { id: string; label: string }) {
  const own = APP_MARK[id];
  return (
    <span className="set-app-name">
      <span className={`set-app-icon ${own ?? "app-mark-other"}`} style={own ? undefined : { "--mark-hue": markHue(id) } as CSSProperties} aria-hidden="true">{own ? null : label.charAt(0)}</span>
      {label}
    </span>
  );
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
          <span className="set-row-label"><AppName id={a.id} label={a.label} /></span>
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
        <Row key={a.id} label={<AppName id={a.id} label={a.label} />}>
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
    <Group footer="Shows the Developer section after About: adb, touches and the frame rate on screen, the animation speed.">
      <Row label="Developer Settings"><Switch label="Developer Settings" on={e.shell.developer} onChange={(on) => shellAction({ type: "SetDeveloper", on })} /></Row>
    </Group>
    <Group footer={confirm ? "Theme, brightness, clock and sound settings go back to their defaults. Phones, networks and maps stay." : undefined}>
      {confirm
        ? <Row label="Reset All Settings" danger onPress={() => { shellAction({ type: "ResetSettings" }); setConfirm(false); }} />
        : <Row label="Reset Settings…" onPress={() => setConfirm(true)} />}
    </Group>
  </>);
}

/** The developer options that work from roadsterOS on this unit; every other one is in Android's own settings. */
function Developer({ e }: { e: EmulatorState }) {
  const { shell } = e;
  const [opened, setOpened] = useState(false);
  const [erasing, setErasing] = useState(false);
  const address = e.wifi.connected ? `${UNIT_IP}:5555` : null;
  return (<>
    <Group title="Debugging" footer={!shell.adbEnabled ? "adb cannot reach the unit until this is on again." : address ? `From a laptop on the same Wi-Fi: adb connect ${address}` : "Connect the unit to Wi-Fi to reach it with adb."}>
      <Row label="USB Debugging"><Switch label="USB Debugging" on={shell.adbEnabled} onChange={(on) => shellAction({ type: "SetAdb", on })} /></Row>
      {shell.adbEnabled && <Row label="Address" value={address ?? "Not on Wi-Fi"} />}
    </Group>
    <Group title="Screen" footer="On roadsterOS's own screens: a dot under each finger and the frames drawn each second. Fast runs every animation twice as fast, Off skips them.">
      <Row label="Show Touches"><Switch label="Show Touches" on={shell.showTouches} onChange={(on) => shellAction({ type: "SetShowTouches", on })} /></Row>
      <Row label="Show Frame Rate"><Switch label="Show Frame Rate" on={shell.showFrameRate} onChange={(on) => shellAction({ type: "SetShowFrameRate", on })} /></Row>
      <Row label="Animations">
        <Segmented<AnimationSpeed> label="Animation speed" options={[["NORMAL", "Normal"], ["FAST", "Fast"], ["OFF", "Off"]]} value={shell.animationSpeed} onChange={(speed) => shellAction({ type: "SetAnimationSpeed", speed })} />
      </Row>
    </Group>
    <Group title="System" footer={opened
      ? "On the unit this opens Android's own developer options over roadsterOS; the emulator has no Android behind it, so it only logs the request."
      : "Android's own developer options hold every other setting of the unit."}>
      <Row label="Restart roadsterOS" onPress={restartShell} />
      <Row label="Android Developer Options" onPress={() => { openAndroidSettings(); setOpened(true); }} />
    </Group>
    <Group footer={erasing
      ? "Erases everything roadsterOS keeps: every setting, the journal, map packs, the phones paired with the unit and the saved Wi-Fi networks. The unit then restarts into setup."
      : undefined}>
      {erasing ? (<>
        <Row label="Erase Everything" danger onPress={factoryReset} />
        <Row label="Cancel" onPress={() => setErasing(false)} />
      </>) : <Row label="Factory Reset…" onPress={() => setErasing(true)} />}
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
  { id: "dash", label: "Car Display", icon: "i-car-fill", tint: "#ff3b30" },
  { id: "camera", label: "Reverse Camera", icon: "i-camera", tint: "#ff9f0a" },
  { id: "home", label: "Home Screen", icon: "i-house-fill", tint: "#5e5ce6" },
  { id: "maps", label: "Maps", icon: "i-map-trifold-fill", tint: "#30d158" },
  { id: "about", label: "About", icon: "i-info-fill", tint: "#8e8e93" },
  { id: "developer", label: "Developer", icon: "i-code", tint: "#636366" },
];

/** What the list shows beside a section, so most states read without opening it. */
function summary(id: Page, e: EmulatorState): string {
  if (id === "wifi") return !e.wifiOn ? "Off" : e.wifi.connected ?? "On";
  if (id === "bluetooth") return !e.btOn ? "Off" : e.car.btConnected ? e.device : "On";
  if (id === "location") return e.locationOn ? "On" : "Off";
  if (id === "dash") return e.shell.dashMatched ? "Synced" : "";
  return "";
}

/** Settings: the sections on the left, the chosen one on the right, as a tablet's settings in landscape. */
export function SettingsApp({ open }: { open: boolean }) {
  const e = useEmulator();
  const [page, setPage] = useState<Page>("wifi");
  const section = useCloseOntoCard(open);
  // the Developer section shows only while it is switched on in About
  const pages = PAGES.filter((p) => p.id !== "developer" || e.shell.developer);
  const shown = pages.some((p) => p.id === page) ? page : "about";
  const current = PAGES.find((p) => p.id === shown)!;
  return (
    <section ref={section} className={`music-app settings-app${open ? " is-open" : ""}`} aria-label="Settings" aria-hidden={!open} inert={!open}>
      <button className="glass-button music-close" aria-label="Close settings" onClick={() => shellAction({ type: "Home" })}>
        <span className="icon i-caret-down" aria-hidden="true" />
      </button>
      <nav className="set-nav" aria-label="Settings sections">
        <h2 className="set-title">Settings</h2>
        <ul>
          {pages.map((p) => (
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
        {page === "dash" && <CarDisplay e={e} />}
        {page === "camera" && <ReverseCamera e={e} />}
        {shown === "about" && <About e={e} />}
        {shown === "developer" && <Developer e={e} />}
      </div>
    </section>
  );
}
