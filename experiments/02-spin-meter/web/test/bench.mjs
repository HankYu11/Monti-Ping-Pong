// Run engine.js over clips in headless Chromium, exactly as the app's WebView does
// (WebCodecs decode + workers), and report BOTH accuracy against ground truth AND
// wall-clock time. Ground truth comes from spinsim.py, which writes <clip>.json.
//
//   node test/bench.mjs clip1.mp4 clip2.mp4 ...
//   node test/bench.mjs --json out.json --opts '{"useTrack":true}' clips/*.mp4
//
// Exists because the project had no way to tell whether a change makes readings
// better or worse: synthetic clips have ground truth, so here a change is measurable.
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import http from 'http'; import fs from 'fs'; import path from 'path';
import { fileURLToPath } from 'url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(HERE, '..');
const MP4BOX = process.env.MP4BOX ||
  path.resolve(WEB, '../../04-live-coach/android/app/src/main/assets/mp4box.all.min.js');

const argv = process.argv.slice(2);
let jsonOut = null, opts = {}, reps = 1, files = [];
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--json') jsonOut = argv[++i];
  else if (argv[i] === '--opts') opts = JSON.parse(argv[++i]);
  else if (argv[i] === '--reps') reps = +argv[++i];
  else files.push(argv[i]);
}
if (!files.length) { console.error('usage: node test/bench.mjs [--json out] [--opts {}] [--reps n] clip.mp4...'); process.exit(2); }

const PAGE = `<!doctype html><meta charset=utf-8><title>bench</title>
<script src="mp4box.all.min.js"></script><script src="engine.js"></script>`;

const srv = http.createServer((q, s) => {
  const name = decodeURIComponent(q.url.split('?')[0]).replace(/^\//, '');
  if (name === '' || name === 'index.html') { s.setHeader('content-type', 'text/html; charset=utf-8'); return s.end(PAGE); }
  const f = name === 'mp4box.all.min.js' ? MP4BOX : path.join(WEB, name);
  s.setHeader('content-type', f.endsWith('.js') ? 'application/javascript' : 'application/octet-stream');
  fs.createReadStream(f).on('error', () => { s.statusCode = 404; s.end(); }).pipe(s);
}).listen(0);
await new Promise(r => srv.once('listening', r));
const PORT = srv.address().port;

const browser = await chromium.launch({ args: ['--no-sandbox'] });
const page = await browser.newPage();
page.on('pageerror', e => console.log('page error:', e.message));
page.on('console', m => { if (m.type() === 'error') console.log('console error:', m.text()); });
await page.goto(`http://127.0.0.1:${PORT}/`);
await page.waitForFunction(() => typeof window.SpinMeter !== 'undefined' && typeof window.MP4Box !== 'undefined');

const rows = [];
for (const f of files) {
  const bytes = fs.readFileSync(f);
  let truth = null;
  const tj = f.replace(/\.mp4$/, '.json');
  if (fs.existsSync(tj)) truth = JSON.parse(fs.readFileSync(tj, 'utf8'));
  const runs = [];
  for (let k = 0; k < reps; k++) {
    const r = await page.evaluate(async ({ b64, name, opts }) => {
      const bin = atob(b64), arr = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
      const file = new File([arr], name, { type: 'video/mp4' });
      const t0 = performance.now();
      try {
        const out = await window.SpinMeter.analyze(file, Object.assign({ captureFps: 240 }, opts));
        return { ms: performance.now() - t0, rps: out.rps, axis: out.axis, reliable: out.reliable,
                 spinType: out.spinType, marking: out.marking, nFrames: out.nFrames, fit: out.fit,
                 error: out.error || null, time: out.meta && out.meta.debug && out.meta.debug.time || null };
      } catch (e) { return { ms: performance.now() - t0, error: String(e && e.message || e) }; }
    }, { b64: bytes.toString('base64'), name: path.basename(f), opts });
    runs.push(r);
  }
  const best = runs.reduce((a, b) => (a.ms <= b.ms ? a : b));
  const row = { file: path.basename(f), ...best, msAll: runs.map(r => +r.ms.toFixed(0)) };
  if (truth) {
    row.trueRps = truth.rps ?? truth.true_rps ?? null;
    row.trueAxis = truth.axis ?? null;
    if (row.trueRps != null && row.rps != null) row.errRps = +(Math.abs(row.rps) - Math.abs(row.trueRps)).toFixed(2);
    if (row.trueAxis && row.axis) {
      const n = v => { const m = Math.hypot(...v) || 1; return v.map(x => x / m); };
      const a = n(row.axis), b = n(row.trueAxis);
      const d = Math.abs(a[0]*b[0] + a[1]*b[1] + a[2]*b[2]);
      row.errAxisDeg = +(Math.acos(Math.min(1, d)) * 180 / Math.PI).toFixed(1);
    }
  }
  rows.push(row);
  const t = row.trueRps != null ? `true ${(+row.trueRps).toFixed(1)}` : 'true -';
  const got = row.error ? `ERROR ${row.error.slice(0, 48)}` :
    `${row.rps.toFixed(2)} rps  d=${row.errRps ?? '-'}  axis=${row.errAxisDeg ?? '-'}deg  ${row.reliable ? 'clear' : 'low  '}`;
  console.log(`${row.file.padEnd(34)} ${t.padEnd(10)} ${got.padEnd(52)} ${(row.ms / 1000).toFixed(2)}s`);
}

if (jsonOut) fs.writeFileSync(jsonOut, JSON.stringify(rows, null, 2));
const ok = rows.filter(r => !r.error && r.errRps != null);
if (ok.length) {
  const abs = ok.map(r => Math.abs(r.errRps)).sort((a, b) => a - b);
  const med = abs[abs.length >> 1];
  console.log(`\n${ok.length} with ground truth | median |err| ${med.toFixed(2)} rps | max ${Math.max(...abs).toFixed(2)} rps`);
}
const tot = rows.reduce((s, r) => s + r.ms, 0);
console.log(`${rows.length} clips | total ${(tot / 1000).toFixed(1)}s | mean ${(tot / rows.length / 1000).toFixed(2)}s/clip`);
await browser.close(); srv.close();
