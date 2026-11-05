import maplibregl from "maplibre-gl";
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { mapStyle } from "../car/mapStyle";
import { shellAction, useEmulator } from "../car/store";

/** Driving zoom, and the closest and farthest the buttons go. */
const DRIVE_ZOOM = 15.5;
const ZOOM_RANGE = { min: 4, max: 18 };
/** The street name under the car is looked up this often, not every position. */
const STREET_EVERY_MS = 1000;
/** How far from the car a road may be and still be the one it is on, in screen pixels. */
const STREET_PX = 24;

let bordersFile: Promise<GeoJSON.FeatureCollection> | null = null;
const loadBorders = () => (bordersFile ??= fetch("/maps/countries.geojson").then((r) => r.json()));

/**
 * The car, as a disc lying on the road seen at an angle: a white rim with some thickness, a blue face, and a white
 * chevron on it that turns with the heading. Everything is drawn in the road's plane and then flattened by [TILT],
 * so the chevron turns on the disc as it would on the ground. Plain shapes (no blur), the same layers as on the unit.
 */
const TILT = 0.62;
const CHEVRON = "M 0 -13 L 11 10 L 0 4.5 L -11 10 Z";
const PUCK = `<svg viewBox="-36 -36 72 72" width="97" height="97" aria-hidden="true">
  <defs>
    <linearGradient id="puck-face" x1="0" y1="-21" x2="0" y2="21" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#4fb0ff"/><stop offset="1" stop-color="#0a64e8"/></linearGradient>
    <linearGradient id="puck-gloss" x1="0" y1="-21" x2="0" y2="2" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#fff" stop-opacity="0.35"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>
  </defs>
  <g transform="translate(0 7) scale(1 ${TILT})" fill="#000">
    <circle r="31" fill-opacity="0.06"/><circle r="28.5" fill-opacity="0.08"/><circle r="26.5" fill-opacity="0.12"/>
  </g>
  <g transform="translate(0 4.5) scale(1 ${TILT})"><circle r="26" fill="#8e9bb0"/></g>
  <g transform="translate(0 2.2) scale(1 ${TILT})"><circle r="26" fill="#cdd5e1"/></g>
  <g transform="scale(1 ${TILT})">
    <circle r="26" fill="#fff"/>
    <circle r="20.5" fill="url(#puck-face)"/>
    <circle r="20.5" fill="url(#puck-gloss)"/>
    <circle r="20.5" fill="none" stroke="#0a4fc0" stroke-opacity="0.45" stroke-width="1.2"/>
  </g>
  <g data-turn-shadow><path d="${CHEVRON}" fill="#06357f" fill-opacity="0.35" stroke="#06357f" stroke-opacity="0.35" stroke-width="2.4" stroke-linejoin="round"/></g>
  <g data-turn><path d="${CHEVRON}" fill="#fff" stroke="#fff" stroke-width="2.4" stroke-linejoin="round"/></g>
</svg>`;
/** Turns the chevron on the disc: in the road's plane first, then flattened with it. */
function turnPuck(el: HTMLElement, degrees: number) {
  el.querySelector("[data-turn]")?.setAttribute("transform", `scale(1 ${TILT}) rotate(${degrees})`);
  // the chevron's shadow on the disc, a little below it
  el.querySelector("[data-turn-shadow]")?.setAttribute("transform", `translate(0 1.4) scale(1 ${TILT}) rotate(${degrees})`);
}

/** Squared distance from a point to a segment, in screen pixels. */
function segmentDistance(p: { x: number; y: number }, a: { x: number; y: number }, b: { x: number; y: number }) {
  const dx = b.x - a.x, dy = b.y - a.y;
  const t = dx || dy ? Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy))) : 0;
  const x = a.x + t * dx - p.x, y = a.y + t * dy - p.y;
  return x * x + y * y;
}

/** The name of the road under the car, from the road names in the tiles already loaded. */
function streetAt(map: maplibregl.Map, lon: number, lat: number) {
  const at = map.project([lon, lat]);
  let best = { d: STREET_PX * STREET_PX, name: "" };
  for (const f of map.querySourceFeatures("omt", { sourceLayer: "transportation_name" })) {
    const name = f.properties?.name as string | undefined;
    if (!name || (f.geometry.type !== "LineString" && f.geometry.type !== "MultiLineString")) continue;
    const lines = f.geometry.type === "LineString" ? [f.geometry.coordinates] : f.geometry.coordinates;
    for (const line of lines) {
      for (let i = 1; i < line.length; i++) {
        const d = segmentDistance(at, map.project(line[i - 1] as [number, number]), map.project(line[i] as [number, number]));
        if (d < best.d) best = { d, name };
      }
    }
  }
  return best.name;
}

/** Full-screen map: the car in the middle, heading up while it follows, the street it is on, and the map controls. */
export function MapsApp({ open, light }: { open: boolean; light: boolean }) {
  const { gps, maps, driving } = useEmulator();
  const box = useRef<HTMLDivElement>(null);
  const map = useRef<maplibregl.Map | null>(null);
  const puck = useRef<maplibregl.Marker | null>(null);
  const [follow, setFollow] = useState(true);
  const [headingUp, setHeadingUp] = useState(true);
  const [bearing, setBearing] = useState(0);
  const [street, setStreet] = useState("");
  const [credit, setCredit] = useState(false);
  const [borders, setBorders] = useState<GeoJSON.FeatureCollection | null>(null);
  const streetAtMs = useRef(0);
  const latest = useRef({ gps });
  latest.current = { gps };

  useEffect(() => { loadBorders().then(setBorders).catch(() => {}); }, []);
  // the packs built on this laptop: with one, the map draws from it and needs no network, as on the unit
  const [offline, setOffline] = useState<boolean | null>(null);
  useEffect(() => { fetch("/api/tiles").then((r) => r.json()).then((d: { packs: string[] }) => setOffline(d.packs.length > 0)).catch(() => setOffline(false)); }, []);
  // the countries without their pack, covered on the map
  const missing = useMemo<GeoJSON.FeatureCollection>(() => ({
    type: "FeatureCollection",
    features: (borders?.features ?? []).filter((f) => !maps.installed.includes(f.properties?.code)),
  }), [borders, maps.installed]);

  // the map is made the first time the app opens and kept, so opening it again is instant
  useEffect(() => {
    if (!open || map.current || !box.current || offline === null) return;
    const m = new maplibregl.Map({
      container: box.current,
      style: mapStyle(light, missing, offline),
      center: [gps.lon, gps.lat],
      zoom: DRIVE_ZOOM,
      bearing: gps.heading,
      attributionControl: false,
      pitchWithRotate: false,
      minZoom: ZOOM_RANGE.min,
      maxZoom: ZOOM_RANGE.max,
      fadeDuration: 0,
    });
    // a finger on the map stops following the car until the recentre button
    m.on("dragstart", (e) => { if (e.originalEvent) setFollow(false); });
    m.on("rotate", () => {
      setBearing(m.getBearing());
      turnPuck(el, latest.current.gps.heading - m.getBearing());
    });
    // once the tiles under the car have loaded, the street is known even before the car moves
    m.on("idle", () => { const { gps: at } = latest.current; setStreet(streetAt(m, at.lon, at.lat)); });
    const el = document.createElement("div");
    el.className = "map-puck";
    el.innerHTML = PUCK;
    puck.current = new maplibregl.Marker({ element: el }).setLngLat([gps.lon, gps.lat]).addTo(m);
    turnPuck(el, gps.heading - m.getBearing());
    map.current = m;
  }, [open, offline]);

  useLayoutEffect(() => { if (open) map.current?.resize(); }, [open]);
  useEffect(() => { if (offline !== null) map.current?.setStyle(mapStyle(light, missing, offline)); }, [light, missing, offline]);

  useEffect(() => {
    const m = map.current;
    if (!m) return;
    puck.current?.setLngLat([gps.lon, gps.lat]);
    if (puck.current) turnPuck(puck.current.getElement(), gps.heading - m.getBearing());
    if (follow) m.easeTo({ center: [gps.lon, gps.lat], bearing: headingUp ? gps.heading : 0, duration: driving ? 260 : 600, easing: (t) => t });
    const now = performance.now();
    if (open && now - streetAtMs.current > STREET_EVERY_MS && m.isStyleLoaded()) {
      streetAtMs.current = now;
      setStreet(streetAt(m, gps.lon, gps.lat));
    }
  }, [gps, follow, headingUp, open, driving]);

  const zoom = (by: number) => map.current?.easeTo({ zoom: map.current.getZoom() + by, duration: 250 });
  const recentre = () => { setFollow(true); map.current?.easeTo({ zoom: DRIVE_ZOOM, duration: 500 }); };

  return (
    <section className={`music-app maps-app${open ? " is-open" : ""}`} aria-label="Maps" aria-hidden={!open} inert={!open}>
      <div ref={box} className="map-canvas" role="region" aria-label="Map" />

      <button className="glass-button music-close" aria-label="Close maps" onClick={() => shellAction({ type: "Home" })}>
        <span className="icon i-caret-down" aria-hidden="true" />
      </button>

      <div className="map-controls">
        <button className="glass-button" aria-label={headingUp ? "North up" : "Heading up"} aria-pressed={headingUp} onClick={() => setHeadingUp(!headingUp)}>
          <span className="map-compass" style={{ transform: `rotate(${-bearing}deg)` }} aria-hidden="true"><i /></span>
        </button>
        <div className="map-zoom">
          <button aria-label="Zoom in" onClick={() => zoom(1)}><span aria-hidden="true">+</span></button>
          <button aria-label="Zoom out" onClick={() => zoom(-1)}><span aria-hidden="true">−</span></button>
        </div>
        <button className="glass-button map-locate" aria-label={follow ? "Following the car" : "Follow the car"} aria-pressed={follow} onClick={recentre}>
          <span className={`icon ${follow ? "i-navigation-arrow-fill" : "i-navigation-arrow"}`} aria-hidden="true" />
        </button>
      </div>

      {(street || driving) && (
        <div className="map-status" aria-live="polite">
          {driving && <span className="map-speed"><b>{Math.round(gps.kmh)}</b> km/h</span>}
          {street && <span className="map-street">{street}</span>}
        </div>
      )}
      {offline === false && <p className="map-preview">Online preview: no map pack built on this laptop</p>}
      <div className="map-credit">
        {credit && <p className="map-credit-text">© OpenMapTiles © OpenStreetMap contributors</p>}
        <button className="glass-button" aria-label="Map data credits" aria-expanded={credit} onClick={() => setCredit(!credit)}>
          <span className="icon i-info" aria-hidden="true" />
        </button>
      </div>
    </section>
  );
}
