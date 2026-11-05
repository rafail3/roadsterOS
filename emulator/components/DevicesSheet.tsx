import { carCommand, shellAction, useEmulator } from "../car/store";

/** Phones the unit has paired with: tap one to connect, the connected one can be dropped. */
export function DevicesSheet() {
  const { shell, car, device, saved, connecting, failed } = useEmulator();
  const open = shell.devices;
  const close = () => shellAction({ type: "CloseDevices" });

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
        <ul className="devices-list">
          {saved.map(({ name }) => {
            const status = statusOf(name);
            const busy = status.key === "connecting";
            return (
              <li key={name} className={`saved-device is-${status.key}`}>
                <button
                  className="device-main"
                  disabled={status.key === "connected" || busy || connecting !== null}
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
      </section>
    </div>
  );
}
