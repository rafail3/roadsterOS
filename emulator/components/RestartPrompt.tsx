import { askRestart, restartUnit, useEmulator } from "../car/store";

/**
 * Asked before the unit restarts (Settings > About, or a long press on Settings in the drawer): a restart stops the
 * music and the screens for the length of a boot.
 */
export function RestartPrompt() {
  const { restartAsked: open } = useEmulator();
  const close = () => askRestart(false);
  return (
    <div className={`alert${open ? " is-open" : ""}`} aria-hidden={!open} inert={!open}>
      <button className="alert-scrim" tabIndex={-1} aria-label="Cancel" onClick={close} />
      <section className="alert-card" role="alertdialog" aria-modal="true" aria-labelledby="restart-title" aria-describedby="restart-text">
        <span className="alert-icon" aria-hidden="true"><span className="icon i-power" /></span>
        <h2 id="restart-title">Restart the unit?</h2>
        <p id="restart-text">The screen goes dark and the music stops while the unit starts again. It takes about a minute.</p>
        <div className="alert-actions">
          <button className="alert-button is-primary" onClick={restartUnit}>Restart</button>
          <button className="alert-button" onClick={close}>Cancel</button>
        </div>
      </section>
    </div>
  );
}
