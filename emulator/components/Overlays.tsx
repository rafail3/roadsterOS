import { useEffect, useState } from "react";
import { bridge } from "../car/bridge";
import { carCommand, carEvent, dismissMessage, openThread, useNow, type EmulatorState } from "../car/store";
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

/** A second call that rings during this one: decline it, or take it holding or ending this one. */
function Waiting({ name, number }: { name: string; number: string }) {
  return (
    <div className="call-waiting" role="group" aria-label={`Second call, ${name || number}`}>
      <span className="call-waiting-text"><strong>{name || number}</strong> is calling</span>
      <div className="call-waiting-actions">
        <button className="call-pill is-decline" onClick={() => carCommand({ type: "Phone.DeclineWaiting" })}>Decline</button>
        <button className="call-pill" onClick={() => carCommand({ type: "Phone.AnswerWaiting", keepCurrent: false })}>End &amp; Accept</button>
        <button className="call-pill is-accept" onClick={() => carCommand({ type: "Phone.AnswerWaiting", keepCurrent: true })}>Hold &amp; Accept</button>
      </div>
    </div>
  );
}

/**
 * The call over everything: who it is on the left; on the right the answer and decline keys while it rings, the
 * controls while it is placed or talked, and the end key. Names come from the phone book; the phone sends a number.
 * A second call shows above the controls while it rings, and under the name while it waits on hold.
 */
export function CallOverlay({ emulator }: { emulator: EmulatorState }) {
  const { car, call } = emulator;
  const now = useNow(null);
  const [tones, setTones] = useState<string | null>(null);
  const ringing = car.call === "RINGING";
  const timer = call.since !== null ? bridge.callDuration(now.getTime() - call.since) : "";
  const status = ringing ? "Incoming call" : car.call === "OUTGOING" ? "Calling…" : call.held ? "On Hold" : timer;
  const title = call.conference ? "Conference" : call.name || call.number;
  const initials = call.conference ? "" : bridge.initials(call.name);
  const onHold = call.other && !call.other.waiting ? call.other : null;
  const press = (digit: string) => { carCommand({ type: "Phone.Dtmf", digit }); setTones((t) => (t ?? "") + digit); };

  return (
    <section className="overlay call" role="alertdialog" aria-modal="true" aria-labelledby="call-name" aria-describedby="call-status">
      <div className="call-who">
        <span className="call-avatar" aria-hidden="true">{initials || <span className={`icon ${call.conference ? "i-users-fill" : "i-user"}`} />}</span>
        <h2 className="call-name" id="call-name">{title}</h2>
        <p className="call-sub">{call.conference ? "Calls merged" : call.name ? [call.label, call.number].filter(Boolean).join(" · ") : "Not in contacts"}</p>
        <p className="call-status" id="call-status" aria-live="polite">{[status, call.micMuted && "Muted", !call.inCar && "On the phone"].filter(Boolean).join(" · ")}</p>
        {onHold && <p className="call-held"><span className="icon i-pause-fill" aria-hidden="true" />{onHold.name || onHold.number} on hold</p>}
      </div>

      <div className="call-side">
        {call.other?.waiting && <Waiting name={call.other.name} number={call.other.number} />}
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
              {onHold
                ? <CallToggle icon="i-arrows-left-right" label="Swap" onPress={() => carCommand({ type: "Phone.Hold" })} />
                : <CallToggle icon="i-pause-fill" label={call.held ? "Resume" : "Hold"} on={call.held} onPress={() => carCommand({ type: "Phone.Hold" })} />}
              {onHold && <CallToggle icon="i-arrows-merge" label="Merge" onPress={() => carCommand({ type: "Phone.Merge" })} />}
            </div>
          )}
          <div className="call-end-row">
            {tones !== null && <button className="call-hide" onClick={() => setTones(null)}>Hide</button>}
            <button className="call-key is-decline" aria-label={call.conference ? "End conference" : "End call"} onClick={() => carCommand({ type: "Phone.HangUp" })}>
              <span className="icon i-phone-disconnect-fill" aria-hidden="true" />
            </button>
          </div>
        </>)}
      </div>
    </section>
  );
}

/** The phone's assistant listening through the car's microphone; it ends by itself on the phone, or here. */
export function VoiceOverlay() {
  return (
    <section className="voice" role="status" aria-live="polite">
      <span className="voice-mic" aria-hidden="true"><span className="icon i-microphone-fill" /></span>
      <span className="voice-text">Listening on your phone…</span>
      <button className="call-pill" onClick={() => carCommand({ type: "Phone.VoiceAssistant", on: false })}>Stop</button>
    </section>
  );
}

/** How long a new message stays up before it goes back into the phone app. */
const BANNER_MS = 6000;

/** A new text at the top of the screen; its words only while the car stands. A tap opens the thread. */
export function MessageBanner({ emulator }: { emulator: EmulatorState }) {
  const message = emulator.newMessage;
  useEffect(() => {
    if (!message) return;
    const timer = setTimeout(dismissMessage, BANNER_MS);
    return () => clearTimeout(timer);
  }, [message]);
  if (!message) return null;
  const name = message.name || bridge.phoneFind(emulator.contacts, message.number)?.name || message.number;
  return (
    <button className="message-banner" key={message.id} onClick={() => openThread(message.number)} aria-label={`Message from ${name}, open`}>
      <span className="message-banner-icon" aria-hidden="true"><span className="icon i-chat-circle-text-fill" /></span>
      <span className="message-banner-text">
        <span className="message-banner-name">{name}</span>
        <span className="message-banner-body">{emulator.speed.stopped ? message.text : "New message"}</span>
      </span>
    </button>
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
