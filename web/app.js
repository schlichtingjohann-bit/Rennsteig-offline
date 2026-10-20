/* Rennsteig Offline – Web-App (iPhone, iPad, Android-Browser, Desktop).
 * Gleiche Daten und Bedienung wie die Android-App; Kartendaten liegen nach dem ersten
 * Download im Offline-Speicher (Cache Storage) und werden als PMTiles gelesen. */
"use strict";
(() => {
  const DATA_CACHE_PREFIX = "rennsteig-data-";
  const HEADING_TOP_PAD = 0.32;
  const GEOID_HEIGHT = 47; // GPS-Höhe (Ellipsoid) liegt in Thüringen ca. 47 m über NHN
  const METERS_PER_PIXEL_Z0 = 78271.517;
  const EMPTY = { type: "FeatureCollection", features: [] };
  const STALE_MS = 120000; // iOS meldet im Stehen nur selten neue Positionen

  const CATS = [
    { key: "lodging", label: "Unterkünfte", color: "#3949AB" },
    { key: "food", label: "Essen & Trinken", color: "#E8650A" },
    { key: "sight", label: "Sehenswürdigkeiten", color: "#8E24AA" },
    { key: "view", label: "Aussicht & Gipfel", color: "#2E7D32" },
    { key: "shelter", label: "Schutzhütten", color: "#795548" },
    { key: "water", label: "Trinkwasser", color: "#0288D1" },
    { key: "station", label: "Bahnhöfe", color: "#546E7A" },
  ];
  const CAT = Object.fromEntries(CATS.map((c) => [c.key, c]));
  const KIND_LABELS = {
    hotel: "Hotel", guest_house: "Pension / Gasthof", alpine_hut: "Berghütte / Baude", hostel: "Herberge",
    motel: "Motel", chalet: "Ferienhaus", apartment: "Ferienwohnung", camp_site: "Zeltplatz",
    wilderness_hut: "Wanderhütte", restaurant: "Gaststätte", biergarten: "Biergarten", cafe: "Café",
    pub: "Kneipe", fast_food: "Imbiss", ice_cream: "Eiscafé", castle: "Burg / Schloss", museum: "Museum",
    attraction: "Sehenswürdigkeit", ruins: "Ruine", monument: "Denkmal", archaeological_site: "Bodendenkmal",
    boundary_stone: "Grenzstein", memorial: "Gedenkstätte", waterfall: "Wasserfall", cave: "Höhle",
    rock: "Felsen", spring: "Quelle", mine: "Bergwerk", church: "Kirche", tower: "Aussichtsturm",
    viewpoint: "Aussichtspunkt", peak: "Gipfel", weather_shelter: "Schutzhütte", basic_hut: "Hütte",
    lean_to: "Unterstand", picnic_shelter: "Rasthütte", rock_shelter: "Felsdach", drinking_water: "Trinkwasser",
    station: "Bahnhof", halt: "Haltepunkt", start: "Start des Rennsteigs", end: "Ziel des Rennsteigs",
  };
  const START_DESC = "Hier an der Werra in Hörschel beginnt der Rennsteig. Alter Brauch: Nimm einen Kiesel aus " +
    "der Werra mit und wirf ihn am Ziel in Blankenstein in die Selbitz. Der Gruß der Rennsteigwanderer lautet „Gut Runst!“";
  const END_DESC = "Ziel in Blankenstein an der Selbitz. Hier wird der in Hörschel aufgelesene Werra-Kiesel " +
    "in die Selbitz geworfen – Glückwunsch zur Rennsteig-Wanderung!";

  const $ = (id) => document.getElementById(id);
  const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  const isStandalone = window.navigator.standalone === true || matchMedia("(display-mode: standalone)").matches;
  const dpr = Math.min(window.devicePixelRatio || 1, 3);

  // ------------------------------------------------------------------ Einstellungen

  const prefs = (() => {
    const defaults = { cats: CATS.map((c) => c.key), onlyTop: false, headingUp: true, reverse: false, keepAwake: false };
    try {
      return Object.assign(defaults, JSON.parse(localStorage.getItem("rennsteig-prefs") || "{}"));
    } catch (e) {
      return defaults;
    }
  })();
  function savePrefs() {
    try { localStorage.setItem("rennsteig-prefs", JSON.stringify(prefs)); } catch (e) { /* privat */ }
  }

  // ------------------------------------------------------------------ Geo

  const toRad = (d) => (d * Math.PI) / 180;
  const toDeg = (r) => (r * 180) / Math.PI;
  const norm360 = (d) => ((d % 360) + 360) % 360;
  function distance(lat1, lon1, lat2, lon2) {
    const p1 = toRad(lat1), p2 = toRad(lat2), dp = p2 - p1, dl = toRad(lon2 - lon1);
    const h = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
    return 2 * 6371008.8 * Math.asin(Math.sqrt(Math.min(1, h)));
  }
  function bearing(lat1, lon1, lat2, lon2) {
    const p1 = toRad(lat1), p2 = toRad(lat2), dl = toRad(lon2 - lon1);
    const y = Math.sin(dl) * Math.cos(p2);
    const x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
    return norm360(toDeg(Math.atan2(y, x)));
  }
  function angleDiff(a, b) {
    let d = (b - a) % 360;
    if (d > 180) d -= 360;
    if (d < -180) d += 360;
    return d;
  }
  const cardinal = (deg) => ["N", "NO", "O", "SO", "S", "SW", "W", "NW"][Math.floor((norm360(deg) + 22.5) / 45) % 8];
  const fmtKm = (km) => km.toLocaleString("de-DE", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  function fmtDist(m) {
    if (m < 20) return `${Math.round(m)} m`;
    if (m < 950) return `${Math.round(m / 10) * 10} m`;
    if (m < 9950) return `${fmtKm(m / 1000)} km`;
    return `${Math.round(m / 1000)} km`;
  }
  /** Missweisung am Rennsteig (WMM 2025: 4,2° bei Hörschel bis 4,6° bei Blankenstein, +0,15°/Jahr). */
  function declination(lon) {
    const year = new Date().getFullYear() + new Date().getMonth() / 12;
    return 4.4 + (lon - 10.9) * 0.22 + (year - 2026.8) * 0.15;
  }

  class Route {
    constructor(coords) {
      this.lons = coords.map((c) => c[0]);
      this.lats = coords.map((c) => c[1]);
      this.cum = [0];
      for (let i = 1; i < coords.length; i++) {
        this.cum.push(this.cum[i - 1] + distance(this.lats[i - 1], this.lons[i - 1], this.lats[i], this.lons[i]));
      }
      this.lengthM = this.cum[this.cum.length - 1];
    }
    locate(lat, lon) {
      const kx = 111320 * Math.cos(toRad(lat)), ky = 110574;
      let best = Infinity, along = 0, bl = this.lats[0], bo = this.lons[0];
      for (let i = 0; i < this.lats.length - 1; i++) {
        const ax = (this.lons[i] - lon) * kx, ay = (this.lats[i] - lat) * ky;
        const dx = (this.lons[i + 1] - lon) * kx - ax, dy = (this.lats[i + 1] - lat) * ky - ay;
        const len2 = dx * dx + dy * dy;
        const t = len2 > 0 ? Math.min(1, Math.max(0, -(ax * dx + ay * dy) / len2)) : 0;
        const px = ax + t * dx, py = ay + t * dy, d2 = px * px + py * py;
        if (d2 < best) {
          best = d2;
          along = this.cum[i] + t * (this.cum[i + 1] - this.cum[i]);
          bl = this.lats[i] + t * (this.lats[i + 1] - this.lats[i]);
          bo = this.lons[i] + t * (this.lons[i + 1] - this.lons[i]);
        }
      }
      return { km: along / 1000, offM: Math.sqrt(best), lat: bl, lon: bo };
    }
    pointAt(km) {
      const d = Math.min(this.lengthM, Math.max(0, km * 1000));
      let lo = 0, hi = this.cum.length - 1;
      while (lo < hi - 1) {
        const mid = (lo + hi) >> 1;
        if (this.cum[mid] <= d) lo = mid; else hi = mid;
      }
      const seg = this.cum[hi] - this.cum[lo], f = seg > 0 ? (d - this.cum[lo]) / seg : 0;
      return [this.lats[lo] + f * (this.lats[hi] - this.lats[lo]), this.lons[lo] + f * (this.lons[hi] - this.lons[lo])];
    }
    bounds() {
      return [Math.min(...this.lons), Math.min(...this.lats), Math.max(...this.lons), Math.max(...this.lats)];
    }
  }

  class Profile {
    constructor(p) { Object.assign(this, p); }
    eleAt(km) {
      const e = this.ele;
      const x = Math.min(e.length - 1, Math.max(0, (km * 1000) / this.step_m));
      const i = Math.floor(x), j = Math.min(i + 1, e.length - 1), f = x - i;
      return e[i] * (1 - f) + e[j] * f;
    }
  }

  // ------------------------------------------------------------------ Symbole (wie in der Android-App)

  function canvas(w, h) {
    const c = document.createElement("canvas");
    c.width = Math.round(w * dpr);
    c.height = Math.round(h * dpr);
    const g = c.getContext("2d");
    g.scale(dpr, dpr);
    return [c, g];
  }

  function drawGlyph(g, cat, cx, cy, r, catColor) {
    g.fillStyle = "#fff";
    g.strokeStyle = "#fff";
    g.lineWidth = r * 0.22;
    g.lineCap = "round";
    g.lineJoin = "round";
    const x = (f) => cx + f * r, y = (f) => cy + f * r;
    const poly = (pts) => { g.beginPath(); pts.forEach(([a, b], i) => (i ? g.lineTo(x(a), y(b)) : g.moveTo(x(a), y(b)))); g.closePath(); g.fill(); };
    const rect = (a, b, c, d) => g.fillRect(x(a), y(b), x(c) - x(a), y(d) - y(b));
    const line = (a, b, c, d) => { g.beginPath(); g.moveTo(x(a), y(b)); g.lineTo(x(c), y(d)); g.stroke(); };
    switch (cat) {
      case "lodging":
        rect(-0.9, -0.55, -0.68, 0.75); rect(-0.9, 0.05, 0.9, 0.38); rect(0.68, 0.05, 0.9, 0.75);
        rect(-0.6, -0.35, -0.12, 0); rect(-0.05, -0.3, 0.9, 0);
        break;
      case "food":
        line(-0.45, -0.85, -0.45, 0.85); line(-0.75, -0.85, -0.75, -0.25); line(-0.15, -0.85, -0.15, -0.25);
        line(-0.75, -0.25, -0.15, -0.25);
        g.beginPath(); g.moveTo(x(0.45), y(0.85)); g.lineTo(x(0.45), y(-0.85));
        g.quadraticCurveTo(x(0.95), y(-0.5), x(0.75), y(0.05)); g.lineTo(x(0.45), y(0.05)); g.stroke(); g.fill();
        break;
      case "sight": {
        g.beginPath();
        for (let i = 0; i < 10; i++) {
          const a = toRad(-90 + i * 36), rr = i % 2 === 0 ? r * 0.98 : r * 0.42;
          const px = cx + rr * Math.cos(a), py = cy + rr * Math.sin(a) + r * 0.08;
          i ? g.lineTo(px, py) : g.moveTo(px, py);
        }
        g.closePath(); g.fill();
        break;
      }
      case "view":
        poly([[-0.95, 0.7], [-0.25, -0.65], [0.12, 0], [0.38, -0.35], [0.95, 0.7]]);
        break;
      case "shelter":
        poly([[-0.95, 0], [0, -0.85], [0.95, 0], [0.7, 0], [0.7, 0.8], [-0.7, 0.8], [-0.7, 0]]);
        break;
      case "water":
        g.beginPath(); g.moveTo(x(0), y(-0.95));
        g.bezierCurveTo(x(0.25), y(-0.45), x(0.75), y(0), x(0.7), y(0.35));
        g.bezierCurveTo(x(0.65), y(0.95), x(-0.65), y(0.95), x(-0.7), y(0.35));
        g.bezierCurveTo(x(-0.75), y(0), x(-0.25), y(-0.45), x(0), y(-0.95)); g.fill();
        break;
      case "station":
        g.beginPath(); g.roundRect ? g.roundRect(x(-0.65), y(-0.9), 1.3 * r, 1.45 * r, r * 0.3) : g.rect(x(-0.65), y(-0.9), 1.3 * r, 1.45 * r); g.fill();
        g.fillStyle = catColor; rect(-0.45, -0.65, 0.45, -0.15);
        g.beginPath(); g.arc(x(-0.35), y(0.22), r * 0.12, 0, 7); g.arc(x(0.35), y(0.22), r * 0.12, 0, 7); g.fill();
        line(-0.4, 0.6, -0.7, 0.95); line(0.4, 0.6, 0.7, 0.95);
        break;
    }
  }

  const iconCache = {};
  function poiIcon(cat, top) {
    const key = `poi-${cat}${top ? "-top" : ""}`;
    if (iconCache[key]) return iconCache[key];
    const size = top ? 34 : 28;
    const [c, g] = canvas(size, size);
    const cx = size / 2, outer = cx - 1.2, inner = outer - (top ? 3.2 : 2);
    g.fillStyle = "rgba(0,0,0,0.25)"; g.beginPath(); g.arc(cx, cx + 0.8, cx - 0.5, 0, 7); g.fill();
    g.fillStyle = top ? "#FFB300" : "#fff"; g.beginPath(); g.arc(cx, cx, outer, 0, 7); g.fill();
    g.fillStyle = CAT[cat].color; g.beginPath(); g.arc(cx, cx, inner, 0, 7); g.fill();
    drawGlyph(g, cat, cx, cx, inner * 0.62, CAT[cat].color);
    return (iconCache[key] = c);
  }
  function viewCone() {
    const size = 150;
    const [c, g] = canvas(size, size);
    const r = size / 2;
    const grad = g.createRadialGradient(r, r, 0, r, r, r);
    grad.addColorStop(0, "rgba(31,99,214,0.69)"); grad.addColorStop(0.55, "rgba(31,99,214,0.38)"); grad.addColorStop(1, "rgba(31,99,214,0)");
    g.fillStyle = grad; g.beginPath(); g.moveTo(r, r); g.arc(r, r, r, toRad(-90 - 32), toRad(-90 + 32)); g.closePath(); g.fill();
    return c;
  }
  function peakIcon() {
    const [c, g] = canvas(11, 9);
    g.fillStyle = "#6B4A2B"; g.beginPath(); g.moveTo(5.5, 1); g.lineTo(10, 8); g.lineTo(1, 8); g.closePath(); g.fill();
    return c;
  }
  function flagIcon(color) {
    const [c, g] = canvas(26, 32);
    g.fillStyle = "#333"; g.fillRect(2, 2, 2.5, 30);
    g.fillStyle = "#fff"; g.fillRect(4.5, 2, 20.5, 15);
    g.fillStyle = color; g.fillRect(5.5, 3, 18.5, 13);
    g.fillStyle = "#fff"; g.font = "bold 11px sans-serif"; g.textAlign = "center"; g.fillText("R", 14.75, 13.5);
    return c;
  }
  function imageFor(id) {
    let m;
    if ((m = /^poi-(\w+?)(-top)?$/.exec(id)) && CAT[m[1]]) return poiIcon(m[1], !!m[2]);
    if (id === "view-cone") return viewCone();
    if (id === "peak") return peakIcon();
    if (id === "flag-start") return flagIcon("#2E7D32");
    if (id === "flag-end") return flagIcon("#D1294D");
    return null;
  }
  function addImage(map, id) {
    if (map.hasImage(id)) return;
    const c = imageFor(id);
    if (!c) return;
    map.addImage(id, c.getContext("2d").getImageData(0, 0, c.width, c.height), { pixelRatio: dpr });
  }

  // ------------------------------------------------------------------ Offline-Daten

  async function openDataCache(manifest) {
    return caches.open(DATA_CACHE_PREFIX + manifest.dataVersion);
  }
  /** Name des Offline-Speichers mit vollständigen Kartendaten: aktuelle Version, sonst eine ältere. */
  async function findDataCache(manifest) {
    if (!("caches" in window)) return null;
    const current = DATA_CACHE_PREFIX + manifest.dataVersion;
    const keys = (await caches.keys()).filter((k) => k.startsWith(DATA_CACHE_PREFIX) && k !== current);
    for (const key of [current, ...keys.reverse()]) {
      const cache = await caches.open(key);
      let complete = true;
      for (const m of manifest.maps) if (!(await cache.match(m.url))) { complete = false; break; }
      if (complete) return key;
    }
    return null;
  }

  function showSetup(manifest, message) {
    const total = manifest.maps.reduce((s, m) => s + m.size, 0);
    const mb = (total / 1e6).toLocaleString("de-DE", { maximumFractionDigits: 0 });
    $("setup").hidden = false;
    const btns = $("setupButtons");
    btns.innerHTML = "";
    const button = (label, cls, fn) => {
      const b = document.createElement("button");
      b.className = "btn " + (cls || "");
      b.textContent = label;
      b.onclick = fn;
      btns.appendChild(b);
    };
    if (!("caches" in window) || !("serviceWorker" in navigator)) {
      $("setupText").innerHTML = "<p>Dieser Browser unterstützt keinen Offline-Speicher. Bitte die Seite in Safari (iPhone) " +
        "oder Chrome öffnen – nicht in einem privaten Fenster.</p>";
      return;
    }
    if (S.map) {
      $("setupText").innerHTML = `<p>Neue Kartendaten sind verfügbar (ca. ${mb} MB). Bis zum Download bleiben die bisherigen Daten in Gebrauch.</p>`;
      button("Jetzt laden", "", () => download(manifest));
      button("Später", "secondary", () => { $("setup").hidden = true; });
    } else if (isIOS && !isStandalone) {
      $("setupText").innerHTML =
        "<p><b>So wird Rennsteig Offline zur App auf deinem iPhone:</b></p><ol>" +
        "<li>Unten in Safari auf <b>Teilen</b> tippen (Quadrat mit Pfeil nach oben).</li>" +
        "<li><b>„Zum Home-Bildschirm“</b> wählen und hinzufügen.</li>" +
        "<li>Die App <b>vom Home-Bildschirm</b> öffnen und dort die Offline-Daten laden.</li></ol>" +
        `<p class="muted" style="padding:0">Die Kartendaten (ca. ${mb} MB) werden in der Home-Bildschirm-App gespeichert, ` +
        "nicht in Safari – deshalb erst hinzufügen, dann laden.</p>";
      button("Trotzdem hier im Browser laden", "secondary", () => download(manifest));
    } else {
      $("setupText").innerHTML =
        `<p>${message || ""}Einmalig werden ca. <b>${mb} MB</b> Kartendaten (Karte, Höhenmodell, Höhenlinien) auf dein Gerät geladen – ` +
        "am besten im WLAN. Danach funktionieren Karte, Route, Orte, GPS und Kompass <b>ohne Internet</b>.</p>";
      button("Offline-Daten laden", "", () => download(manifest));
    }
  }

  async function download(manifest) {
    $("setupButtons").innerHTML = "";
    $("progressWrap").hidden = false;
    const total = manifest.maps.reduce((s, m) => s + m.size, 0);
    let done = 0;
    const status = $("setupStatus");
    try {
      if (navigator.storage && navigator.storage.persist) await navigator.storage.persist().catch(() => {});
      const cache = await openDataCache(manifest);
      for (const m of manifest.maps) {
        if (await cache.match(m.url)) { done += m.size; continue; }
        const res = await fetch(m.url, { cache: "no-store" });
        if (!res.ok) throw new Error(`${m.name}: HTTP ${res.status}`);
        const reader = res.body.getReader();
        const chunks = [];
        for (;;) {
          const { done: end, value } = await reader.read();
          if (end) break;
          chunks.push(value);
          done += value.length;
          $("progressBar").style.width = `${Math.min(100, (done / total) * 100)}%`;
          status.textContent = `${(done / 1e6).toLocaleString("de-DE", { maximumFractionDigits: 1 })} von ` +
            `${(total / 1e6).toLocaleString("de-DE", { maximumFractionDigits: 1 })} MB`;
        }
        await cache.put(m.url, new Response(new Blob(chunks), { headers: { "Content-Type": "application/octet-stream" } }));
      }
      for (const key of await caches.keys()) {
        if (key.startsWith(DATA_CACHE_PREFIX) && key !== DATA_CACHE_PREFIX + manifest.dataVersion) await caches.delete(key);
      }
      status.textContent = "Fertig – alles ist offline verfügbar.";
      if (S.map) { location.reload(); return; }
      $("setup").hidden = true;
      start(manifest, DATA_CACHE_PREFIX + manifest.dataVersion);
    } catch (e) {
      $("progressWrap").hidden = true;
      status.textContent = `Download unterbrochen (${e.message}). Bitte mit Internetverbindung erneut versuchen.`;
      showSetup(manifest);
    }
  }

  // ------------------------------------------------------------------ App-Zustand

  const S = {
    map: null, route: null, routeFC: null, pois: [], profile: null, dataDate: "",
    mode: "FREE", fix: null, routePos: null, sensorHeading: null, shownHeading: null,
    shownLat: NaN, shownLon: NaN, anim: { fromLat: 0, fromLon: 0, toLat: NaN, toLon: NaN, start: 0, dur: 900 },
    frame: 0, cameraOnTarget: true, zoomTarget: null, topPadding: 0, selected: null, compassAccuracy: null,
    track: [], trackLen: 0, recording: false, wakeLock: null, firstFix: false, compassStarted: false,
    lastStatus: 0, styleReady: false,
  };

  function loadTrack() {
    try {
      S.track = JSON.parse(localStorage.getItem("rennsteig-track") || "[]");
    } catch (e) { S.track = []; }
    S.trackLen = 0;
    for (let i = 1; i < S.track.length; i++) {
      S.trackLen += distance(S.track[i - 1][0], S.track[i - 1][1], S.track[i][0], S.track[i][1]);
    }
  }
  function saveTrack() {
    try { localStorage.setItem("rennsteig-track", JSON.stringify(S.track)); } catch (e) { /* voll */ }
  }

  // ------------------------------------------------------------------ Start

  async function start(manifest, cacheName) {
    S.manifest = manifest;
    S.dataOutdated = cacheName !== DATA_CACHE_PREFIX + manifest.dataVersion;
    $("statusTitle").textContent = "Karte wird geladen …";
    const [routeFC, poiFC, profile, style] = await Promise.all(
      ["data/route.geojson", "data/pois.geojson", "data/profile.json", "style.json"].map((u) => fetch(u).then((r) => r.json()))
    );
    const line = routeFC.features.find((f) => f.properties.kind === "route");
    S.route = new Route(line.geometry.coordinates);
    S.routeFC = routeFC;
    S.dataDate = line.properties.generated || "";
    S.profile = new Profile(profile);
    S.pois = poiFC.features.map((f) => {
      const p = f.properties;
      const tags = {};
      for (const k in p) if (k.startsWith("osm:")) tags[k.slice(4)] = p[k];
      return {
        id: p.id, cat: p.cat, kind: p.kind, name: p.name || "", lon: f.geometry.coordinates[0], lat: f.geometry.coordinates[1],
        km: p.km, off: p.off, score: p.score, top: !!p.top, desc: p.desc || null, demEle: p.dem_ele, tags,
      };
    }).filter((p) => CAT[p.cat]);
    const [lon0, lat0] = line.geometry.coordinates[0];
    const [lon1, lat1] = line.geometry.coordinates[line.geometry.coordinates.length - 1];
    S.pois.push({ id: "start", cat: "sight", kind: "start", name: "Start in Hörschel", lat: lat0, lon: lon0, km: 0, off: 0, score: 99, top: true, desc: START_DESC, tags: {} });
    S.pois.push({ id: "end", cat: "sight", kind: "end", name: "Ziel in Blankenstein", lat: lat1, lon: lon1, km: S.route.lengthM / 1000, off: 0, score: 99, top: true, desc: END_DESC, tags: {} });
    S.pois.sort((a, b) => a.km - b.km);
    loadTrack();

    // Kartendateien aus dem Offline-Speicher
    const protocol = new pmtiles.Protocol({ metadata: true });
    maplibregl.addProtocol("pmtiles", protocol.tile);
    const cache = await caches.open(cacheName);
    for (const m of manifest.maps) {
      const res = await cache.match(m.url);
      const blob = await res.blob();
      protocol.add(new pmtiles.PMTiles(new pmtiles.FileSource(new File([blob], m.name))));
    }
    const base = location.href.replace(/[#?].*$/, "").replace(/[^/]*$/, "");
    style.glyphs = style.glyphs.replace("{BASE}", base);

    const b = S.route.bounds();
    const cam = prefs.camera;
    const map = new maplibregl.Map({
      container: "map", style, attributionControl: false, pitchWithRotate: false, maxPitch: 0,
      minZoom: 7, maxZoom: 18.5, fadeDuration: 150,
      maxBounds: [[b[0] - 0.6, b[1] - 0.4], [b[2] + 0.6, b[3] + 0.4]],
      ...(cam ? { center: [cam.lon, cam.lat], zoom: cam.zoom, bearing: cam.bearing } : { bounds: [[b[0], b[1]], [b[2], b[3]]], fitBoundsOptions: { padding: 40 } }),
    });
    S.map = map;
    window.rennsteig = S; // für Tests und die Browser-Konsole
    map.touchPitch.disable();
    map.on("styleimagemissing", (e) => addImage(map, e.id));
    map.on("load", () => {
      for (const id of ["view-cone", "peak", "flag-start", "flag-end", ...CATS.flatMap((c) => [`poi-${c.key}`, `poi-${c.key}-top`])]) addImage(map, id);
      map.getSource("route").setData(routeFC);
      S.styleReady = true;
      applyFilter();
      updateTrackSource();
      updateMeSource();
      updateSelectedSource();
      if (S.fix) handleFirstFix();
    });
    map.on("dragstart", (e) => {
      const touches = e.originalEvent && e.originalEvent.touches;
      if (e.originalEvent && S.mode !== "FREE" && !(touches && touches.length > 1)) setMode("FREE");
    });
    map.on("rotatestart", (e) => {
      if (e.originalEvent && S.mode === "HEADING") { prefs.headingUp = false; setMode("FREE"); }
    });
    map.on("rotate", updateCompassButton);
    map.on("moveend", () => {
      const c = map.getCenter();
      prefs.camera = { lat: c.lat, lon: c.lng, zoom: map.getZoom(), bearing: map.getBearing() };
      savePrefs();
    });
    map.on("click", onMapClick);

    bindUi();
    startLocation();
    if (typeof DeviceOrientationEvent !== "undefined" && typeof DeviceOrientationEvent.requestPermission === "function") {
      // iOS: war der Kompass schon freigegeben, klappt das ohne Antippen; sonst fragt das erste Antippen
      DeviceOrientationEvent.requestPermission().then((state) => { if (state === "granted") listenCompass(); }).catch(() => {});
    } else startCompass();
    setInterval(() => { updateStatus(true); updateMeSource(); }, 10000);
    updateLocateButton();
    updateStatus(true);
  }

  // ------------------------------------------------------------------ Standort & Kompass

  function startLocation() {
    if (!("geolocation" in navigator)) {
      setStatus("Kein GPS verfügbar", "Dieses Gerät oder dieser Browser liefert keinen Standort.");
      return;
    }
    navigator.geolocation.watchPosition(onPosition, (err) => {
      if (err.code === err.PERMISSION_DENIED) {
        setStatus("Standort nicht freigegeben", "Einstellungen → Datenschutz → Ortungsdienste → Safari-Websites bzw. Rennsteig: „Beim Verwenden“.");
      }
    }, { enableHighAccuracy: true, maximumAge: 1000, timeout: 60000 });
  }

  function onPosition(pos) {
    const c = pos.coords;
    const fix = { lat: c.latitude, lon: c.longitude, acc: c.accuracy, alt: c.altitude, heading: c.heading, speed: c.speed, time: pos.timestamp };
    const prev = S.fix;
    S.fix = fix;
    if (!S.compassStarted && fix.heading != null && !isNaN(fix.heading) && fix.speed > 0.8) S.sensorHeading = fix.heading;
    const now = performance.now();
    if (isNaN(S.shownLat) || !prev) {
      S.shownLat = fix.lat; S.shownLon = fix.lon;
      S.anim.fromLat = fix.lat; S.anim.fromLon = fix.lon;
    } else {
      S.anim.fromLat = S.shownLat; S.anim.fromLon = S.shownLon;
      S.anim.dur = Math.min(1500, Math.max(300, now - S.anim.start));
    }
    S.anim.toLat = fix.lat; S.anim.toLon = fix.lon; S.anim.start = now;
    S.routePos = S.route.locate(fix.lat, fix.lon);
    if (S.recording) recordPoint(fix);
    handleFirstFix();
    updateMeSource();
    updatePoiLive();
    if (!$("listPanel").hidden) renderList();
    drawProfile();
    updateStatus(!prev);
    ensureFrame();
  }

  function handleFirstFix() {
    if (S.firstFix || !S.styleReady || !S.fix) return;
    S.firstFix = true;
    if (S.route.locate(S.fix.lat, S.fix.lon).offM < 15000) setMode(prefs.headingUp ? "HEADING" : "FOLLOW");
  }

  function listenCompass() {
    if (S.compassStarted) return;
    S.compassStarted = true;
    window.addEventListener("deviceorientationabsolute", onOrientationAbsolute, true);
    window.addEventListener("deviceorientation", onOrientation, true);
    updateStatus(true);
  }
  function startCompass() {
    if (S.compassStarted) return;
    const listen = listenCompass;
    if (typeof DeviceOrientationEvent !== "undefined" && typeof DeviceOrientationEvent.requestPermission === "function") {
      DeviceOrientationEvent.requestPermission().then((state) => {
        if (state === "granted") listen();
        else toast("Ohne Freigabe der Bewegungssensoren kann die Karte nicht nach der Blickrichtung gedreht werden.");
      }).catch(() => {});
    } else {
      listen();
    }
  }

  function screenAngle() {
    if (screen.orientation && typeof screen.orientation.angle === "number") return screen.orientation.angle;
    return typeof window.orientation === "number" ? window.orientation : 0;
  }

  let absoluteSeen = false;
  function onOrientationAbsolute(e) {
    if (e.alpha == null) return;
    absoluteSeen = true;
    setHeading(norm360(360 - e.alpha + screenAngle()) + declination(S.fix ? S.fix.lon : 10.9));
  }
  function onOrientation(e) {
    if (typeof e.webkitCompassHeading === "number" && !isNaN(e.webkitCompassHeading)) {
      // iOS: Kompasskurs bezogen auf magnetisch Nord
      S.compassAccuracy = e.webkitCompassAccuracy;
      setHeading(e.webkitCompassHeading + screenAngle() + declination(S.fix ? S.fix.lon : 10.9));
    } else if (!absoluteSeen && e.absolute && e.alpha != null) {
      setHeading(norm360(360 - e.alpha + screenAngle()) + declination(S.fix ? S.fix.lon : 10.9));
    }
  }
  let headingSin = 0, headingCos = 1, headingInit = false;
  function setHeading(deg) {
    const r = toRad(deg);
    if (!headingInit) { headingSin = Math.sin(r); headingCos = Math.cos(r); headingInit = true; }
    headingSin += 0.18 * (Math.sin(r) - headingSin);
    headingCos += 0.18 * (Math.cos(r) - headingCos);
    S.sensorHeading = norm360(toDeg(Math.atan2(headingSin, headingCos)));
    ensureFrame();
    updateStatus(false);
  }

  // ------------------------------------------------------------------ Kamera

  function ensureFrame() {
    if (!S.frame && S.map) S.frame = requestAnimationFrame(onFrame);
  }

  function onFrame() {
    S.frame = 0;
    const map = S.map;
    const now = performance.now();
    let changed = false;
    if (!isNaN(S.anim.toLat)) {
      const t = Math.min(1, Math.max(0, (now - S.anim.start) / S.anim.dur));
      const lat = S.anim.fromLat + (S.anim.toLat - S.anim.fromLat) * t;
      const lon = S.anim.fromLon + (S.anim.toLon - S.anim.fromLon) * t;
      if (lat !== S.shownLat || lon !== S.shownLon) { S.shownLat = lat; S.shownLon = lon; changed = true; }
    }
    let headingSettled = true;
    if (S.sensorHeading != null) {
      const cur = S.shownHeading;
      const next = cur == null ? S.sensorHeading : norm360(cur + angleDiff(cur, S.sensorHeading) * 0.22);
      if (cur == null || Math.abs(angleDiff(cur, next)) > 0.08) { S.shownHeading = next; changed = true; headingSettled = false; }
    }
    if (changed) { updateMeSource(); if (S.selected) updatePoiLive(); }
    let cameraSettled = true;
    if (S.mode !== "FREE" && !isNaN(S.shownLat) && (changed || !S.cameraOnTarget)) cameraSettled = followCamera(map);
    S.cameraOnTarget = cameraSettled;
    const animating = !isNaN(S.anim.toLat) && now - S.anim.start < S.anim.dur;
    if (animating || !headingSettled || !cameraSettled) ensureFrame();
  }

  function followCamera(map) {
    const c = map.getCenter();
    const k = 0.25;
    let lat = c.lat + (S.shownLat - c.lat) * k, lon = c.lng + (S.shownLon - c.lng) * k;
    const posDone = Math.abs(S.shownLat - lat) < 1e-7 && Math.abs(S.shownLon - lon) < 1e-7;
    if (posDone) { lat = S.shownLat; lon = S.shownLon; }
    const curBearing = map.getBearing();
    const want = S.mode === "HEADING" ? (S.shownHeading ?? curBearing) : 0;
    const db = angleDiff(curBearing, want);
    const bearingDone = Math.abs(db) < 0.3;
    const bear = bearingDone ? want : curBearing + db * 0.3;
    let zoom = map.getZoom(), zoomDone = true;
    if (S.zoomTarget != null) {
      zoom += (S.zoomTarget - zoom) * 0.2;
      if (Math.abs(S.zoomTarget - zoom) < 0.01) { zoom = S.zoomTarget; S.zoomTarget = null; } else zoomDone = false;
    }
    const wantPad = S.mode === "HEADING" ? map.getContainer().clientHeight * HEADING_TOP_PAD : 0;
    const padDone = Math.abs(wantPad - S.topPadding) < 1;
    S.topPadding = padDone ? wantPad : S.topPadding + (wantPad - S.topPadding) * 0.25;
    map.jumpTo({ center: [lon, lat], bearing: bear, zoom, padding: { top: S.topPadding, bottom: 0, left: 0, right: 0 } });
    return posDone && bearingDone && zoomDone && padDone;
  }

  function setMode(mode) {
    const old = S.mode;
    S.mode = mode;
    if (mode === "HEADING") prefs.headingUp = true;
    if (mode === "FOLLOW" && old === "HEADING") prefs.headingUp = false;
    savePrefs();
    updateLocateButton();
    const map = S.map;
    if (!map) return;
    if (mode === "FREE") {
      map.touchZoomRotate.enable();
      map.touchZoomRotate.enableRotation();
      map.scrollZoom.enable();
      return;
    }
    // Beim Folgen um die eigene Position zoomen, Drehen übernimmt der Kompass
    map.touchZoomRotate.enable({ around: "center" });
    map.scrollZoom.enable({ around: "center" });
    if (mode === "HEADING") map.touchZoomRotate.disableRotation(); else map.touchZoomRotate.enableRotation();
    if (!S.fix || isNaN(S.shownLat)) { toast("Warte auf GPS-Signal …"); return; }
    map.stop();
    if (old === "FREE") {
      S.zoomTarget = Math.max(map.getZoom(), 14.5);
      S.topPadding = map.getPadding().top || 0;
    }
    S.cameraOnTarget = false;
    ensureFrame();
  }

  function onLocateClicked() {
    startCompass();
    if (S.mode === "FREE") setMode(prefs.headingUp ? "HEADING" : "FOLLOW");
    else if (S.mode === "FOLLOW") setMode("HEADING");
    else setMode("FOLLOW");
    if (S.fix && S.route.locate(S.fix.lat, S.fix.lon).offM > 15000) {
      toast(`Du bist ${fmtDist(S.routePos.offM)} vom Rennsteig entfernt – dort enthält die Offline-Karte keine Details.`);
    }
  }
  function onCompassClicked() {
    startCompass();
    const map = S.map;
    if (S.mode === "HEADING") setMode("FOLLOW");
    else if (S.mode === "FOLLOW") setMode("HEADING");
    else if (Math.abs(map.getBearing()) > 0.5) map.easeTo({ bearing: 0, duration: 400 });
    else if (S.fix) setMode("HEADING");
  }
  function updateCompassButton() {
    if (S.map) $("compassNeedle").style.transform = `rotate(${-S.map.getBearing()}deg)`;
    if (S.selected) updatePoiLive();
  }
  function updateLocateButton() {
    const btn = $("btnLocate");
    const crosshair = '<path d="M5 12a7 7 0 1 0 14 0a7 7 0 1 0-14 0z" fill="none" stroke="currentColor" stroke-width="2"/>' +
      '<circle cx="12" cy="12" r="3.2"/><path d="M12 1v4M12 19v4M1 12h4M19 12h4" stroke="currentColor" stroke-width="2"/>';
    const arrow = '<path d="M12 2l7.5 19L12 17l-7.5 4z"/>';
    $("locateIcon").innerHTML = S.mode === "HEADING" ? arrow : crosshair;
    btn.classList.toggle("active", S.mode === "HEADING");
    btn.style.color = S.mode === "FOLLOW" ? "#1f63d6" : S.mode === "HEADING" ? "#fff" : "#555";
    $("locateIcon").style.fill = btn.style.color;
    $("btnCompass").classList.toggle("light", S.mode === "HEADING");
  }

  function focusOn(lat, lon, zoom) {
    if (S.mode !== "FREE") setMode("FREE");
    S.map.stop();
    S.topPadding = 0;
    // Ziel mittig im sichtbaren Teil der Karte, nicht hinter der Ortskarte oder dem Profil
    let bottom = 0;
    for (const id of ["poiCard", "profilePanel"]) if (!$(id).hidden) bottom += $(id).offsetHeight;
    bottom = Math.min(bottom, S.map.getContainer().clientHeight * 0.6);
    S.map.easeTo({ center: [lon, lat], zoom, padding: { top: 0, bottom, left: 0, right: 0 }, duration: 500 });
  }

  // ------------------------------------------------------------------ Kartenquellen

  function updateMeSource() {
    if (!S.styleReady) return;
    const src = S.map.getSource("me");
    if (!S.fix || isNaN(S.shownLat)) { src.setData(EMPTY); return; }
    const props = {
      kind: "me", r0: (S.fix.acc || 30) / (METERS_PER_PIXEL_Z0 * Math.cos(toRad(S.shownLat))),
      stale: Date.now() - S.fix.time > STALE_MS,
    };
    if (S.shownHeading != null) props.bearing = S.shownHeading;
    src.setData({ type: "Feature", geometry: { type: "Point", coordinates: [S.shownLon, S.shownLat] }, properties: props });
  }
  function applyFilter() {
    if (!S.styleReady) return;
    const feats = [];
    for (const p of S.pois) {
      if (p.id === "start" || p.id === "end") continue;
      if (!prefs.cats.includes(p.cat) || (prefs.onlyTop && !p.top)) continue;
      feats.push({ type: "Feature", geometry: { type: "Point", coordinates: [p.lon, p.lat] },
        properties: { id: p.id, name: displayName(p), top: p.top, score: p.score, icon: `poi-${p.cat}${p.top ? "-top" : ""}` } });
    }
    S.map.getSource("pois").setData({ type: "FeatureCollection", features: feats });
    if (!$("listPanel").hidden) renderList();
  }
  function updateTrackSource() {
    if (!S.styleReady) return;
    S.map.getSource("track").setData(S.track.length < 2 ? EMPTY :
      { type: "Feature", properties: {}, geometry: { type: "LineString", coordinates: S.track.map((p) => [p[1], p[0]]) } });
  }
  function updateSelectedSource(lat, lon) {
    if (!S.styleReady) return;
    const p = S.selected;
    const coords = lat != null ? [lon, lat] : p ? [p.lon, p.lat] : null;
    S.map.getSource("selected").setData(coords ? { type: "Feature", properties: {}, geometry: { type: "Point", coordinates: coords } } : EMPTY);
  }

  // ------------------------------------------------------------------ Orte

  const displayName = (p) => p.name || kindLabel(p);
  const kindLabel = (p) => KIND_LABELS[p.kind] || (p.cat === "shelter" ? "Schutzhütte" : CAT[p.cat].label);

  function onMapClick(e) {
    const r = 20;
    const feats = S.map.queryRenderedFeatures([[e.point.x - r, e.point.y - r], [e.point.x + r, e.point.y + r]],
      { layers: ["poi-top", "poi-other", "route-ends"] });
    for (const f of feats) {
      const id = f.properties.id || f.properties.kind;
      const poi = S.pois.find((p) => p.id === id);
      if (poi) { selectPoi(poi, false); return; }
    }
    if (S.selected) clearSelection();
  }

  function selectPoi(p, moveCamera) {
    S.selected = p;
    updateSelectedSource();
    buildPoiCard(p);
    drawProfile();
    if (moveCamera) focusOn(p.lat, p.lon, Math.max(S.map.getZoom(), 14.5));
  }
  function clearSelection() {
    S.selected = null;
    $("poiCard").hidden = true;
    updateSelectedSource();
    drawProfile();
  }

  const CUISINE = {
    regional: "regional", german: "deutsch", thuringian: "thüringisch", italian: "italienisch", pizza: "Pizza",
    greek: "griechisch", asian: "asiatisch", chinese: "chinesisch", vietnamese: "vietnamesisch", burger: "Burger",
    kebab: "Döner", coffee_shop: "Kaffee", cake: "Kuchen", ice_cream: "Eis", steak_house: "Steakhaus",
    barbecue: "Grill", bratwurst: "Bratwurst", sausage: "Wurst", international: "international", fish: "Fisch",
    vegetarian: "vegetarisch", vegan: "vegan", local: "regional", breakfast: "Frühstück", turkish: "türkisch",
    indian: "indisch", thai: "thailändisch", mexican: "mexikanisch", balkan: "Balkan",
  };
  const yesNo = (v) => ({ yes: "ja", no: "nein", limited: "eingeschränkt", leashed: "angeleint", designated: "ja" }[v] || v);
  function openingHours(raw) {
    let s = raw;
    for (const [en, de] of [["Mo", "Mo"], ["Tu", "Di"], ["We", "Mi"], ["Th", "Do"], ["Fr", "Fr"], ["Sa", "Sa"], ["Su", "So"], ["PH", "Feiertag"], ["SH", "Ferien"]]) {
      s = s.replace(new RegExp(`\\b${en}\\b`, "g"), de);
    }
    return s.replace(/\boff\b|\bclosed\b/g, "geschlossen").replace("24/7", "rund um die Uhr").replace(/;\s*/g, "\n");
  }
  function facts(p) {
    const t = p.tags, out = [];
    const ele = t.ele && !isNaN(parseFloat(t.ele.replace(",", "."))) ? `${Math.round(parseFloat(t.ele.replace(",", ".")))} m` : p.demEle != null ? `ca. ${p.demEle} m` : null;
    if (ele) out.push(["Höhe", ele]);
    if (t.stars) { const n = parseInt(t.stars, 10); out.push(["Sterne", n >= 1 && n <= 5 ? "★".repeat(n) + (t.stars.includes("S") ? " Superior" : "") : t.stars]); }
    if (t.cuisine) out.push(["Küche", t.cuisine.split(";").map((c) => CUISINE[c.trim()] || c.trim()).join(", ")]);
    if (t.opening_hours) out.push(["Geöffnet", openingHours(t.opening_hours)]);
    const phone = t.phone || t["contact:phone"];
    if (phone) { const first = phone.split(";")[0].trim(); out.push(["Telefon", first, "tel:" + first.replace(/\s/g, "")]); }
    const web = t.website || t["contact:website"] || t.url;
    if (web) out.push(["Website", web.replace(/^https?:\/\//, "").replace(/\/$/, "") + " (online)", /^https?:/.test(web) ? web : "https://" + web]);
    const mail = t.email || t["contact:email"];
    if (mail) out.push(["E-Mail", mail, "mailto:" + mail]);
    const street = [t["addr:street"] || t["addr:place"], t["addr:housenumber"]].filter(Boolean).join(" ");
    const city = [t["addr:postcode"], t["addr:city"]].filter(Boolean).join(" ");
    const addr = [street, city].filter(Boolean).join(", ");
    if (addr) out.push(["Adresse", addr]);
    if (t.wikipedia) {
      const i = t.wikipedia.indexOf(":");
      const lang = i > 0 ? t.wikipedia.slice(0, i) : "de", title = i > 0 ? t.wikipedia.slice(i + 1) : t.wikipedia;
      out.push(["Wikipedia", `${title} (online)`, `https://${lang}.wikipedia.org/wiki/${encodeURIComponent(title.replace(/ /g, "_"))}`]);
    }
    if (t.operator) out.push(["Betreiber", t.operator]);
    if (t.beds) out.push(["Betten", t.beds]);
    if (t.rooms) out.push(["Zimmer", t.rooms]);
    if (t.capacity && p.cat === "lodging") out.push(["Plätze", t.capacity]);
    if (t.outdoor_seating === "yes") out.push(["Draußen", "Sitzplätze im Freien"]);
    if (t["diet:vegetarian"] === "yes" || t["diet:vegetarian"] === "only") out.push(["Vegetarisch", t["diet:vegetarian"] === "only" ? "nur vegetarisch" : "ja"]);
    if (t.wheelchair) out.push(["Rollstuhl", yesNo(t.wheelchair)]);
    if (t.dog) out.push(["Hunde", yesNo(t.dog)]);
    if (t.internet_access && t.internet_access !== "no") out.push(["Internet", t.internet_access === "wlan" ? "WLAN" : yesNo(t.internet_access)]);
    if (p.cat === "shelter") {
      if (t.bench) out.push(["Sitzbank", yesNo(t.bench)]);
      if (t.fireplace) out.push(["Feuerstelle", yesNo(t.fireplace)]);
    }
    if (t.drinking_water && p.cat !== "water") out.push(["Trinkwasser", yesNo(t.drinking_water)]);
    if (t.fee) out.push(["Eintritt", t.fee === "no" ? "frei" : t.fee === "yes" ? "kostenpflichtig" : t.fee]);
    if (t.height && p.kind === "tower") out.push(["Turmhöhe", `${t.height} m`]);
    if (t.inscription) out.push(["Inschrift", t.inscription]);
    if (t.start_date) out.push(["Errichtet", t.start_date]);
    return out;
  }

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function buildPoiCard(p) {
    const card = $("poiCard");
    card.innerHTML = "";
    const head = el("div", "poiHead");
    const img = el("img"); img.src = poiIcon(p.cat, p.top).toDataURL(); img.alt = "";
    const titles = el("div", "poiTitle");
    titles.appendChild(el("b", null, displayName(p)));
    titles.appendChild(el("span", p.top ? "top" : null, kindLabel(p) + (p.top ? " · ★ Empfehlung" : "")));
    const close = el("button", "close", "✕"); close.setAttribute("aria-label", "Schließen"); close.onclick = clearSelection;
    head.append(img, titles, close);
    card.appendChild(head);
    const routeInfo = p.id === "start" ? "Rennsteig-km 0" : p.id === "end" ? `Rennsteig-km ${fmtKm(p.km)}` :
      p.off < 30 ? `Rennsteig-km ${fmtKm(p.km)} · direkt am Weg` : `Rennsteig-km ${fmtKm(p.km)} · ${fmtDist(p.off)} abseits`;
    card.appendChild(el("div", "routeInfo", routeInfo));
    const live = el("div", "live");
    live.innerHTML = '<svg viewBox="0 0 24 24" id="liveArrow"><path d="M12 2l7.5 19L12 17l-7.5 4z"/></svg><span id="liveText"></span>';
    card.appendChild(live);
    if (p.desc) card.appendChild(el("div", "desc", p.desc));
    const od = p.tags["description:de"] || p.tags.description;
    if (od) card.appendChild(el("div", "osmdesc", od));
    const f = facts(p);
    if (f.length) {
      const box = el("div", "facts");
      for (const [k, v, href] of f) {
        box.appendChild(el("div", "k", k));
        const val = el("div", "v");
        if (href) { const a = el("a", null, v); a.href = href; if (/^https?:/.test(href)) a.target = "_blank"; val.appendChild(a); } else val.textContent = v;
        box.appendChild(val);
      }
      card.appendChild(box);
    }
    if (p.id !== "start" && p.id !== "end") card.appendChild(el("div", "source", `Daten: OpenStreetMap (${p.id})`));
    card.hidden = false;
    card.scrollTop = 0;
    updatePoiLive();
  }

  function updatePoiLive() {
    const p = S.selected, text = $("liveText"), arrow = $("liveArrow");
    if (!p || !text) return;
    if (!S.fix) { text.textContent = "Entfernung erscheint, sobald GPS bereit ist"; arrow.style.visibility = "hidden"; return; }
    const lat = isNaN(S.shownLat) ? S.fix.lat : S.shownLat, lon = isNaN(S.shownLon) ? S.fix.lon : S.shownLon;
    const d = distance(lat, lon, p.lat, p.lon), brg = bearing(lat, lon, p.lat, p.lon);
    arrow.style.visibility = "visible";
    arrow.style.transform = `rotate(${brg - S.map.getBearing()}deg)`;
    let along = "";
    const rp = S.routePos;
    if (rp && rp.offM < 2000) {
      const dk = prefs.reverse ? rp.km - p.km : p.km - rp.km;
      if (Math.abs(dk) >= 0.05) along = dk > 0 ? ` · ${fmtKm(dk)} km voraus` : ` · ${fmtKm(-dk)} km zurück`;
    }
    const s = `${fmtDist(d)} Luftlinie, Richtung ${cardinal(brg)}${along}`;
    if (text.textContent !== s) text.textContent = s;
  }

  // ------------------------------------------------------------------ Liste

  let sortByDistance = false;
  const fold = (s) => s.toLowerCase().normalize("NFD").replace(/\p{Mn}+/gu, "").replace(/ß/g, "ss").trim();
  function renderList() {
    const q = fold($("search").value || "");
    let items = q
      ? S.pois.filter((p) => fold(displayName(p)).includes(q) || fold(kindLabel(p)).includes(q))
      : S.pois.filter((p) => (prefs.cats.includes(p.cat) || p.id === "start" || p.id === "end") && (!prefs.onlyTop || p.top));
    const f = S.fix;
    if (sortByDistance && f) items = items.slice().sort((a, b) => distance(f.lat, f.lon, a.lat, a.lon) - distance(f.lat, f.lon, b.lat, b.lon));
    $("btnSort").textContent = sortByDistance && f ? "Sortiert: Nähe" : "Sortiert: Rennsteig-km";
    $("listCount").textContent = `${items.length} Orte` + (q ? " (Suche in allen Kategorien)" : "");
    $("cbTop").checked = prefs.onlyTop;
    const list = $("list");
    const frag = document.createDocumentFragment();
    const urls = {};
    for (const p of items.slice(0, 1500)) {
      const row = el("div", "item");
      const key = p.cat + p.top;
      const img = el("img"); img.src = urls[key] || (urls[key] = poiIcon(p.cat, p.top).toDataURL()); img.alt = "";
      const t = el("div", "t");
      t.appendChild(el("b", null, displayName(p)));
      const parts = [`km ${fmtKm(p.km)}`, kindLabel(p)];
      if (p.off >= 30) parts.push(`${fmtDist(p.off)} abseits`);
      if (p.top) parts.push("★ Empfehlung");
      t.appendChild(el("span", null, parts.join(" · ")));
      row.append(img, t, el("div", "d", f ? fmtDist(distance(f.lat, f.lon, p.lat, p.lon)) : ""));
      row.onclick = () => { $("listPanel").hidden = true; selectPoi(p, true); };
      frag.appendChild(row);
    }
    list.innerHTML = "";
    list.appendChild(frag);
  }

  // ------------------------------------------------------------------ Höhenprofil

  let profileKm = null;
  function drawProfile() {
    const cv = $("profileCanvas");
    if ($("profilePanel").hidden || !S.profile) return;
    const w = cv.clientWidth, h = cv.clientHeight;
    cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr);
    const g = cv.getContext("2d");
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    const P = S.profile, len = S.route.lengthM / 1000;
    const padL = 34, padR = 10, padT = 18, padB = 18, W = w - padL - padR, H = h - padT - padB;
    const lo = Math.floor(P.min_m / 100) * 100, hi = Math.max(lo + 100, Math.ceil(P.max_m / 100) * 100);
    const X = (km) => padL + (km / len) * W, Y = (e) => padT + (1 - (e - lo) / (hi - lo)) * H;
    g.font = "10.5px -apple-system, sans-serif";
    g.fillStyle = "#555048"; g.strokeStyle = "rgba(0,0,0,0.13)"; g.lineWidth = 1;
    for (let e = lo; e <= hi + 0.1; e += hi - lo > 500 ? 200 : 100) {
      g.beginPath(); g.moveTo(padL, Y(e)); g.lineTo(padL + W, Y(e)); g.stroke(); g.fillText(String(e), 2, Y(e) + 4);
    }
    for (let k = 0; k <= len; k += 20) {
      g.beginPath(); g.moveTo(X(k), padT); g.lineTo(X(k), padT + H); g.stroke();
      const label = String(k), tw = g.measureText(label).width;
      g.fillText(label, Math.min(Math.max(X(k) - tw / 2, padL), w - padR - tw), h - 4);
    }
    const step = P.step_m / 1000, n = P.ele.length, stride = Math.max(1, Math.floor(n / Math.max(1, W)));
    g.beginPath(); g.moveTo(X(0), Y(lo));
    for (let i = 0; i < n; i += stride) g.lineTo(X(Math.min(i * step, len)), Y(P.ele[i]));
    g.lineTo(X(len), Y(P.ele[n - 1])); g.lineTo(X(len), Y(lo)); g.closePath();
    const grad = g.createLinearGradient(0, padT, 0, padT + H);
    grad.addColorStop(0, "#9CC77E"); grad.addColorStop(1, "#E9E3C9");
    g.fillStyle = grad; g.fill(); g.strokeStyle = "#5B4A32"; g.lineWidth = 1.6; g.stroke();
    const marker = (km, color) => {
      const x = X(km), y = Y(P.eleAt(km));
      g.strokeStyle = color; g.fillStyle = color; g.lineWidth = 2;
      g.beginPath(); g.moveTo(x, padT); g.lineTo(x, padT + H); g.stroke();
      g.beginPath(); g.arc(x, y, 4.5, 0, 7); g.fill();
      const text = `km ${fmtKm(km)} · ${Math.round(P.eleAt(km))} m`, tw = g.measureText(text).width;
      const tx = x + 6 + tw > w - padR ? x - 6 - tw : x + 6, ty = Math.max(y - 8, padT + 10);
      g.fillStyle = "rgba(255,255,255,0.9)"; g.fillRect(tx - 2, ty - 11, tw + 4, 14);
      g.fillStyle = "#000"; g.fillText(text, tx, ty);
    };
    const sel = profileKm != null ? profileKm : S.selected ? S.selected.km : null;
    if (sel != null) marker(sel, "#FFB300");
    if (S.routePos && S.routePos.offM < 3000) marker(S.routePos.km, "#1F63D6");
    g.fillStyle = "#555048";
    g.fillText("km", 2, h - 4);
    g.fillText(`↑ ${P.ascent_m} m   ↓ ${P.descent_m} m   ${P.min_m}–${P.max_m} m`, padL + 4, padT - 5);
  }
  function profileTouch(ev) {
    const cv = $("profileCanvas"), rect = cv.getBoundingClientRect();
    const x = (ev.touches ? ev.touches[0].clientX : ev.clientX) - rect.left;
    const len = S.route.lengthM / 1000;
    profileKm = Math.min(len, Math.max(0, ((x - 34) / (rect.width - 44)) * len));
    drawProfile();
    const [lat, lon] = S.route.pointAt(profileKm);
    focusOn(lat, lon, Math.max(S.map.getZoom(), 13));
    updateSelectedSource(lat, lon);
    ev.preventDefault();
  }

  // ------------------------------------------------------------------ Status

  function setStatus(title, detail, warn) {
    if ($("statusTitle").textContent !== title) $("statusTitle").textContent = title;
    if ($("statusDetail").textContent !== detail) $("statusDetail").textContent = detail;
    const w = warn || "";
    if ($("statusWarn").textContent !== w) $("statusWarn").textContent = w;
    $("statusWarn").hidden = !w;
  }
  function updateStatus(force) {
    const now = performance.now();
    if (!force && now - S.lastStatus < 500) return;
    S.lastStatus = now;
    const f = S.fix, warn = [];
    let title = "Rennsteig Offline", detail = "";
    if (!f) {
      title = "Suche GPS-Signal …";
      detail = "GPS funktioniert ohne Internet. Unter freiem Himmel dauert der erste Fix meist unter einer Minute.";
    } else {
      const pos = S.routePos, parts = [];
      if (pos && pos.offM < 3000) {
        const total = S.route.lengthM / 1000, remaining = prefs.reverse ? pos.km : total - pos.km;
        title = `Rennsteig-km ${fmtKm(pos.km)} · noch ${fmtKm(remaining)} km bis ${prefs.reverse ? "Hörschel" : "Blankenstein"}`;
        if (pos.offM < 300) parts.push(`Höhe ca. ${Math.round(S.profile.eleAt(pos.km))} m`);
        else if (f.alt != null) parts.push(`Höhe ca. ${Math.round(f.alt - GEOID_HEIGHT)} m`);
        parts.push(pos.offM < 25 ? "auf dem Weg" : `${fmtDist(pos.offM)} neben dem Weg`);
        if (pos.offM > 150) warn.push(`Du bist ${fmtDist(pos.offM)} vom Rennsteig entfernt.`);
      } else if (pos) {
        title = `${fmtDist(pos.offM)} bis zum Rennsteig`;
        parts.push(`nächster Punkt: km ${fmtKm(pos.km)}`);
      }
      if (f.acc != null) parts.push(`GPS ±${Math.round(f.acc)} m`);
      const age = Date.now() - f.time;
      if (age > STALE_MS) warn.push(`Letzter Standort ist ${Math.floor(age / 60000)} min alt – kein GPS-Empfang?`);
      detail = parts.join(" · ");
    }
    if (S.sensorHeading != null) {
      const h = `Blick ${cardinal(S.sensorHeading)} ${Math.round(S.sensorHeading)}°`;
      detail = detail && f ? `${detail} · ${h}` : h;
    } else if (isIOS && !S.compassStarted) {
      detail += (detail ? "\n" : "") + "Kompass: tippe auf ◎ oder die Kompassnadel.";
    }
    if (S.compassAccuracy != null && (S.compassAccuracy < 0 || S.compassAccuracy > 25)) {
      warn.push("Kompass ungenau: Handy einige Male in Form einer Acht schwenken.");
    }
    if (S.recording || S.track.length > 1) detail += `\n${S.recording ? "● Aufzeichnung" : "Aufgezeichnet"}: ${fmtDist(S.trackLen)}`;
    setStatus(title, detail, warn.join("\n"));
  }

  // ------------------------------------------------------------------ Aufzeichnung, Menü, Dialoge

  function recordPoint(f) {
    if (f.acc > 35) return;
    const last = S.track[S.track.length - 1];
    if (last) {
      const d = distance(last[0], last[1], f.lat, f.lon);
      if (d < 8) return;
      S.trackLen += d;
    }
    S.track.push([f.lat, f.lon, f.time]);
    saveTrack();
    updateTrackSource();
  }
  async function setWakeLock(on) {
    try {
      if (on && "wakeLock" in navigator && !S.wakeLock) {
        S.wakeLock = await navigator.wakeLock.request("screen");
        S.wakeLock.addEventListener("release", () => { S.wakeLock = null; });
      } else if (!on && S.wakeLock) {
        await S.wakeLock.release();
        S.wakeLock = null;
      }
    } catch (e) { /* nicht unterstützt */ }
  }
  function toggleTracking() {
    S.recording = !S.recording;
    $("btnTrack").classList.toggle("rec", S.recording);
    if (S.recording) {
      setWakeLock(true);
      toast("Aufzeichnung läuft. Im Browser nur, solange die App geöffnet ist – der Bildschirm bleibt dafür an.");
    } else {
      setWakeLock(prefs.keepAwake);
      toast(`Aufzeichnung beendet (${fmtDist(S.trackLen)})`);
    }
    updateStatus(true);
  }

  function openSheet(build) {
    const box = $("sheetBox");
    box.innerHTML = "";
    build(box);
    $("sheet").hidden = false;
  }
  function closeSheet() { $("sheet").hidden = true; }
  function option(box, label, checked, onClick) {
    const row = el("label", "opt");
    if (checked != null) {
      const cb = el("input"); cb.type = "checkbox"; cb.checked = checked;
      cb.onchange = () => onClick(cb.checked);
      row.append(cb, document.createTextNode(label));
    } else {
      row.textContent = label;
      row.onclick = onClick;
    }
    box.appendChild(row);
  }

  function showMenu() {
    openSheet((box) => {
      box.appendChild(el("h3", null, "Rennsteig Offline"));
      option(box, "Kategorien auswählen …", null, () => { closeSheet(); showCategories(); });
      option(box, "Nur Empfehlungen zeigen", prefs.onlyTop, (v) => { prefs.onlyTop = v; savePrefs(); applyFilter(); });
      option(box, "Ganze Route zeigen", null, () => { closeSheet(); showWholeRoute(); });
      option(box, "Bildschirm anlassen", prefs.keepAwake, (v) => { prefs.keepAwake = v; savePrefs(); setWakeLock(v || S.recording); });
      option(box, "Gehrichtung: Blankenstein → Hörschel", prefs.reverse, (v) => { prefs.reverse = v; savePrefs(); updateStatus(true); updatePoiLive(); });
      option(box, "Aufgezeichneten Weg löschen", null, () => {
        if (confirm(`Der aufgezeichnete Weg (${fmtDist(S.trackLen)}) wird gelöscht.`)) { S.track = []; S.trackLen = 0; saveTrack(); updateTrackSource(); updateStatus(true); }
        closeSheet();
      });
      if (S.dataOutdated) option(box, "Neue Kartendaten laden …", null, () => { closeSheet(); showSetup(S.manifest); });
      option(box, "Über die App & Datenquellen", null, () => { closeSheet(); showAbout(); });
      const b = el("button", "btn secondary", "Schließen"); b.onclick = closeSheet; box.appendChild(b);
    });
  }
  function showCategories() {
    openSheet((box) => {
      box.appendChild(el("h3", null, "Was soll angezeigt werden?"));
      const counts = {};
      for (const p of S.pois) counts[p.cat] = (counts[p.cat] || 0) + 1;
      const sel = new Set(prefs.cats);
      for (const c of CATS) option(box, `${c.label} (${counts[c.key] || 0})`, sel.has(c.key), (v) => (v ? sel.add(c.key) : sel.delete(c.key)));
      const ok = el("button", "btn", "Übernehmen");
      ok.onclick = () => { prefs.cats = CATS.map((c) => c.key).filter((k) => sel.has(k)); savePrefs(); applyFilter(); closeSheet(); };
      box.appendChild(ok);
    });
  }
  function showAbout() {
    openSheet((box) => {
      box.appendChild(el("h3", null, "Über Rennsteig Offline"));
      const P = S.profile;
      [
        `Route: ${fmtKm(S.route.lengthM / 1000)} km von Hörschel nach Blankenstein, ↑ ${P.ascent_m} m ↓ ${P.descent_m} m. ` +
          `${S.pois.length} Orte entlang des Weges, Stand der Daten: ${S.dataDate}.`,
        "Alles funktioniert ohne Internet: Karte, Route, Orte, Höhenprofil, GPS und Kompass.",
        "Empfehlungen (★): OpenStreetMap enthält keine Bewertungen. Ausgewählt werden kuratierte Höhepunkte sowie je " +
          "Abschnitt die Orte mit den vollständigsten Angaben und der größten Nähe zum Weg. Öffnungszeiten können sich ändern.",
        "Kartendaten © OpenStreetMap-Mitwirkende (ODbL), Kartenschema OpenMapTiles, Höhendaten: Mapzen Terrain Tiles " +
          "(SRTM, EU-DEM/Copernicus u. a.). Kartenbibliothek MapLibre GL JS, PMTiles.",
        "Gut Runst!",
      ].forEach((t) => box.appendChild(el("p", null, t)));
      const b = el("button", "btn secondary", "Schließen"); b.onclick = closeSheet; box.appendChild(b);
    });
  }
  function showWholeRoute() {
    if (S.mode !== "FREE") setMode("FREE");
    const b = S.route.bounds();
    S.topPadding = 0;
    S.map.fitBounds([[b[0], b[1]], [b[2], b[3]]], { padding: 40, bearing: S.map.getBearing(), duration: 800 });
  }

  let toastTimer = 0;
  function toast(text) {
    let t = document.getElementById("toast");
    if (!t) {
      t = el("div");
      t.id = "toast";
      Object.assign(t.style, {
        position: "fixed", left: "50%", transform: "translateX(-50%)", bottom: "calc(env(safe-area-inset-bottom, 0px) + 90px)",
        background: "rgba(30,30,30,0.92)", color: "#fff", padding: "10px 14px", borderRadius: "10px", zIndex: 50,
        maxWidth: "86vw", fontSize: "14px", textAlign: "center",
      });
      document.body.appendChild(t);
    }
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, 4500);
  }

  function bindUi() {
    $("btnLocate").onclick = onLocateClicked;
    $("btnCompass").onclick = onCompassClicked;
    $("btnTrack").onclick = toggleTracking;
    $("btnMenu").onclick = showMenu;
    $("status").onclick = () => { startCompass(); if (S.fix) setMode(prefs.headingUp ? "HEADING" : "FOLLOW"); };
    $("btnProfile").onclick = () => {
      const show = $("profilePanel").hidden;
      $("profilePanel").hidden = !show;
      $("btnProfile").querySelector("svg").style.fill = show ? "#1f63d6" : "";
      profileKm = null;
      drawProfile();
    };
    $("btnList").onclick = () => { $("listPanel").hidden = false; renderList(); };
    $("btnListClose").onclick = () => { $("listPanel").hidden = true; };
    $("search").oninput = renderList;
    $("btnSort").onclick = () => { sortByDistance = !sortByDistance; renderList(); };
    $("btnCats").onclick = showCategories;
    $("cbTop").onchange = (e) => { prefs.onlyTop = e.target.checked; savePrefs(); applyFilter(); };
    $("sheet").onclick = (e) => { if (e.target === $("sheet")) closeSheet(); };
    const cv = $("profileCanvas");
    cv.addEventListener("touchstart", profileTouch, { passive: false });
    cv.addEventListener("touchmove", profileTouch, { passive: false });
    cv.addEventListener("mousedown", profileTouch);
    window.addEventListener("resize", () => { drawProfile(); S.cameraOnTarget = false; ensureFrame(); });
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "visible" && (prefs.keepAwake || S.recording)) setWakeLock(true);
    });
    if (prefs.keepAwake) setWakeLock(true);
  }

  // ------------------------------------------------------------------ Start

  async function boot() {
    if ("serviceWorker" in navigator) {
      try { await navigator.serviceWorker.register("sw.js"); } catch (e) { console.warn("Service Worker:", e); }
    }
    let manifest;
    try {
      manifest = await (await fetch("offline.json")).json();
    } catch (e) {
      $("setup").hidden = false;
      $("setupText").textContent = "Die App konnte nicht geladen werden. Bitte einmal mit Internetverbindung öffnen.";
      return;
    }
    const cacheName = await findDataCache(manifest);
    if (cacheName) await start(manifest, cacheName);
    else showSetup(manifest);
    if (S.dataOutdated) toast("Neue Kartendaten verfügbar – im Menü ⋮ laden.");
  }
  window.addEventListener("error", (e) => console.error("Fehler:", e.message));
  // Safari ignoriert user-scalable=no: Seitenzoom unterbinden (die Karte zoomt über Touch-Ereignisse)
  document.addEventListener("gesturestart", (e) => e.preventDefault());
  boot();
})();
