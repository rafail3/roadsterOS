"""Sea polygons for a map pack from Natural Earth, in place of water-polygons-split-3857.zip.

planetiler's OpenMapTiles profile takes its sea outlines from osmdata.openstreetmap.de. When that host is down, this
writes the same file from the Natural Earth 10 m ocean already in the sources folder, cut to one area: less precise
along the coast than the OSM outlines, the same everywhere else.

    python -I scripts/water-from-natural-earth.py <natural_earth_vector.sqlite.zip> <sources dir> <west> <south> <east> <north>

Writes <sources dir>/water-polygons-split-3857.zip (a Web Mercator shapefile). No libraries beyond the standard one.
"""
import io
import math
import os
import sqlite3
import struct
import sys
import tempfile
import zipfile

R = 6378137.0
# the outline is kept this much beyond the cut, so the land inside never touches it and every polygon stays valid
MARGIN_DEG = 0.5


def mercator(lon, lat):
    lat = max(-85.05112878, min(85.05112878, lat))
    return R * math.radians(lon), R * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


def read_multipolygon(wkb):
    """Rings of every polygon in a little- or big-endian WKB Polygon or MultiPolygon."""
    pos = 0

    def take(fmt):
        nonlocal pos
        value = struct.unpack_from(fmt, wkb, pos)
        pos += struct.calcsize(fmt)
        return value

    def polygon(order):
        (rings,) = take(order + "I")
        out = []
        for _ in range(rings):
            (n,) = take(order + "I")
            pts = take(order + "%dd" % (2 * n))
            out.append(list(zip(pts[0::2], pts[1::2])))
        return out

    order = "<" if take("B")[0] == 1 else ">"
    (kind,) = take(order + "I")
    if kind == 3:
        return [polygon(order)]
    (count,) = take(order + "I")
    polys = []
    for _ in range(count):
        o = "<" if take("B")[0] == 1 else ">"
        take(o + "I")
        polys.append(polygon(o))
    return polys


def clip(ring, west, south, east, north):
    """Sutherland-Hodgman against the cut rectangle."""
    edges = [
        (lambda p: p[0] >= west, lambda a, b: (west, a[1] + (b[1] - a[1]) * (west - a[0]) / (b[0] - a[0]))),
        (lambda p: p[0] <= east, lambda a, b: (east, a[1] + (b[1] - a[1]) * (east - a[0]) / (b[0] - a[0]))),
        (lambda p: p[1] >= south, lambda a, b: (a[0] + (b[0] - a[0]) * (south - a[1]) / (b[1] - a[1]), south)),
        (lambda p: p[1] <= north, lambda a, b: (a[0] + (b[0] - a[0]) * (north - a[1]) / (b[1] - a[1]), north)),
    ]
    for inside, cross in edges:
        if not ring:
            break
        src, ring = ring, []
        prev = src[-1]
        for cur in src:
            if inside(cur):
                if not inside(prev):
                    ring.append(cross(prev, cur))
                ring.append(cur)
            elif inside(prev):
                ring.append(cross(prev, cur))
            prev = cur
    return ring


def area(ring):
    return sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(ring, ring[1:] + ring[:1])) / 2


def oriented(ring, clockwise):
    """Shapefile order: outer rings clockwise, holes counter-clockwise; the ring closed."""
    if (area(ring) < 0) != clockwise:
        ring = ring[::-1]
    return ring + ring[:1] if ring[0] != ring[-1] else ring


def write_shapefile(polygons, folder):
    """Polygon shapefile (.shp .shx .dbf .prj), one record per polygon given as [outer, *holes]."""
    records = []
    for rings in polygons:
        parts, points = [], []
        for ring in rings:
            parts.append(len(points))
            points.extend(ring)
        xs, ys = [p[0] for p in points], [p[1] for p in points]
        body = struct.pack("<i4dii", 5, min(xs), min(ys), max(xs), max(ys), len(parts), len(points))
        body += struct.pack("<%di" % len(parts), *parts) + b"".join(struct.pack("<2d", *p) for p in points)
        records.append(body)
    allx = [x for r in polygons for ring in r for x, _ in ring]
    ally = [y for r in polygons for ring in r for _, y in ring]
    bbox = (min(allx), min(ally), max(allx), max(ally))

    def header(length_words):
        return struct.pack(">7i", 9994, 0, 0, 0, 0, 0, length_words) + struct.pack("<2i4d4d", 1000, 5, *bbox, 0, 0, 0, 0)

    shp, shx, offset = io.BytesIO(), io.BytesIO(), 50
    for i, body in enumerate(records):
        shp.write(struct.pack(">2i", i + 1, len(body) // 2) + body)
        shx.write(struct.pack(">2i", offset, len(body) // 2))
        offset += 4 + len(body) // 2
    with open(os.path.join(folder, "water_polygons.shp"), "wb") as f:
        f.write(header(offset) + shp.getvalue())
    with open(os.path.join(folder, "water_polygons.shx"), "wb") as f:
        f.write(header(50 + 4 * len(records)) + shx.getvalue())
    # one numeric field, as every dBASE table needs at least one
    with open(os.path.join(folder, "water_polygons.dbf"), "wb") as f:
        f.write(struct.pack("<BBBBIHH20x", 3, 126, 1, 1, len(records), 32 + 32 + 1, 1 + 10))
        f.write(b"FID".ljust(11, b"\0") + b"N" + b"\0" * 4 + bytes([10, 0]) + b"\0" * 14 + b"\r")
        for i in range(len(records)):
            f.write(b" " + str(i).rjust(10).encode())
        f.write(b"\x1a")
    with open(os.path.join(folder, "water_polygons.prj"), "w") as f:
        f.write('PROJCS["WGS 84 / Pseudo-Mercator",GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],'
                'PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]],PROJECTION["Mercator_1SP"],PARAMETER["central_meridian",0],'
                'PARAMETER["scale_factor",1],PARAMETER["false_easting",0],PARAMETER["false_northing",0],UNIT["metre",1],'
                'EXTENSION["PROJ4","+proj=merc +a=6378137 +b=6378137 +lat_ts=0 +lon_0=0 +x_0=0 +y_0=0 +k=1 +units=m +nadgrids=@null +no_defs"]]')


def main():
    ne_zip, sources = sys.argv[1], sys.argv[2]
    west, south, east, north = map(float, sys.argv[3:7])
    with tempfile.TemporaryDirectory() as tmp:
        db_path = os.path.join(tmp, "ne.sqlite")
        with zipfile.ZipFile(ne_zip) as z, z.open("packages/natural_earth_vector.sqlite") as src, open(db_path, "wb") as dst:
            while chunk := src.read(1 << 20):
                dst.write(chunk)
        db = sqlite3.connect(db_path)
        blobs = [row[0] for row in db.execute("SELECT GEOMETRY FROM ne_10m_ocean")]
        db.close()

        outer = [(west - MARGIN_DEG, south - MARGIN_DEG), (east + MARGIN_DEG, south - MARGIN_DEG),
                 (east + MARGIN_DEG, north + MARGIN_DEG), (west - MARGIN_DEG, north + MARGIN_DEG)]
        polygons = []
        for blob in blobs:
            for rings in read_multipolygon(blob):
                shell = clip(rings[0], west, south, east, north)
                if len(shell) < 3 or abs(area(shell)) < 1e-9:
                    continue
                # the ocean's holes are the land; cut to the area, they lie inside the slightly larger outline
                holes = [h for h in (clip(r, west, south, east, north) for r in rings[1:]) if len(h) >= 3 and abs(area(h)) > 1e-9]
                ring_out = outer if abs(area(shell)) > 0.99 * (east - west) * (north - south) else shell
                polygons.append([oriented([mercator(*p) for p in ring_out], True)] + [oriented([mercator(*p) for p in h], False) for h in holes])

        folder = os.path.join(tmp, "water-polygons-split-3857")
        os.makedirs(folder)
        write_shapefile(polygons, folder)
        target = os.path.join(sources, "water-polygons-split-3857.zip")
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
            for name in sorted(os.listdir(folder)):
                z.write(os.path.join(folder, name), "water-polygons-split-3857/" + name)
    print("%s: %d polygons, %d KB" % (target, len(polygons), os.path.getsize(target) // 1024))


if __name__ == "__main__":
    main()
