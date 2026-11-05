// The road the car is on, found as the unit finds it (core-map Roads, Street, SpeedLimit): the nearest line of a tile
// layer within 24 px at a zoom, among those running the way the car drives once it moves. The emulator reads the
// features of the tiles MapLibre has loaded; the unit reads the same tiles from its pack. Measured in world pixels at
// the zoom, so the map's turn does not change the match.
import type maplibregl from "maplibre-gl";

const MAX_PX = 24;
const MAX_TURN = 40;
const DRIVABLE = new Set(["motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service", "track"]);
/** The limit is looked up at this zoom whatever the map shows; the limit line must lie within this of the car's road. */
const LIMIT_ZOOM = 15.5;
const SAME_ROAD_PX = 6;

type Hit = { props: Record<string, unknown>; d2: number; along: boolean };

const worldX = (lon: number, z: number) => ((lon + 180) / 360) * 512 * 2 ** z;
const worldY = (lat: number, z: number) => {
  const r = (Math.max(-85.05112878, Math.min(85.05112878, lat)) * Math.PI) / 180;
  return ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * 512 * 2 ** z;
};

/** The angle between two compass directions, 0 to 180. */
const turn = (a: number, b: number) => Math.abs(((((a - b) % 360) + 540) % 360) - 180);

function toSegment(ax: number, ay: number, bx: number, by: number) {
  const dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
  const t = len === 0 ? 0 : Math.max(0, Math.min(1, (-ax * dx - ay * dy) / len));
  const x = ax + t * dx, y = ay + t * dy;
  return x * x + y * y;
}

function nearest(map: maplibregl.Map, sourceLayer: string, lon: number, lat: number, zoom: number, heading: number | null,
  accept: (props: Record<string, unknown>) => boolean): Hit | null {
  const cx = worldX(lon, zoom), cy = worldY(lat, zoom);
  let best: Hit | null = null;
  let bestD = MAX_PX * MAX_PX;
  for (const f of map.querySourceFeatures("omt", { sourceLayer })) {
    const props = (f.properties ?? {}) as Record<string, unknown>;
    if ((f.geometry.type !== "LineString" && f.geometry.type !== "MultiLineString") || !accept(props)) continue;
    const lines = f.geometry.type === "LineString" ? [f.geometry.coordinates] : f.geometry.coordinates;
    for (const line of lines) {
      for (let i = 1; i < line.length; i++) {
        const ax = worldX(line[i - 1][0], zoom) - cx, ay = worldY(line[i - 1][1], zoom) - cy;
        const bx = worldX(line[i][0], zoom) - cx, by = worldY(line[i][1], zoom) - cy;
        const d = toSegment(ax, ay, bx, by);
        if (d >= bestD) continue;
        let along = true;
        if (heading !== null) {
          const t = turn(heading, (Math.atan2(bx - ax, -(by - ay)) * 180) / Math.PI);
          if (t <= MAX_TURN) along = true;
          else if (t >= 180 - MAX_TURN) along = false;
          else continue;
        }
        bestD = d;
        best = { props, d2: d, along };
      }
    }
  }
  return best;
}

const drivable = (p: Record<string, unknown>) => DRIVABLE.has(p.class as string);

/** The name of the street the car is on at the map's [zoom]; [heading] null while the car stands. */
export function streetAt(map: maplibregl.Map, lon: number, lat: number, zoom: number, heading: number | null): string {
  const hit = nearest(map, "transportation_name", lon, lat, zoom, heading, (p) => drivable(p) && typeof p.name === "string");
  return (hit?.props.name as string | undefined) ?? "";
}

/** The posted limit of the car's road for its direction, in km/h, from the pack's maxspeed layer; null when unknown. */
export function limitAt(map: maplibregl.Map, lon: number, lat: number, heading: number | null): number | null {
  const road = nearest(map, "transportation", lon, lat, LIMIT_ZOOM, heading, drivable);
  const line = road && nearest(map, "maxspeed", lon, lat, LIMIT_ZOOM, heading, () => true);
  if (!road || !line || Math.sqrt(line.d2) > Math.sqrt(road.d2) + SAME_ROAD_PX) return null;
  const kmh = line.props[line.along ? "fwd" : "bwd"];
  return typeof kmh === "number" ? kmh : null;
}

let audio: AudioContext | null = null;

/** The over-limit chime, the same two notes as the unit's: 880 Hz then 1318.5 Hz 120 ms later, each fading in 350 ms. */
export function chime() {
  try {
    audio ??= new AudioContext();
    const t = audio.currentTime;
    for (const [hz, at] of [[880, 0], [1318.5, 0.12]]) {
      const o = audio.createOscillator();
      const g = audio.createGain();
      o.frequency.value = hz;
      g.gain.setValueAtTime(0, t + at);
      g.gain.linearRampToValueAtTime(0.18, t + at + 0.01);
      g.gain.exponentialRampToValueAtTime(0.001, t + at + 0.35);
      o.connect(g).connect(audio.destination);
      o.start(t + at);
      o.stop(t + at + 0.4);
    }
  } catch { /* no audio in this browser */ }
}
