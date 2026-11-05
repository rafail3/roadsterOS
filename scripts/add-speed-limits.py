"""Adds the speed limits to a slimmed country pack, as the layer `maxspeed` of its zoom 14 tiles.

    python -I scripts/add-speed-limits.py <pack.mbtiles> <limits.mbtiles>
    python -I scripts/add-speed-limits.py --test

<limits.mbtiles> is planetiler's output for speed-limits.yml: road lines with their raw OpenStreetMap tags. Each
line keeps its geometry and gets `fwd` and `bwd`, the limit in km/h along and against the way's direction; lines
without a usable limit are dropped. Running it again on the same pack replaces the layer.
"""
import gzip
import os
import sqlite3
import sys

exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "slim-map-pack.py"), encoding="utf-8").read().split("def main")[0])

LAYER = "maxspeed"
# the implicit limits written as a country code and a road type; Romania's: 50 in towns, 90 outside,
# 100 on expressways and European national roads, 130 on motorways
IMPLICIT = {"RO:urban": 50, "RO:rural": 90, "RO:trunk": 100, "RO:motorway": 130}


def kmh(value):
    """A maxspeed tag value in km/h, or None when it is not a single posted limit ("none", "signals", "walk")."""
    if not value:
        return None
    v = value.split(";")[0].strip()
    if v in IMPLICIT:
        return IMPLICIT[v]
    mph = "mph" in v
    digits = v.replace("mph", "").replace("km/h", "").replace("kmh", "").strip()
    try:
        n = float(digits)
    except ValueError:
        return None
    if mph:
        n *= 1.609344
    return round(n) if 5 <= n <= 200 else None


def values_of(parts):
    out = []
    for f, _, v, _ in parts:
        if f != 4:
            continue
        val = None
        for _, w, fv, _ in fields(v):
            val = fv.decode() if w == 2 else fv
        out.append(val)
    return out


def limits_layer(layer):
    """The planetiler layer rewritten: geometry as it is, tags `fwd` and `bwd` in km/h; None when no line is left."""
    parts = list(fields(layer))
    keys = [v.decode() for f, _, v, _ in parts if f == 3]
    values = values_of(parts)
    features, used = [], {}
    for f, _, v, _ in parts:
        if f != 2:
            continue
        tags, rest = {}, bytearray()
        for ff, _, fv, fraw in fields(v):
            if ff == 2:
                t = packed(fv)
                tags = {keys[a]: values[b] for a, b in zip(t[0::2], t[1::2])}
            elif ff != 1:
                rest += fraw
        both = kmh(tags.get("maxspeed"))
        fwd, bwd = kmh(tags.get("forward")) or both, kmh(tags.get("backward")) or both
        if fwd is None and bwd is None:
            continue
        kept = []
        for key, n in ((0, fwd), (1, bwd)):
            if n is not None:
                used.setdefault(n, len(used))
                kept += [key, used[n]]
        features.append(bytes(rest) + field(2, b"".join(put_varint(t) for t in kept)))
    if not features:
        return None
    extent = next((raw for f, _, _, raw in parts if f == 5), b"")
    body = field(1, LAYER.encode())
    for feat in features:
        body += field(2, feat)
    body += field(3, b"fwd") + field(3, b"bwd")
    for n in sorted(used, key=used.get):
        body += field(4, put_varint(5 << 3 | 0) + put_varint(n))
    # version 2, as every layer of the pack
    return bytes(body) + extent + put_varint(15 << 3 | 0) + put_varint(2)


def without_layer(tile, name):
    out = bytearray()
    for f, _, v, raw in fields(tile):
        if f == 3 and next(fv for ff, _, fv, _ in fields(v) if ff == 1).decode() == name:
            continue
        out += raw
    return bytes(out)


def main():
    pack, limits = sys.argv[1], sys.argv[2]
    a, b = sqlite3.connect(limits), sqlite3.connect(pack)
    next_id = (b.execute("SELECT MAX(tile_data_id) FROM tiles_data").fetchone()[0] or 0) + 1
    added, tiles = 0, 0
    for z, x, y, data in a.execute("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles WHERE zoom_level = 14"):
        layer = next((limits_layer(v) for f, _, v, _ in fields(gzip.decompress(data)) if f == 3), None)
        if layer is None:
            continue
        row = b.execute("SELECT d.tile_data FROM tiles_shallow s JOIN tiles_data d ON s.tile_data_id = d.tile_data_id "
                        "WHERE s.zoom_level = ? AND s.tile_column = ? AND s.tile_row = ?", (z, x, y)).fetchone()
        tile = without_layer(gzip.decompress(row[0]), LAYER) if row else b""
        merged = gzip.compress(tile + field(3, layer), 9, mtime=0)
        b.execute("INSERT INTO tiles_data VALUES (?, ?)", (next_id, merged))
        b.execute("INSERT OR REPLACE INTO tiles_shallow VALUES (?, ?, ?, ?)", (z, x, y, next_id))
        next_id += 1
        tiles += 1
        added += len(layer)
    # tiles no coordinate points at any more (the ones replaced) go
    b.execute("DELETE FROM tiles_data WHERE tile_data_id NOT IN (SELECT tile_data_id FROM tiles_shallow)")
    b.commit()
    b.execute("VACUUM")
    b.close()
    print("speed limits: %d tiles, %.1f MB of lines, pack %.0f MB" % (tiles, added / 1e6, os.path.getsize(pack) / 1e6))


def test():
    assert kmh("50") == 50 and kmh("50 mph") == 80 and kmh("90 km/h") == 90
    assert kmh("RO:urban") == 50 and kmh("RO:rural") == 90 and kmh("RO:trunk") == 100 and kmh("RO:motorway") == 130
    assert kmh("none") is None and kmh("signals") is None and kmh("walk") is None and kmh(None) is None
    assert kmh("30;50") == 30 and kmh("1000") is None
    # one line with a limit each way, one without any, rewritten
    geometry = field(4, b"".join(put_varint(n) for n in (9, 0, 0, 10, 8, 8)))
    feature = lambda tags: field(2, b"".join(put_varint(t) for t in tags)) + put_varint(3 << 3 | 0) + put_varint(2) + geometry
    src = (field(1, b"maxspeed") + field(2, feature([0, 0])) + field(2, feature([1, 1, 2, 2])) + field(2, feature([]))
           + field(3, b"maxspeed") + field(3, b"forward") + field(3, b"backward")
           + field(4, field(1, b"RO:urban")) + field(4, field(1, b"70")) + field(4, field(1, b"none"))
           + put_varint(5 << 3 | 0) + put_varint(4096))
    out = list(fields(limits_layer(src)))
    keys = [v.decode() for f, _, v, _ in out if f == 3]
    vals = values_of(out)
    feats = [{keys[a]: vals[b] for a, b in zip(*[iter(packed(next(fv for ff, _, fv, _ in fields(v) if ff == 2)))] * 2)}
             for f, _, v, _ in out if f == 2]
    assert feats == [{"fwd": 50, "bwd": 50}, {"fwd": 70}], feats
    print("ok")


if __name__ == "__main__":
    test() if sys.argv[1:] == ["--test"] else main()
