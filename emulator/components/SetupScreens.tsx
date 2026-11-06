import { useEffect, useRef, useState, type ReactNode } from "react";
import { bridge, type Design, type Download, type Prepare, type PrepareTask, type SetupStep, type TaskState, type ThemeMode } from "../car/bridge";
import { setLocation, setReleaseOut, setWifi, shellAction, useEmulator, WIFI_NETWORKS, wifiConnect, wifiScan, type WifiNetwork } from "../car/store";
import { CubeMark, pixelFont } from "./Boot";
import { Keyboard } from "./Keyboard";
import { WifiBars } from "./WifiBars";

// The setup's screens after the start screen: the home screen's dark glass and type, with the boot screen's tube
// (scanlines), its amber and its pixel captions. One accent across the setup: the boot amber.

/** The screens the progress bar counts, in order. */
const STEPS: SetupStep[] = ["CAR_NAME", "PREPARE", "WIFI", "UPDATE", "LOCATION", "LOOK", "FILES", "WELCOME"];

/** Text in the boot screen's 5x7 pixel font; [cell] in canvas pixels (2.5 draws 2 of the unit's pixels per cell). */
export function PixelText({ text, cell = 2.5, className }: { text: string; cell?: number; className?: string }) {
  const font = pixelFont();
  const rects: ReactNode[] = [];
  [...text.toUpperCase()].forEach((ch, k) => {
    const g = font[ch] ?? font[" "];
    for (let i = 0; i < 35; i++) if (g[i] === "#") rects.push(<rect key={`${k}-${i}`} x={(k * 6 + (i % 5)) * cell} y={Math.floor(i / 5) * cell} width={cell * 0.8} height={cell * 0.8} />);
  });
  return (
    <svg className={className} width={text.length * 6 * cell} height={7 * cell} role="img" aria-label={text}>
      <g fill="currentColor">{rects}</g>
    </svg>
  );
}

/** Where the setup is: one block of dots per screen, full when done, dithered on the current one, faint ahead. */
function Progress({ step }: { step: SetupStep }) {
  const at = STEPS.indexOf(step);
  return (
    <div className="sf-progress" role="progressbar" aria-valuemin={1} aria-valuemax={STEPS.length} aria-valuenow={at + 1} aria-label="Setup progress">
      {STEPS.map((s, i) => (
        <span key={s} className={`sf-block${i < at ? " is-done" : i === at ? " is-now" : ""}`}>
          {Array.from({ length: 18 }, (_, d) => <i key={d} />)}
        </span>
      ))}
    </div>
  );
}

/** A bar of dots that fills left to right, the boot screen's dither at its leading edge. */
function DotBar({ fraction, busy }: { fraction: number; busy?: boolean }) {
  const cols = 64;
  const full = Math.round(fraction * cols);
  return (
    <span className={`sf-dots${busy ? " is-busy" : ""}`} aria-hidden="true">
      {Array.from({ length: cols * 3 }, (_, i) => {
        const c = Math.floor(i / 3);
        // two columns of dither ahead of the filled part, as the boot screen's bars end
        const lit = c < full || (c < full + 2 && (i + c) % 2 === 0 && fraction > 0 && fraction < 1);
        return <i key={i} className={lit ? "is-lit" : undefined} />;
      })}
    </span>
  );
}

function Frame({ step, title, text, children, next, nextLabel = "Continue", canNext = true, back = true, extra }: {
  step: SetupStep; title: string; text: string; children?: ReactNode; next: () => void; nextLabel?: string; canNext?: boolean; back?: boolean; extra?: ReactNode;
}) {
  const at = STEPS.indexOf(step);
  return (
    <section className="sf" aria-label="Setup">
      <header className="sf-bar">
        <PixelText text={`Setup ${at + 1} of ${STEPS.length}`} className="sf-caption" />
        <Progress step={step} />
      </header>
      <div className="sf-head">
        <CubeMark size={104} />
        <div className="sf-heading">
          <h1 className="sf-title">{title}</h1>
          <p className="sf-text">{text}</p>
        </div>
        <div className="sf-actions">
          {extra}
          {back && at > 0 && <button className="sf-button" onClick={() => shellAction({ type: "SetupBack" })}>Back</button>}
          <button className="sf-button is-primary" disabled={!canNext} onClick={next}>{nextLabel}</button>
        </div>
      </div>
      {children}
    </section>
  );
}

/** The car's name, typed on our keyboard; it is also the name phones see when they share their screen. */
function CarName({ saved, next }: { saved: string; next: () => void }) {
  const [name, setName] = useState(saved);
  // the keyboard comes up when the field is touched, and goes with Done
  const [typing, setTyping] = useState(false);
  const trimmed = name.trim();
  const save = () => {
    if (!trimmed) return;
    shellAction({ type: "SetCarName", name });
    next();
  };
  return (
    <Frame step="CAR_NAME" title="Name your car" text="Phones see this name when they share their screen with the car." next={save} canNext={trimmed !== ""}>
      <button className={`sf-field${typing ? " is-typing" : ""}`} aria-label={`Car name${name ? `: ${name}` : ""}`} onClick={() => setTyping(true)}>
        <span className="sf-value">{name}</span>
        {typing && <span className="sf-cursor" aria-hidden="true" />}
      </button>
      <Keyboard open={typing} onKey={(c) => setName((n) => (n + c).slice(0, 24))} onBackspace={() => setName((n) => n.slice(0, -1))}
        onSubmit={() => setTyping(false)} submitLabel="Done" submitEnabled />
    </Frame>
  );
}

const TASKS: { task: PrepareTask; label: string; text: string }[] = [
  { task: "KEYS", label: "System keys", text: "The firmware trusts roadsterOS with the system's own permissions" },
  { task: "PACKAGES", label: "Packages", text: "The apps the car does not need are switched off" },
  { task: "PERMISSIONS", label: "Permissions", text: "Location, Bluetooth, the phone and the clock" },
  { task: "HOME", label: "Home screen", text: "roadsterOS opens whenever the car starts" },
];
const STATE_LABEL: Record<TaskState, string> = { WAITING: "Waiting", RUNNING: "Working", DONE: "Done", FAILED: "Failed", SKIPPED: "Skipped" };
/** How long each task takes on the emulated unit. */
const TASK_MS: Record<PrepareTask, number> = { KEYS: 700, PACKAGES: 1600, PERMISSIONS: 1100, HOME: 600 };

/** The system preparation: the four tasks run one after the other by themselves; a failed one can run again. */
function PrepareScreen({ next }: { next: () => void }) {
  const [p, setP] = useState<Prepare>(() => bridge.setupPrepare());
  const running = (Object.keys(p.tasks) as PrepareTask[]).find((t) => p.tasks[t] === "RUNNING") ?? null;
  // the next task starts as soon as none runs; a running one finishes after its time
  useEffect(() => { if (!running && p.next) setP(bridge.setupPrepareStep(p, "running", p.next)); }, [p, running]);
  useEffect(() => {
    if (!running) return;
    const timer = setTimeout(() => setP((now) => bridge.setupPrepareStep(now, "done", running)), TASK_MS[running]);
    return () => clearTimeout(timer);
  }, [running]);
  return (
    <Frame step="PREPARE" title="Preparing the system" text="roadsterOS takes over the unit. This runs once." next={next} canNext={p.ok}
      nextLabel={p.finished && !p.ok ? "Try again" : "Continue"}>
      <div className="sf-meter">
        <DotBar fraction={p.progress} busy={!p.finished} />
        <PixelText text={`${Math.round(p.progress * 100)}%`} className="sf-caption" />
      </div>
      <ul className="sf-list" aria-live="polite">
        {TASKS.map(({ task, label, text }) => {
          const state = p.tasks[task];
          return (
            <li key={task} className={`sf-row is-${state.toLowerCase()}`}>
              <span className="sf-row-text"><span className="sf-row-name">{label}</span><span className="sf-row-detail">{text}</span></span>
              <PixelText text={STATE_LABEL[state]} className="sf-state" />
            </li>
          );
        })}
      </ul>
      {p.finished && !p.ok && <button className="sf-button sf-retry" onClick={() => setP(bridge.setupPrepareStep(p, "retry"))}>Run again</button>}
    </Frame>
  );
}

/** Wi-Fi for updates and maps: the networks in range, joined with one tap or a password; skipping is fine. */
function WifiScreen({ next }: { next: () => void }) {
  const { wifiOn, wifi, locationOn } = useEmulator();
  const [joining, setJoining] = useState<string | null>(null);
  const [password, setPassword] = useState("");
  const [typing, setTyping] = useState(false);
  useEffect(() => {
    if (!wifiOn || !locationOn) return;
    wifiScan();
    const timer = setInterval(wifiScan, 10000);
    return () => clearInterval(timer);
  }, [wifiOn, locationOn]);
  useEffect(() => { setPassword(""); setTyping(false); }, [joining]);
  useEffect(() => { if (joining && wifi.connected === joining) setJoining(null); }, [joining, wifi.connected]);
  const seen = WIFI_NETWORKS.filter((n) => wifi.seen.includes(n.ssid) || wifi.connected === n.ssid);
  const pick = (n: WifiNetwork) => (n.secure && !wifi.saved.includes(n.ssid) ? setJoining(n.ssid) : wifiConnect(n.ssid));
  const status = (n: WifiNetwork) => wifi.connected === n.ssid ? "Connected" : wifi.connecting === n.ssid ? "Connecting…" : n.secure ? "Secured" : "Open";
  const connected = wifi.connected !== null;

  if (joining) {
    const send = () => { if (password.length >= 8) wifiConnect(joining, password); };
    return (
      <Frame step="WIFI" title={joining} text={wifi.wrongPassword === joining ? "Incorrect password. Try again." : "Enter the password, at least 8 characters."}
        next={send} nextLabel={wifi.connecting === joining ? "Connecting…" : "Join"} canNext={password.length >= 8 && wifi.connecting !== joining} back={false}
        extra={<button className="sf-button" onClick={() => setJoining(null)}>Cancel</button>}>
        <button className={`sf-field${typing ? " is-typing" : ""}`} aria-label="Password" onClick={() => setTyping(true)}>
          <span className="sf-value">{"•".repeat(password.length)}</span>
          {typing && <span className="sf-cursor" aria-hidden="true" />}
        </button>
        <Keyboard open={typing} onKey={(c) => setPassword((p) => p + c)} onBackspace={() => setPassword((p) => p.slice(0, -1))}
          onSubmit={() => { setTyping(false); send(); }} submitLabel="Join" submitEnabled={password.length >= 8} />
      </Frame>
    );
  }
  return (
    <Frame step="WIFI" title="Connect to Wi-Fi" text="For updates and maps. You can skip it and connect later from the top bar." next={next} nextLabel={connected ? "Continue" : "Skip"}>
      {!wifiOn || !locationOn ? (
        <div className="sf-empty">
          <p>{!wifiOn ? "Wi-Fi is off." : "Finding networks needs Location on."}</p>
          <button className="sf-button is-primary" onClick={() => { setLocation(true); setWifi(true); }}>{!wifiOn ? "Turn on Wi-Fi" : "Turn on Location"}</button>
        </div>
      ) : (
        <ul className="sf-list sf-networks" aria-live="polite">
          {seen.map((n) => (
            <li key={n.ssid}>
              <button className={`sf-row${wifi.connected === n.ssid ? " is-done" : ""}`} disabled={wifi.connecting !== null} onClick={() => pick(n)}>
                <span className="sf-row-text"><span className="sf-row-name">{n.ssid}</span><span className="sf-row-detail">{status(n)}</span></span>
                <span className="sf-signal">{n.secure && <span className="icon i-lock" aria-label="Secured" />}<WifiBars level={n.signal} /></span>
              </button>
            </li>
          ))}
          {seen.length < WIFI_NETWORKS.length && <li className="sf-row is-waiting"><PixelText text="Searching" className="sf-state" /></li>}
        </ul>
      )}
    </Frame>
  );
}

/** The download of a new version: percent, speed, size and time left, then a countdown and a restart. */
function UpdateScreen({ version, next }: { version: string; next: () => void }) {
  const [d, setD] = useState<Download | null>(null);
  const [restartIn, setRestartIn] = useState<number | null>(null);
  const last = useRef(0);
  const install = () => {
    // the emulated release: a 2.6 MB APK arriving at a few hundred KB a second
    last.current = performance.now();
    setD(bridge.downloadStep({ total: 2_617_448, done: 0, bytesPerSecond: 0 }, 0, 0));
  };
  useEffect(() => {
    if (!d || d.done >= d.total) return;
    const timer = setTimeout(() => {
      const now = performance.now();
      const ms = now - last.current;
      last.current = now;
      setD(bridge.downloadStep(d, Math.round((380 + Math.random() * 260) * 1024 * ms / 1000), ms));
    }, 200);
    return () => clearTimeout(timer);
  }, [d]);
  useEffect(() => {
    if (!d || d.done < d.total || restartIn !== null) return;
    setReleaseOut(null);
    setRestartIn(bridge.restartInS());
  }, [d, restartIn]);
  useEffect(() => {
    if (restartIn === null) return;
    // the new version is in: the setup moves past this screen, then the unit restarts and resumes there
    if (restartIn <= 0) { next(); window.location.reload(); return; }
    const timer = setTimeout(() => setRestartIn(restartIn - 1), 1000);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [restartIn]);

  const done = d !== null && d.done >= d.total;
  return (
    <Frame step="UPDATE" title={`roadsterOS ${version} is out`} text="Install it now, or later from Settings > About."
      next={d ? () => undefined : install} nextLabel={d ? (done ? "Installed" : "Installing…") : "Install now"} canNext={!d}
      back={!d} extra={!d && <button className="sf-button" onClick={next}>Later</button>}>
      {d && (
        <div className="sf-download" aria-live="polite">
          <div className="sf-meter">
            <DotBar fraction={d.fraction} busy={!done} />
            <PixelText text={`${d.percent}%`} className="sf-caption" />
          </div>
          <p className="sf-figures">
            {done ? <>Installed. Restarting in <b>{restartIn ?? bridge.restartInS()} s</b></> : <>{d.doneText} of {d.totalText} · {d.speed}{d.left ? ` · ${d.left}` : ""}</>}
          </p>
        </div>
      )}
    </Frame>
  );
}

/** Where the car is: the country from GPS gives the map, the time zone and how phone numbers are written. */
function LocationScreen({ next }: { next: () => void }) {
  const { country, timeZone, maps, shell } = useEmulator();
  const name = (code: string) => new Intl.DisplayNames(["en"], { type: "region" }).of(code) ?? code;
  const city = timeZone.split("/").pop()?.replace(/_/g, " ") ?? timeZone;
  const here = country || "RO";
  const rows = [
    { name: "Map", value: maps.installed.includes(here) ? `${name(here)}, on the unit` : `${name(here)}, downloads on Wi-Fi` },
    { name: "Time zone", value: city },
    { name: "Phone numbers", value: name(shell.phoneRegion) },
  ];
  return (
    <Frame step="LOCATION" title="Where you drive" text="Found from the GPS. Each one can be changed later in Settings." next={() => {
      if (country && country !== shell.phoneRegion) shellAction({ type: "SetPhoneRegion", region: country });
      next();
    }}>
      <ul className="sf-list">
        {rows.map((r) => (
          <li key={r.name} className="sf-row is-done">
            <span className="sf-row-text"><span className="sf-row-name">{r.name}</span><span className="sf-row-detail">{r.value}</span></span>
            <PixelText text="Found" className="sf-state" />
          </li>
        ))}
      </ul>
    </Frame>
  );
}

const DESIGNS: { id: Design; label: string; text: string }[] = [
  { id: "SOLID", label: "Solid", text: "Flat panels, the clearest at a glance" },
  { id: "GLASSY", label: "Glassy", text: "Frosted glass over the background" },
];
const THEMES: { id: ThemeMode; label: string }[] = [{ id: "AUTO", label: "Auto" }, { id: "LIGHT", label: "Light" }, { id: "DARK", label: "Dark" }];

/** The look: Solid or Glassy, and the theme; both apply at once and stay changeable in Settings. */
function LookScreen({ next }: { next: () => void }) {
  const { shell } = useEmulator();
  return (
    <Frame step="LOOK" title="Choose the look" text="Auto follows the headlights: dark at night, light by day." next={next}>
      <div className="sf-looks" role="radiogroup" aria-label="Design">
        {DESIGNS.map((d) => (
          <button key={d.id} role="radio" aria-checked={shell.design === d.id} className={`sf-look${shell.design === d.id ? " is-on" : ""}`}
            onClick={() => shellAction({ type: "SetDesign", design: d.id })}>
            <img className="sf-look-picture" src={`/images/setup/look-${d.id.toLowerCase()}.png`} alt="" width={420} height={246} />
            <span className="sf-row-name">{d.label}</span>
            <span className="sf-row-detail">{d.text}</span>
          </button>
        ))}
      </div>
      <div className="sf-segments" role="radiogroup" aria-label="Theme">
        {THEMES.map((t) => (
          <button key={t.id} role="radio" aria-checked={shell.theme === t.id} className={`sf-segment${shell.theme === t.id ? " is-on" : ""}`}
            onClick={() => shellAction({ type: "SetTheme", mode: t.id })}>{t.label}</button>
        ))}
      </div>
    </Frame>
  );
}

/** The files the OS ships with, checked or installed: the map of Romania (already on the unit) and the fonts. */
function FilesScreen({ next }: { next: () => void }) {
  const files = [{ name: "Map of Romania", size: "363 MB", ms: 1400 }, { name: "Fonts", size: "1.8 MB", ms: 900 }];
  const [at, setAt] = useState(0);
  const [fraction, setFraction] = useState(0);
  useEffect(() => {
    if (at >= files.length) return;
    const start = performance.now();
    const timer = setInterval(() => {
      const f = Math.min(1, (performance.now() - start) / files[at].ms);
      setFraction(f);
      if (f >= 1) { clearInterval(timer); setAt(at + 1); setFraction(0); }
    }, 60);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [at]);
  return (
    <Frame step="FILES" title="Installing files" text="Everything the car needs offline. Other countries' maps come when you drive there." next={next} canNext={at >= files.length}>
      <ul className="sf-list" aria-live="polite">
        {files.map((f, i) => (
          <li key={f.name} className={`sf-row${i < at ? " is-done" : i === at ? " is-running" : " is-waiting"}`}>
            <span className="sf-row-text"><span className="sf-row-name">{f.name}</span><span className="sf-row-detail">{f.size}</span></span>
            {i === at ? <DotBar fraction={fraction} busy /> : <PixelText text={i < at ? "Done" : "Waiting"} className="sf-state" />}
          </li>
        ))}
      </ul>
    </Frame>
  );
}

/** The last screen: the car's name and the way home. */
function WelcomeScreen({ carName, next }: { carName: string; next: () => void }) {
  return (
    <Frame step="WELCOME" title={carName ? `${carName} is ready` : "The car is ready"} text="Everything is set. Settings holds it all if you want to change something."
      next={next} nextLabel="Start driving" />
  );
}

/** The setup screen for [step]; the start screen and the boot screen are drawn by Boot. */
export function SetupScreen({ step, carName, next }: { step: SetupStep; carName: string; next: () => void }) {
  const { releaseOut } = useEmulator();
  switch (step) {
    case "CAR_NAME": return <CarName saved={carName} next={next} />;
    case "PREPARE": return <PrepareScreen next={next} />;
    case "WIFI": return <WifiScreen next={next} />;
    case "UPDATE": return <UpdateScreen version={releaseOut ?? ""} next={next} />;
    case "LOCATION": return <LocationScreen next={next} />;
    case "LOOK": return <LookScreen next={next} />;
    case "FILES": return <FilesScreen next={next} />;
    default: return <WelcomeScreen carName={carName} next={next} />;
  }
}
