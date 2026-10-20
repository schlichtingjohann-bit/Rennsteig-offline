#!/usr/bin/env python3
"""Höhendaten für den Rennsteig.

* lädt Terrarium-Höhenkacheln (Mapzen/AWS Terrain Tiles) für den Korridor
* schreibt sie als MBTiles (raster-dem, Encoding "terrarium") für die Schummerung
* berechnet das Höhenprofil der Route (profile.json) inkl. Auf-/Abstieg
* ergänzt km-Marken und POIs um die Geländehöhe (dem_ele)
"""
import argparse
import io
import json
import math
import os
import sqlite3
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import mercantile
import numpy as np
from PIL import Image
from shapely.geometry import box, shape

TILE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
ATTRIBUTION = ("Höhendaten: Mapzen Terrain Tiles (SRTM, EU-DEM/Copernicus u. a.)")


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def fetch(cache, z, x, y):
    path = os.path.join(cache, str(z), str(x), f"{y}.png")
    if os.path.exists(path) and os.path.getsize(path) > 0:
        with open(path, "rb") as f:
            return f.read()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    url = TILE_URL.format(z=z, x=x, y=y)
    for attempt in range(6):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                data = r.read()
            with open(path + ".tmp", "wb") as f:
                f.write(data)
            os.replace(path + ".tmp", path)
            return data
        except Exception as e:  # noqa: BLE001
            log(f"  Fehler {url}: {e} (Versuch {attempt + 1})")
            time.sleep(2 ** attempt)
    raise RuntimeError(f"Download fehlgeschlagen: {url}")


class Dem:
    """Bilineare Abfrage der Terrarium-Kacheln einer Zoomstufe."""

    def __init__(self, cache, z):
        self.cache, self.z, self.tiles = cache, z, {}

    def _tile(self, x, y):
        key = (x, y)
        if key not in self.tiles:
            img = Image.open(io.BytesIO(fetch(self.cache, self.z, x, y))).convert("RGB")
            a = np.asarray(img, dtype=np.float64)
            self.tiles[key] = a[:, :, 0] * 256 + a[:, :, 1] + a[:, :, 2] / 256 - 32768
        return self.tiles[key]

    def ele(self, lon, lat):
        n = 2 ** self.z
        fx = (lon + 180) / 360 * n
        lr = math.radians(lat)
        fy = (1 - math.log(math.tan(lr) + 1 / math.cos(lr)) / math.pi) / 2 * n
        px, py = fx * 256 - 0.5, fy * 256 - 0.5
        x0, y0 = math.floor(px), math.floor(py)
        dx, dy = px - x0, py - y0

        def v(ix, iy):
            tx, ty = ix // 256, iy // 256
            return self._tile(tx, ty)[iy % 256, ix % 256]

        return ((1 - dx) * (1 - dy) * v(x0, y0) + dx * (1 - dy) * v(x0 + 1, y0)
                + (1 - dx) * dy * v(x0, y0 + 1) + dx * dy * v(x0 + 1, y0 + 1))


def haversine(lon1, lat1, lon2, lat2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def resample(coords, step):
    """Punkte im festen Abstand `step` (Meter) entlang der Linie."""
    out = [tuple(coords[0])]
    since = 0.0  # Abstand vom zuletzt erzeugten Punkt bis zum Segmentanfang
    for a, b in zip(coords, coords[1:]):
        d = haversine(*a, *b)
        pos = step - since
        while pos <= d:
            f = pos / d
            out.append((a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1])))
            pos += step
        since = d - (pos - step)
    if since > step * 0.2:
        out.append(tuple(coords[-1]))
    return out


def climb(ele, threshold=4.0):
    """Auf-/Abstieg mit Hysterese, damit Rauschen nicht aufsummiert wird."""
    up = down = 0.0
    ref = ele[0]
    for e in ele[1:]:
        if e - ref >= threshold:
            up += e - ref
            ref = e
        elif ref - e >= threshold:
            down += ref - e
            ref = e
    return up, down


def write_mbtiles(path, tiles, bounds, minzoom, maxzoom):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    db.executescript("""
        CREATE TABLE metadata (name text, value text);
        CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob);
        CREATE UNIQUE INDEX tile_index on tiles (zoom_level, tile_column, tile_row);
    """)
    meta = {
        "name": "Rennsteig Höhenmodell", "format": "png", "type": "baselayer", "version": "1",
        "description": "Terrarium-kodierte Höhenkacheln", "attribution": ATTRIBUTION,
        "minzoom": str(minzoom), "maxzoom": str(maxzoom),
        "bounds": ",".join(f"{b:.5f}" for b in bounds),
        "center": f"{(bounds[0] + bounds[2]) / 2:.5f},{(bounds[1] + bounds[3]) / 2:.5f},{minzoom + 3}",
        "encoding": "terrarium",
    }
    db.executemany("INSERT INTO metadata VALUES (?, ?)", meta.items())
    db.executemany("INSERT INTO tiles VALUES (?, ?, ?, ?)",
                   [(z, x, (2 ** z - 1 - y), sqlite3.Binary(data)) for (z, x, y), data in tiles])
    db.commit()
    db.execute("VACUUM")
    db.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--route", required=True)
    ap.add_argument("--pois", required=True)
    ap.add_argument("--corridor", required=True, help="corridor.geojson")
    ap.add_argument("--cache", default="build/dem-cache")
    ap.add_argument("--minzoom", type=int, default=5)
    ap.add_argument("--overview-bbox", help="minlon,minlat,maxlon,maxlat: bis z9 das ganze Gebiet abdecken")
    ap.add_argument("--overview-maxzoom", type=int, default=9)
    ap.add_argument("--maxzoom", type=int, default=12)
    ap.add_argument("--profile-zoom", type=int, default=13)
    ap.add_argument("--out-mbtiles", required=True)
    ap.add_argument("--out-profile", required=True)
    args = ap.parse_args()

    with open(args.corridor) as f:
        corridor = shape(json.load(f)["geometry"])
    bounds = corridor.bounds

    # --- Kacheln für die Schummerung
    overview = [float(v) for v in args.overview_bbox.split(",")] if args.overview_bbox else None
    wanted = []
    for z in range(args.minzoom, args.maxzoom + 1):
        area = overview if overview and z <= args.overview_maxzoom else bounds
        for t in mercantile.tiles(*area, zooms=[z]):
            b = mercantile.bounds(t)
            if (overview and z <= args.overview_maxzoom) or corridor.intersects(box(b.west, b.south, b.east, b.north)):
                wanted.append((z, t.x, t.y))
    if overview:
        bounds = (min(bounds[0], overview[0]), min(bounds[1], overview[1]),
                  max(bounds[2], overview[2]), max(bounds[3], overview[3]))
    log(f"{len(wanted)} Höhenkacheln (z{args.minzoom}-{args.maxzoom})")
    with ThreadPoolExecutor(16) as ex:
        datas = list(ex.map(lambda k: fetch(args.cache, *k), wanted))
    write_mbtiles(args.out_mbtiles, list(zip(wanted, datas)), bounds, args.minzoom, args.maxzoom)
    log(f"MBTiles: {os.path.getsize(args.out_mbtiles) / 1e6:.1f} MB")

    # --- Höhenprofil
    with open(args.route, encoding="utf-8") as f:
        route = json.load(f)
    line = next(ft for ft in route["features"] if ft["properties"].get("kind") == "route")
    coords = line["geometry"]["coordinates"]
    dem = Dem(args.cache, args.profile_zoom)
    step = 50.0
    samples = resample(coords, step)
    raw = np.array([dem.ele(lon, lat) for lon, lat in samples])
    # leichte Glättung (Fenster ~250 m), Ränder auffüllen
    k = 5
    padded = np.pad(raw, (k // 2, k // 2), mode="edge")
    smooth = np.convolve(padded, np.ones(k) / k, mode="valid")
    up, down = climb(list(smooth))
    length_km = line["properties"]["length_km"]
    profile = {
        "step_m": step,
        "length_km": length_km,
        "ascent_m": int(round(up)),
        "descent_m": int(round(down)),
        "min_m": int(round(float(smooth.min()))),
        "max_m": int(round(float(smooth.max()))),
        "ele": [int(round(float(e))) for e in smooth],
    }
    with open(args.out_profile, "w") as f:
        json.dump(profile, f, separators=(",", ":"))
    log(f"Profil: {len(samples)} Punkte, ↑{profile['ascent_m']} m ↓{profile['descent_m']} m, "
        f"{profile['min_m']}–{profile['max_m']} m")
    line["properties"].update(ascent_m=profile["ascent_m"], descent_m=profile["descent_m"],
                              min_m=profile["min_m"], max_m=profile["max_m"])
    for ft in route["features"]:
        if ft["geometry"]["type"] == "Point":
            lon, lat = ft["geometry"]["coordinates"]
            ft["properties"]["ele"] = int(round(dem.ele(lon, lat)))
    with open(args.route, "w", encoding="utf-8") as f:
        json.dump(route, f, ensure_ascii=False, separators=(",", ":"))

    # --- POIs mit Geländehöhe
    with open(args.pois, encoding="utf-8") as f:
        pois = json.load(f)
    for ft in pois["features"]:
        lon, lat = ft["geometry"]["coordinates"]
        try:
            ft["properties"]["dem_ele"] = int(round(dem.ele(lon, lat)))
        except Exception as e:  # noqa: BLE001
            log(f"  keine Höhe für {ft['properties'].get('name')}: {e}")
    with open(args.pois, "w", encoding="utf-8") as f:
        json.dump(pois, f, ensure_ascii=False, separators=(",", ":"))
    log("fertig")


if __name__ == "__main__":
    main()
