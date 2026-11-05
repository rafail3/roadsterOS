import { useMemo, useRef, useState, type PointerEvent } from "react";
import { bridge, type CallType, type Contact } from "../car/bridge";
import { carCommand, shellAction, turnOnBluetooth, useEmulator, useNow, type EmulatorState } from "../car/store";
import { holdToRepeat } from "./controls";
import { useCloseOntoCard } from "./media";
import { NoPhone } from "./MusicApp";

const KEYS: [string, string][] = [
  ["1", ""], ["2", "ABC"], ["3", "DEF"],
  ["4", "GHI"], ["5", "JKL"], ["6", "MNO"],
  ["7", "PQRS"], ["8", "TUV"], ["9", "WXYZ"],
  ["*", ""], ["0", "+"], ["#", ""],
];
/** Holding 0 types the "+" of an international number, as on a phone. */
const PLUS_HOLD_MS = 550;
const DAY_MS = 86_400_000;

const TYPE_ICON: Record<CallType, string> = { INCOMING: "i-phone-incoming", OUTGOING: "i-phone-outgoing", MISSED: "i-phone-x" };
const TYPE_LABEL: Record<CallType, string> = { INCOMING: "Incoming", OUTGOING: "Outgoing", MISSED: "Missed" };

const dial = (number: string) => carCommand({ type: "Phone.Dial", number });

/** When a call happened: the time today, then Yesterday, the weekday within a week, else the date. */
function when(atMs: number, now: Date) {
  const d = new Date(atMs);
  const midnight = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((midnight(now) - midnight(d)) / DAY_MS);
  if (days <= 0) return d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
  if (days === 1) return "Yesterday";
  if (days < 7) return d.toLocaleDateString("en-GB", { weekday: "long" });
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "short" });
}

/** Signal bars and battery as the phone reports them over the hands-free link, both 0..5. */
function PhoneChip({ emulator }: { emulator: EmulatorState }) {
  const { device, phoneStatus: { battery, signal, operator } } = emulator;
  return (
    <button className="phone-chip" aria-label={`${device}, ${operator}, signal ${signal} of 5, battery ${battery * 20}%, choose device`} onClick={() => shellAction({ type: "OpenDevices" })}>
      <span className="phone-bars" aria-hidden="true">{[1, 2, 3, 4, 5].map((b) => <i key={b} className={b <= signal ? "is-on" : undefined} />)}</span>
      <span className="phone-chip-name">{device}</span>
      <span className={`phone-battery${battery <= 1 ? " is-low" : ""}`} aria-hidden="true"><i style={{ transform: `scaleX(${battery / 5})` }} /></span>
    </button>
  );
}

function Recents({ emulator }: { emulator: EmulatorState }) {
  const now = useNow(null);
  const rows = useMemo(() => bridge.phoneRecents(emulator.calls, emulator.contacts), [emulator.calls, emulator.contacts]);
  if (!rows.length) return <p className="phone-empty">No recent calls.</p>;
  return (
    <ul className="phone-list" aria-label="Recent calls">
      {rows.map((r) => (
        <li key={`${r.atMs}-${r.number}`}>
          <button className={`phone-row${r.type === "MISSED" ? " is-missed" : ""}`} aria-label={`Call ${r.name || r.number}, ${TYPE_LABEL[r.type]}${r.count > 1 ? `, ${r.count} calls` : ""}, ${when(r.atMs, now)}`} onClick={() => dial(r.number)}>
            <span className={`icon phone-row-type ${TYPE_ICON[r.type]}`} aria-hidden="true" />
            <span className="phone-row-text">
              <span className="phone-row-name">{r.name || r.number}{r.count > 1 && <span className="phone-row-count"> ({r.count})</span>}</span>
              <span className="phone-row-sub">{r.name ? r.label || r.number : TYPE_LABEL[r.type]}</span>
            </span>
            <span className="phone-row-when">{when(r.atMs, now)}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}

function Contacts({ emulator }: { emulator: EmulatorState }) {
  const sections = useMemo(() => bridge.phoneSections(emulator.contacts), [emulator.contacts]);
  const [open, setOpen] = useState<string | null>(null);
  const heads = useRef<Record<string, HTMLElement | null>>({});
  if (!sections.length) return <p className="phone-empty">Contacts from {emulator.device} show here once the phone shares them.</p>;
  const pick = (c: Contact) => (c.numbers.length === 1 ? dial(c.numbers[0].number) : setOpen(open === c.name ? null : c.name));
  return (
    <div className="phone-contacts">
      <div className="phone-list" role="list" aria-label="Contacts">
        {sections.map(({ letter, contacts }) => (
          <section key={letter} role="listitem" aria-label={letter} ref={(el) => { heads.current[letter] = el; }}>
            <h2 className="phone-letter">{letter}</h2>
            {contacts.map((c) => (
              <div key={c.name} className={`phone-contact${open === c.name ? " is-open" : ""}`}>
                <button className="phone-row" aria-expanded={c.numbers.length > 1 ? open === c.name : undefined} aria-label={c.numbers.length === 1 ? `Call ${c.name}` : `${c.name}, ${c.numbers.length} numbers`} onClick={() => pick(c)}>
                  <span className="phone-avatar" aria-hidden="true">{bridge.initials(c.name) || <span className="icon i-user" />}</span>
                  <span className="phone-row-text"><span className="phone-row-name">{c.name}</span></span>
                  {c.numbers.length > 1 && <span className="phone-row-when">{c.numbers.length} numbers</span>}
                </button>
                {open === c.name && c.numbers.map((n) => (
                  <button key={n.number} className="phone-number" aria-label={`Call ${c.name}, ${n.label || n.number}`} onClick={() => dial(n.number)}>
                    <span className="icon i-phone-fill" aria-hidden="true" />
                    <span className="phone-row-sub">{n.label}</span>
                    <span className="phone-number-digits">{n.number}</span>
                  </button>
                ))}
              </div>
            ))}
          </section>
        ))}
      </div>
      {/* the letters down the edge jump to their section; scrolling a long phone book while parked is slow */}
      <nav className="phone-index" aria-label="Jump to letter">
        {sections.map(({ letter }) => (
          <button key={letter} onClick={() => heads.current[letter]?.scrollIntoView({ block: "start", behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" })}>{letter}</button>
        ))}
      </nav>
    </div>
  );
}

/** A dial pad key; used by the keypad here and the tones keypad on the call screen. */
export function PadKey({ digit, letters, onPress }: { digit: string; letters: string; onPress: (key: string) => void }) {
  const hold = useRef<{ timer?: ReturnType<typeof setTimeout>; held: boolean }>({ held: false });
  const down = (e: PointerEvent) => {
    if (e.button !== 0 || digit !== "0") return;
    hold.current.held = false;
    hold.current.timer = setTimeout(() => { hold.current.held = true; onPress("+"); }, PLUS_HOLD_MS);
  };
  const up = () => clearTimeout(hold.current.timer);
  return (
    <button className="pad-key" aria-label={letters && digit !== "0" ? `${digit}, ${letters}` : digit} onPointerDown={down} onPointerUp={up} onPointerLeave={up} onPointerCancel={up}
      onClick={() => { if (hold.current.held) hold.current.held = false; else onPress(digit); }}>
      <span className="pad-digit">{digit}</span>
      <span className="pad-letters" aria-hidden="true">{letters}</span>
    </button>
  );
}

function Keypad({ emulator }: { emulator: EmulatorState }) {
  const [typed, setTyped] = useState("");
  const match = useMemo(() => bridge.phoneSuggest(emulator.contacts, typed), [emulator.contacts, typed]);
  // the call key with nothing typed brings back the number called last, as phones do
  const last = emulator.calls.find((c) => c.type === "OUTGOING")?.number ?? "";
  const call = () => { if (typed) { dial(typed); setTyped(""); } else setTyped(last); };
  return (
    <div className="phone-pad">
      <div className="phone-typed" aria-live="polite">
        <output className="phone-typed-number" aria-label="Number">{typed}</output>
        <span className="phone-typed-match">{match && `${match.name}${match.label ? ` · ${match.label}` : ""}`}</span>
      </div>
      <div className="pad-grid">
        {KEYS.map(([d, l]) => <PadKey key={d} digit={d} letters={l} onPress={(k) => setTyped((t) => t + k)} />)}
      </div>
      <div className="pad-foot">
        <span aria-hidden="true" />
        <button className="call-key is-accept" aria-label={typed ? `Call ${match?.name ?? typed}` : "Last number"} disabled={!typed && !last} onClick={call}>
          <span className="icon i-phone-fill" aria-hidden="true" />
        </button>
        <button className={`pad-delete${typed ? "" : " is-hidden"}`} aria-label="Delete" tabIndex={typed ? 0 : -1} {...holdToRepeat(() => setTyped((t) => t.slice(0, -1)))}>
          <span className="icon i-backspace-fill" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}

function BluetoothOff({ turningOn }: { turningOn: boolean }) {
  return (
    <div className="music-nophone" aria-live="polite">
      <span className="music-nophone-icon" aria-hidden="true"><span className="icon i-bluetooth" /></span>
      <h2>{turningOn ? "Turning on Bluetooth…" : "Bluetooth is off"}</h2>
      <p>Calls go through a phone connected over Bluetooth.</p>
      {!turningOn && <button className="music-nophone-pair" onClick={turnOnBluetooth}><span className="icon i-bluetooth" aria-hidden="true" />Turn On Bluetooth</button>}
    </div>
  );
}

/**
 * The phone app. It opens only on a phone connected for calls (core-car PhoneGate): with Bluetooth off it switches
 * it on, then asks for a phone; after that the history and the phone book on the left, the keypad on the right.
 */
export function PhoneApp({ open }: { open: boolean }) {
  const emulator = useEmulator();
  const { car, btOn, btTurningOn } = emulator;
  const screen = bridge.phoneScreen(btOn, car.hfpConnected);
  const section = useCloseOntoCard(open);
  const [tab, setTab] = useState<"recents" | "contacts">("recents");

  return (
    <section ref={section} className={`music-app phone-app${open ? " is-open" : ""}${screen === "READY" ? " is-ready" : ""}`} aria-label="Phone" aria-hidden={!open} inert={!open}>
      <button className="glass-button music-close" aria-label="Close phone" onClick={() => shellAction({ type: "Home" })}>
        <span className="icon i-caret-down" aria-hidden="true" />
      </button>

      {screen === "BT_OFF" && <BluetoothOff turningOn={btTurningOn} />}
      {screen === "CONNECT" && <NoPhone emulator={emulator} forCalls />}
      {screen === "READY" && (<>
        <div className="phone-side">
          <header className="phone-head">
            <div className="phone-tabs" role="tablist" aria-label="Phone">
              <button role="tab" id="phone-tab-recents" aria-selected={tab === "recents"} aria-controls="phone-panel" onClick={() => setTab("recents")}>
                <span className="icon i-clock-fill" aria-hidden="true" />Recents
              </button>
              <button role="tab" id="phone-tab-contacts" aria-selected={tab === "contacts"} aria-controls="phone-panel" onClick={() => setTab("contacts")}>
                <span className="icon i-user-circle-fill" aria-hidden="true" />Contacts
              </button>
            </div>
            <PhoneChip emulator={emulator} />
          </header>
          <div className="phone-panel" id="phone-panel" role="tabpanel" aria-labelledby={`phone-tab-${tab}`} key={tab}>
            {tab === "recents" ? <Recents emulator={emulator} /> : <Contacts emulator={emulator} />}
          </div>
        </div>
        <Keypad emulator={emulator} />
      </>)}
    </section>
  );
}
