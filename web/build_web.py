#!/usr/bin/env python3
"""Baut die Web-App (PWA) für iPhone & Co. aus denselben Daten wie die Android-App.

    python3 web/build_web.py --data build/out --out build/web

Ergebnis ist ein statischer Ordner, der auf jedem HTTPS-Webspace (z. B. GitHub Pages)
liegen kann. Die großen Kartendateien werden als PMTiles abgelegt und von der App einmalig
in den Offline-Speicher des Geräts geladen.
"""
import argparse
import gzip
import hashlib
import json
import os
import shutil
import sqlite3
import sys

from pmtiles.convert import mbtiles_to_header_json
from pmtiles.tile import zxy_to_tileid, tileid_to_zxy
from pmtiles.writer import write

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(ROOT, "web")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

SHELL_FILES = ["index.html", "app.css", "app.js", "manifest.webmanifest",
               "vendor/maplibre-gl.js", "vendor/maplibre-gl.css", "vendor/pmtiles.js",
               "icons/icon-180.png", "icons/icon-192.png", "icons/icon-512.png",
               "style.json", "data/route.geojson", "data/pois.geojson", "data/profile.json"]


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def mbtiles_to_pmtiles(src, dst):
    """Wie pmtiles.convert.mbtiles_to_pmtiles, aber mit ausgewerteten vector_layers."""
    conn = sqlite3.connect(src)
    meta = dict(conn.execute("SELECT name, value FROM metadata").fetchall())
    is_pbf = meta.get("format") == "pbf"
    ids = []
    for z, x, y in conn.execute("SELECT zoom_level, tile_column, tile_row FROM tiles"):
        ids.append(zxy_to_tileid(z, x, (1 << z) - 1 - y))
    ids.sort()
    with write(dst) as writer:
        for tid in ids:
            z, x, y = tileid_to_zxy(tid)
            data = conn.execute(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                (z, x, (1 << z) - 1 - y)).fetchone()[0]
            if is_pbf and data[:2] != b"\x1f\x8b":
                data = gzip.compress(data, mtime=0)
            writer.write_tile(tid, data)
        header, _ = mbtiles_to_header_json(meta)
        out_meta = {k: v for k, v in meta.items() if k != "json"}
        if "json" in meta:
            out_meta.update(json.loads(meta["json"]))
        writer.finalize(header, out_meta)
    conn.close()
    log(f"  {os.path.basename(dst)}: {len(ids)} Kacheln, {os.path.getsize(dst) / 1e6:.1f} MB")


def web_style():
    """Kartenstil der Android-App, Quellen auf PMTiles umgestellt (Pfade setzt app.js)."""
    with open(os.path.join(ASSETS, "style", "rennsteig.json"), encoding="utf-8") as f:
        style = json.load(f)
    style["metadata"] = {"rennsteig:comment": "Erzeugt von web/build_web.py aus dem Android-Stil"}
    style["glyphs"] = "{BASE}fonts/{fontstack}/{range}.pbf"
    s = style["sources"]
    s["omt"] = {"type": "vector", "url": "pmtiles://rennsteig.pmtiles",
                "attribution": s["omt"].get("attribution", "")}
    s["dem"] = {"type": "raster-dem", "url": "pmtiles://dem.pmtiles", "encoding": "terrarium",
                "tileSize": 256, "attribution": s["dem"].get("attribution", "")}
    s["contours"] = {"type": "vector", "url": "pmtiles://contours.pmtiles"}
    return style


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, help="Ordner mit rennsteig/dem/contours.mbtiles")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    out = args.out
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(out)

    # App-Hülle
    for name in ["index.html", "app.css", "app.js", "sw.js", "manifest.webmanifest"]:
        shutil.copy(os.path.join(WEB, name), os.path.join(out, name))
    for d in ["vendor", "icons"]:
        shutil.copytree(os.path.join(WEB, d), os.path.join(out, d))
    shutil.copytree(os.path.join(ASSETS, "fonts"), os.path.join(out, "fonts"))
    shutil.copytree(os.path.join(ASSETS, "data"), os.path.join(out, "data"),
                    ignore=shutil.ignore_patterns(".gitkeep"))
    with open(os.path.join(out, "style.json"), "w", encoding="utf-8") as f:
        json.dump(web_style(), f, ensure_ascii=False, separators=(",", ":"))

    # Große Kartendateien
    log("== PMTiles")
    os.makedirs(os.path.join(out, "maps"))
    big = []
    for name in ["rennsteig", "dem", "contours"]:
        src = os.path.join(args.data, f"{name}.mbtiles")
        if not os.path.exists(src):
            log(f"  fehlt: {src}")
            continue
        dst = os.path.join(out, "maps", f"{name}.pmtiles")
        mbtiles_to_pmtiles(src, dst)
        big.append({"name": f"{name}.pmtiles", "url": f"maps/{name}.pmtiles",
                    "size": os.path.getsize(dst), "hash": sha(dst)})

    # Liste für den Service Worker (App-Hülle) und die Offline-Daten
    fonts = []
    for root, _, files in os.walk(os.path.join(out, "fonts")):
        for fn in files:
            rel = os.path.relpath(os.path.join(root, fn), out).replace(os.sep, "/")
            fonts.append(rel)
    shell = ["./"] + SHELL_FILES + sorted(fonts)
    for rel in SHELL_FILES:
        if not os.path.exists(os.path.join(out, rel)):
            sys.exit(f"Datei fehlt in der Web-App: {rel}")
    data_version = hashlib.sha256("".join(b["hash"] for b in big).encode()).hexdigest()[:12]
    # neue Kartendaten ändern auch die Version, damit der Service Worker das Update erkennt
    shell_hash = hashlib.sha256(data_version.encode())
    for rel in SHELL_FILES + ["sw.js"]:
        with open(os.path.join(out, rel), "rb") as f:
            shell_hash.update(f.read())
    manifest = {"version": shell_hash.hexdigest()[:12], "dataVersion": data_version,
                "shell": shell, "maps": big}
    with open(os.path.join(out, "offline.json"), "w") as f:
        json.dump(manifest, f, indent=1)
    # Version in den Service Worker schreiben, damit Updates erkannt werden
    sw = os.path.join(out, "sw.js")
    with open(sw, encoding="utf-8") as f:
        code = f.read().replace("__VERSION__", manifest["version"])
    with open(sw, "w", encoding="utf-8") as f:
        f.write(code)
    total = sum(b["size"] for b in big)
    log(f"Web-App in {out}: Version {manifest['version']}, Kartendaten {total / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
