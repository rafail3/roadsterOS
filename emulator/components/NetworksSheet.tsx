import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { setLocation, setWifi, shellAction, useEmulator, WIFI_NETWORKS, wifiConnect, wifiForget, wifiScan, type WifiNetwork } from "../car/store";
import { SavedRow, type Status } from "./DevicesSheet";
import { Keyboard } from "./Keyboard";
import { Switch } from "./SettingsApp";
import { WifiBars } from "./WifiBars";

/** While the list is shown the unit keeps looking, as a phone does. */
const RESCAN_MS = 10000;
/** A password needs at least this many characters before it can be sent. */
const MIN_PASSWORD = 8;

function Signal({ network }: { network: WifiNetwork }) {
  return (
    <span className="wifi-signal">
      {network.secure && <span className="icon i-lock" aria-label="Secured" />}
      <WifiBars level={network.signal} />
    </span>
  );
}

/**
 * The networks: the saved ones to join or forget, the ones in range to join, a password with our own keyboard.
 * Shown in the Wi-Fi sheet and under the switch in Settings > Wi-Fi; [shown] says whether it is on screen.
 */
export function WifiNetworks({ shown, onJoining }: { shown: boolean; onJoining?: (ssid: string | null) => void }) {
  const { wifiOn, wifi, locationOn } = useEmulator();
  const [openRow, setOpenRow] = useState<string | null>(null);
  // the network waiting for its password, and what has been typed so far
  const [joining, setJoining] = useState<string | null>(null);
  const [password, setPassword] = useState("");
  const [reveal, setReveal] = useState(false);
  const told = useRef(onJoining);
  told.current = onJoining;

  useEffect(() => {
    if (!shown || !wifiOn || !locationOn) return;
    wifiScan();
    const timer = setInterval(wifiScan, RESCAN_MS);
    return () => clearInterval(timer);
  }, [shown, wifiOn, locationOn]);
  // a hidden list or Wi-Fi off leaves nothing half typed
  useEffect(() => { if (!shown || !wifiOn) { setJoining(null); setOpenRow(null); } }, [shown, wifiOn]);
  useEffect(() => { setPassword(""); setReveal(false); told.current?.(joining); }, [joining]);
  // a join that worked closes the password view
  useEffect(() => { if (joining && wifi.connected === joining) setJoining(null); }, [joining, wifi.connected]);

  const byName = (ssid: string) => WIFI_NETWORKS.find((n) => n.ssid === ssid)!;
  const statusOf = (ssid: string): Status => {
    if (wifi.connected === ssid) return { key: "connected", text: "Connected" };
    if (wifi.connecting === ssid) return { key: "connecting", text: "Connecting…" };
    return { key: "saved", text: wifi.seen.includes(ssid) ? "Saved" : "Saved · not in range" };
  };
  const others = wifi.seen.filter((ssid) => !wifi.saved.includes(ssid)).map(byName);
  const searching = wifi.seen.length < WIFI_NETWORKS.length;
  const pick = (n: WifiNetwork) => (n.secure ? setJoining(n.ssid) : wifiConnect(n.ssid));
  const sending = joining !== null && wifi.connecting === joining;
  const submit = () => { if (joining && password.length >= MIN_PASSWORD && !sending) wifiConnect(joining, password); };

  return (<>
    {!wifiOn ? (
      <div className="devices-off">
        <span className="device-icon is-large" aria-hidden="true"><WifiBars level={0} /></span>
        <h3>Wi-Fi is off</h3>
        <p>Turn on Wi-Fi to see networks and get covers and updates online.</p>
      </div>
    ) : joining ? (
      <div className="wifi-join">
        <button className="set-back wifi-join-back" onClick={() => setJoining(null)}><span className="icon i-caret-left" aria-hidden="true" />Networks</button>
        <h3>{joining}</h3>
        <p>Enter the password</p>
        <div className={`wifi-field${wifi.wrongPassword === joining ? " is-wrong" : ""}`} aria-live="polite">
          <span className="wifi-field-text">{reveal ? password : "•".repeat(password.length)}<span className="wifi-caret" aria-hidden="true" /></span>
          <button className="wifi-reveal" onClick={() => setReveal(!reveal)}>{reveal ? "Hide" : "Show"}</button>
        </div>
        <p className="wifi-note" aria-live="polite">
          {sending ? "Connecting…" : wifi.wrongPassword === joining ? "Incorrect password. Try again." : `At least ${MIN_PASSWORD} characters`}
        </p>
      </div>
    ) : (
      <div className="devices-scroll">
        {wifi.saved.length > 0 && (
          <section aria-label="My networks">
            <h3 className="devices-section">My networks</h3>
            <ul className="devices-list">
              {wifi.saved.map((ssid) => (
                <SavedRow
                  key={ssid} name={ssid} status={statusOf(ssid)} open={openRow === ssid} onOpen={setOpenRow} connectLocked={wifi.connecting !== null}
                  icon="i-wifi-high" trailing={<Signal network={byName(ssid)} />}
                  onSelect={() => wifiConnect(ssid)}
                  onForget={() => wifiForget(ssid)}
                />
              ))}
            </ul>
          </section>
        )}
        <section aria-label="Other networks">
          <h3 className="devices-section">Other networks</h3>
          <ul className="devices-list" aria-live="polite">
            {others.map((n) => (
              <li key={n.ssid} className="saved-device is-found">
                <button className="device-main" disabled={wifi.connecting !== null} onClick={() => pick(n)}>
                  <span className="device-icon" aria-hidden="true"><span className="icon i-wifi-high" /></span>
                  <span className="device-text">
                    <span className="device-name">{n.ssid}</span>
                    <span className="device-status">{wifi.connecting === n.ssid ? "Connecting…" : n.secure ? "Secured" : "Open"}</span>
                  </span>
                  <Signal network={n} />
                </button>
              </li>
            ))}
            {!locationOn ? (
              // a scan needs location on this Android; saved networks above still join
              <li className="devices-empty wifi-nolocation">
                <span>Turn on Location to find networks.</span>
                <button className="wifi-location-on" onClick={() => setLocation(true)}>Turn on Location</button>
              </li>
            ) : (<>
              {searching && <li className="devices-empty">Searching…</li>}
              {!searching && others.length === 0 && <li className="devices-empty">No other networks around.</li>}
            </>)}
          </ul>
        </section>
      </div>
    )}
    {/* the keyboard rises from the screen's bottom edge, the whole width, wherever the list is shown */}
    {typeof document !== "undefined" && document.querySelector(".screen") && createPortal(
      <Keyboard
        open={shown && joining !== null}
        onKey={(c) => setPassword((p) => p + c)}
        onBackspace={() => setPassword((p) => p.slice(0, -1))}
        onSubmit={submit}
        submitLabel={sending ? "Connecting…" : "Connect"}
        submitEnabled={password.length >= MIN_PASSWORD && !sending}
      />, document.querySelector(".screen")!)}
  </>);
}

/** The Wi-Fi sheet: the switch and the networks, over whatever is on screen. */
export function NetworksSheet() {
  const { shell, wifiOn } = useEmulator();
  const open = shell.networks;
  const [joining, setJoining] = useState(false);
  const close = () => shellAction({ type: "CloseNetworks" });
  return (
    <div className={`devices networks${open ? " is-open" : ""}`} aria-hidden={!open} inert={!open}>
      <button className="devices-scrim" tabIndex={-1} aria-label="Close Wi-Fi" onClick={close} />
      <section className={`devices-panel${joining ? " has-keyboard" : ""}`} role="dialog" aria-modal="true" aria-labelledby="networks-title">
        <header className="devices-head">
          <h2 id="networks-title">Wi-Fi</h2>
          <div className="devices-head-end">
            <Switch label="Wi-Fi" on={wifiOn} onChange={setWifi} />
            <button className="glass-button" aria-label="Close Wi-Fi" onClick={close}><span className="icon i-x" aria-hidden="true" /></button>
          </div>
        </header>
        <WifiNetworks shown={open} onJoining={(ssid) => setJoining(ssid !== null)} />
      </section>
    </div>
  );
}
