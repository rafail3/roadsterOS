// The map's look, as a style over the OpenMapTiles schema. Kept to fills, lines and text with zoom stops,
// so the unit's own renderer can draw the same style. No buildings: the country packs leave them out to stay small.

import type { StyleSpecification } from "maplibre-gl";

/** Without a local pack the emulator previews the same schema from the network; the unit never does. */
const ONLINE_TILES = "https://tiles.openfreemap.org/planet";
const ATTRIBUTION = "© OpenMapTiles © OpenStreetMap contributors";

interface Palette {
  land: string; landcover: string; park: string; residential: string; water: string; waterway: string; boundary: string;
  motorway: string; trunk: string; primary: string; secondary: string; minor: string; service: string; path: string; rail: string; casing: string;
  label: string; labelMuted: string; halo: string; roadLabel: string; waterLabel: string; mask: string;
}

const DARK: Palette = {
  land: "#0d1015", landcover: "#10161a", park: "#0f1a16", residential: "#11141a", water: "#0b1824", waterway: "#10253a", boundary: "#4a5260",
  motorway: "#b58a4f", trunk: "#8f7248", primary: "#5a6576", secondary: "#414a58", minor: "#272d35", service: "#1e2229", path: "#232830", rail: "#2c323b", casing: "#0d1015",
  label: "#e6e9ee", labelMuted: "#9aa3ae", halo: "#0d1015", roadLabel: "#aab3be", waterLabel: "#5f87ad", mask: "#06080b",
};

const LIGHT: Palette = {
  land: "#eef0f3", landcover: "#e3eae1", park: "#d6e8d4", residential: "#e8eaee", water: "#bcd6ee", waterway: "#a9c9e8", boundary: "#9aa3b0",
  motorway: "#f2b35b", trunk: "#f6c987", primary: "#ffffff", secondary: "#ffffff", minor: "#ffffff", service: "#f7f8fa", path: "#d9dde3", rail: "#c9ced6", casing: "#cfd4dc",
  label: "#1b2028", labelMuted: "#5a6472", halo: "#ffffff", roadLabel: "#4b5563", waterLabel: "#3f6f9c", mask: "#d9dde3",
};

/** Road width in pixels by zoom: thin at country view, wide in the street. */
const width = (z6: number, z18: number) => ["interpolate", ["exponential", 1.6], ["zoom"], 6, z6, 18, z18] as unknown as number;

const ROADS: { cls: string[]; color: keyof Palette; w: [number, number]; minzoom: number }[] = [
  { cls: ["path", "track"], color: "path", w: [0.2, 2], minzoom: 14 },
  { cls: ["service"], color: "service", w: [0.2, 6], minzoom: 13 },
  { cls: ["minor"], color: "minor", w: [0.3, 12], minzoom: 11 },
  { cls: ["tertiary", "secondary"], color: "secondary", w: [0.5, 18], minzoom: 8 },
  { cls: ["primary"], color: "primary", w: [0.7, 22], minzoom: 7 },
  { cls: ["trunk"], color: "trunk", w: [0.8, 24], minzoom: 5 },
  { cls: ["motorway"], color: "motorway", w: [1, 26], minzoom: 4 },
];

/**
 * `missing`: the outlines of the countries whose pack is not on the unit. `offline`: the tiles come from the local
 * packs (MBTiles, zoom 0 to 14, drawn larger beyond) and the label fonts from the app itself, with no network at all.
 */
export function mapStyle(light: boolean, missing: GeoJSON.FeatureCollection, offline: boolean): StyleSpecification {
  const p = light ? LIGHT : DARK;
  const road = (r: (typeof ROADS)[number], casing: boolean) => ({
    id: `road-${r.cls[0]}${casing ? "-casing" : ""}`,
    type: "line" as const,
    source: "omt",
    "source-layer": "transportation",
    minzoom: r.minzoom,
    filter: ["in", ["get", "class"], ["literal", r.cls]] as never,
    layout: { "line-cap": "round" as const, "line-join": "round" as const },
    paint: casing
      ? { "line-color": p.casing, "line-width": width(r.w[0] + 0.6, r.w[1] + 3) }
      : { "line-color": p[r.color], "line-width": width(r.w[0], r.w[1]) },
  });
  return {
    version: 8,
    name: light ? "rdstr-day" : "rdstr-night",
    glyphs: `${location.origin}/maps/fonts/{fontstack}/{range}.pbf`,
    sources: {
      omt: offline
        ? { type: "vector", tiles: [`${location.origin}/api/tiles/{z}/{x}/{y}`], minzoom: 0, maxzoom: 14, attribution: ATTRIBUTION }
        : { type: "vector", url: ONLINE_TILES, attribution: ATTRIBUTION },
      missing: { type: "geojson", data: missing },
    },
    layers: [
      { id: "land", type: "background", paint: { "background-color": p.land } },
      { id: "landcover", type: "fill", source: "omt", "source-layer": "landcover", filter: ["in", ["get", "class"], ["literal", ["wood", "grass", "farmland"]]], paint: { "fill-color": p.landcover } },
      { id: "residential", type: "fill", source: "omt", "source-layer": "landuse", minzoom: 10, filter: ["in", ["get", "class"], ["literal", ["residential", "suburb", "neighbourhood"]]], paint: { "fill-color": p.residential } },
      { id: "park", type: "fill", source: "omt", "source-layer": "park", paint: { "fill-color": p.park } },
      { id: "water", type: "fill", source: "omt", "source-layer": "water", paint: { "fill-color": p.water } },
      { id: "waterway", type: "line", source: "omt", "source-layer": "waterway", minzoom: 9, paint: { "line-color": p.waterway, "line-width": width(0.4, 6) } },
      { id: "boundary-country", type: "line", source: "omt", "source-layer": "boundary", filter: ["all", ["==", ["get", "admin_level"], 2], ["!=", ["get", "maritime"], 1]], paint: { "line-color": p.boundary, "line-width": 1.2, "line-dasharray": [3, 2] } },
      { id: "rail", type: "line", source: "omt", "source-layer": "transportation", minzoom: 12, filter: ["==", ["get", "class"], "rail"], paint: { "line-color": p.rail, "line-width": width(0.3, 3) } },
      // the day map outlines its white roads; at night the roads stand out on their own
      ...(light ? ROADS.filter((r) => r.minzoom <= 11).map((r) => road(r, true)) : []),
      ...ROADS.map((r) => road(r, false)),
      { id: "water-name", type: "symbol", source: "omt", "source-layer": "water_name", layout: { "text-field": ["get", "name"], "text-font": ["Noto Sans Italic"], "text-size": 13 }, paint: { "text-color": p.waterLabel, "text-halo-color": p.halo, "text-halo-width": 1.2 } },
      {
        id: "road-name", type: "symbol", source: "omt", "source-layer": "transportation_name", minzoom: 13,
        layout: { "symbol-placement": "line", "text-field": ["get", "name"], "text-font": ["Noto Sans Regular"], "text-size": ["interpolate", ["linear"], ["zoom"], 13, 11, 18, 15] as never, "text-max-angle": 30 },
        paint: { "text-color": p.roadLabel, "text-halo-color": p.halo, "text-halo-width": 1.4 },
      },
      {
        id: "place-village", type: "symbol", source: "omt", "source-layer": "place", minzoom: 11, filter: ["in", ["get", "class"], ["literal", ["village", "suburb", "hamlet"]]],
        layout: { "text-field": ["get", "name"], "text-font": ["Noto Sans Regular"], "text-size": 13 },
        paint: { "text-color": p.labelMuted, "text-halo-color": p.halo, "text-halo-width": 1.4 },
      },
      {
        id: "place-town", type: "symbol", source: "omt", "source-layer": "place", minzoom: 8, filter: ["==", ["get", "class"], "town"],
        layout: { "text-field": ["get", "name"], "text-font": ["Noto Sans Regular"], "text-size": ["interpolate", ["linear"], ["zoom"], 8, 12, 14, 17] as never },
        paint: { "text-color": p.label, "text-halo-color": p.halo, "text-halo-width": 1.5 },
      },
      {
        id: "place-city", type: "symbol", source: "omt", "source-layer": "place", minzoom: 4, filter: ["==", ["get", "class"], "city"],
        layout: { "text-field": ["get", "name"], "text-font": ["Noto Sans Bold"], "text-size": ["interpolate", ["linear"], ["zoom"], 4, 12, 12, 22] as never },
        paint: { "text-color": p.label, "text-halo-color": p.halo, "text-halo-width": 1.6 },
      },
      // countries whose pack is not on the unit: covered, as the unit has no tiles there
      { id: "missing-pack", type: "fill", source: "missing", paint: { "fill-color": p.mask, "fill-opacity": 0.92 } },
    ],
  } as StyleSpecification;
}

/**
 * Pack sizes, in MB. Romania is measured (built and slimmed); the others are the Geofabrik extract size times the
 * ratio measured on Romania (363 MB pack from a 333 MB extract), until each is built.
 */
export const PACK_MB: Record<string, number> = { RO: 363, HU: 358, BG: 193, RS: 263, UA: 963, MD: 111, AT: 888, SK: 379 };
