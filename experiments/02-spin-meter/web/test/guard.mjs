// ballisticRule: the check that makes measuring along the live tracker's own track safe.
// A real ball's track must pass; a hand, an arm, or a ball merged with the paddle must not.
// Real tracks come from spinsim.py's ground truth (gravity, so a true parabola), with
// tracker-like jitter added; the rest are built here.
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import http from 'http'; import fs from 'fs'; import path from 'path';
import { fileURLToPath } from 'url';
const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const clips = process.argv.slice(2);

const srv = http.createServer((q, s) => {
  const n = decodeURIComponent(q.url.split('?')[0]).replace(/^\//, '');
  if (n === '') { s.setHeader('content-type', 'text/html'); return s.end('<!doctype html><script src="engine.js"></script>'); }
  fs.createReadStream(path.join(WEB, n)).on('error', () => { s.statusCode = 404; s.end(); }).pipe(s);
}).listen(0);
await new Promise(r => srv.once('listening', r));
const b = await chromium.launch({ args: ['--no-sandbox'] });
const p = await b.newPage();
await p.goto(`http://127.0.0.1:${srv.address().port}/`);
await p.waitForFunction(() => typeof window.SpinMeter !== 'undefined');

// deterministic jitter, so the test gives the same answer every run
let seed = 7; const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff - 0.5;
const jitter = (tr, px) => tr.map(q => [q[0], q[1] + 2 * px * rnd(), q[2] + 2 * px * rnd(), q[3]]);
const half = tr => tr.map(q => [q[0], q[1] * 0.5, q[2] * 0.5, q[3] * 0.5]);

const cases = [];
for (const f of clips) {
  const t = JSON.parse(fs.readFileSync(f.replace(/\.mp4$/, '.json'), 'utf8')).track;
  const name = path.basename(f, '.mp4');
  cases.push({ name: `${name} (ball)`, track: half(t), want: null });
  cases.push({ name: `${name} +1px jitter`, track: jitter(half(t), 1), want: null });
}
const base = cases[0].track;                       // a real flight, to build the fakes from
const n = base.length, r = base[0][3];
cases.push({ name: 'hand: slides and comes back', want: 'reject',
  track: Array.from({ length: n }, (_, k) => [k, 60 + 40 * Math.sin(k / 7), 120 + 3 * Math.sin(k / 4), r * 2.2]) });
cases.push({ name: 'arm: climbs harder and harder', want: 'reject',
  track: Array.from({ length: n }, (_, k) => [k, 40 + 3 * k, 300 - 0.06 * k * k, r]) });
cases.push({ name: 'ball merged with the paddle at the hit', want: 'reject',
  track: base.map((q, k) => (k < Math.ceil(0.3 * n) ? [q[0], q[1], q[2], q[3] * 2.4] : q)) });
cases.push({ name: 'jitter far beyond a ball (3 px)', want: 'reject', track: jitter(half(base.map(q => [q[0], q[1] * 2, q[2] * 2, q[3] * 2])), 6) });

const out = await p.evaluate((cs) => cs.map(c => ({ name: c.name, want: c.want,
  got: window.SpinMeter._internals.ballisticRule(c.track, 240) })), cases);

let bad = 0;
for (const c of out) {
  const pass = c.want === null ? c.got === null : c.got !== null;
  if (!pass) bad++;
  console.log(`${pass ? 'ok  ' : 'FAIL'}  ${c.name.padEnd(38)} ${c.want === null ? 'expect a ball' : 'expect rejected'} -> ${c.got === null ? 'ball' : '"' + c.got + '"'}`);
}
console.log(`\n${out.length - bad}/${out.length} as expected`);
await b.close(); srv.close();
process.exit(bad ? 1 : 0);
