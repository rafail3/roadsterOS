"use client";

import { useLayoutEffect, useRef, useState } from "react";
import { useEmulator } from "../car/store";
import { ControlPanel } from "./ControlPanel";
import { Inspector } from "./Inspector";
import { Screen } from "./Screen";
import { Segmented } from "./controls";

/** The screen plus its bezel, unscaled. */
const FRAME = { width: 1340, height: 784 };
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
  ];
  return (
    <dl className="car-status" aria-label="Car status">
      {items.map(([label, value]) => (
        <div key={label}><dt>{label}</dt><dd>{value}</dd></div>
      ))}
    </dl>
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
          <section className="device" aria-label="Head unit, 1280 by 720"><Screen /></section>
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
