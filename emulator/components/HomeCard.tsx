import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import type { CardPage } from "../car/bridge";
import { setSlot } from "../car/mapHost";
import { pressSound, setBluetooth, shellAction, type EmulatorState } from "../car/store";
import { Marquee, TransportRow } from "./media";
import { NoPhone } from "./MusicApp";
import { Bluetooth, Radio } from "./NowPlaying";
import { FreqCounter, useGlidingKhz } from "./RadioApp";

/** A swipe longer than this share of the card, or quicker than this, turns the page. */
const TURN_SHARE = 0.18;
const TURN_PX_PER_MS = 0.45;
/** Canvas pixels a finger moves before the card knows the swipe is sideways. */
const INTENT_PX = 8;
/** Past the first or last page the card gives only this share of the finger's way. */
const EDGE_GIVE = 0.3;

type Card = { pages: CardPage[]; shown: CardPage; strip: "NONE" | "BT" | "RADIO" };

/** Under the map, the sound that plays: the track or the station, with its transport. */
function SoundStrip({ emulator, kind }: { emulator: EmulatorState; kind: "BT" | "RADIO" }) {
  const { car, track, radio } = emulator;
  // the frequency glides and its digits roll as in the radio app, not jumping step by step
  const gliding = useGlidingKhz(radio.band, radio.khz);
  return (
    <div className="card-strip">
      {kind === "BT" ? (
        <>
          <span className="card-strip-cover">{track.art ? <img src={track.art} alt="" /> : <span className="icon i-music-notes" />}</span>
          <span className="card-strip-meta" key={track.title}>
            <Marquee text={track.title} className="np-title" />
            <Marquee text={track.artist} className="np-artist" />
          </span>
          <TransportRow
            playing={car.playing}
            onPrev={() => pressSound("BLUETOOTH", "PREV")}
            onPlay={() => pressSound("BLUETOOTH", "PLAY")}
            onNext={() => pressSound("BLUETOOTH", "NEXT")}
          />
        </>
      ) : (
        <>
          <span className="card-strip-cover is-radio"><span className="icon i-radio" /></span>
          <span className="card-strip-meta" key={radio.rds}>
            <span className="np-title">{radio.rds || "Radio"}</span>
            <span className="np-artist card-strip-freq"><FreqCounter className="card-strip-freq-num" band={radio.band} khz={gliding} target={radio.khz} /> {radio.band === "FM" ? "MHz" : "kHz"}</span>
          </span>
          <TransportRow
            playing={car.radioOn}
            onPrev={() => pressSound("RADIO", "PREV")}
            onPlay={() => pressSound("RADIO", "PLAY")}
            onNext={() => pressSound("RADIO", "NEXT")}
          />
        </>
      )}
    </div>
  );
}

/** The map page: the one map lives here while the Maps app is closed; a tap opens the app. */
function MapPage({ emulator, strip }: { emulator: EmulatorState; strip: Card["strip"] }) {
  return (
    <div className={`np np-map${strip !== "NONE" ? " has-strip" : ""}`}>
      <div className="card-map" ref={(el) => setSlot("card", el)} />
      <button className="card-map-open" aria-label="Open maps" onClick={() => shellAction({ type: "OpenApp", app: "MAPS" })} />
      {strip !== "NONE" && <SoundStrip emulator={emulator} kind={strip} />}
    </div>
  );
}

/** Bluetooth is off: the one thing to do is switch it on; the saved phones then show here. */
function BluetoothOff() {
  return (
    <div className="np np-phone">
      <div className="music-nophone">
        <span className="music-nophone-icon" aria-hidden="true"><span className="icon i-bluetooth" /></span>
        <h2>Bluetooth is off</h2>
        <p>Turn it on to play music from a phone.</p>
        <button className="music-nophone-pair" onClick={() => setBluetooth(true)}><span className="icon i-bluetooth" aria-hidden="true" />Turn on Bluetooth</button>
      </div>
    </div>
  );
}

function Page({ page, emulator, strip }: { page: CardPage; emulator: EmulatorState; strip: Card["strip"] }) {
  switch (page) {
    case "BLUETOOTH": return <Bluetooth emulator={emulator} />;
    // the music app's own screen: the saved phones to connect with one tap, and the devices panel
    case "NO_PHONE": return <div className="np np-phone"><NoPhone emulator={emulator} /></div>;
    case "BT_OFF": return <BluetoothOff />;
    case "RADIO": return <Radio emulator={emulator} />;
    case "NAVIGATION": return <MapPage emulator={emulator} strip={strip} />;
  }
}

/**
 * The card on home: a page for each sound that is on and the map, swiped sideways one at a time, with dots below
 * (pages and the page shown by the unit's rule, core-car HomeCard). The track follows the finger and settles with a
 * spring; a swipe never taps what is under it.
 */
export function HomeCard({ emulator, card }: { emulator: EmulatorState; card: Card }) {
  const { pages, shown, strip } = card;
  const index = Math.max(0, pages.indexOf(shown));
  const [drag, setDrag] = useState<number | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const swipe = useRef<{ x: number; y: number; t: number; sideways: boolean | null; dx: number } | null>(null);
  const swallowClick = useRef(false);

  const onPointerDown = (e: ReactPointerEvent) => {
    if (pages.length < 2 || (e.pointerType === "mouse" && e.button !== 0)) return;
    swipe.current = { x: e.clientX, y: e.clientY, t: e.timeStamp, sideways: null, dx: 0 };
  };

  useEffect(() => {
    const move = (e: PointerEvent) => {
      const s = swipe.current;
      if (!s) return;
      // the screen is drawn scaled; the finger's way is measured in the card's own pixels
      const scale = box.current ? box.current.getBoundingClientRect().width / box.current.offsetWidth || 1 : 1;
      const dx = e.clientX - s.x;
      const dy = e.clientY - s.y;
      if (s.sideways === null && Math.hypot(dx, dy) / scale > INTENT_PX) s.sideways = Math.abs(dx) > Math.abs(dy);
      if (!s.sideways) return;
      const atEdge = (dx > 0 && index === 0) || (dx < 0 && index === pages.length - 1);
      s.dx = dx / scale;
      setDrag(atEdge ? s.dx * EDGE_GIVE : s.dx);
    };
    const up = (e: PointerEvent) => {
      const s = swipe.current;
      swipe.current = null;
      if (!s?.sideways) return;
      swallowClick.current = true;
      const width = box.current?.offsetWidth ?? 1;
      const speed = s.dx / Math.max(1, e.timeStamp - s.t);
      const turn = Math.abs(s.dx) > width * TURN_SHARE || Math.abs(speed) > TURN_PX_PER_MS ? -Math.sign(s.dx) : 0;
      const next = Math.min(pages.length - 1, Math.max(0, index + turn));
      setDrag(null);
      if (next !== index) shellAction({ type: "ShowCard", page: pages[next] });
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    window.addEventListener("pointercancel", up);
    return () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      window.removeEventListener("pointercancel", up);
    };
  }, [index, pages]);

  return (
    <div ref={box} className="card-pages" onPointerDown={onPointerDown}
      onClickCapture={(e) => { if (swallowClick.current) { swallowClick.current = false; e.stopPropagation(); e.preventDefault(); } }}>
      <div className={`card-track${drag !== null ? " is-dragging" : ""}`} style={{ transform: `translateX(calc(${-index * 100}% + ${drag ?? 0}px))` }}>
        {pages.map((p) => (
          <div key={p} className="card-page" aria-hidden={p !== shown} inert={p !== shown}>
            <Page page={p} emulator={emulator} strip={strip} />
          </div>
        ))}
      </div>
      {pages.length > 1 && (
        <div className="card-dots" role="tablist" aria-label="Card pages">
          {pages.map((p) => (
            <button key={p} role="tab" aria-selected={p === shown} aria-label={p.toLowerCase().replace("_", " ")} className={p === shown ? "is-on" : undefined}
              onClick={() => shellAction({ type: "ShowCard", page: p })} />
          ))}
        </div>
      )}
    </div>
  );
}
