// Testet die Web-App wie auf einem iPhone (WebKit) und macht Bildschirmfotos.
// Aufruf: node web_screenshots.js <web-ordner> <ausgabeordner> <breitengrad> <längengrad>
// Für den Offline-Test wird der Webserver abgeschaltet und die App neu geladen.
const { webkit, devices } = require('playwright');
const { spawn } = require('child_process');
const [webDir, out, lat, lon] = process.argv.slice(2);
const url = 'http://localhost:8080/';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const server = spawn('python3', ['-m', 'http.server', '8080'], { cwd: webDir, stdio: 'ignore' });

(async () => {
  const browser = await webkit.launch();
  const ctx = await browser.newContext({
    ...devices['iPhone 14'], deviceScaleFactor: 2, locale: 'de-DE',
    geolocation: { latitude: +lat, longitude: +lon, accuracy: 8 }, permissions: ['geolocation'],
  });
  const p = await ctx.newPage();
  p.on('console', (m) => { if (['error', 'warning'].includes(m.type())) console.log('Konsole:', m.text()); });
  p.on('pageerror', (e) => console.log('Fehler auf der Seite:', e.message));
  const shot = async (name) => { await p.screenshot({ path: `${out}/${name}.png` }); console.log('Bild', name); };
  const mapReady = async () => {
    await p.waitForFunction(() => window.rennsteig && rennsteig.map && rennsteig.map.loaded() && rennsteig.map.areTilesLoaded(),
      null, { timeout: 90000 });
    await sleep(1000);
  };
  // iOS liefert den Kompasskurs als webkitCompassHeading (magnetisch)
  const compass = async (deg) => {
    for (let i = 0; i < 30; i++) {
      await p.evaluate((d) => {
        const e = new Event('deviceorientation');
        Object.defineProperty(e, 'webkitCompassHeading', { value: d });
        Object.defineProperty(e, 'webkitCompassAccuracy', { value: 10 });
        window.dispatchEvent(e);
      }, deg);
      await sleep(50);
    }
  };
  const state = () => p.evaluate(() => ({
    orientation: window.orientation, screenAngle: screen.orientation && screen.orientation.angle, mode: rennsteig.mode, bearing: Math.round(rennsteig.map.getBearing()), zoom: +rennsteig.map.getZoom().toFixed(2),
    title: document.getElementById('statusTitle').textContent, detail: document.getElementById('statusDetail').textContent,
  }));

  await sleep(1000);
  await p.goto(url);
  await p.waitForSelector('#setup:not([hidden])');
  await shot('web-01-installieren');
  await p.click('#setupButtons button');
  await p.waitForSelector('#setup', { state: 'hidden', timeout: 180000 });
  await mapReady();
  await p.click('#btnLocate'); // erstes Antippen: Kompass freigeben und der Position folgen
  await sleep(500);
  if ((await state()).mode !== 'HEADING') await p.click('#btnLocate');
  await compass(55);
  await sleep(2500);
  await mapReady();
  console.log('Karte:', await state());
  await shot('web-02-karte');

  await p.click('#btnList');
  await p.fill('#search', 'Inselsberg');
  await sleep(400);
  await shot('web-03-liste');
  await p.click('#list .item');
  await sleep(1500);
  await mapReady();
  await shot('web-04-ort');
  await p.click('#poiCard .close');

  await p.click('#btnProfile');
  await sleep(600);
  await shot('web-05-profil');
  await p.click('#btnProfile');

  try {
    server.kill();
    await sleep(500);
    await p.reload();
    await mapReady();
    await compass(120);
    await sleep(2500);
    console.log('Offline:', await state());
    await shot('web-06-offline');
  } catch (e) {
    console.log('Offline-Test nicht möglich:', e.message);
  }
  await browser.close();
  server.kill();
})().catch((e) => { console.error(e); server.kill(); process.exit(1); });
