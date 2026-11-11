// Which way the ball drills, not just how much. The engine reports gyro signed: positive means
// the spin points along the flight, away down the table, which is what decides whether the ball
// skids right or left off the bounce. Nothing downstream can check that for itself, so this does.
//
//   node test/gyrosign.mjs clip.mp4 ...        (needs <clip>.json from spinsim.py)
//   bash test/make_gyro_clips.sh /tmp/gyro && node test/gyrosign.mjs /tmp/gyro/*.mp4
//
// The truth gyro is the true spin projected on the true flight direction, both from spinsim's
// own JSON, so a sign dropped or flipped anywhere between the fit and the result shows up here.
// What this canNOT check: the colour-dot path. spinsim renders one grey channel, so every clip
// reads with the half marking. On a half-marked ball the engine can misjudge front from back
// (see mirrored() in engine.js) and the sign goes with it; only the dots settle that, and only
// real footage has them.
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import http from 'http'; import fs from 'fs'; import path from 'path';
import { fileURLToPath } from 'url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(HERE, '..');
const MP4BOX = process.env.MP4BOX ||
  path.resolve(WEB, '../../04-live-coach/android/app/src/main/assets/mp4box.all.min.js');
const files = process.argv.slice(2);
if (!files.length) { console.error('usage: node test/gyrosign.mjs clip.mp4...'); process.exit(2); }

const PAGE = `<!doctype html><meta charset=utf-8><title>gyrosign</title>
<script src="mp4box.all.min.js"></script><script src="engine.js"></script>`;
const srv = http.createServer((q, s) => {
  const n = decodeURIComponent(q.url.split('?')[0]).replace(/^\//, '');
  if (!n || n === 'index.html') { s.setHeader('content-type', 'text/html; charset=utf-8'); return s.end(PAGE); }
  const f = n === 'mp4box.all.min.js' ? MP4BOX : path.join(WEB, n);
  s.setHeader('content-type', f.endsWith('.js') ? 'application/javascript' : 'application/octet-stream');
  fs.createReadStream(f).on('error', () => { s.statusCode = 404; s.end(); }).pipe(s);
}).listen(0);
await new Promise((r) => srv.once('listening', r));
const browser = await chromium.launch({ args: ['--no-sandbox'] });
const page = await browser.newPage();
page.on('pageerror', (e) => console.log('page error:', e.message));
await page.goto(`http://127.0.0.1:${srv.address().port}/`);
await page.waitForFunction(() => window.SpinMeter && window.MP4Box);

console.log(`${'clip'.padEnd(14)}${'true gyro'.padEnd(12)}${'measured'.padEnd(11)}${'rps'.padEnd(8)}marking  sign`);
let bad = 0, seen = 0;
for (const mp4 of files) {
  const name = path.basename(mp4, '.mp4');
  const truth = JSON.parse(fs.readFileSync(mp4.replace(/\.mp4$/, '.json'), 'utf8'));
  // the flight direction from the true track, in the camera axes spinsim renders in
  const t = truth.track, a = t[0], z = t[t.length - 1];
  const d = [z[1] - a[1], z[2] - a[2], 0], L = Math.hypot(d[0], d[1]) || 1;
  const want = truth.rps * (truth.axis[0] * d[0] + truth.axis[1] * d[1]) / L;
  const bytes = fs.readFileSync(mp4);
  const r = await page.evaluate(async ({ b64, nm }) => {
    const bin = atob(b64), arr = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    try {
      const out = await window.SpinMeter.analyze(new File([arr], nm, { type: 'video/mp4' }),
        { captureFps: 240, serveDir: 'any', single: true, robust: true });
      const sv = (out.serves || [])[0] || out;
      return { rps: sv.rps, gyro: sv.gyro, marking: sv.marking, reliable: !!sv.reliable,
               mirrored: !!sv.mirrored, error: sv.error || out.error || null };
    } catch (e) { return { error: String((e && e.message) || e) }; }
  }, { b64: bytes.toString('base64'), nm: path.basename(mp4) });

  if (r.error) { console.log(`${name.padEnd(14)}${want.toFixed(1).padEnd(12)}${'ERROR'.padEnd(11)}`); console.log(`   ${r.error}`); bad++; continue; }
  // a clip with almost no gyro has no direction worth checking
  if (Math.abs(want) < 3) { console.log(`${name.padEnd(14)}${want.toFixed(1).padEnd(12)}${r.gyro.toFixed(1).padEnd(11)}${r.rps.toFixed(1).padEnd(8)}${(r.marking||'?').padEnd(9)}skipped (no gyro to speak of)`); continue; }
  seen++;
  const ok = Math.sign(r.gyro) === Math.sign(want);
  if (!ok) bad++;
  console.log(`${name.padEnd(14)}${want.toFixed(1).padEnd(12)}${r.gyro.toFixed(1).padEnd(11)}${r.rps.toFixed(1).padEnd(8)}` +
    `${(r.marking || '?').padEnd(9)}${ok ? 'ok' : 'WRONG WAY'}${r.mirrored ? ' (mirrored)' : ''}${r.reliable ? '' : ' (low)'}`);
}
await browser.close(); srv.close();
console.log(`\n${seen - bad}/${seen} drilled the way they were spun`);
process.exit(bad ? 1 : 0);
