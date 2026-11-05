import { useState } from "react";
import { bridge } from "../car/bridge";
import { carCommand, carEvent, useNow, type EmulatorState } from "../car/store";
import { PadKey } from "./PhoneApp";

const TONE_KEYS: [string, string][] = [
  ["1", ""], ["2", "ABC"], ["3", "DEF"], ["4", "GHI"], ["5", "JKL"], ["6", "MNO"],
  ["7", "PQRS"], ["8", "TUV"], ["9", "WXYZ"], ["*", ""], ["0", ""], ["#", ""],
];

/** A round call control with its label under it; a toggle shows its state filled. */
function CallToggle({ icon, label, on, onPress }: { icon: string; label: string; on?: boolean; onPress: () => void }) {
  return (
    <button className={`call-toggle${on ? " is-on" : ""}`} aria-pressed={on} onClick={onPress}>
      <span className="call-toggle-disc"><span className={`icon ${icon}`} aria-hidden="true" /></span>
      <span className="call-toggle-label">{label}</span>
    </button>
  );
}

/**
 * The call over everything: who it is on the left; on the right the answer and decline keys while it rings, the
 * controls while it is placed or talked, and the end key. Names come from the phone book; the phone sends a number.
 */
export function CallOverlay({ emulator }: { emulator: EmulatorState }) {
  const { car, call } = emulator;
  const now = useNow(null);
  const [tones, setTones] = useState<string | null>(null);
  const ringing = car.call === "RINGING";
  const status = ringing ? "Incoming call" : car.call === "OUTGOING" ? "Calling…" : call.since !== null ? bridge.callDuration(now.getTime() - call.since) : "";
  const initials = bridge.initials(call.name);
  const press = (digit: string) => { carCommand({ type: "Phone.Dtmf", digit }); setTones((t) => (t ?? "") + digit); };

  return (
    <section className="overlay call" role="alertdialog" aria-modal="true" aria-labelledby="call-name" aria-describedby="call-status">
      <div className="call-who">
        <span className="call-avatar" aria-hidden="true">{initials || <span className="icon i-user" />}</span>
        <h2 className="call-name" id="call-name">{call.name || call.number}</h2>
        <p className="call-sub">{call.name ? [call.label, call.number].filter(Boolean).join(" · ") : "Not in contacts"}</p>
        <p className="call-status" id="call-status" aria-live="polite">{[status, call.micMuted && "Muted", !call.inCar && "On the phone"].filter(Boolean).join(" · ")}</p>
      </div>

      <div className="call-side">
        {ringing ? (
          <div className="call-answer">
            <button className="call-key-labelled" onClick={() => carCommand({ type: "Phone.HangUp" })}>
              <span className="call-key is-decline"><span className="icon i-phone-disconnect-fill" aria-hidden="true" /></span>Decline
            </button>
            <button className="call-key-labelled" onClick={() => carCommand({ type: "Phone.Answer" })}>
              <span className="call-key is-accept is-ringing"><span className="icon i-phone-fill" aria-hidden="true" /></span>Accept
            </button>
          </div>
        ) : (<>
          {tones !== null ? (
            <div className="call-tones">
              <output className="call-tones-typed" aria-label="Tones sent">{tones}</output>
              <div className="pad-grid is-compact">{TONE_KEYS.map(([d, l]) => <PadKey key={d} digit={d} letters={l} onPress={press} />)}</div>
            </div>
          ) : (
            <div className="call-controls">
              <CallToggle icon="i-microphone-slash-fill" label="Mute" on={call.micMuted} onPress={() => carCommand({ type: "Phone.Mute", on: !call.micMuted })} />
              <CallToggle icon="i-dots-nine-fill" label="Keypad" onPress={() => setTones("")} />
              <CallToggle icon="i-device-mobile-speaker-fill" label="Phone Audio" on={!call.inCar} onPress={() => carCommand({ type: "Phone.AudioInCar", on: !call.inCar })} />
            </div>
          )}
          <div className="call-end-row">
            {tones !== null && <button className="call-hide" onClick={() => setTones(null)}>Hide</button>}
            <button className="call-key is-decline" aria-label="End call" onClick={() => carCommand({ type: "Phone.HangUp" })}>
              <span className="icon i-phone-disconnect-fill" aria-hidden="true" />
            </button>
          </div>
        </>)}
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
