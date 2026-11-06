import { useEffect, useRef, useState } from "react";
import { bridge, type Download } from "../car/bridge";
import { setReleaseOut } from "../car/store";

/** The emulated release: a 2.6 MB APK, as the unit's, arriving at a few hundred KB a second. */
const APK_BYTES = 2_617_448;

/**
 * Installing a new version, as the unit does it (CarRuntime.updates): the download with its figures
 * (core-car Updater.Download), then a countdown and a restart. [beforeRestart] runs just before it.
 */
export function useUpdateInstall(beforeRestart?: () => void) {
  const [download, setDownload] = useState<Download | null>(null);
  const [restartIn, setRestartIn] = useState<number | null>(null);
  const last = useRef(0);
  const install = () => {
    last.current = performance.now();
    setDownload(bridge.downloadStep({ total: APK_BYTES, done: 0, bytesPerSecond: 0 }, 0, 0));
  };
  useEffect(() => {
    if (!download || download.done >= download.total) return;
    const timer = setTimeout(() => {
      const now = performance.now();
      const ms = now - last.current;
      last.current = now;
      setDownload(bridge.downloadStep(download, Math.round((380 + Math.random() * 260) * 1024 * ms / 1000), ms));
    }, 200);
    return () => clearTimeout(timer);
  }, [download]);
  useEffect(() => {
    if (!download || download.done < download.total || restartIn !== null) return;
    setReleaseOut(null);
    setRestartIn(bridge.restartInS());
  }, [download, restartIn]);
  useEffect(() => {
    if (restartIn === null) return;
    if (restartIn <= 0) { beforeRestart?.(); window.location.reload(); return; }
    const timer = setTimeout(() => setRestartIn(restartIn - 1), 1000);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [restartIn]);
  const done = download !== null && download.done >= download.total;
  return { download, done, restartIn: restartIn ?? bridge.restartInS(), install };
}

/** "1.1 MB of 2.4 MB · 480 KB/s · 2 s left" */
export const downloadFigures = (d: Download) => `${d.doneText} of ${d.totalText} · ${d.speed}${d.left ? ` · ${d.left}` : ""}`;
