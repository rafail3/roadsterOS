import { answerWifiNeedsLocation, useEmulator } from "../car/store";

/** Asked when Wi-Fi is switched on with location off: one tap turns both on, or nothing changes. */
export function LocationPrompt() {
  const { wifiNeedsLocation } = useEmulator();
  return (
    <div className={`alert${wifiNeedsLocation ? " is-open" : ""}`} aria-hidden={!wifiNeedsLocation} inert={!wifiNeedsLocation}>
      <button className="alert-scrim" tabIndex={-1} aria-label="Cancel" onClick={() => answerWifiNeedsLocation(false)} />
      <section className="alert-card" role="alertdialog" aria-modal="true" aria-labelledby="alert-title" aria-describedby="alert-text">
        <span className="alert-icon" aria-hidden="true"><span className="icon i-navigation-arrow-fill" /></span>
        <h2 id="alert-title">Wi-Fi needs Location</h2>
        <p id="alert-text">Location must be on for the unit to find Wi-Fi networks.</p>
        <div className="alert-actions">
          <button className="alert-button is-primary" onClick={() => answerWifiNeedsLocation(true)}>Turn on Location</button>
          <button className="alert-button" onClick={() => answerWifiNeedsLocation(false)}>Cancel</button>
        </div>
      </section>
    </div>
  );
}
