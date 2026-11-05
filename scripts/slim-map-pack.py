"""Slims a country map pack built by planetiler, keeping only what the unit draws.

    python -I scripts/slim-map-pack.py <in.mbtiles> <out.mbtiles>

- Names: keeps `name` (the local name) and `name_en`; drops the other translations.
- Layers the map neither draws nor searches: mountain peaks, airports. Points of interest stay, for search.
- Zoom 14: drops the landcover layer; zoom 13's landcover is drawn larger there and looks the same.
- Identical tiles (open fields, sea) are stored once, as planetiler does.
Reads and writes plain Mapbox Vector Tiles in gzip; no libraries beyond the standard one.
"""
import gzip
import hashlib
import os
import sqlite3
import sys

KEEP_NAMES = {"name", "name_en"}
DROP_LAYERS = {"mountain_peak", "aeroway", "aerodrome_label"}
DROP_LAYERS_AT = {14: {"landcover"}}


def varint(b, i):
    r = s = 0
    while True:
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        s += 7
        if c < 0x80:
            return r, i


def put_varint(n):
    out = bytearray()
    while True:
        byte = n & 0x7F
        n >>= 7
        if n:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def fields(b):
    """(field number, wire type, value or raw bytes, the field's own bytes) for each field of a message."""
    i = 0
    while i < len(b):
        start = i
        key, i = varint(b, i)
        f, w = key >> 3, key & 7
        if w == 2:
            n, i = varint(b, i)
            yield f, w, b[i:i + n], b[start:i + n]
            i += n
        elif w == 0:
            v, i = varint(b, i)
            yield f, w, v, b[start:i]
        elif w == 5:
            yield f, w, b[i:i + 4], b[start:i + 4]
            i += 4
        else:
            yield f, w, b[i:i + 8], b[start:i + 8]
            i += 8


def field(n, payload):
    return put_varint(n << 3 | 2) + put_varint(len(payload)) + payload


def packed(data):
    out, i = [], 0
    while i < len(data):
        v, i = varint(data, i)
        out.append(v)
    return out


def slim_layer(layer):
    """The layer without the dropped name keys; keys and values renumbered, unused values removed."""
    parts = list(fields(layer))
    keys = [v.decode() for f, _, v, _ in parts if f == 3]
    values = [raw for f, _, _, raw in parts if f == 4]
    keep_key = [not (k.startswith("name") and k not in KEEP_NAMES) for k in keys]
    if all(keep_key):
        return layer
    key_map, new_keys = {}, []
    for i, k in enumerate(keys):
        if keep_key[i]:
            key_map[i] = len(new_keys)
            new_keys.append(k)
    features, used_values = [], {}
    for f, _, v, raw in parts:
        if f != 2:
            continue
        out, tags = bytearray(), None
        for ff, _, fv, fraw in fields(v):
            if ff == 2:
                tags = packed(fv)
            else:
                out += fraw
        if tags is not None:
            kept = []
            for k, val in zip(tags[0::2], tags[1::2]):
                if k in key_map:
                    if val not in used_values:
                        used_values[val] = len(used_values)
                    kept += [key_map[k], used_values[val]]
            if kept:
                out += field(2, b"".join(put_varint(t) for t in kept))
        features.append(bytes(out))
    body = bytearray()
    for f, _, v, raw in parts:
        if f in (2, 3, 4):
            continue
        body += raw
    for feat in features:
        body += field(2, feat)
    for k in new_keys:
        body += field(3, k.encode())
    for old in sorted(used_values, key=used_values.get):
        raw = values[old]
        body += field(4, raw[_header_len(raw):])
    return bytes(body)


def _header_len(raw):
    """Length of a length-delimited field's key and length prefix."""
    _, i = varint(raw, 0)
    _, i = varint(raw, i)
    return i


def slim_tile(data, zoom):
    tile = gzip.decompress(data)
    drop = DROP_LAYERS | DROP_LAYERS_AT.get(zoom, set())
    out = bytearray()
    for f, _, v, raw in fields(tile):
        if f != 3:
            out += raw
            continue
        name = next(fv for ff, _, fv, _ in fields(v) if ff == 1).decode()
        if name in drop:
            continue
        out += field(3, slim_layer(v))
    return gzip.compress(bytes(out), 9, mtime=0)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    if os.path.exists(dst):
        os.remove(dst)
    a, b = sqlite3.connect(src), sqlite3.connect(dst)
    b.executescript("""
        CREATE TABLE metadata (name TEXT, value TEXT);
        CREATE TABLE tiles_data (tile_data_id INTEGER PRIMARY KEY, tile_data BLOB);
        CREATE TABLE tiles_shallow (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data_id INTEGER,
            PRIMARY KEY (zoom_level, tile_column, tile_row)) WITHOUT ROWID;
        CREATE VIEW tiles AS SELECT s.zoom_level, s.tile_column, s.tile_row, d.tile_data
            FROM tiles_shallow s JOIN tiles_data d ON s.tile_data_id = d.tile_data_id;
    """)
    b.executemany("INSERT INTO metadata VALUES (?, ?)", a.execute("SELECT name, value FROM metadata"))
    seen, before, after = {}, 0, 0
    for z, x, y, data in a.execute("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles"):
        before += len(data)
        slim = slim_tile(data, z)
        key = hashlib.sha1(slim).digest()
        if key not in seen:
            seen[key] = len(seen) + 1
            b.execute("INSERT INTO tiles_data VALUES (?, ?)", (seen[key], slim))
            after += len(slim)
        b.execute("INSERT INTO tiles_shallow VALUES (?, ?, ?, ?)", (z, x, y, seen[key]))
    b.commit()
    b.execute("VACUUM")
    b.close()
    print("tiles %.0f MB -> %.0f MB, file %.0f MB" % (before / 1e6, after / 1e6, os.path.getsize(dst) / 1e6))


if __name__ == "__main__":
    main()
