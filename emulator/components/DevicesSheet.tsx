import { useEffect, useRef } from "react";
import { carCommand, shellAction, useEmulator } from "../car/store";

const groupCode = (code: string) => `${code.slice(0, 3)} ${code.slice(3)}`;

/** Phones the unit has paired with, and a search for new ones: tap a saved phone to connect, a found one to pair. */
export function DevicesSheet() {
  const { shell, car, device, saved, connecting, failed, scanning, searched, nearby, pairing } = useEmulator();
  const open = shell.devices;
  const close = () => shellAction({ type: "CloseDevices" });
  const nearbyRef = useRef<HTMLElement>(null);
  // a search or a pairing below the saved phones scrolls itself into view
  const scrollTo = (el: HTMLElement | null, block: ScrollLogicalPosition) =>
    el?.scrollIntoView({ block, behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
  useEffect(() => { if (scanning) scrollTo(nearbyRef.current, "start"); }, [scanning]);
  // closing the panel while the code is on screen answers no, so no pairing is left waiting
  useEffect(() => { if (!open && pairing && !pairing.confirmed) carCommand({ type: "Bt.ConfirmPairing", accept: false }); }, [open, pairing]);

  const statusOf = (name: string) => {
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
          <button className="glass-button" aria-label="Close devices" onClick={close}><span className="icon i-x" aria-hidden="true" /></button>
        </header>

        {pairing && !pairing.confirmed ? (
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
            {saved.map(({ name }) => {
              const status = statusOf(name);
              return (
                <li key={name} className={`saved-device is-${status.key}`}>
                  <button
                    className="device-main"
                    disabled={status.key === "connected" || connecting !== null}
                    aria-label={status.key === "connected" ? `${name}, connected` : `Connect to ${name}`}
                    onClick={() => carCommand({ type: "Bt.Connect", device: name })}
                  >
                    <span className="device-icon" aria-hidden="true"><span className="icon i-device-mobile" /></span>
                    <span className="device-text">
                      <span className="device-name">{name}</span>
                      <span className="device-status" aria-live="polite">{status.text}</span>
                    </span>
                  </button>
                  {status.key === "connected" && (
                    <button className="device-drop" onClick={() => carCommand({ type: "Bt.Disconnect" })}>Disconnect</button>
                  )}
                </li>
              );
            })}
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
