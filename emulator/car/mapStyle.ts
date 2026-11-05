// The map's look in the emulator: the shared style file (core-map, also read by the unit's renderer) turned into a
// MapLibre style, so both draw from the same colours, layers and widths.

import type { ExpressionSpecification, LayerSpecification, StyleSpecification } from "maplibre-gl";
import shared from "../../core-map/src/main/resources/ro/rdstr/map/style.json";

/** Without a local pack the emulator previews the same schema from the network; the unit never does. */
const ONLINE_TILES = "https://tiles.openfreemap.org/planet";
const ATTRIBUTION = "© OpenMapTiles © OpenStreetMap contributors";
const FONTS: Record<string, string> = { regular: "Noto Sans Regular", bold: "Noto Sans Bold", italic: "Noto Sans Italic" };

type Scalar = number | { base: number; stops: number[][] };
interface SharedLayer {
  id: string; type: "background" | "fill" | "line" | "text"; source?: string; minzoom?: number; day?: boolean; night?: boolean;
  classes?: string[]; where?: Record<string, string | number>; not?: Record<string, string | number>;
  color: string; width?: Scalar; dash?: number[]; placement?: "line"; font?: string; size?: Scalar; halo?: number;
}

const zoomed = (v: Scalar | undefined) =>
  v === undefined || typeof v === "number" ? v : (["interpolate", v.base === 1 ? ["linear"] : ["exponential", v.base], ["zoom"], ...v.stops.flat()] as unknown as number);

function filter(l: SharedLayer): ExpressionSpecification | undefined {
  const all: unknown[] = [];
  if (l.classes) all.push(l.classes.length === 1 ? ["==", ["get", "class"], l.classes[0]] : ["in", ["get", "class"], ["literal", l.classes]]);
  for (const [k, v] of Object.entries(l.where ?? {})) all.push(["==", ["get", k], v]);
  for (const [k, v] of Object.entries(l.not ?? {})) all.push(["!=", ["get", k], v]);
  if (!all.length) return undefined;
  return (all.length === 1 ? all[0] : ["all", ...all]) as ExpressionSpecification;
}

function layer(l: SharedLayer, p: Record<string, string>): LayerSpecification {
  const base = { id: l.id, ...(l.minzoom !== undefined && { minzoom: l.minzoom }) };
  const src = { source: "omt", "source-layer": l.source!, ...(filter(l) && { filter: filter(l) }) };
  if (l.type === "background") return { ...base, type: "background", paint: { "background-color": p[l.color] } };
  if (l.type === "fill") return { ...base, ...src, type: "fill", paint: { "fill-color": p[l.color] } } as LayerSpecification;
  if (l.type === "line") {
    return {
      ...base, ...src, type: "line",
      layout: { "line-cap": "round", "line-join": "round" },
      paint: { "line-color": p[l.color], "line-width": zoomed(l.width) ?? 1, ...(l.dash && { "line-dasharray": l.dash }) },
    } as LayerSpecification;
  }
  return {
    ...base, ...src, type: "symbol",
    layout: {
      "text-field": ["get", "name"], "text-font": [FONTS[l.font ?? "regular"]], "text-size": zoomed(l.size) ?? 13,
      ...(l.placement === "line" && { "symbol-placement": "line", "text-max-angle": 30 }),
    },
    paint: { "text-color": p[l.color], "text-halo-color": p.halo, "text-halo-width": l.halo ?? 1 },
  } as LayerSpecification;
}

/**
 * `missing`: the outlines of the countries whose pack is not on the unit. `offline`: the tiles come from the local
 * packs (MBTiles, zoom 0 to 14, drawn larger beyond) and the label fonts from the app itself, with no network at all.
 */
export function mapStyle(light: boolean, missing: GeoJSON.FeatureCollection, offline: boolean): StyleSpecification {
  const p = (light ? shared.palettes.day : shared.palettes.night) as Record<string, string>;
  const layers = (shared.layers as SharedLayer[]).filter((l) => !(light ? l.night : l.day)).map((l) => layer(l, p));
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
      ...layers,
      // countries whose pack is not on the unit: covered, as the unit has no tiles there
      { id: "missing-pack", type: "fill", source: "missing", paint: { "fill-color": p.mask, "fill-opacity": 0.92 } },
    ],
  };
}

/**
 * Pack sizes, in MB. Romania is measured (built and slimmed); the others are the Geofabrik extract size times the
 * ratio measured on Romania (363 MB pack from a 333 MB extract), until each is built.
 */
export const PACK_MB: Record<string, number> = { RO: 363, HU: 358, BG: 193, RS: 263, UA: 963, MD: 111, AT: 888, SK: 379 };
