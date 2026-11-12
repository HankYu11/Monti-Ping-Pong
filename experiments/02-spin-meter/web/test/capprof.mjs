// CPU-profile SpinMeter.measureCapture on a .cap, exactly as the WebView runs it, and say where
// the time goes. Exists because the engine's share of a serve's wait was argued about for months
// without anyone looking: it turned out the spin maths is 6% of it and per-pixel work is the rest.
//
//   node test/capprof.mjs serve.cap [reps]
//
// Chromium's own sampling profiler, so no instrumentation and no guessing. Self time says which
// function burns the CPU; inclusive time says which stage owns it. A .cap of a real serve can be
// rebuilt from a session's saved clip with test/capfromclip.py.
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import http from 'http'; import fs from 'fs'; import path from 'path';
import { fileURLToPath } from 'url';
const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const file = path.resolve(process.argv[2]);
const reps = +(process.argv[3] || 1);

const srv = http.createServer((q, s) => {
  const n = decodeURIComponent(q.url.split('?')[0]).replace(/^\//, '');
  if (n === '') { s.setHeader('content-type', 'text/html'); return s.end('<!doctype html><script src="engine.js"></script>'); }
  const f = n === 'cap' ? file : path.join(WEB, n);
  fs.createReadStream(f).on('error', () => { s.statusCode = 404; s.end(); }).pipe(s);
}).listen(0);
await new Promise(r => srv.once('listening', r));
const base = `http://127.0.0.1:${srv.address().port}`;
const browser = await chromium.launch({ args: ['--no-sandbox'] });
const page = await browser.newPage();
page.on('pageerror', e => console.log('page error:', e.message));
await page.goto(base + '/');
await page.waitForFunction(() => typeof window.SpinMeter !== 'undefined');

await page.evaluate(async (url) => {
  const b = new Uint8Array(await (await fetch(url)).arrayBuffer());
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const hLen = dv.getInt32(0);
  const head = JSON.parse(new TextDecoder().decode(b.subarray(4, 4 + hLen)));
  let o = 4 + hLen;
  const cw = head.rect[2], ch = head.rect[3];
  const frameBytes = cw * ch + 2 * ((cw / 2) * (ch / 2));
  const cap = Object.assign({}, head, { frames: [] });
  for (let i = 0; i < head.n; i++) { cap.frames.push(b.subarray(o, o + frameBytes)); o += frameBytes; }
  window.__cap = cap;
}, base + '/cap');

const cdp = await page.context().newCDPSession(page);
await cdp.send('Profiler.enable');
await cdp.send('Profiler.setSamplingInterval', { interval: 80 });
// warm up once so JIT state matches a steady run
const warm = await page.evaluate(async () => {
  const t = performance.now();
  const r = await window.SpinMeter.measureCapture(window.__cap, { useTrack: true, single: true, serveDir: 'any', robust: true });
  return { ms: performance.now() - t, rps: r.rps };
});
await cdp.send('Profiler.start');
const runs = [];
for (let i = 0; i < reps; i++) {
  runs.push(await page.evaluate(async () => {
    const t = performance.now();
    const r = await window.SpinMeter.measureCapture(window.__cap, { useTrack: true, single: true, serveDir: 'any', robust: true });
    return { ms: performance.now() - t, rps: r.rps };
  }));
}
const { profile } = await cdp.send('Profiler.stop');

const byId = new Map(profile.nodes.map(n => [n.id, n]));
const self = new Map();
let total = 0;
for (let i = 0; i < profile.samples.length; i++) {
  const dt = profile.timeDeltas[i] || 0; total += dt;
  const n = byId.get(profile.samples[i]); if (!n) continue;
  const cf = n.callFrame;
  const key = `${cf.functionName || '(anon)'}  ${(cf.url || '').split('/').pop()}:${cf.lineNumber + 1}`;
  self.set(key, (self.get(key) || 0) + dt);
}
// roll up a node's total (self + descendants) too
const parent = new Map();
for (const n of profile.nodes) for (const c of n.children || []) parent.set(c, n.id);
const selfById = new Map();
for (let i = 0; i < profile.samples.length; i++) selfById.set(profile.samples[i], (selfById.get(profile.samples[i]) || 0) + (profile.timeDeltas[i] || 0));
const totalById = new Map();
for (const [id, t] of selfById) { let c = id; const seen = new Set(); while (c && !seen.has(c)) { seen.add(c); totalById.set(c, (totalById.get(c) || 0) + t); c = parent.get(c); } }
const nameTotal = new Map();
for (const [id, t] of totalById) { const n = byId.get(id); if (!n) continue; const k = n.callFrame.functionName || '(anon)'; nameTotal.set(k, Math.max(nameTotal.get(k) || 0, t)); }

console.log(`runs: ${runs.map(r => r.ms.toFixed(0)).join(', ')} ms   (warm-up ${warm.ms.toFixed(0)} ms)   rps ${runs[0].rps.toFixed(2)}`);
console.log(`profiled wall ${(total / 1000).toFixed(0)} ms over ${reps} run(s) -> ${(total / 1000 / reps).toFixed(0)} ms/run\n`);
console.log('SELF TIME (where the CPU actually sits)');
console.log('  ms/run   %  function');
for (const [k, v] of [...self].sort((a, b) => b[1] - a[1]).slice(0, 22))
  console.log(`  ${(v / 1000 / reps).toFixed(0).padStart(6)}  ${(100 * v / total).toFixed(1).padStart(5)}  ${k}`);
console.log('\nINCLUSIVE TIME (self + everything it calls)');
console.log('  ms/run   %  function');
for (const [k, v] of [...nameTotal].sort((a, b) => b[1] - a[1]).slice(0, 20))
  if (k && k !== '(anon)' && k !== '(root)' && k !== '(program)' && k !== '(garbage collector)' && k !== '(idle)')
    console.log(`  ${(v / 1000 / reps).toFixed(0).padStart(6)}  ${(100 * v / total).toFixed(1).padStart(5)}  ${k}`);
await browser.close(); srv.close();
