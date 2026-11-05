import { useLayoutEffect, useRef, useState, type PointerEvent } from "react";
import { bridge } from "../car/bridge";
import { carCommand, setVolume, shellAction, stepVolume, useEmulator, type EmulatorState } from "../car/store";
import { holdToRepeat } from "./controls";
import { useMuteFill } from "./VolumeHud";
import { Marquee, Progress, time, TransportRow, useCoverColors, useCrossfade, usePosition } from "./media";

/** No phone connected: the saved phones to connect with one tap, and the devices panel for anything else. */
export function NoPhone({ emulator }: { emulator: EmulatorState }) {
  const { saved, connecting, failed } = emulator;
  return (
    <div className="music-nophone">
      <span className="music-nophone-icon" aria-hidden="true"><span className="icon i-device-mobile" /></span>
      <h2>No phone connected</h2>
      <p>{saved.length ? "Connect a phone to play its music." : "Pair a phone to play its music."}</p>
      {saved.length > 0 && (
        <ul className="music-nophone-list" aria-label="Saved phones">
          {saved.map(({ name }) => (
            <li key={name}>
              <button className="music-nophone-phone" disabled={connecting !== null} onClick={() => carCommand({ type: "Bt.Connect", device: name })}>
                <span className="icon i-bluetooth" aria-hidden="true" />
                <span>{connecting === name ? "Connecting…" : failed === name ? `${name} · not available` : name}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {saved.length > 0
        ? <button className="music-nophone-more" onClick={() => shellAction({ type: "OpenDevices" })}>Other devices</button>
        // nothing saved: pairing is the only way forward, so it is the one solid button
        : <button className="music-nophone-pair" onClick={() => shellAction({ type: "OpenDevices" })}><span className="icon i-bluetooth" aria-hidden="true" />Pair a phone</button>}
    </div>
  );
}

/** Full-screen Bluetooth music: the cover blurred into the whole background, a large cover, transport and volume. */
export function MusicApp({ open }: { open: boolean }) {
  const emulator = useEmulator();
  const { car, track, device } = emulator;
  const { elapsed, duration } = usePosition();
  // without a phone there is no track: no cover, no cover colours, the screen asks for a phone instead
  const art = car.btConnected ? track.art : null;
  const backdrops = useCrossfade(art);
  const covers = useCrossfade(art);
  const max = bridge.maxVolume();
  const colors = useCoverColors(art);
  const [dragging, setDragging] = useState(false);
  const slider = useRef<HTMLDivElement>(null);
  const muteFill = useMuteFill(car.muted);

  // leaving, the app's outline closes onto its card on home (now playing); measured from layout, so home's settle zoom does not skew it
  const section = useRef<HTMLElement>(null);
  useLayoutEffect(() => {
    const app = section.current;
    const card = app?.parentElement?.querySelector<HTMLElement>(".panel");
    if (!app || !card) return;
    const { offsetLeft: l, offsetTop: t, offsetWidth: w, offsetHeight: h } = card;
    app.style.setProperty("--to-t", `${t}px`);
    app.style.setProperty("--to-l", `${l}px`);
    app.style.setProperty("--to-r", `${app.offsetWidth - l - w}px`);
    app.style.setProperty("--to-b", `${app.offsetHeight - t - h}px`);
  }, [open]);

  const volumeAt = (e: PointerEvent) => {
    const r = slider.current!.getBoundingClientRect();
    setVolume(Math.round(Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)) * max));
  };

  return (
    <section ref={section} className={`music-app${open ? " is-open" : ""}`} aria-label="Music" aria-hidden={!open} inert={!open} style={{ "--cover-top": colors.top, "--cover-bottom": colors.bottom } as React.CSSProperties}>
      {/* on the unit the cover is decoded small and blurred once per track; no live blur */}
      <div className="music-backdrop" aria-hidden="true">
        {backdrops.layers.map((layer, i, all) => layer.src && <img key={layer.id} src={layer.src} alt="" className={i < all.length - 1 ? "is-leaving" : undefined} onAnimationEnd={() => backdrops.settle(layer.id)} />)}
      </div>

      <button className="glass-button music-close" aria-label="Close music" onClick={() => shellAction({ type: "Home" })}>
        <span className="icon i-caret-down" aria-hidden="true" />
      </button>
      <button className="glass-button music-devices" aria-label="Devices" onClick={() => shellAction({ type: "OpenDevices" })}>
        <span className="icon i-bluetooth" aria-hidden="true" />
      </button>

      {!car.btConnected ? <NoPhone emulator={emulator} /> : (<>
      <div className={`music-cover${car.playing ? " is-playing" : ""}`}>
        {covers.layers.map((layer) => (layer.src
          ? <img key={layer.id} src={layer.src} alt={`Cover of ${track.title}`} onAnimationEnd={() => covers.settle(layer.id)} />
          : <div key={layer.id} className="music-cover-empty" onAnimationEnd={() => covers.settle(layer.id)}><span className="icon i-music-notes" aria-hidden="true" /></div>))}
      </div>

      <div className="music-side">
        <div className="music-meta" key={track.title}>
          <Marquee text={track.title} className="music-title" />
          <Marquee text={track.artist} className="music-artist" />
        </div>

        <div className="music-seek">
          <Progress elapsed={elapsed} duration={duration} />
          <div className="music-times">
            <span>{time(elapsed)}</span>
            <button className="music-device" aria-label={`${device}, choose device`} onClick={() => shellAction({ type: "OpenDevices" })}><span className="icon i-bluetooth" aria-hidden="true" />{device}</button>
            <span>-{time(duration - elapsed)}</span>
          </div>
        </div>

        <TransportRow
          playing={car.playing}
          onPrev={() => carCommand({ type: "Media.Prev" })}
          onPlay={() => carCommand({ type: car.playing ? "Media.Pause" : "Media.Play" })}
          onNext={() => carCommand({ type: "Media.Next" })}
        />

        <div className={`music-volume${dragging ? " is-active" : ""}${car.muted ? " is-muted" : ""}`}>
          <button className="music-volume-step music-mute" aria-label={car.muted ? "Sound on" : "Mute"} aria-pressed={car.muted} onClick={() => carCommand({ type: "Audio.Mute", on: !car.muted })}>
            <span className={`icon ${car.muted ? "i-speaker-slash-fill" : "i-speaker-low-fill"}`} aria-hidden="true" />
          </button>
          <div
            ref={slider}
            className="music-volume-track"
            role="slider"
            tabIndex={0}
            aria-label="Volume"
            aria-valuemin={0}
            aria-valuemax={max}
            aria-valuenow={car.volume}
            aria-valuetext={car.muted ? "Muted" : String(car.volume)}
            onPointerDown={(e) => { setDragging(true); e.currentTarget.setPointerCapture(e.pointerId); volumeAt(e); }}
            onPointerMove={(e) => { if (dragging) volumeAt(e); }}
            onPointerUp={() => setDragging(false)}
            onPointerCancel={() => setDragging(false)}
            onKeyDown={(e) => {
              const step = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1 }[e.key];
              if (step) { e.preventDefault(); setVolume(car.volume + step); }
            }}
          >
            <div className="music-volume-bar"><div className="music-volume-fill" style={{ transform: `scaleX(${car.muted ? 0 : car.volume / max})`, ...muteFill }} /></div>
          </div>
          <button className="music-volume-step" aria-label="Volume up" {...holdToRepeat(() => stepVolume(1))}>
            <span className="icon i-speaker-high-fill" aria-hidden="true" />
          </button>
        </div>
      </div>
      </>)}
    </section>
  );
}
