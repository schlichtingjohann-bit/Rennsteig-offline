/* Service Worker: hält App und Kartendaten offline vor. */
const VERSION = "__VERSION__";
const SHELL = "rennsteig-shell-" + VERSION;
const DATA_PREFIX = "rennsteig-data-";

self.addEventListener("install", (event) => {
  event.waitUntil((async () => {
    const res = await fetch("offline.json", { cache: "no-store" });
    const manifest = await res.json();
    const cache = await caches.open(SHELL);
    await cache.put("offline.json", new Response(JSON.stringify(manifest), { headers: { "Content-Type": "application/json" } }));
    // in Paketen laden, damit langsame Verbindungen nicht an zu vielen gleichzeitigen Anfragen scheitern
    const files = manifest.shell;
    for (let i = 0; i < files.length; i += 24) {
      await Promise.all(files.slice(i, i + 24).map(async (f) => {
        const r = await fetch(f, { cache: "no-store" });
        if (!r.ok) throw new Error("Download fehlgeschlagen: " + f);
        await cache.put(f, r);
      }));
    }
    await self.skipWaiting();
  })());
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) {
      if (key.startsWith("rennsteig-shell-") && key !== SHELL) await caches.delete(key);
    }
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  const url = new URL(req.url);
  // Kartendateien lädt und liest app.js selbst aus ihrem Speicher (DATA_PREFIX)
  if (req.method !== "GET" || url.origin !== self.location.origin || url.pathname.includes("/maps/")) return;
  event.respondWith((async () => {
    const cache = await caches.open(SHELL);
    const hit = await cache.match(req, { ignoreSearch: true });
    if (hit) return hit;
    try {
      return await fetch(req);
    } catch (e) {
      if (req.mode === "navigate") {
        const shell = await cache.match("./");
        if (shell) return shell;
      }
      throw e;
    }
  })());
});
