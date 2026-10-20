#!/usr/bin/env python3
"""Rennsteig-Route und POIs aus einem OSM-Extrakt (PBF) gewinnen.

Erzeugt
  route.geojson   Hauptroute Hörschel -> Blankenstein (LineString) + km-Marken + Start/Ziel
  pois.geojson    Unterkünfte, Gastronomie, Sehenswürdigkeiten, Aussichtspunkte,
                  Schutzhütten, Trinkwasser und Bahnhöfe im Korridor um die Route
  corridor.poly   Puffer um die Route (für planetiler --polygon)
  corridor.geojson
"""
import argparse
import heapq
import json
import math
import re
import sys
from collections import defaultdict
from datetime import datetime, timezone

import osmium
from pyproj import Transformer
from shapely.geometry import LineString, Point, Polygon, mapping

TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:25832", always_xy=True)
TO_WGS = Transformer.from_crs("EPSG:25832", "EPSG:4326", always_xy=True)

SKIP_ROLES = {"alternative", "alternate", "excursion", "approach", "connection",
              "link", "variant", "detour", "spur"}
BAD_NAME_WORDS = ("zubringer", "zuweg", "radweg", "radwander", "mtb", "bike", "ski",
                  "loipe", "lauf", "rundweg", "variante", "anbindung", "verbindung",
                  "schleife", "erlebnis", "pfad")
GOOD_NAMES = {"rennsteig", "rennsteig-wanderweg", "rennsteigwanderweg",
              "rennsteig (wanderweg)", "der rennsteig"}


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def haversine(lon1, lat1, lon2, lat2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


# --------------------------------------------------------------------------- route

def scan_route_relations(path):
    rels = {}
    for r in osmium.FileProcessor(path, osmium.osm.RELATION):
        typ = r.tags.get("type")
        if typ not in ("route", "superroute"):
            continue
        rels[r.id] = {
            "tags": {t.k: t.v for t in r.tags},
            "members": [(m.type, m.ref, m.role) for m in r.members],
        }
    return rels


def norm(s):
    return re.sub(r"\s+", " ", (s or "").strip().lower())


def pick_candidates(rels, forced_ids):
    if forced_ids:
        return [i for i in forced_ids if i in rels]
    named = {i: r for i, r in rels.items() if "rennsteig" in norm(r["tags"].get("name"))}
    log(f"Relationen mit 'Rennsteig' im Namen: {len(named)}")
    for i, r in sorted(named.items()):
        t = r["tags"]
        log(f"  r{i}: name={t.get('name')!r} type={t.get('type')} route={t.get('route')} "
            f"network={t.get('network')} ref={t.get('ref')} members={len(r['members'])}")
    hiking = ("hiking", "foot", "walking", None)
    exact = [i for i, r in named.items()
             if norm(r["tags"].get("name")) in GOOD_NAMES and r["tags"].get("route") in hiking]
    if exact:
        return exact
    loose = [i for i, r in named.items()
             if r["tags"].get("route") in hiking
             and norm(r["tags"].get("name")).startswith("rennsteig")
             and not any(w in norm(r["tags"].get("name")) for w in BAD_NAME_WORDS)]
    return loose


def expand_ways(rid, rels, seen=None):
    seen = seen if seen is not None else set()
    if rid in seen or rid not in rels:
        return []
    seen.add(rid)
    out = []
    for typ, ref, role in rels[rid]["members"]:
        if norm(role) in SKIP_ROLES:
            continue
        if typ == "w":
            out.append(ref)
        elif typ == "r":
            out.extend(expand_ways(ref, rels, seen))
    return out


def load_ways(path, way_ids):
    ways = {}
    wanted = set(way_ids)
    fp = (osmium.FileProcessor(path, osmium.osm.NODE | osmium.osm.WAY)
          .with_locations()
          .with_filter(osmium.filter.EntityFilter(osmium.osm.WAY))
          .with_filter(osmium.filter.IdFilter(wanted)))
    for o in fp:
        if o.is_way() and o.id in wanted:
            pts = []
            for n in o.nodes:
                if n.location.valid():
                    pts.append((n.ref, n.location.lon, n.location.lat))
            if len(pts) >= 2:
                ways[o.id] = pts
    return ways


def build_graph(ways):
    graph = defaultdict(dict)
    coords = {}
    for nodes in ways.values():
        for (a, lon1, lat1), (b, lon2, lat2) in zip(nodes, nodes[1:]):
            if a == b:
                continue
            d = haversine(lon1, lat1, lon2, lat2)
            coords[a] = (lon1, lat1)
            coords[b] = (lon2, lat2)
            if b not in graph[a] or graph[a][b] > d:
                graph[a][b] = d
                graph[b][a] = d
    return graph, coords


def components(graph):
    comp = {}
    cid = 0
    for start in graph:
        if start in comp:
            continue
        stack = [start]
        comp[start] = cid
        while stack:
            n = stack.pop()
            for m in graph[n]:
                if m not in comp:
                    comp[m] = cid
                    stack.append(m)
        cid += 1
    return comp


def bridge_gaps(graph, coords, max_gap):
    """Verbindet Lücken zwischen Teilstücken (Endpunkte verschiedener Komponenten)."""
    comp = components(graph)
    leaves = [n for n in graph if len(graph[n]) <= 1]
    # alle Knoten als Ziel zulassen, Endpunkte als Quelle (Lücken entstehen an Wegenden)
    added = 0
    for a in leaves:
        lon1, lat1 = coords[a]
        best = None
        for b, (lon2, lat2) in coords.items():
            if comp[b] == comp[a]:
                continue
            if abs(lat2 - lat1) > max_gap / 111000 or abs(lon2 - lon1) > max_gap / 70000:
                continue
            d = haversine(lon1, lat1, lon2, lat2)
            if d <= max_gap and (best is None or d < best[0]):
                best = (d, b)
        if best:
            d, b = best
            graph[a][b] = graph[b][a] = d * 1.5 + 5
            added += 1
            log(f"  Lücke überbrückt: {d:.0f} m bei {coords[a][1]:.5f},{coords[a][0]:.5f}")
    return added


def dijkstra(graph, src, dst):
    dist = {src: 0.0}
    prev = {}
    pq = [(0.0, src)]
    while pq:
        d, n = heapq.heappop(pq)
        if n == dst:
            break
        if d > dist.get(n, math.inf):
            continue
        for m, w in graph[n].items():
            nd = d + w
            if nd < dist.get(m, math.inf):
                dist[m] = nd
                prev[m] = n
                heapq.heappush(pq, (nd, m))
    if dst not in dist:
        return None
    path = [dst]
    while path[-1] != src:
        path.append(prev[path[-1]])
    return list(reversed(path))


def route_from_ways(ways):
    graph, coords = build_graph(ways)
    if not graph:
        return None
    leaves = [n for n in graph if len(graph[n]) == 1] or list(graph)
    start = min(leaves, key=lambda n: coords[n][0])   # westlichster Endpunkt (Hörschel)
    end = max(leaves, key=lambda n: coords[n][0])     # östlichster Endpunkt (Blankenstein)
    for gap in (0, 150, 400, 1000, 3000):
        if gap:
            bridge_gaps(graph, coords, gap)
        path = dijkstra(graph, start, end)
        if path:
            break
    if not path:
        return None
    return [coords[n] for n in path]


def line_length_m(pts):
    return sum(haversine(*a, *b) for a, b in zip(pts, pts[1:]))


def cumulative(pts):
    cum = [0.0]
    for a, b in zip(pts, pts[1:]):
        cum.append(cum[-1] + haversine(*a, *b))
    return cum


def point_at(pts, cum, dist):
    lo, hi = 0, len(cum) - 1
    while lo < hi - 1:
        mid = (lo + hi) // 2
        if cum[mid] <= dist:
            lo = mid
        else:
            hi = mid
    seg = cum[hi] - cum[lo]
    f = 0 if seg == 0 else (dist - cum[lo]) / seg
    return (pts[lo][0] + f * (pts[hi][0] - pts[lo][0]),
            pts[lo][1] + f * (pts[hi][1] - pts[lo][1]))


# --------------------------------------------------------------------------- POIs

LODGING = {"hotel": 3.0, "guest_house": 2.6, "alpine_hut": 3.0, "hostel": 2.2, "motel": 1.5,
           "chalet": 1.4, "apartment": 1.2, "camp_site": 2.0, "wilderness_hut": 1.6}
FOOD = {"restaurant": 2.5, "biergarten": 2.4, "cafe": 2.0, "pub": 1.6, "fast_food": 0.8,
        "ice_cream": 0.8}
SIGHT = {"castle": 3.0, "museum": 2.6, "attraction": 2.2, "ruins": 2.0, "monument": 1.6,
         "archaeological_site": 1.5, "boundary_stone": 1.2, "memorial": 0.6, "waterfall": 1.8,
         "cave": 1.8, "rock": 1.0, "spring": 0.8, "mine": 1.5, "church": 1.0}
VIEW = {"tower": 3.2, "viewpoint": 2.2, "peak": 1.4}

MAX_OFF = {"lodging": 3000, "food": 2000, "sight": 3000, "view": 2000, "shelter": 400,
           "water": 600, "station": 4000}
TOP_PER_SECTION = {"lodging": 2, "food": 2, "sight": 2, "view": 2}
TOP_MIN_SCORE = {"lodging": 4.5, "food": 4.5, "sight": 4.0, "view": 4.0}
SECTION_KM = 15.0

KEEP_TAGS = ["ele", "stars", "cuisine", "opening_hours", "phone", "contact:phone", "website",
             "contact:website", "url", "email", "contact:email", "addr:street",
             "addr:housenumber", "addr:postcode", "addr:city", "addr:place", "wikipedia",
             "wikidata", "operator", "capacity", "beds", "rooms", "fee", "wheelchair",
             "outdoor_seating", "internet_access", "shelter_type", "heritage", "description",
             "description:de", "historic", "tourism", "amenity", "natural", "tower:type",
             "height", "inscription", "start_date", "drinking_water", "toilets", "fireplace",
             "bench", "check_date", "diet:vegetarian", "diet:vegan", "takeaway", "reservation",
             "smoking", "dog", "lunch", "breakfast"]


def classify(t):
    name = t.get("name")
    tourism, amenity = t.get("tourism"), t.get("amenity")
    historic, natural = t.get("historic"), t.get("natural")
    if tourism in LODGING:
        if tourism in ("apartment", "chalet") and not name:
            return None
        return "lodging", tourism
    if amenity in FOOD:
        return ("food", amenity) if name else None
    if t.get("man_made") == "tower" and t.get("tower:type") == "observation":
        return "view", "tower"
    if tourism == "viewpoint":
        return "view", "viewpoint"
    if natural == "peak" and name:
        return "view", "peak"
    if tourism == "museum" and name:
        return "sight", "museum"
    if historic in ("castle", "ruins", "monument", "memorial", "boundary_stone",
                    "archaeological_site", "mine", "church") and name:
        return "sight", historic
    if tourism == "attraction" and name:
        return "sight", "attraction"
    if t.get("waterway") == "waterfall":
        return "sight", "waterfall"
    if natural == "cave_entrance" and name:
        return "sight", "cave"
    if natural in ("rock", "stone") and name:
        return "sight", "rock"
    if natural == "spring" and name:
        return "sight", "spring"
    if amenity == "shelter" and t.get("shelter_type") not in ("public_transport",):
        if t.get("bus") == "yes" or t.get("public_transport"):
            return None
        return "shelter", t.get("shelter_type", "shelter")
    if amenity in ("drinking_water", "water_point") or (
            t.get("man_made") == "water_tap" and t.get("drinking_water") == "yes"):
        return "water", "drinking_water"
    if t.get("railway") in ("station", "halt") and name and t.get("station") not in ("subway",):
        if t.get("usage") == "tourism" or t.get("disused") == "yes":
            return None
        return "station", t.get("railway")
    return None


class PoiHandler(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.items = []

    def _add(self, tags, oid, lon, lat):
        c = classify(tags)
        if not c:
            return
        t = {tg.k: tg.v for tg in tags}
        self.items.append({"id": oid, "cat": c[0], "kind": c[1], "lon": lon, "lat": lat,
                           "tags": t})

    def node(self, n):
        if len(n.tags) == 0 or not n.location.valid():
            return
        self._add(n.tags, f"n{n.id}", n.location.lon, n.location.lat)

    def area(self, a):
        if len(a.tags) == 0:
            return
        if classify(a.tags) is None:
            return
        best = None
        for ring in a.outer_rings():
            pts = [(nd.lon, nd.lat) for nd in ring if nd.location.valid()]
            if len(pts) >= 4 and (best is None or len(pts) > len(best)):
                best = pts
        if not best:
            return
        poly = Polygon(best)
        c = poly.representative_point() if not poly.is_valid else poly.centroid
        if not poly.contains(c):
            c = poly.representative_point()
        oid = f"w{a.orig_id()}" if a.from_way() else f"r{a.orig_id()}"
        self._add(a.tags, oid, c.x, c.y)


def tag_float(v):
    if not v:
        return None
    m = re.search(r"-?\d+(?:[.,]\d+)?", v)
    return float(m.group(0).replace(",", ".")) if m else None


def score(p):
    t, cat, kind, off = p["tags"], p["cat"], p["kind"], p["off"]
    s = {"lodging": LODGING, "food": FOOD, "sight": SIGHT, "view": VIEW}.get(cat, {}).get(kind, 1.0)
    has = lambda *ks: any(t.get(k) for k in ks)
    if has("wikidata", "wikipedia"):
        s += 2.5 if cat in ("sight", "view") else 1.2
    if has("website", "contact:website", "url"):
        s += 1.0
    if has("phone", "contact:phone"):
        s += 0.5
    if has("opening_hours"):
        s += 0.7
    if has("description", "description:de"):
        s += 0.3
    if cat == "lodging":
        stars = tag_float(t.get("stars"))
        if stars:
            s += min(stars, 5) * 0.4
    if cat == "food":
        if has("cuisine"):
            s += 0.4
        if t.get("outdoor_seating") == "yes":
            s += 0.4
    if cat == "sight" and has("heritage"):
        s += 0.6
    if cat == "view":
        ele = tag_float(t.get("ele"))
        if kind == "peak" and ele:
            s += max(0.0, (ele - 600) / 150)
        if kind == "tower" and has("name"):
            s += 0.8
    if not t.get("name"):
        s -= 1.0
    # Nähe zum Weg
    if off <= 150:
        s += 2.5
    elif off <= 500:
        s += 1.8
    elif off <= 1000:
        s += 1.0
    elif off <= 2000:
        s += 0.4
    return round(s, 2)


def dedupe(items):
    """Gleiches Objekt doppelt gemappt (Knoten + Gebäudefläche) -> das mit mehr Tags behalten."""
    out = []
    by_key = defaultdict(list)
    for p in sorted(items, key=lambda p: -len(p["tags"])):
        key = (p["cat"], norm(p["tags"].get("name")))
        dup = False
        if key[1]:
            for q in by_key[key]:
                if haversine(p["lon"], p["lat"], q["lon"], q["lat"]) < 150:
                    dup = True
                    break
        if not dup:
            by_key[key].append(p)
            out.append(p)
    return out


def process_pois(items, route_utm, cum_utm, cum_m, highlights):
    total_utm = route_utm.length
    hl = [dict(h, rx=re.compile(h["match"], re.I)) for h in highlights]
    kept = []
    for p in items:
        x, y = TO_UTM.transform(p["lon"], p["lat"])
        pt = Point(x, y)
        off = route_utm.distance(pt)
        along_utm = route_utm.project(pt)
        # UTM-Abstand entlang der Linie -> geodätische km
        p["km"] = round(interp(along_utm, cum_utm, cum_m) / 1000.0, 2)
        p["off"] = int(round(off))
        name = p["tags"].get("name", "")
        p["highlight"] = None
        for h in hl:
            if p["cat"] in h["cats"] and name and h["rx"].search(name) and off <= h["max_off"]:
                p["highlight"] = h["desc"]
                break
        if off > MAX_OFF[p["cat"]] and not p["highlight"]:
            continue
        kept.append(p)
    kept = dedupe(kept)
    for p in kept:
        p["score"] = score(p)
        p["top"] = bool(p["highlight"])
    # Empfehlungen je Abschnitt
    total_km = cum_m[-1] / 1000.0
    nsec = max(1, int(math.ceil(total_km / SECTION_KM)))
    for cat, n in TOP_PER_SECTION.items():
        for s in range(nsec):
            lo, hi = s * SECTION_KM, (s + 1) * SECTION_KM
            cands = sorted((p for p in kept if p["cat"] == cat and lo <= p["km"] < hi
                            and p["score"] >= TOP_MIN_SCORE[cat] and p["tags"].get("name")),
                           key=lambda p: -p["score"])
            for p in cands[:n]:
                p["top"] = True
    return kept


def interp(x, xs, ys):
    if x <= xs[0]:
        return ys[0]
    if x >= xs[-1]:
        return ys[-1]
    lo, hi = 0, len(xs) - 1
    while lo < hi - 1:
        mid = (lo + hi) // 2
        if xs[mid] <= x:
            lo = mid
        else:
            hi = mid
    f = (x - xs[lo]) / (xs[hi] - xs[lo]) if xs[hi] != xs[lo] else 0
    return ys[lo] + f * (ys[hi] - ys[lo])


def poi_feature(p):
    t = p["tags"]
    props = {"id": p["id"], "cat": p["cat"], "kind": p["kind"],
             "name": t.get("name", ""), "km": p["km"], "off": p["off"],
             "score": p["score"], "top": p["top"]}
    if p["highlight"]:
        props["desc"] = p["highlight"]
    for k in KEEP_TAGS:
        if t.get(k):
            props["osm:" + k] = t[k]
    return {"type": "Feature", "id": p["id"],
            "geometry": {"type": "Point", "coordinates": [round(p["lon"], 6), round(p["lat"], 6)]},
            "properties": props}


def write_gpx(path, name, pts):
    from xml.sax.saxutils import escape
    with open(path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<gpx version="1.1" creator="Rennsteig Offline" xmlns="http://www.topografix.com/GPX/1/1">\n')
        f.write(f"<metadata><name>{escape(name)}</name>"
                "<copyright author=\"OpenStreetMap-Mitwirkende\"><license>https://opendatacommons.org/licenses/odbl/</license></copyright>"
                "</metadata>\n")
        f.write(f"<trk><name>{escape(name)} (Hörschel – Blankenstein)</name><trkseg>\n")
        for lon, lat in pts:
            f.write(f'<trkpt lat="{lat:.6f}" lon="{lon:.6f}"/>\n')
        f.write("</trkseg></trk>\n</gpx>\n")


# --------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--region", required=True, help="OSM-PBF mit Routenrelationen und Wegen")
    ap.add_argument("--pois", required=True, help="vorgefiltertes OSM-PBF mit POI-Objekten")
    ap.add_argument("--highlights", required=True)
    ap.add_argument("--relation", type=int, action="append", default=[],
                    help="Relation-ID(s) erzwingen")
    ap.add_argument("--buffer-km", type=float, default=10.0)
    ap.add_argument("--out-route", required=True)
    ap.add_argument("--out-pois", required=True)
    ap.add_argument("--out-corridor-poly", required=True)
    ap.add_argument("--out-corridor-geojson", required=True)
    ap.add_argument("--out-gpx", help="Route zusätzlich als GPX-Track")
    args = ap.parse_args()

    log("== Routenrelationen lesen")
    rels = scan_route_relations(args.region)
    cands = pick_candidates(rels, args.relation)
    if not cands:
        sys.exit("Keine Rennsteig-Relation gefunden")
    log(f"Kandidaten: {cands}")
    cand_ways = {c: expand_ways(c, rels) for c in cands}
    all_ways = set(w for ws in cand_ways.values() for w in ws)
    log(f"== {len(all_ways)} Wege laden")
    ways = load_ways(args.region, all_ways)

    best = None
    for c, ws in cand_ways.items():
        pts = route_from_ways({w: ways[w] for w in ws if w in ways})
        if not pts:
            log(f"  r{c}: keine zusammenhängende Route")
            continue
        length = line_length_m(pts)
        log(f"  r{c}: {len(pts)} Punkte, {length / 1000:.1f} km, "
            f"Start {pts[0][1]:.5f},{pts[0][0]:.5f} Ziel {pts[-1][1]:.5f},{pts[-1][0]:.5f}")
        if best is None or length > best[1]:
            best = (c, length, pts)
    if not best:
        sys.exit("Route konnte nicht zusammengesetzt werden")
    rid, length, pts = best
    rtags = rels[rid]["tags"]
    log(f"== Gewählt: r{rid} {rtags.get('name')!r}, {length / 1000:.2f} km")

    cum_m = cumulative(pts)
    utm_pts = [TO_UTM.transform(lon, lat) for lon, lat in pts]
    route_utm = LineString(utm_pts)
    cum_utm = [0.0]
    for a, b in zip(utm_pts, utm_pts[1:]):
        cum_utm.append(cum_utm[-1] + math.dist(a, b))

    feats = [{
        "type": "Feature",
        "geometry": {"type": "LineString",
                     "coordinates": [[round(lon, 6), round(lat, 6)] for lon, lat in pts]},
        "properties": {"kind": "route", "name": rtags.get("name", "Rennsteig"),
                       "length_km": round(length / 1000, 2), "osm_relation": rid,
                       "generated": datetime.now(timezone.utc).strftime("%Y-%m-%d")},
    }]
    total_km = int(length // 1000)
    for k in range(1, total_km + 1):
        lon, lat = point_at(pts, cum_m, k * 1000.0)
        feats.append({"type": "Feature",
                      "geometry": {"type": "Point", "coordinates": [round(lon, 6), round(lat, 6)]},
                      "properties": {"kind": "km", "km": k,
                                     "rank": 10 if k % 10 == 0 else (5 if k % 5 == 0 else 1)}})
    feats.append({"type": "Feature",
                  "geometry": {"type": "Point", "coordinates": [round(pts[0][0], 6), round(pts[0][1], 6)]},
                  "properties": {"kind": "start", "name": "Start Hörschel", "km": 0}})
    feats.append({"type": "Feature",
                  "geometry": {"type": "Point", "coordinates": [round(pts[-1][0], 6), round(pts[-1][1], 6)]},
                  "properties": {"kind": "end", "name": "Ziel Blankenstein",
                                 "km": round(length / 1000, 2)}})
    with open(args.out_route, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": feats}, f, ensure_ascii=False,
                  separators=(",", ":"))

    if args.out_gpx:
        write_gpx(args.out_gpx, rtags.get("name", "Rennsteig"), pts)

    # Korridor
    corridor = route_utm.buffer(args.buffer_km * 1000, quad_segs=8).simplify(300)
    corridor_wgs = Polygon([TO_WGS.transform(x, y) for x, y in corridor.exterior.coords])
    with open(args.out_corridor_poly, "w") as f:
        f.write("rennsteig_corridor\n1\n")
        for lon, lat in corridor_wgs.exterior.coords:
            f.write(f"   {lon:.6f}   {lat:.6f}\n")
        f.write("END\nEND\n")
    with open(args.out_corridor_geojson, "w") as f:
        json.dump({"type": "Feature", "properties": {}, "geometry": mapping(corridor_wgs)}, f)
    log(f"Korridor-BBox: {corridor_wgs.bounds}")

    log("== POIs lesen")
    h = PoiHandler()
    h.apply_file(args.pois, locations=True)
    log(f"  {len(h.items)} POI-Kandidaten im Extrakt")
    with open(args.highlights, encoding="utf-8") as f:
        highlights = json.load(f)["highlights"]
    pois = process_pois(h.items, route_utm, cum_utm, cum_m, highlights)
    pois.sort(key=lambda p: (p["km"], p["cat"]))
    stats = defaultdict(lambda: [0, 0])
    for p in pois:
        stats[p["cat"]][0] += 1
        stats[p["cat"]][1] += p["top"]
    for c, (n, t) in sorted(stats.items()):
        log(f"  {c:8s} {n:4d} (Empfehlungen: {t})")
    for p in pois:
        if p["top"]:
            log(f"  TOP km {p['km']:6.1f} {p['cat']:7s} {p['kind']:14s} {p['score']:5.1f} "
                f"{p['off']:5d} m  {p['tags'].get('name', '')}" + ("  [Highlight]" if p["highlight"] else ""))
    with open(args.out_pois, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": [poi_feature(p) for p in pois]}, f,
                  ensure_ascii=False, separators=(",", ":"))
    log("fertig")


if __name__ == "__main__":
    main()
