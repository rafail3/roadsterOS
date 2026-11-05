// The one map of the emulator: MapsApp makes it once, and it lives either in the open Maps app or on the card on home,
// moved between the two as an element, never made twice (the unit does the same with its one MapView).

type Slot = "app" | "card";

let host: { root: HTMLDivElement; gl: HTMLDivElement; overlay: HTMLDivElement } | null = null;
const slots: Record<Slot, HTMLElement | null> = { app: null, card: null };
let inApp = false;
let moved: (() => void) | null = null;

/** The map's element: the MapLibre container and, over it, the layer for the street pill and the speed dial. */
export function mapHost() {
  if (!host) {
    const root = document.createElement("div");
    root.className = "map-host";
    const gl = document.createElement("div");
    gl.className = "map-gl";
    const overlay = document.createElement("div");
    overlay.className = "map-overlay";
    root.append(gl, overlay);
    host = { root, gl, overlay };
  }
  return host;
}

function place() {
  const target = inApp ? slots.app : slots.card;
  const root = mapHost().root;
  if (target && root.parentElement !== target) {
    target.appendChild(root);
    moved?.();
  }
}

/** The app and the card each say where the map goes in them; a slot that leaves passes null. */
export function setSlot(slot: Slot, el: HTMLElement | null) {
  slots[slot] = el;
  place();
}

/** The open app takes the map; closed, the card has it. */
export function showMapIn(app: boolean) {
  inApp = app;
  place();
}

export function onMapMoved(f: (() => void) | null) {
  moved = f;
}

export const mapInApp = () => inApp;
