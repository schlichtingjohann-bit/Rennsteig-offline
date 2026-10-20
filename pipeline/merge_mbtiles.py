#!/usr/bin/env python3
"""Fügt die Übersichtskacheln (ganzes Gebiet, niedrige Zoomstufen) und die Detailkacheln
(Korridor um den Rennsteig, hohe Zoomstufen) zu einer MBTiles-Datei zusammen."""
import argparse
import json
import os
import sqlite3


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--overview", required=True)
    ap.add_argument("--detail", required=True)
    ap.add_argument("--split-zoom", type=int, required=True, help="erste Zoomstufe aus den Detailkacheln")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    if os.path.exists(args.out):
        os.remove(args.out)
    out = sqlite3.connect(args.out)
    out.executescript("""
        CREATE TABLE metadata (name text, value text);
        CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob);
        CREATE UNIQUE INDEX tile_index on tiles (zoom_level, tile_column, tile_row);
    """)
    counts = {}
    for path, cond in ((args.overview, f"zoom_level < {args.split_zoom}"),
                       (args.detail, f"zoom_level >= {args.split_zoom}")):
        src = sqlite3.connect(path)
        rows = src.execute(f"SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles WHERE {cond}")
        n = 0
        for row in rows:
            out.execute("INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)", row)
            n += 1
        counts[os.path.basename(path)] = n
        src.close()

    meta = dict(sqlite3.connect(args.detail).execute("SELECT name, value FROM metadata").fetchall())
    overview_meta = dict(sqlite3.connect(args.overview).execute("SELECT name, value FROM metadata").fetchall())
    meta["minzoom"] = overview_meta.get("minzoom", "0")
    meta["bounds"] = overview_meta.get("bounds", meta.get("bounds", ""))
    if "json" in meta:
        j = json.loads(meta["json"])
        for layer in j.get("vector_layers", []):
            layer["minzoom"] = 0
        meta["json"] = json.dumps(j)
    out.executemany("INSERT INTO metadata VALUES (?, ?)", meta.items())
    out.commit()
    out.execute("VACUUM")
    out.close()
    print(f"Kacheln: {counts} -> {args.out} ({os.path.getsize(args.out) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
