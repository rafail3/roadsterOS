import maplibregl from "maplibre-gl";
import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { mapStyle } from "../car/mapStyle";
import { bridge } from "../car/bridge";
import { mapHost, onMapMoved, setSlot, showMapIn } from "../car/mapHost";
import { chime, limitAt, streetAt } from "../car/roads";
import { shellAction, useEmulator } from "../car/store";

/** Driving zoom, and the closest and farthest the buttons go. */
const DRIVE_ZOOM = 15.5;
const ZOOM_RANGE = { min: 4, max: 18 };
/** The street name under the car is looked up this often, not every position. */
const STREET_EVERY_MS = 1000;
/** The speed limit of the car's road is looked up this often, as on the unit. */
const LIMIT_EVERY_MS = 1000;

let bordersFile: Promise<GeoJSON.FeatureCollection> | null = null;
const loadBorders = () => (bordersFile ??= fetch("/maps/countries.geojson").then((r) => r.json()));

/**
 * The car: the navigation arrow seen from above (the Material Icons "navigation" shape), 53 px tall (44 units at 1.2), with a little
 * volume: filled with the location button's blue, #0a84ff, flat, a white rim with a fine dark edge, and a soft shadow that stays below it however it turns. Plain shapes (no blur), the same layers as on the unit.
 */
const ARROW = "M 0 -22 L 18 22 L 0 11.5 L -18 22 Z";
const PUCK = `<svg viewBox="-36 -36 72 72" width="86" height="86" aria-hidden="true">
  <g data-shadow stroke-linejoin="round" fill="#000" stroke="#000">
    <path d="${ARROW}" fill-opacity="0" stroke-opacity="0.05" stroke-width="13"/>
    <path d="${ARROW}" fill-opacity="0" stroke-opacity="0.07" stroke-width="9"/>
    <path d="${ARROW}" fill-opacity="0.2" stroke-opacity="0.1" stroke-width="5.5"/>
  </g>
  <g data-turn stroke-linejoin="round">
    <path d="${ARROW}" fill="none" stroke="#000" stroke-opacity="0.18" stroke-width="6.5"/>
    <path d="${ARROW}" fill="#fff" stroke="#fff" stroke-width="5"/>
    <path d="${ARROW}" fill="#0a84ff" stroke="#0a84ff" stroke-width="1.2"/>
  </g>
</svg>`;
/** Turns the arrow with the heading; its shadow turns too but stays a little below it on the screen. */
function turnPuck(el: HTMLElement, degrees: number) {
  el.querySelector("[data-turn]")?.setAttribute("transform", `rotate(${degrees})`);
  el.querySelector("[data-shadow]")?.setAttribute("transform", `translate(0 3) rotate(${degrees})`);
}

const TICKS = 60;
const TONE = { WHITE: "is-lit", ORANGE: "is-near", RED: "is-hot" } as const;

/**
 * The ticks around the speed, lit by the unit's rule (core-car SpeedWarning.ring): in proportion to the limit, full at
 * it, orange close to it, all red over it. Each tick changes a moment after the one before, so a change sweeps round.
 */
const SpeedTicks = memo(function SpeedTicks({ lit, tone }: { lit: number; tone: keyof typeof TONE }) {
  return (
    <svg className="map-speed-ticks" viewBox="0 0 84 84" aria-hidden="true">
      {Array.from({ length: TICKS }, (_, i) => {
        const a = ((180 + (i * 360) / TICKS) * Math.PI) / 180;
        return (
          <line key={i} className={i < lit ? TONE[tone] : undefined} x1={42 + 34 * Math.sin(a)} y1={42 - 34 * Math.cos(a)}
            x2={42 + 39.5 * Math.sin(a)} y2={42 - 39.5 * Math.cos(a)} style={{ transitionDelay: `${i * 5}ms` }} />
        );
      })}
    </svg>
  );
});

/**
 * Full-screen map: the car in the middle, heading up while it follows, the street it is on, and the map controls.
 * Closed, the same map shows on the card on home ([inCard] while that page is in view); it works only while seen.
 */
export function MapsApp({ open, inCard, light }: { open: boolean; inCard: boolean; light: boolean }) {
  const { gps, maps, driving, speed } = useEmulator();
  const seen = open || inCard;
  const section = useRef<HTMLElement>(null);
  const map = useRef<maplibregl.Map | null>(null);
  const puck = useRef<maplibregl.Marker | null>(null);
  const [follow, setFollow] = useState(true);
  const [headingUp, setHeadingUp] = useState(true);
  const [bearing, setBearing] = useState(0);
  const [street, setStreet] = useState("");
  const [warning, setWarning] = useState<{ limit: number | null; over: boolean; lit: number; tone: "WHITE" | "ORANGE" | "RED" }>({ limit: null, over: false, lit: 0, tone: "WHITE" });
  const limitAtMs = useRef(0);
  // the limit of the car's road, looked up once a second; the warning follows every fix
  const roadLimit = useRef<number | null>(null);
  // the last limit stays written while its sign shrinks away
  const shownLimit = useRef<number | null>(null);
  if (warning.limit !== null) shownLimit.current = warning.limit;
  // the last street stays written while its pill folds away
  const shownStreet = useRef("");
  if (street) shownStreet.current = street;
  const [credit, setCredit] = useState(false);
  const [borders, setBorders] = useState<GeoJSON.FeatureCollection | null>(null);
  const streetAtMs = useRef(0);
  const latest = useRef({ gps, speed });
  latest.current = { gps, speed };

  useEffect(() => { loadBorders().then(setBorders).catch(() => {}); }, []);
  // the packs built on this laptop: with one, the map draws from it and needs no network, as on the unit
  const [offline, setOffline] = useState<boolean | null>(null);
  useEffect(() => { fetch("/api/tiles").then((r) => r.json()).then((d: { packs: string[] }) => setOffline(d.packs.length > 0)).catch(() => setOffline(false)); }, []);
  // the countries without their pack, covered on the map
  const missing = useMemo<GeoJSON.FeatureCollection>(() => ({
    type: "FeatureCollection",
    features: (borders?.features ?? []).filter((f) => !maps.installed.includes(f.properties?.code)),
  }), [borders, maps.installed]);

  // the map is made once, on the card, and kept: opening the app moves it there at once
  useEffect(() => {
    if (map.current || offline === null) return;
    const m = new maplibregl.Map({
      container: mapHost().gl,
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
    // once the tiles under the car have loaded, its street and limit are known even before it moves
    m.on("idle", () => {
      const { gps: at, speed: s } = latest.current;
      const heading = s.stopped ? null : at.heading;
      setStreet(streetAt(m, at.lon, at.lat, m.getZoom(), heading));
      roadLimit.current = limitAt(m, at.lon, at.lat, heading);
      const w = bridge.speedWarning(roadLimit.current, performance.now());
      if (w.chime) chime();
      setWarning((old) => (old.limit === w.limit && old.over === w.over && old.lit === w.lit && old.tone === w.tone ? old : w));
    });
    const el = document.createElement("div");
    el.className = "map-puck";
    el.innerHTML = PUCK;
    puck.current = new maplibregl.Marker({ element: el }).setLngLat([gps.lon, gps.lat]).addTo(m);
    turnPuck(el, gps.heading - m.getBearing());
    map.current = m;
  }, [offline]);

  // the open app takes the map, the card has it otherwise; on the card it always follows the car
  useLayoutEffect(() => {
    onMapMoved(() => {
      map.current?.resize();
      if (!open) setFollow(true);
    });
    showMapIn(open);
  }, [open]);
  // seen again: straight onto the car, no long glide from where it was left
  useEffect(() => {
    if (seen) map.current?.jumpTo({ center: [latest.current.gps.lon, latest.current.gps.lat] });
  }, [seen]);

  // leaving, the app's outline closes onto the card on home, as the music app does
  useLayoutEffect(() => {
    const app = section.current;
    const card = app?.parentElement?.querySelector<HTMLElement>(".panel");
    if (!app || !card) return;
    const { offsetLeft: l, offsetTop: t, offsetWidth: w, offsetHeight: h } = card;
    app.style.setProperty("--to-t", `${t}px`);
    app.style.setProperty("--to-l", `${l}px`);
    app.style.setProperty("--to-r", `${app.offsetWidth - l - w}px`);
    app.style.setProperty("--to-b", `${app.offsetHeight - t - h}px`);
  }, [open]);
  useEffect(() => { if (offline !== null) map.current?.setStyle(mapStyle(light, missing, offline)); }, [light, missing, offline]);

  useEffect(() => {
    const m = map.current;
    // unseen, the map does no work
    if (!m || !seen) return;
    puck.current?.setLngLat([gps.lon, gps.lat]);
    if (puck.current) turnPuck(puck.current.getElement(), gps.heading - m.getBearing());
    if (follow) m.easeTo({ center: [gps.lon, gps.lat], bearing: headingUp ? gps.heading : 0, duration: driving ? 260 : 600, easing: (t) => t });
    const now = performance.now();
    const heading = speed.stopped ? null : gps.heading;
    if (now - streetAtMs.current > STREET_EVERY_MS && m.isStyleLoaded()) {
      streetAtMs.current = now;
      setStreet(streetAt(m, gps.lon, gps.lat, m.getZoom(), heading));
    }
    // the limit sign and the warning, by the unit's rule (core-car SpeedWarning)
    if (now - limitAtMs.current > LIMIT_EVERY_MS && m.isStyleLoaded()) {
      limitAtMs.current = now;
      roadLimit.current = limitAt(m, gps.lon, gps.lat, heading);
    }
    const w = bridge.speedWarning(roadLimit.current, now);
    if (w.chime) chime();
    setWarning((old) => (old.limit === w.limit && old.over === w.over && old.lit === w.lit && old.tone === w.tone ? old : w));
  }, [gps, speed, follow, headingUp, seen, driving]);

  const zoom = (by: number) => map.current?.easeTo({ zoom: map.current.getZoom() + by, duration: 250 });
  const recentre = () => { setFollow(true); map.current?.easeTo({ zoom: DRIVE_ZOOM, duration: 500 }); };

  return (
    <section ref={section} className={`music-app maps-app${open ? " is-open" : ""}`} aria-label="Maps" aria-hidden={!open} inert={!open}>
      <div ref={(el) => setSlot("app", el)} className="map-canvas" role="region" aria-label="Map" />

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

      {/* in the map's own layer, so it goes with the map onto the card */}
      {createPortal(<div className="map-status">
        <div className={`map-street-slot${street ? " is-shown" : ""}`} aria-hidden={!street}>
          <span className="map-pill map-street" aria-live="polite">{shownStreet.current}</span>
        </div>
        <span className={`map-speed${warning.over ? " is-over" : ""}`} role="img" aria-label={`${speed.kmh} km/h`}>
          {/* the dial's ring of ticks */}
          <SpeedTicks lit={warning.lit} tone={warning.tone} />
          <b>{speed.kmh}</b>
          <small>km/h</small>
          <span className={`map-limit${warning.limit !== null ? " is-shown" : ""}`} role="img"
            aria-label={warning.limit !== null ? `Speed limit ${warning.limit} km/h` : undefined} aria-hidden={warning.limit === null}>
            <b key={shownLimit.current ?? 0}>{shownLimit.current}</b>
          </span>
        </span>
      </div>, mapHost().overlay)}
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
