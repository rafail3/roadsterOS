import { useEffect, useRef, useState } from "react";
import { bridge } from "../car/bridge";
import { castPicture, setCastVisible, shellAction, useEmulator } from "../car/store";

/** The close button fades this long after the last touch, so nothing covers the phone's picture. */
const CONTROLS_MS = 3000;

/**
 * The phone's screen, as the Miracast receiver shows it on the unit: the picture fitted inside ours with black bands,
 * its sound playing with it. Until a phone shares, the app says how to start from Smart View.
 */
export function PhoneScreenApp({ open }: { open: boolean }) {
  const { car, shell, castDevice } = useEmulator();
  const video = useRef<HTMLVideoElement>(null);
  const [controls, setControls] = useState(true);
  const live = car.cast === "LIVE";

  useEffect(() => {
    const el = video.current;
    if (!el) return;
    el.srcObject = live ? castPicture() : null;
  }, [live]);

  useEffect(() => {
    if (!controls || !live) return;
    const timer = setTimeout(() => setControls(false), CONTROLS_MS);
    return () => clearTimeout(timer);
  }, [controls, live]);

  const name = bridge.castName();
  const status = !shell.castVisible
    ? { title: "Hidden from Phones", text: "Phones cannot find the unit while this is off." }
    : car.cast === "CONNECTING"
      ? { title: `Connecting to ${castDevice || "the phone"}…`, text: "Keep the phone close; the picture starts in a moment." }
      : car.cast === "OFF"
        ? { title: "Phone Screen", text: "Phones can find the unit while the car is on." }
        : { title: "Phone Screen", text: `On the phone, open Smart View or Screen Mirroring and choose ${name}.` };

  return (
    <section className={`cast-app${open ? " is-open" : ""}${live ? " is-live" : ""}`} aria-label="Phone screen" aria-hidden={!open} inert={!open}
      onPointerDown={() => setControls(true)}>
      <video ref={video} className="cast-picture" autoPlay playsInline aria-label={live ? `Screen of ${castDevice}` : undefined} hidden={!live} />
      {!live && (
        <div className="cast-status" role="status">
          <span className="cast-glyph" aria-hidden="true"><span className="icon i-device-mobile" /></span>
          <h2 className="cast-title">{status.title}</h2>
          <p className="cast-text">{status.text}</p>
          {!shell.castVisible && <button className="cast-action" onClick={() => setCastVisible(true)}>Make Visible</button>}
        </div>
      )}
      <button className={`glass-button cast-close${live && !controls ? " is-faded" : ""}`} inert={live && !controls} aria-label="Close phone screen" onClick={() => shellAction({ type: "Home" })}>
        <span className="icon i-caret-down" aria-hidden="true" />
      </button>
    </section>
  );
}
