import { useEffect, useMemo, useRef, useState, type PointerEvent } from "react";
import { bridge, type CallType, type Contact } from "../car/bridge";
import { carCommand, formatNumber, markRead, openThread, shellAction, turnOnBluetooth, useEmulator, useNow, type EmulatorState } from "../car/store";
import { holdToRepeat } from "./controls";
import { useCloseOntoCard } from "./media";
import { Keyboard } from "./Keyboard";
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

/** Five signal bars, the phone's 0..5 over the hands-free link. */
function SignalBars({ signal }: { signal: number }) {
  return <span className="phone-bars" aria-hidden="true">{[1, 2, 3, 4, 5].map((b) => <i key={b} className={b <= signal ? "is-on" : undefined} />)}</span>;
}

/** The phone's network in the status bar: its signal, the operator's name, and roaming when abroad. */
export function PhoneNetwork({ status: { signal, operator, roaming, battery } }: { status: EmulatorState["phoneStatus"] }) {
  return (
    <span className="bar-network" role="img" aria-label={`${operator || "Phone network"}, signal ${signal} of 5${roaming ? ", roaming" : ""}, phone battery ${battery * 20}%`}>
      <SignalBars signal={signal} />
      {operator && <span className="bar-text">{operator}</span>}
      {roaming && <span className="bar-roaming">Roaming</span>}
      <Battery level={battery} />
    </span>
  );
}

/** The phone's battery, 0..5 over the hands-free link; red at the last step. */
function Battery({ level }: { level: number }) {
  return <span className={`phone-battery${level <= 1 ? " is-low" : ""}`} aria-hidden="true"><i style={{ transform: `scaleX(${level / 5})` }} /></span>;
}

/** Signal bars and battery as the phone reports them over the hands-free link, both 0..5. */
function PhoneChip({ emulator }: { emulator: EmulatorState }) {
  const { device, phoneStatus: { battery, signal, operator } } = emulator;
  return (
    <button className="phone-chip" aria-label={`${device}, ${operator}, signal ${signal} of 5, battery ${battery * 20}%, choose device`} onClick={() => shellAction({ type: "OpenDevices" })}>
      <SignalBars signal={signal} />
      <span className="phone-chip-name">{device}</span>
      <Battery level={battery} />
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
              <span className="phone-row-name">{r.name || formatNumber(r.number)}{r.count > 1 && <span className="phone-row-count"> ({r.count})</span>}</span>
              <span className="phone-row-sub">{r.name ? r.label || formatNumber(r.number) : TYPE_LABEL[r.type]}</span>
            </span>
            <span className="phone-row-when">{when(r.atMs, now)}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}

/** What a contacts list searches: the text typed, whether the keyboard is up, and the field's two taps. */
export interface ContactSearch { query: string; typing: boolean; onFocus: () => void; onClear: () => void }

/**
 * The phone book with a letter index; [onDialed] runs after a number is called (the call screen closes its picker).
 * With [search], a field stays at the top of the list while it scrolls, and typed text narrows the list to matches.
 */
export function Contacts({ emulator, onDialed, search }: { emulator: EmulatorState; onDialed?: () => void; search?: ContactSearch }) {
  const call = (number: string) => { dial(number); onDialed?.(); };
  const query = search?.query.trim() ?? "";
  const found = useMemo(() => (query ? bridge.phoneSearch(emulator.contacts, query) : null), [emulator.contacts, query]);
  const sections = useMemo(() => bridge.phoneSections(emulator.contacts), [emulator.contacts]);
  const [open, setOpen] = useState<string | null>(null);
  const heads = useRef<Record<string, HTMLElement | null>>({});
  if (!sections.length) return <p className="phone-empty">Contacts from {emulator.device} show here once the phone shares them.</p>;
  const row = (c: Contact) => (
    <div key={c.name} className={`phone-contact${open === c.name ? " is-open" : ""}`}>
      <button className="phone-row" aria-expanded={c.numbers.length > 1 ? open === c.name : undefined} aria-label={c.numbers.length === 1 ? `Call ${c.name}` : `${c.name}, ${c.numbers.length} numbers`} onClick={() => pick(c)}>
        <span className="phone-avatar" aria-hidden="true">{bridge.initials(c.name) || <span className="icon i-user" />}</span>
        <span className="phone-row-text"><span className="phone-row-name">{c.name}</span></span>
        {c.numbers.length > 1 && <span className="phone-row-when">{c.numbers.length} numbers</span>}
      </button>
      {open === c.name && c.numbers.map((n) => (
        <button key={n.number} className="phone-number" aria-label={`Call ${c.name}, ${n.label || n.number}`} onClick={() => call(n.number)}>
          <span className="icon i-phone-fill" aria-hidden="true" />
          <span className="phone-row-sub">{n.label}</span>
          <span className="phone-number-digits">{formatNumber(n.number)}</span>
        </button>
      ))}
    </div>
  );
  const pick = (c: Contact) => (c.numbers.length === 1 ? call(c.numbers[0].number) : setOpen(open === c.name ? null : c.name));
  return (
    <div className={`phone-contacts${found ? " is-searching" : ""}${search ? " has-search" : ""}`}>
      <div className="phone-list" role="list" aria-label={found ? "Matching contacts" : "Contacts"}>
        {search && (
          <div className="phone-search" role="search">
            <button className={`phone-search-field${search.typing ? " is-focused" : ""}`} aria-label={search.query ? `Search, ${search.query}` : "Search contacts"} onClick={search.onFocus}>
              <span className="icon i-magnifying-glass" aria-hidden="true" />
              <span className={search.query ? "phone-search-text" : "phone-search-placeholder"}>{search.query || "Search"}</span>
              {search.typing && <span className="phone-search-caret" aria-hidden="true" />}
            </button>
            {(search.query || search.typing) && <button className="phone-search-cancel" onClick={search.onClear}>Cancel</button>}
          </div>
        )}
        {found ? (
          found.length ? <section role="listitem" aria-label="Matches">{found.map(row)}</section> : <p className="phone-empty">No contacts match “{query}”.</p>
        ) : sections.map(({ letter, contacts }) => (
          <section key={letter} role="listitem" aria-label={letter} ref={(el) => { heads.current[letter] = el; }}>
            <h2 className="phone-letter">{letter}</h2>
            {contacts.map(row)}
          </section>
        ))}
      </div>
      {/* the letters down the edge jump to their section; scrolling a long phone book while parked is slow */}
      {!found && (
        <nav className="phone-index" aria-label="Jump to letter">
          {sections.map(({ letter }) => (
            <button key={letter} onClick={() => heads.current[letter]?.scrollIntoView({ block: "start", behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" })}>{letter}</button>
          ))}
        </nav>
      )}
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
  // the call key with nothing typed has the phone call its own last number (HFP redial)
  // the key says whom it calls: the suggested contact's number when there is one, else the digits as typed
  const call = () => { if (typed) { dial(match?.number ?? typed); setTyped(""); } else carCommand({ type: "Phone.Redial" }); };
  return (
    <div className="phone-pad">
      <div className="phone-typed" aria-live="polite">
        <output className="phone-typed-number" aria-label="Number">{formatNumber(typed)}</output>
        <span className="phone-typed-match">{match && `${match.name}${match.label ? ` · ${match.label}` : ""}`}</span>
      </div>
      <div className="pad-grid">
        {KEYS.map(([d, l]) => <PadKey key={d} digit={d} letters={l} onPress={(k) => setTyped((t) => t + k)} />)}
      </div>
      <div className="pad-foot">
        {/* the phone's voice assistant key waits for later; Phone.VoiceAssistant stays in core-car and on the unit */}
        <span aria-hidden="true" />
        <button className="call-key is-accept" aria-label={typed ? `Call ${match?.name ?? typed}` : "Redial"} onClick={call}>
          <span className="icon i-call-accept" aria-hidden="true" />
        </button>
        <button className={`pad-delete${typed ? "" : " is-hidden"}`} aria-label="Delete" tabIndex={typed ? 0 : -1} {...holdToRepeat(() => setTyped((t) => t.slice(0, -1)))}>
          <span className="icon i-backspace-fill" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}

/** The message being written in an open thread: its text, whether the keyboard is up for it, and its two actions. */
export interface Compose { draft: string; typing: boolean; onFocus: () => void; onSend: (to: string) => void }

/**
 * Text messages from the phone, thread by thread (core-car Messages). The words show only while the car stands;
 * typing too when the setting locks it in motion. The quick answers and the call key work either way.
 */
function Messages({ emulator, compose }: { emulator: EmulatorState; compose: Compose }) {
  const { messages, read, contacts, thread, speed } = emulator;
  const now = useNow(null);
  const threads = useMemo(() => bridge.conversations(messages, read, contacts), [messages, read, contacts]);
  const open = thread ? threads.find((t) => t.messages.some((m) => m.number === thread)) ?? null : null;
  const replies = useMemo(() => bridge.quickReplies(), []);
  useEffect(() => { if (open?.unread) markRead(open.messages.filter((m) => !m.sent).map((m) => m.id)); }, [open]);

  // a unit whose Bluetooth stack has no message client: say so rather than show an empty list
  if (!emulator.messagesSupported) {
    return (
      <div className="phone-unsupported" role="note">
        <span className="music-nophone-icon" aria-hidden="true"><span className="icon i-chat-circle-text-fill" /></span>
        <h2>Messages are not available on this unit</h2>
        <p>Its Bluetooth cannot receive or send text messages. Calls, contacts and recent calls work as usual.</p>
      </div>
    );
  }
  if (!open) {
    if (!threads.length) return <p className="phone-empty">Messages to {emulator.device} show here while it is connected.</p>;
    return (
      <ul className="phone-list" aria-label="Messages">
        {threads.map((t) => (
          <li key={t.number}>
            <button className="phone-row" onClick={() => openThread(t.messages[t.messages.length - 1].number)} aria-label={`${t.name || t.number}${t.unread ? `, ${t.unread} unread` : ""}`}>
              <span className="phone-avatar" aria-hidden="true">{bridge.initials(t.name) || <span className="icon i-user" />}</span>
              <span className="phone-row-text">
                <span className="phone-row-name">{t.name || formatNumber(t.number)}</span>
                <span className="phone-row-sub phone-row-preview">{speed.stopped ? t.last : "Stop the car to read"}</span>
              </span>
              <span className="phone-row-when">{when(t.atMs, now)}</span>
              {t.unread > 0 && <span className="phone-dot" aria-hidden="true" />}
            </button>
          </li>
        ))}
      </ul>
    );
  }
  // typing in motion is blocked only when the setting asks for it (core-car ShellState.lockTypingWhileMoving)
  const locked = emulator.shell.lockTypingWhileMoving && !speed.stopped;
  const to = [...open.messages].reverse().find((m) => !m.sent)?.number ?? open.number;
  return (
    <div className="phone-thread">
      <header className="phone-thread-head">
        <button className="pad-side" aria-label="All messages" onClick={() => openThread(null)}><span className="icon i-caret-left" aria-hidden="true" /></button>
        <span className="phone-row-name">{open.name || formatNumber(open.number)}</span>
        <button className="call-key is-accept is-small" aria-label={`Call ${open.name || open.number}`} onClick={() => dial(to)}><span className="icon i-call-accept" aria-hidden="true" /></button>
      </header>
      <div className="phone-chat">
        <ol className="phone-bubbles" aria-label="Conversation">
          {open.messages.map((m) => (
            <li key={m.id} className={`bubble${m.sent ? " is-sent" : ""}`}>{speed.stopped || m.sent ? m.text : "Stop the car to read"}</li>
          ))}
        </ol>
        {!compose.typing && (
          <div className="phone-replies" role="group" aria-label="Quick replies">
            {replies.map((r) => <button key={r} className="call-pill" onClick={() => carCommand({ type: "Phone.SendMessage", number: to, text: r })}>{r}</button>)}
          </div>
        )}
        <div className="phone-compose">
          <button className={`phone-compose-field${compose.typing ? " is-focused" : ""}`} disabled={locked} onClick={compose.onFocus}
            aria-label={locked ? "Stop the car to type" : compose.draft ? `Message, ${compose.draft}` : "Write a message"}>
            <span className={compose.draft ? "phone-search-text" : "phone-search-placeholder"}>{compose.draft || (locked ? "Stop the car to type" : "Message")}</span>
            {compose.typing && <span className="phone-search-caret" aria-hidden="true" />}
          </button>
          <button className="phone-send" aria-label="Send" disabled={!compose.draft.trim()} onClick={() => compose.onSend(to)}>
            <span className="icon i-arrow-up-bold" aria-hidden="true" />
          </button>
        </div>
      </div>
    </div>
  );
}

/** Who a reply in the open thread goes to: the number the last message came from. */
function threadNumber(e: EmulatorState): string {
  const thread = bridge.conversations(e.messages, e.read, e.contacts).find((t) => t.messages.some((m) => m.number === e.thread));
  return [...(thread?.messages ?? [])].reverse().find((m) => !m.sent)?.number ?? e.thread ?? "";
}

/** Unread messages across the threads; none on a unit that cannot carry messages. */
export function useUnread(e: EmulatorState) {
  return useMemo(
    () => (e.messagesSupported ? bridge.conversations(e.messages, e.read, e.contacts).reduce((n, t) => n + t.unread, 0) : 0),
    [e.messages, e.read, e.contacts, e.messagesSupported],
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
  const [tab, setTab] = useState<"recents" | "contacts" | "messages">("recents");
  // the contacts search: its text and whether the on-screen keyboard is up for it
  const [query, setQuery] = useState("");
  const [typing, setTyping] = useState(false);
  const search: ContactSearch = { query, typing, onFocus: () => setTyping(true), onClear: () => { setQuery(""); setTyping(false); } };
  // the message being written in an open thread, on the same keyboard
  const [draft, setDraft] = useState("");
  const [writing, setWriting] = useState(false);
  const compose: Compose = {
    draft, typing: writing, onFocus: () => setWriting(true),
    onSend: (to) => { carCommand({ type: "Phone.SendMessage", number: to, text: draft.trim() }); setDraft(""); setWriting(false); },
  };
  // leaving the tab or the app, or a car that starts moving, puts the keyboard away
  useEffect(() => { if (tab !== "contacts" || !open) setTyping(false); }, [tab, open]);
  const typingLocked = emulator.shell.lockTypingWhileMoving && !emulator.speed.stopped;
  useEffect(() => { if (tab !== "messages" || !open || !emulator.thread || typingLocked) setWriting(false); }, [tab, open, emulator.thread, typingLocked]);
  const unread = useUnread(emulator);
  // a tap on a message banner opens its thread here
  useEffect(() => { if (emulator.thread) setTab("messages"); }, [emulator.thread]);

  return (
    <section ref={section} className={`music-app phone-app${open ? " is-open" : ""}${screen === "READY" ? " is-ready" : ""}${writing ? " is-writing" : ""}`} aria-label="Phone" aria-hidden={!open} inert={!open}>
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
              <button role="tab" id="phone-tab-messages" aria-selected={tab === "messages"} aria-controls="phone-panel" onClick={() => setTab("messages")}>
                <span className="icon i-chat-circle-text-fill" aria-hidden="true" />Messages
                {unread > 0 && <span className="phone-badge" aria-label={`${unread} unread`}>{unread}</span>}
              </button>
            </div>
            <PhoneChip emulator={emulator} />
          </header>
          <div className="phone-panel" id="phone-panel" role="tabpanel" aria-labelledby={`phone-tab-${tab}`} key={tab}>
            {tab === "recents" ? <Recents emulator={emulator} /> : tab === "contacts" ? <Contacts emulator={emulator} search={search} /> : <Messages emulator={emulator} compose={compose} />}
          </div>
        </div>
        <Keypad emulator={emulator} />
        {/* one keyboard for both fields: the search while it is up, else the message */}
        <Keyboard
          open={typing || writing}
          onKey={(c) => (typing ? setQuery((q) => q + c) : setDraft((d) => d + c))}
          onBackspace={() => (typing ? setQuery((q) => q.slice(0, -1)) : setDraft((d) => d.slice(0, -1)))}
          onSubmit={() => { if (typing) setTyping(false); else if (draft.trim() && emulator.thread) compose.onSend(threadNumber(emulator)); }}
          submitLabel={typing ? "Search" : "Send"}
          submitEnabled={typing || Boolean(draft.trim())}
        />
      </>)}
    </section>
  );
}
