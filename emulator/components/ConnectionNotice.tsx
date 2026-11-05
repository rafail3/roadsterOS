import { useEffect, useState } from "react";
import type { ConnectionNotice as Notice } from "../car/store";
import { WifiBars } from "./WifiBars";

const VISIBLE_MS = 3000;

/**
 * A short notice at the bottom of the screen whenever Bluetooth or Wi-Fi connects or drops, whoever started it.
 * A newer notice replaces the one on screen and restarts its time. `hidden` while a call or the camera fills the screen.
 */
export function ConnectionNotice({ notice, hidden }: { notice: Notice | null; hidden: boolean }) {
  const [shown, setShown] = useState(false);

  useEffect(() => {
    if (!notice) return;
    setShown(true);
    const timer = setTimeout(() => setShown(false), VISIBLE_MS);
    return () => clearTimeout(timer);
  }, [notice?.id]);

  const what = notice?.kind === "bt" ? "Bluetooth" : "Wi-Fi";
  return (
    <div className={`notice${shown && !hidden ? " is-shown" : ""}${notice && !notice.connected ? " is-off" : ""}`} role="status" aria-live="polite">
      {notice && (
        // keyed by the notice, so a replacing notice plays its icon and text in again
        <div key={notice.id} className="notice-body">
          <span className="notice-disc" aria-hidden="true">
            {notice.kind === "bt" ? <span className="icon i-bluetooth" /> : <WifiBars level={3} />}
          </span>
          <span className="notice-text">
            <span className="notice-title">{what} {notice.connected ? "connected" : "disconnected"}</span>
            <span className="notice-name">{notice.name}</span>
          </span>
        </div>
      )}
    </div>
  );
}
