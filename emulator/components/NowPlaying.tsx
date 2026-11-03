import { useState } from "react";
import { carCommand, shellAction, type EmulatorState } from "../car/store";
import { Marquee, Progress, time, TransportRow, useCrossfade, usePosition } from "./media";

const PRESETS = [1, 2, 3, 4, 5, 6];

function Bluetooth({ emulator }: { emulator: EmulatorState }) {
  const { car, track } = emulator;
  const { elapsed, duration } = usePosition();
  const tints = useCrossfade(track.art);
  const covers = useCrossfade(track.art);
  return (
    <div className="np np-bt">
      <div className="np-tint" aria-hidden="true">
        {tints.map((layer) => layer.src && <img key={layer.id} src={layer.src} alt="" />)}
      </div>
      <button className="np-open" aria-label={`Open music, ${track.title} by ${track.artist}`} onClick={() => shellAction({ type: "OpenApp", app: "MUSIC" })}>
        <span className={`np-cover${car.playing ? " is-playing" : ""}`}>
          {covers.map((layer) => (layer.src
            ? <img key={layer.id} src={layer.src} alt="" />
            : <span key={layer.id} className="np-cover-empty"><span className="icon i-music-notes" /></span>))}
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

function Radio({ emulator }: { emulator: EmulatorState }) {
  const { car, radio } = emulator;
  const [preset, setPreset] = useState(0);
  return (
    <div className="np np-radio">
      <p className="freq"><span className="freq-num">{radio.band === "AM" ? radio.khz : (radio.khz / 1000).toFixed(1)}</span><span className="freq-band">{radio.band}</span></p>
      <p className="np-title">{radio.rds}</p>
      <p className="np-artist">Evening programme</p>
      <div className="presets">
        {PRESETS.map((n, i) => (
          <button
            key={n}
            className={`preset${i === preset ? " is-active" : ""}`}
            aria-label={`Preset ${n}`}
            aria-pressed={i === preset}
            onClick={() => { setPreset(i); carCommand({ type: "Radio.Preset", index: i }); }}
          >{n}</button>
        ))}
      </div>
      <div className="controls">
        <button className="ctl" aria-label="Seek down" onClick={() => carCommand({ type: "Radio.Seek", up: false })}><span className="icon i-skip-back" /></button>
        <button className="ctl ctl-primary" aria-label="Mute" onClick={() => carCommand({ type: "Audio.Mute", on: !car.muted })}><span className="icon i-speaker-high" /></button>
        <button className="ctl" aria-label="Seek up" onClick={() => carCommand({ type: "Radio.Seek", up: true })}><span className="icon i-skip-forward" /></button>
      </div>
    </div>
  );
}

function NoSource() {
  return (
    <div className="np np-empty">
      <p className="np-title">No source</p>
      <p className="np-artist">Connect a phone or choose Radio</p>
      <div className="controls">
        <button className="ctl ctl-wide" onClick={() => carCommand({ type: "Audio.Source", source: "BT" })}><span className="icon i-bluetooth" /><span>Bluetooth</span></button>
        <button className="ctl ctl-wide" onClick={() => carCommand({ type: "Audio.Source", source: "RADIO" })}><span className="icon i-radio" /><span>Radio</span></button>
      </div>
    </div>
  );
}

export function NowPlaying({ emulator }: { emulator: EmulatorState }) {
  const { source } = emulator.car;
  if (source === "BT") return <Bluetooth emulator={emulator} />;
  if (source === "RADIO") return <Radio emulator={emulator} />;
  return <NoSource />;
}
