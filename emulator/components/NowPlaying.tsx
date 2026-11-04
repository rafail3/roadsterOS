import { useState } from "react";
import { carCommand, type EmulatorState } from "../car/store";

const PRESETS = [1, 2, 3, 4, 5, 6];

function time(ms: number) {
  const seconds = Math.round(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

function Bluetooth({ emulator }: { emulator: EmulatorState }) {
  const { car, track, positionMs } = emulator;
  const progress = track.durationMs ? Math.min(100, (positionMs / track.durationMs) * 100) : 0;
  return (
    <div className="np">
      <div className="art" aria-hidden="true" />
      <div className="np-meta" aria-live="polite">
        <p className="np-title">{track.title}</p>
        <p className="np-artist">{track.artist}</p>
      </div>
      <div className="progress" aria-hidden="true"><div className="progress-fill" style={{ width: `${progress}%` }} /></div>
      <div className="np-times"><span>{time(positionMs)}</span><span>{time(track.durationMs)}</span></div>
      <div className="controls">
        <button className="ctl" aria-label="Previous" onClick={() => carCommand({ type: "Media.Prev" })}><span className="icon i-skip-back" /></button>
        <button className="ctl ctl-primary" aria-label={car.playing ? "Pause" : "Play"} onClick={() => carCommand({ type: car.playing ? "Media.Pause" : "Media.Play" })}>
          <span className={`icon ${car.playing ? "i-pause" : "i-play"}`} />
        </button>
        <button className="ctl" aria-label="Next" onClick={() => carCommand({ type: "Media.Next" })}><span className="icon i-skip-forward" /></button>
      </div>
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
