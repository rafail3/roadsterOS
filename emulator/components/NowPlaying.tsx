import type { CSSProperties } from "react";
import { bridge } from "../car/bridge";
import { formatFreq } from "../car/radio";
import { carCommand, setBluetooth, shellAction, toggleFavorite, type EmulatorState } from "../car/store";
import { FreqCounter, genreColor, Star, useGlidingKhz } from "./RadioApp";
import { Marquee, Progress, time, TransportRow, useCrossfade, usePosition } from "./media";


/** Bluetooth is the source but no phone is connected: nothing to play, one way to a phone. */
export function NoPhone() {
  return (
    <div className="np np-empty">
      <p className="np-title">No phone connected</p>
      <p className="np-artist">Connect a phone to play its music</p>
      <div className="controls">
        <button className="ctl ctl-wide" onClick={() => shellAction({ type: "OpenDevices" })}><span className="icon i-bluetooth" /><span>Connect</span></button>
      </div>
    </div>
  );
}

export function Bluetooth({ emulator }: { emulator: EmulatorState }) {
  const { car, track } = emulator;
  const { elapsed, duration } = usePosition();
  const tints = useCrossfade(track.art);
  const covers = useCrossfade(track.art);
  return (
    <div className="np np-bt">
      <div className="np-tint" aria-hidden="true">
        {tints.layers.map((layer, i, all) => layer.src && <img key={layer.id} src={layer.src} alt="" className={i < all.length - 1 ? "is-leaving" : undefined} onAnimationEnd={() => tints.settle(layer.id)} />)}
      </div>
      <button className="np-open" aria-label={`Open music, ${track.title} by ${track.artist}`} onClick={() => shellAction({ type: "OpenApp", app: "MUSIC" })}>
        <span className={`np-cover${car.playing ? " is-playing" : ""}`}>
          {covers.layers.map((layer) => (layer.src
            ? <img key={layer.id} src={layer.src} alt="" onAnimationEnd={() => covers.settle(layer.id)} />
            : <span key={layer.id} className="np-cover-empty" onAnimationEnd={() => covers.settle(layer.id)}><span className="icon i-music-notes" /></span>))}
        </span>
        <span className="np-meta" key={track.title}>
          <Marquee text={track.title} className="np-title" />
          <Marquee text={track.artist} className="np-artist" />
        </span>
      </button>
      <Progress elapsed={elapsed} duration={duration} className="np-progress" />
      <div className="np-times"><span>{time(elapsed)}</span><span>-{time(duration - elapsed)}</span></div>
      <TransportRow
        playing={car.playing}
        onPrev={() => carCommand({ type: "Media.Prev" })}
        onPlay={() => carCommand({ type: car.playing ? "Media.Pause" : "Media.Play" })}
        onNext={() => carCommand({ type: "Media.Next" })}
      />
    </div>
  );
}

/** The station playing, tinted by its genre; a tap opens the radio app. */
export function Radio({ emulator }: { emulator: EmulatorState }) {
  const { car, radio, favorites } = emulator;
  const genre = bridge.ptyName(radio.pty);
  const favorite = favorites.includes(radio.khz);
  const freq = formatFreq(radio.band, radio.khz);
  const gliding = useGlidingKhz(radio.band, radio.khz);
  return (
    <div className="np np-radio" style={{ "--genre": genreColor(radio.pty) } as CSSProperties}>
      <button className="np-open" aria-label={`Open radio, ${radio.rds || freq}`} onClick={() => shellAction({ type: "OpenApp", app: "RADIO" })}>
        <span className="radio-tags">
          <span className="radio-tag is-band">{radio.band}</span>
          {radio.stereo && <span className="radio-tag">Stereo</span>}
        </span>
        <span className="freq"><FreqCounter className="freq-num" band={radio.band} khz={gliding} target={radio.khz} /><span className="freq-band">{radio.band === "FM" ? "MHz" : "kHz"}</span></span>
        <span className="np-title np-radio-name" key={radio.rds}>{radio.rds || "Radio"}</span>
        {genre && <span className="radio-genre" key={genre}>{genre}</span>}
      </button>
      {/* between the station details and the transport, on its centre line */}
      <Star on={favorite} label={favorite ? "Remove from favorites" : "Add to favorites"} onToggle={() => toggleFavorite(radio.khz)} />
      <TransportRow
        playing={!car.muted}
        onPrev={() => carCommand({ type: "Radio.Seek", up: false })}
        onPlay={() => carCommand({ type: "Audio.Mute", on: !car.muted })}
        onNext={() => carCommand({ type: "Radio.Seek", up: true })}
      />
    </div>
  );
}

export function NoSource() {
  return (
    <div className="np np-empty">
      <p className="np-title">No source</p>
      <p className="np-artist">Connect a phone or choose Radio</p>
      <div className="controls">
        {/* choosing Bluetooth switches it on first when it was off */}
        <button className="ctl ctl-wide" onClick={() => { setBluetooth(true); carCommand({ type: "Audio.Source", source: "BT" }); }}><span className="icon i-bluetooth" /><span>Bluetooth</span></button>
        <button className="ctl ctl-wide" onClick={() => carCommand({ type: "Audio.Source", source: "RADIO" })}><span className="icon i-radio" /><span>Radio</span></button>
      </div>
    </div>
  );
}
