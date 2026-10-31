import { useState } from "react";
import { carCommand, carEvent, type EmulatorState } from "../car/store";

export function CallOverlay({ emulator }: { emulator: EmulatorState }) {
  const { car, call } = emulator;
  const [micMuted, setMicMuted] = useState(false);
  const ringing = car.call === "RINGING";
  return (
    <section className="overlay call" role="alertdialog" aria-modal="true" aria-labelledby="call-name">
      <div className="call-card">
        <p className="call-state">{ringing ? "Incoming call" : "On call"}</p>
        <p className="call-name" id="call-name">{call.name || call.number}</p>
        {ringing ? <p className="call-number">{call.number}</p> : <p className="call-timer">00:00</p>}
        {ringing ? (
          <div className="call-actions">
            <button className="ctl ctl-lg ctl-danger" aria-label="Decline" onClick={() => carCommand({ type: "Phone.HangUp" })}><span className="icon i-phone-disconnect" /></button>
            <button className="ctl ctl-lg ctl-accent" aria-label="Answer" onClick={() => carCommand({ type: "Phone.Answer" })}><span className="icon i-phone-incoming" /></button>
          </div>
        ) : (
          <div className="call-actions">
            <button className="ctl ctl-lg" aria-label="Mute microphone" aria-pressed={micMuted} onClick={() => { carCommand({ type: "Phone.Mute", on: !micMuted }); setMicMuted(!micMuted); }}>
              <span className="icon i-microphone-slash" />
            </button>
            <button className="ctl ctl-lg ctl-danger" aria-label="Hang up" onClick={() => carCommand({ type: "Phone.HangUp" })}><span className="icon i-phone-disconnect" /></button>
          </div>
        )}
      </div>
    </section>
  );
}

export function CameraOverlay() {
  return (
    <section className="overlay camera" role="dialog" aria-modal="true" aria-label="Reverse camera">
      <div className="camera-feed" role="img" aria-label="Reverse camera, synthetic frame">
        <div className="guide guide-l" aria-hidden="true" /><div className="guide guide-r" aria-hidden="true" />
      </div>
      <button className="camera-close" aria-label="Close camera" onClick={() => carEvent({ type: "ReverseGear", on: false })}><span className="icon i-x" /></button>
      <p className="camera-label">Reverse camera</p>
    </section>
  );
}
