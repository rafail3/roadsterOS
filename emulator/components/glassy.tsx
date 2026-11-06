import { useEffect, useState } from "react";
import { useEmulator } from "../car/store";

type Glisse = typeof import("glisse");

let loaded: Glisse | null = null;
let loading: Promise<Glisse> | null = null;

/**
 * The glisse controls, for the Glassy design only: the library (and the styles it injects) loads the first time
 * Glassy is chosen; on Solid this answers null and nothing of it is fetched, so the Solid controls render as before.
 */
export function useGlisse(): Glisse | null {
  const glassy = useEmulator().shell.design === "GLASSY";
  const [mod, setMod] = useState<Glisse | null>(loaded);
  useEffect(() => {
    if (!glassy || loaded) return;
    let live = true;
    (loading ??= import("glisse").then((m) => (loaded = m))).then((m) => { if (live) setMod(m); });
    return () => { live = false; };
  }, [glassy]);
  return glassy ? mod ?? loaded : null;
}
