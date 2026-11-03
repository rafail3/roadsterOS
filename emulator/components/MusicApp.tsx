import { useRef, useState, type PointerEvent } from "react";
import { bridge } from "../car/bridge";
import { carCommand, setVolume, shellAction, useEmulator } from "../car/store";
import { Marquee, Progress, time, TransportRow, useCoverColors, useCrossfade, usePosition } from "./media";

/** Full-screen Bluetooth music: the cover blurred into the whole background, a large cover, transport and volume. */
export function MusicApp({ open }: { open: boolean }) {
  const emulator = useEmulator();
  const { car, track, device } = emulator;
  const { elapsed, duration } = usePosition();
  const backdrops = useCrossfade(track.art);
  const covers = useCrossfade(track.art);
  const max = bridge.maxVolume();
  const colors = useCoverColors(track.art);
  const [dragging, setDragging] = useState(false);
  const slider = useRef<HTMLDivElement>(null);

  const volumeAt = (e: PointerEvent) => {
    const r = slider.current!.getBoundingClientRect();
    setVolume(Math.round(Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)) * max));
  };

  return (
    <section className={`music-app${open ? " is-open" : ""}`} aria-label="Music" aria-hidden={!open} inert={!open} style={{ "--cover-top": colors.top, "--cover-bottom": colors.bottom } as React.CSSProperties}>
      {/* on the unit the cover is decoded small and blurred once per track; no live blur */}
      <div className="music-backdrop" aria-hidden="true">
        {backdrops.map((layer) => layer.src && <img key={layer.id} src={layer.src} alt="" />)}
      </div>

      <button className="glass-button music-close" aria-label="Close music" onClick={() => shellAction({ type: "Home" })}>
        <span className="icon i-caret-down" aria-hidden="true" />
      </button>

      <div className={`music-cover${car.playing ? " is-playing" : ""}`}>
        {covers.map((layer) => (layer.src
          ? <img key={layer.id} src={layer.src} alt={`Cover of ${track.title}`} />
          : <div key={layer.id} className="music-cover-empty"><span className="icon i-music-notes" aria-hidden="true" /></div>))}
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
            <span className="music-device"><span className="icon i-bluetooth" aria-hidden="true" />{device}</span>
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
          <span className="icon i-speaker-low" aria-hidden="true" />
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
            <div className="music-volume-bar"><div className="music-volume-fill" style={{ transform: `scaleX(${car.muted ? 0 : car.volume / max})` }} /></div>
          </div>
          <span className="icon i-speaker-high" aria-hidden="true" />
        </div>
      </div>
    </section>
  );
}
