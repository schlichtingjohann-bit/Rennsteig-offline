#!/usr/bin/env python3
"""Höhenlinien für den Rennsteig-Korridor als Vektorkacheln (MBTiles, Layer "contour").

Grundlage sind die Terrarium-Höhenkacheln (z12, ca. 24 m Raster), die zu einem Mosaik
zusammengesetzt, leicht geglättet und mit skimage in Linien umgewandelt werden.
"""
import argparse
import io
import json
import math
import os
import sqlite3
import sys
from concurrent.futures import ThreadPoolExecutor

import mapbox_vector_tile
import mercantile
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter
import shapely
from shapely.geometry import LineString, MultiLineString, box, shape
from skimage.measure import find_contours

sys.path.insert(0, os.path.dirname(__file__))
from build_dem import fetch  # noqa: E402

EXTENT = 4096
WORLD = 2 * math.pi * 6378137.0


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def lonlat_to_merc(c):
    """Nx2-Array (lon, lat) -> Web-Mercator-Meter."""
    x = np.radians(c[:, 0]) * 6378137.0
    y = np.log(np.tan(np.pi / 4 + np.radians(c[:, 1]) / 2)) * 6378137.0
    return np.column_stack((x, y))


def mosaic(cache, z, bounds):
    tiles = list(mercantile.tiles(*bounds, zooms=[z]))
    xs = [t.x for t in tiles]
    ys = [t.y for t in tiles]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    w, h = (x1 - x0 + 1) * 256, (y1 - y0 + 1) * 256
    log(f"Mosaik z{z}: {x1 - x0 + 1}×{y1 - y0 + 1} Kacheln ({w}×{h} px)")
    arr = np.zeros((h, w), dtype=np.float32)

    def load(t):
        img = Image.open(io.BytesIO(fetch(cache, z, t.x, t.y))).convert("RGB")
        a = np.asarray(img, dtype=np.float32)
        return t, a[:, :, 0] * 256 + a[:, :, 1] + a[:, :, 2] / 256 - 32768

    with ThreadPoolExecutor(16) as ex:
        for t, e in ex.map(load, tiles):
            arr[(t.y - y0) * 256:(t.y - y0 + 1) * 256, (t.x - x0) * 256:(t.x - x0 + 1) * 256] = e
    # Pixel -> Web-Mercator-Meter
    n = 2 ** z
    size = WORLD / n / 256

    def px_to_merc(row, col):
        mx = (x0 * 256 + col + 0.5) * size - WORLD / 2
        my = WORLD / 2 - (y0 * 256 + row + 0.5) * size
        return mx, my

    return arr, px_to_merc


def build_lines(arr, px_to_merc, interval, smooth_px):
    dem = gaussian_filter(arr, smooth_px) if smooth_px > 0 else arr
    lo = math.ceil(float(dem.min()) / interval) * interval
    hi = math.floor(float(dem.max()) / interval) * interval
    out = []
    for level in np.arange(lo, hi + 1, interval):
        for c in find_contours(dem, level):
            if len(c) < 6:
                continue
            pts = [px_to_merc(r, col) for r, col in c]
            ln = LineString(pts)
            if ln.length < 150:
                continue
            out.append((int(level), ln))
    return out


def merc_box(t):
    b = mercantile.xy_bounds(t)
    return b.left, b.bottom, b.right, b.top


def encode_tile(t, lines, simplify_m):
    left, bottom, right, top = merc_box(t)
    buf = (right - left) * 16 / EXTENT
    clip = box(left - buf, bottom - buf, right + buf, top + buf)
    sx = EXTENT / (right - left)
    sy = EXTENT / (top - bottom)
    feats = []
    for ele, idx, ln in lines:
        if not ln.intersects(clip):
            continue
        g = ln.intersection(clip)
        if g.is_empty:
            continue
        g = g.simplify(simplify_m)
        g = shapely.transform(g, lambda c: np.column_stack(((c[:, 0] - left) * sx, (top - c[:, 1]) * sy)))
        if g.is_empty or g.geom_type not in ("LineString", "MultiLineString"):
            if g.geom_type == "GeometryCollection":
                parts = [p for p in g.geoms if p.geom_type == "LineString"]
                if not parts:
                    continue
                g = MultiLineString(parts)
            else:
                continue
        feats.append({"geometry": g, "properties": {"ele": ele, "idx": idx}})
    if not feats:
        return None
    return mapbox_vector_tile.encode(
        [{"name": "contour", "features": feats}],
        default_options={"extents": EXTENT, "y_coord_down": True, "quantize_bounds": None},
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corridor", required=True)
    ap.add_argument("--cache", default="build/dem-cache")
    ap.add_argument("--dem-zoom", type=int, default=12)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    with open(args.corridor) as f:
        corridor_ll = shape(json.load(f)["geometry"])
    corridor = shapely.transform(corridor_ll, lonlat_to_merc)

    arr, px_to_merc = mosaic(args.cache, args.dem_zoom, corridor_ll.bounds)
    # Linien je Detailstufe: Äquidistanz, Glättung
    sets = {}
    for interval in (100, 50, 20):
        raw = build_lines(arr, px_to_merc, interval, smooth_px=1.2)
        clipped = []
        for ele, ln in raw:
            if not ln.intersects(corridor):
                continue
            g = ln.intersection(corridor)
            parts = [g] if g.geom_type == "LineString" else [p for p in getattr(g, "geoms", []) if p.geom_type == "LineString"]
            for p in parts:
                if p.length >= 100:
                    clipped.append((ele, 1 if ele % 100 == 0 else 0, p))
        sets[interval] = clipped
        log(f"Äquidistanz {interval} m: {len(clipped)} Linien")

    plan = {11: (100, 25.0), 12: (50, 12.0), 13: (20, 6.0), 14: (20, 3.0)}
    if os.path.exists(args.out):
        os.remove(args.out)
    db = sqlite3.connect(args.out)
    db.executescript("""
        CREATE TABLE metadata (name text, value text);
        CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob);
        CREATE UNIQUE INDEX tile_index on tiles (zoom_level, tile_column, tile_row);
    """)
    total = 0
    count = 0
    for z, (interval, simp) in plan.items():
        lines = sets[interval]
        tiles = [t for t in mercantile.tiles(*corridor_ll.bounds, zooms=[z])
                 if corridor.intersects(box(*merc_box(t)))]
        for t in tiles:
            data = encode_tile(t, lines, simp)
            if data:
                db.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)", (z, t.x, 2 ** z - 1 - t.y, sqlite3.Binary(data)))
                total += len(data)
                count += 1
        log(f"z{z}: {len(tiles)} Kacheln geprüft")
    b = corridor_ll.bounds
    meta = {
        "name": "Rennsteig Höhenlinien", "format": "pbf", "type": "overlay", "version": "1",
        "minzoom": "11", "maxzoom": "14", "bounds": ",".join(f"{v:.5f}" for v in b),
        "center": f"{(b[0] + b[2]) / 2:.5f},{(b[1] + b[3]) / 2:.5f},12",
        "attribution": "Höhenlinien aus Mapzen Terrain Tiles",
        "json": json.dumps({"vector_layers": [{"id": "contour", "minzoom": 11, "maxzoom": 14,
                                                "fields": {"ele": "Number", "idx": "Number"}}]}),
    }
    db.executemany("INSERT INTO metadata VALUES (?, ?)", meta.items())
    db.commit()
    db.execute("VACUUM")
    db.close()
    log(f"{count} Kacheln, {total / 1e6:.1f} MB -> {args.out}")


if __name__ == "__main__":
    main()
