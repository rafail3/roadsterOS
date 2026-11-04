"use client";

import { useLayoutEffect, useRef, useState } from "react";
import type { PanelKey } from "../car/bridge";
import { carEvent, pressKey, useEmulator } from "../car/store";
import { ControlPanel } from "./ControlPanel";
import { Inspector } from "./Inspector";
import { Screen } from "./Screen";
import { Segmented } from "./controls";

/** The screen plus its bezel, unscaled. */
const FRAME = { width: 1154, height: 676 };
const VIEWS = [{ value: "fit", label: "Fit" }, { value: "actual", label: "100%" }] as const;

function CarStatus() {
  const { car, acc } = useEmulator();
  const items = [
    ["Ignition", acc ? "On" : "Off"],
    ["Source", { BT: "Bluetooth", RADIO: "Radio", NONE: "None" }[car.source]],
    ["Playback", car.playing ? "Playing" : "Stopped"],
    ["Call", { IDLE: "Idle", RINGING: "Ringing", ACTIVE: "Active" }[car.call]],
    ["Reverse", car.camera ? "Engaged" : "Off"],
    ["Headlights", car.night ? "On" : "Off"],
    ["Volume", car.muted ? "Muted" : String(car.volume)],
  ];
  return (
    <dl className="car-status" aria-label="Car status">
      {items.map(([label, value]) => (
        <div key={label}><dt>{label}</dt><dd>{value}</dd></div>
      ))}
    </dl>
  );
}

const PANEL_KEYS: { key: PanelKey; label: string; icon: string; sign?: string }[] = [
  { key: "POWER", label: "Power", icon: "i-power" },
  { key: "HOME", label: "Home", icon: "i-house" },
  { key: "BACK", label: "Back", icon: "i-back" },
  { key: "VOL_UP", label: "Volume up", icon: "i-speaker-simple", sign: "+" },
  { key: "VOL_DOWN", label: "Volume down", icon: "i-speaker-simple", sign: "−" },
];

/** The frame's left edge: microphone and reset pinholes, then the five touch keys. */
function FrameKeys() {
  return (
    <div className="frame-keys">
      <span className="pinhole pinhole-mic"><i aria-hidden="true" />MIC</span>
      <button className="pinhole pinhole-rst" aria-label="Reset, reloads the emulator" onClick={() => location.reload()}><i aria-hidden="true" />RST</button>
      {PANEL_KEYS.map(({ key, label, icon, sign }) => (
        <button
          key={key}
          className="frame-key"
          aria-label={label}
          onClick={() => carEvent({ type: "PanelKey", key })}
          onPointerDown={() => pressKey(key)}
          onPointerUp={() => pressKey(null)}
          onPointerLeave={() => pressKey(null)}
          onPointerCancel={() => pressKey(null)}
        >
          <span className={`icon ${icon}`} aria-hidden="true" />
          {sign && <span className="frame-sign" aria-hidden="true">{sign}</span>}
        </button>
      ))}
    </div>
  );
}

function Stage({ fit }: { fit: boolean }) {
  const stage = useRef<HTMLElement>(null);
  const [scale, setScale] = useState(1);

  useLayoutEffect(() => {
    if (!fit) return setScale(1);
    const observer = new ResizeObserver(([entry]) => {
      setScale(Math.min(1, entry.contentRect.width / FRAME.width, entry.contentRect.height / FRAME.height));
    });
    observer.observe(stage.current!);
    return () => observer.disconnect();
  }, [fit]);

  return (
    <main className="stage" ref={stage}>
      <div className="frame" style={{ width: FRAME.width * scale, height: FRAME.height * scale }}>
        <div className="bezel" style={{ transform: `scale(${scale})` }}>
          <FrameKeys />
          <section className="device" aria-label="Head unit, 1024 by 600"><Screen /></section>
        </div>
      </div>
    </main>
  );
}

export default function Emulator() {
  const [view, setView] = useState<"fit" | "actual">("fit");
  if (new URLSearchParams(location.search).has("bare")) return <Screen />;
  return (
    <div className="tool">
      <header className="topbar">
        <h1><span translate="no">roadsterOS</span> <span className="tool-name">emulator</span></h1>
        <CarStatus />
        <Segmented label="Screen size" options={VIEWS} value={view} onChange={setView} />
      </header>
      <Stage fit={view === "fit"} />
      <aside className="side" aria-label="Emulator controls">
        <ControlPanel />
        <Inspector />
      </aside>
    </div>
  );
}
