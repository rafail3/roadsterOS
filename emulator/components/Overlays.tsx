import { useEffect, useId, useState } from "react";
import { bridge, type CameraSettings } from "../car/bridge";
import { carCommand, carEvent, dismissMessage, formatNumber, openThread, shellAction, useEmulator, useNow, type EmulatorState } from "../car/store";
import { Contacts, PadKey } from "./PhoneApp";

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
      <span className="call-waiting-text"><strong>{name || formatNumber(number)}</strong> is calling</span>
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
  // a second number being typed, to place another call and then merge the two
  const [adding, setAdding] = useState<string | null>(null);
  const [addFrom, setAddFrom] = useState<"keypad" | "contacts">("keypad");
  const suggestion = adding ? bridge.phoneSuggest(emulator.contacts, adding) : null;
  const ringing = car.call === "RINGING";
  const timer = call.since !== null ? bridge.callDuration(now.getTime() - call.since) : "";
  const status = ringing ? "Incoming call" : car.call === "OUTGOING" ? "Calling…" : call.held ? "On Hold" : timer;
  const title = call.conference ? "Conference" : call.name || formatNumber(call.number);
  const initials = call.conference ? "" : bridge.initials(call.name);
  const onHold = call.other && !call.other.waiting ? call.other : null;
  const press = (digit: string) => { carCommand({ type: "Phone.Dtmf", digit }); setTones((t) => (t ?? "") + digit); };

  return (
    <section className="overlay call" role="alertdialog" aria-modal="true" aria-labelledby="call-name" aria-describedby="call-status">
      <div className="call-who">
        <span className="call-avatar" aria-hidden="true">{initials || <span className={`icon ${call.conference ? "i-users-fill" : "i-user"}`} />}</span>
        <h2 className="call-name" id="call-name">{title}</h2>
        <p className="call-sub">{call.conference ? "Calls merged" : call.name ? [call.label, formatNumber(call.number)].filter(Boolean).join(" · ") : "Not in contacts"}</p>
        <p className="call-status" id="call-status" aria-live="polite">{[status, call.micMuted && "Muted", !call.inCar && "On the phone"].filter(Boolean).join(" · ")}</p>
        {onHold && <p className="call-held"><span className="icon i-pause-fill" aria-hidden="true" />{onHold.name || formatNumber(onHold.number)} on hold</p>}
      </div>

      <div className="call-side">
        {/* adding a call: from the keypad or the phone book */}
        {adding !== null && (
          <div className="phone-tabs call-add-tabs" role="tablist" aria-label="Add a call from">
            <button role="tab" aria-selected={addFrom === "keypad"} onClick={() => setAddFrom("keypad")}><span className="icon i-dots-nine-fill" aria-hidden="true" />Keypad</button>
            <button role="tab" aria-selected={addFrom === "contacts"} onClick={() => setAddFrom("contacts")}><span className="icon i-user-circle-fill" aria-hidden="true" />Contacts</button>
          </div>
        )}
        {call.other?.waiting && <Waiting name={call.other.name} number={call.other.number} />}
        {ringing ? (
          <div className="call-answer">
            <button className="call-key-labelled" onClick={() => carCommand({ type: "Phone.HangUp" })}>
              <span className="call-key is-decline"><span className="icon i-call-decline" aria-hidden="true" /></span>Decline
            </button>
            <button className="call-key-labelled" onClick={() => carCommand({ type: "Phone.Answer" })}>
              <span className="call-key is-accept is-ringing"><span className="icon i-call-accept" aria-hidden="true" /></span>Accept
            </button>
          </div>
        ) : (<>
          {adding !== null && addFrom === "contacts" ? (
            <div className="call-add-contacts">
              <Contacts emulator={emulator} onDialed={() => setAdding(null)} />
            </div>
          ) : adding !== null ? (
            <div className="call-tones">
              <output className="call-tones-typed" aria-label="Number to add">{formatNumber(adding)}</output>
              <span className="phone-typed-match">{suggestion ? `${suggestion.name}${suggestion.label ? ` · ${suggestion.label}` : ""}` : "Add a call, then merge"}</span>
              <div className="pad-grid is-compact">{TONE_KEYS.map(([d, l]) => <PadKey key={d} digit={d} letters={l} onPress={(k) => setAdding((t) => (t ?? "") + k)} />)}</div>
            </div>
          ) : tones !== null ? (
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
              {onHold
                ? <CallToggle icon="i-arrows-merge" label="Merge" onPress={() => carCommand({ type: "Phone.Merge" })} />
                // the current call goes on hold while the new one is placed; the two then merge into a conference
                : !call.other && <CallToggle icon="i-user-plus-fill" label="Add Call" onPress={() => setAdding("")} />}
            </div>
          )}
          {adding !== null ? (
            <div className="call-end-row">
              {addFrom === "contacts" ? <button className="call-pill" onClick={() => setAdding(null)}>Cancel</button> : (<>
              <button className="call-hide is-left" onClick={() => setAdding(null)}>Cancel</button>
              <button className="call-key is-accept" aria-label={suggestion ? `Call ${suggestion.name}` : "Call"} disabled={!adding} onClick={() => { carCommand({ type: "Phone.Dial", number: suggestion?.number ?? adding }); setAdding(null); }}>
                <span className="icon i-call-accept" aria-hidden="true" />
              </button>
              </>)}
            </div>
          ) : (
          <div className="call-end-row">
            {tones !== null && <button className="call-hide" onClick={() => setTones(null)}>Hide</button>}
            <button className="call-key is-decline" aria-label={call.conference ? "End conference" : "End call"} onClick={() => carCommand({ type: "Phone.HangUp" })}>
              <span className="icon i-call-decline" aria-hidden="true" />
            </button>
          </div>
          )}
        </>)}
      </div>
    </section>
  );
}

/**
 * The call folded away by Home or Back: a ringing call as a banner with its two answers, any other as a green pill
 * with the name and the clock; a tap brings the whole call screen back.
 */
export function CallBar({ emulator }: { emulator: EmulatorState }) {
  const { car, call } = emulator;
  const now = useNow(null);
  const name = call.conference ? "Conference" : call.name || formatNumber(call.number);
  const expand = () => shellAction({ type: "ExpandCall" });
  if (car.call === "RINGING") {
    return (
      <section className="call-banner" role="alertdialog" aria-label={`Incoming call, ${name}`}>
        <button className="call-banner-open" onClick={expand} aria-label={`Open the call from ${name}`}>
          <span className="phone-avatar" aria-hidden="true">{bridge.initials(call.name) || <span className="icon i-user" />}</span>
          <span className="message-banner-text">
            <span className="message-banner-name">{name}</span>
            <span className="message-banner-body">Incoming call</span>
          </span>
        </button>
        <button className="call-key is-decline is-small" aria-label="Decline" onClick={() => carCommand({ type: "Phone.HangUp" })}><span className="icon i-call-decline" aria-hidden="true" /></button>
        <button className="call-key is-accept is-small" aria-label="Accept" onClick={() => carCommand({ type: "Phone.Answer" })}><span className="icon i-call-accept" aria-hidden="true" /></button>
      </section>
    );
  }
  const status = car.call === "OUTGOING" ? "Calling…" : call.held ? "On Hold" : call.since !== null ? bridge.callDuration(now.getTime() - call.since) : "";
  // the same banner as a ringing call, with the call's clock and the key that ends it
  return (
    <section className="call-banner" aria-label={`Call with ${name}`}>
      <button className="call-banner-open" onClick={expand} aria-label={`Open the call with ${name}, ${status}`}>
        <span className="phone-avatar" aria-hidden="true">{(!call.conference && bridge.initials(call.name)) || <span className={`icon ${call.conference ? "i-users-fill" : "i-user"}`} />}</span>
        <span className="message-banner-text">
          <span className="message-banner-name">{name}</span>
          <span className="message-banner-body call-banner-time">{[status, call.micMuted && "Muted"].filter(Boolean).join(" · ")}</span>
        </span>
      </button>
      <button className="call-key is-decline is-small" aria-label={call.conference ? "End conference" : "End call"} onClick={() => carCommand({ type: "Phone.HangUp" })}><span className="icon i-call-decline" aria-hidden="true" /></button>
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
  const name = message.name || bridge.phoneFind(emulator.contacts, message.number)?.name || formatNumber(message.number);
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

// the vendor's colours (MainUI com.ts.can.CameraUIView); the lines are yellow where no band recolours them
const GUIDE_GREEN = "#4CAF50";
const GUIDE_YELLOW = "#FFFF00";
const GUIDE_RED = "#FF0000";
const SCREEN_W = 1024;

/** The guidelines as the vendor draws them on the 1024x600 screen, bent by the CAN steering angle [eps]. */
export function GuideLines({ camera, eps }: { camera: CameraSettings; eps: number }) {
  const id = useId();
  if (camera.guidelines === "OFF") return null;
  const g = bridge.cameraGuides(camera.guidelines, eps);
  const curves = [g.leftFix, g.leftControl, g.leftFar, g.rightFix, g.rightControl, g.rightFar];
  const path = [0, 3].map((i) => `M${curves[i].x} ${curves[i].y}Q${curves[i + 1].x} ${curves[i + 1].y} ${curves[i + 2].x} ${curves[i + 2].y}`).join("");
  // the bands that recolour the curves, as the vendor's rectangles: red near the car, green far
  const red: [number, number, number, number][] = [
    [0, g.leftNear.y - 5, g.leftNear.x + 5, g.leftFix.y + 5], [g.rightNear.x - 5, g.rightNear.y - 5, SCREEN_W, g.rightFix.y + 5]];
  const green: [number, number, number, number][] = [
    [0, g.leftFar.y - 5, Math.max(g.leftFar.x, g.leftMiddle.x) + 8, g.leftMiddle.y - 3],
    [Math.min(g.rightFar.x, g.rightMiddle.x) - 8, g.rightFar.y - 5, SCREEN_W, g.rightMiddle.y - 3]];
  const clip = (name: string, rects: [number, number, number, number][]) => (
    <clipPath id={`${id}${name}`}>{rects.map(([x1, y1, x2, y2], i) => <rect key={i} x={x1} y={y1} width={x2 - x1} height={y2 - y1} />)}</clipPath>
  );
  const bar = (a: { x: number; y: number }, b: { x: number; y: number }, color: string) => <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke={color} />;
  return (
    <svg className="guide-lines" viewBox="0 0 1024 600" aria-hidden="true" style={{ transform: `translateY(${(camera.linePos / 600) * 100}%)` }}>
      <defs>{clip("red", red)}{clip("green", green)}</defs>
      <path d={path} stroke={GUIDE_YELLOW} />
      <path d={path} stroke={GUIDE_RED} clipPath={`url(#${id}red)`} />
      <path d={path} stroke={GUIDE_GREEN} clipPath={`url(#${id}green)`} />
      {bar(g.leftFar, g.rightFar, GUIDE_GREEN)}
      {bar(g.leftMiddle, g.rightMiddle, GUIDE_YELLOW)}
      {bar(g.leftNear, g.rightNear, GUIDE_RED)}
    </svg>
  );
}

export function CameraOverlay() {
  const { car, shell, eps } = useEmulator();
  return (
    <section className="overlay camera" role="dialog" aria-modal="true" aria-label="Reverse camera">
      <div className="camera-feed" role="img" aria-label={`Reverse camera, synthetic frame${shell.camera.mirror ? ", mirrored" : ""}`}>
        <div className={`camera-scene${shell.camera.mirror ? " is-mirrored" : ""}`} aria-hidden="true" />
        <GuideLines camera={shell.camera} eps={eps} />
      </div>
      {/* in reverse the gear closes it; opened from its icon, this does */}
      {!car.camera && <button className="camera-close" aria-label="Close camera" onClick={() => shellAction({ type: "CloseCamera" })}><span className="icon i-x" /></button>}
      <p className="camera-label">{car.camera ? "Reverse gear" : "Camera"}</p>
    </section>
  );
}
