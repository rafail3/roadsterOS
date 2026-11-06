import { useEffect, useRef, useState, type MouseEvent, type PointerEvent } from "react";
import { carCommand, setBluetooth, shellAction, useEmulator } from "../car/store";
import { Switch } from "./SettingsApp";

const groupCode = (code: string) => `${code.slice(0, 3)} ${code.slice(3)}`;

/** How far a saved row slides to show its remove key, and how far a drag must go to leave it open (canvas px). */
const REVEAL = 96;
const OPEN_AT = REVEAL / 2;
/** A drag is sideways once it has moved this far more across than down; until then the list may scroll. */
const INTENT_PX = 8;
/** Past the remove key the row gives a little and then stops, so it never pulls away from the key. */
const OVERDRAG_PX = 10;
const LEAVE_MS = 260;
const COLLAPSE_MS = 300;

export type Status = { key: string; text: string };

/** A saved phone or network that slides left to reveal its remove key; removing it slides it out, then closes the gap. */
export function SavedRow({ name, status, open, onOpen, connectLocked, icon = "i-device-mobile", trailing, onSelect, onForget, onDrop }: {
  name: string; status: Status; open: boolean; onOpen: (name: string | null) => void; connectLocked: boolean;
  icon?: string; trailing?: React.ReactNode; onSelect: () => void; onForget: () => void; onDrop?: () => void;
}) {
  const row = useRef<HTMLLIElement>(null);
  const drag = useRef<{ x: number; y: number; from: number; scale: number; sideways: boolean | null; moved: boolean } | null>(null);
  const [offset, setOffset] = useState(0);
  const offsetRef = useRef(0);
  const [dragging, setDragging] = useState(false);
  const [leaving, setLeaving] = useState<"" | "slide" | "collapse">("");
  const [height, setHeight] = useState<number | undefined>(undefined);
  const suppressClick = useRef(false);

  // while leaving the card keeps its place over the key: the whole row, key included, slides out as one
  const shown = `${leaving ? -REVEAL : dragging ? offset : open ? -REVEAL : 0}px`;
  const progress = Math.min(1, Math.max(0, -(dragging ? offset : open ? -REVEAL : 0) / REVEAL));

  // the release is caught anywhere on the page: a finger or mouse let go outside the row still ends the drag
  const endRef = useRef<() => void>(() => {});
  useEffect(() => {
    const end = () => endRef.current();
    window.addEventListener("pointerup", end);
    window.addEventListener("pointercancel", end);
    return () => { window.removeEventListener("pointerup", end); window.removeEventListener("pointercancel", end); };
  }, []);

  const down = (e: PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0 || leaving) return;
    const el = e.currentTarget;
    suppressClick.current = false;
    offsetRef.current = open ? -REVEAL : 0;
    drag.current = { x: e.clientX, y: e.clientY, from: open ? -REVEAL : 0, scale: el.getBoundingClientRect().width / el.offsetWidth || 1, sideways: null, moved: false };
  };
  const move = (e: PointerEvent<HTMLDivElement>) => {
    const d = drag.current;
    if (!d) return;
    const dx = (e.clientX - d.x) / d.scale;
    const dy = (e.clientY - d.y) / d.scale;
    if (d.sideways === null) {
      if (Math.abs(dx) > INTENT_PX && Math.abs(dx) > Math.abs(dy)) {
        d.sideways = true;
        // keeps the drag when the finger leaves the row; a pointer that is already gone cannot be captured
        try { e.currentTarget.setPointerCapture(e.pointerId); } catch { /* the drag still follows moves over the row */ }
        setDragging(true);
      } else if (Math.abs(dy) > INTENT_PX) d.sideways = false;
    }
    if (!d.sideways) return;
    // no button held any more: the release happened where the row could not hear it
    if (e.buttons === 0 && e.pointerType === "mouse") return up();
    d.moved = true;
    let x = d.from + dx;
    // past either end the row follows the finger less and less, and stops a few pixels past the key
    if (x > 0) x = Math.min(OVERDRAG_PX, x * 0.25);
    if (x < -REVEAL) x = Math.max(-REVEAL - OVERDRAG_PX, -REVEAL + (x + REVEAL) * 0.25);
    offsetRef.current = x;
    setOffset(x);
  };
  const up = () => {
    const d = drag.current;
    drag.current = null;
    if (!d?.sideways) return;
    suppressClick.current = d.moved;
    setDragging(false);
    onOpen(offsetRef.current < -OPEN_AT ? name : null);
  };
  endRef.current = up;
  // a drag never counts as a tap, and a tap on an open row only closes it
  const click = (e: MouseEvent) => {
    if (!suppressClick.current && !open) return;
    e.preventDefault();
    e.stopPropagation();
    // the click a browser sends at the end of a drag is only swallowed; a real tap on an open row closes it
    if (suppressClick.current) suppressClick.current = false;
    else onOpen(null);
  };
  const remove = () => {
    setHeight(row.current?.offsetHeight);
    setLeaving("slide");
    setTimeout(() => { setLeaving("collapse"); setHeight(0); }, LEAVE_MS);
    setTimeout(() => { onOpen(null); onForget(); }, LEAVE_MS + COLLAPSE_MS);
  };

  return (
    <li
      ref={row}
      className={`saved-device is-swipeable is-${status.key}${dragging ? " is-dragging" : ""}${leaving ? ` is-${leaving}` : ""}`}
      style={{ height, ["--reveal" as string]: progress } as React.CSSProperties}
    >
      <button className="device-trash" tabIndex={open ? 0 : -1} aria-label={`Remove ${name}`} onFocus={() => onOpen(name)} onClick={remove}>
        <span className="icon i-trash" aria-hidden="true" />
      </button>
      <div className="device-swipe" style={{ transform: `translateX(${shown})` }} onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up} onClickCapture={click}>
        <button
          className="device-main"
          disabled={status.key === "connected" || connectLocked}
          aria-label={status.key === "connected" ? `${name}, connected` : `Connect to ${name}`}
          onClick={onSelect}
        >
          <span className="device-icon" aria-hidden="true"><span className={`icon ${icon}`} /></span>
          <span className="device-text">
            <span className="device-name">{name}</span>
            <span className="device-status" aria-live="polite">{status.text}</span>
          </span>
          {trailing}
        </button>
        {status.key === "connected" && onDrop && (
          <button className="device-drop" onClick={onDrop}>Disconnect</button>
        )}
      </div>
    </li>
  );
}

/** Phones the unit has paired with, and a search for new ones: tap a saved phone to connect, a found one to pair. */
export function DevicesSheet() {
  const { shell, car, device, saved, connecting, failed, scanning, searched, nearby, pairing, btOn } = useEmulator();
  const open = shell.devices;
  const close = () => shellAction({ type: "CloseDevices" });
  const nearbyRef = useRef<HTMLElement>(null);
  // a search or a pairing below the saved phones scrolls itself into view
  const scrollTo = (el: HTMLElement | null, block: ScrollLogicalPosition) =>
    el?.scrollIntoView({ block, behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
  useEffect(() => { if (scanning) scrollTo(nearbyRef.current, "start"); }, [scanning]);
  // a closed panel leaves no row open
  useEffect(() => { if (!open) setOpenRow(null); }, [open]);
  // closing the panel while the code is on screen answers no, so no pairing is left waiting
  useEffect(() => { if (!open && pairing && !pairing.confirmed) carCommand({ type: "Bt.ConfirmPairing", accept: false }); }, [open, pairing]);

  const [openRow, setOpenRow] = useState<string | null>(null);
  const statusOf = (name: string): Status => {
    if (car.btConnected && device === name) return { key: "connected", text: "Connected" };
    if (connecting === name) return { key: "connecting", text: "Connecting…" };
    if (failed === name) return { key: "failed", text: "Not available. Turn on Bluetooth on the phone." };
    return { key: "saved", text: "Saved" };
  };

  return (
    <div className={`devices${open ? " is-open" : ""}`} aria-hidden={!open} inert={!open}>
      <button className="devices-scrim" tabIndex={-1} aria-label="Close devices" onClick={close} />
      <section className="devices-panel" role="dialog" aria-modal="true" aria-labelledby="devices-title">
        <header className="devices-head">
          <h2 id="devices-title">Devices</h2>
          <div className="devices-head-end">
            <Switch label="Bluetooth" on={btOn} onChange={setBluetooth} />
            <button className="glass-button" aria-label="Close devices" onClick={close}><span className="icon i-x" aria-hidden="true" /></button>
          </div>
        </header>

        {!btOn ? (
          // nothing to list or search for: the switch above is the way back
          <div className="devices-off">
            <span className="device-icon is-large" aria-hidden="true"><span className="icon i-bluetooth" /></span>
            <h3>Bluetooth is off</h3>
            <p>Turn on Bluetooth to see your devices and connect a phone.</p>
          </div>
        ) : pairing && !pairing.confirmed ? (
          // the code check gets the whole panel: big digits read at a glance, two large answers
          <div className="devices-pairing" role="group" aria-labelledby="pairing-title">
            <span className="device-icon is-large" aria-hidden="true"><span className="icon i-device-mobile" /></span>
            <h3 id="pairing-title">Pair with {pairing.name}</h3>
            <p>Check that the phone shows the same code</p>
            <strong className="device-code">{groupCode(pairing.code)}</strong>
            <div className="devices-pairing-actions">
              <button className="devices-answer" onClick={() => carCommand({ type: "Bt.ConfirmPairing", accept: false })}>Cancel</button>
              <button className="devices-answer is-primary" onClick={() => carCommand({ type: "Bt.ConfirmPairing", accept: true })}>Pair</button>
            </div>
          </div>
        ) : (<>
        <div className="devices-scroll">
          <ul className="devices-list" aria-label="Saved devices">
            {saved.map(({ name }) => (
              <SavedRow
                key={name} name={name} status={statusOf(name)} open={openRow === name} onOpen={setOpenRow} connectLocked={connecting !== null}
                onSelect={() => carCommand({ type: "Bt.Connect", device: name })}
                onForget={() => carCommand({ type: "Bt.Forget", device: name })}
                onDrop={() => carCommand({ type: "Bt.Disconnect" })}
              />
            ))}
          </ul>

          {searched && (
            <section className="devices-nearby" aria-labelledby="nearby-title" ref={nearbyRef}>
              <h3 id="nearby-title">Nearby</h3>
              <ul className="devices-list" aria-live="polite">
                {nearby.map((name) => {
                  const here = pairing?.name === name ? pairing : null;
                  return (
                    <li key={name} className={`saved-device is-found${here ? " is-pairing" : ""}`}>
                      <button className="device-main" disabled={pairing !== null} aria-label={`Pair with ${name}`} onClick={() => carCommand({ type: "Bt.Pair", device: name })}>
                        <span className="device-icon" aria-hidden="true"><span className="icon i-device-mobile" /></span>
                        <span className="device-text">
                          <span className="device-name">{name}</span>
                          <span className="device-status">{here?.confirmed ? "Pairing…" : "Tap to pair"}</span>
                        </span>
                      </button>
                    </li>
                  );
                })}
                {scanning && nearby.length === 0 && <li className="devices-empty">Looking for phones with Bluetooth on…</li>}
                {!scanning && nearby.length === 0 && <li className="devices-empty">No new phones found.</li>}
              </ul>
            </section>
          )}
        </div>

        <footer className="devices-foot">
          <p>New phone? Search here, or pair it from the phone's Bluetooth settings.</p>
          <button className={`devices-search${scanning ? " is-scanning" : ""}`} disabled={scanning} onClick={() => carCommand({ type: "Bt.Scan" })}>
            <span className="devices-search-chip" aria-hidden="true"><span className="icon i-magnifying-glass" /></span>
            <span>{scanning ? "Searching…" : "Search"}</span>
          </button>
        </footer>
        </>)}
      </section>
    </div>
  );
}
