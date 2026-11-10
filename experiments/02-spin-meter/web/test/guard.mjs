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
// the made-up tracks must not depend on how many clips were passed, so the jitter starts
// from the same place every run
seed = 7;
const base = cases[0].track;                       // a real flight, to build the fakes from
const n = base.length, r = base[0][3];
cases.push({ name: 'hand: slides and comes back', want: 'reject',
  track: Array.from({ length: n }, (_, k) => [k, 60 + 40 * Math.sin(k / 7), 120 + 3 * Math.sin(k / 4), r * 2.2]) });
cases.push({ name: 'arm: climbs harder and harder', want: 'reject',
  track: Array.from({ length: n }, (_, k) => [k, 40 + 3 * k, 300 - 0.06 * k * k, r]) });
cases.push({ name: 'ball merged with the paddle at the hit', want: 'reject',
  track: base.map((q, k) => (k < Math.ceil(0.3 * n) ? [q[0], q[1], q[2], q[3] * 2.4] : q)) });
cases.push({ name: 'jitter far beyond a ball', want: 'reject', track: jitter(base, 12) });

const out = await p.evaluate((cs) => cs.map(c => ({ name: c.name, want: c.want,
  got: window.SpinMeter._internals.ballisticRule(c.track, 240) })), cases);

// --- a serve into the net ------------------------------------------------------------
// On stretches the engine really measured on the phone, not shapes made up here: the net ball
// is the one the app spoke as a clear 40.9 rev/s.
const real = JSON.parse(fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), 'fixtures/real-segments.json'), 'utf8'));
const netOut = await p.evaluate((cases) => cases.map((c) => ({
  name: c.name, isServe: c.isServe,
  fell: window.SpinMeter._internals.stoppedAndFell({ segment: { track: c.track }, flight: c.flight }),
})), Object.entries(real).filter(([k]) => !k.startsWith('_')).map(([k, v]) => ({ name: k, ...v })));
let netBad = 0;
for (const c of netOut) {
  const pass = c.isServe ? !c.fell : c.fell;
  if (!pass) netBad++;
  console.log(`${pass ? 'ok  ' : 'FAIL'}  ${c.name.padEnd(38)} ${(c.isServe ? 'a real serve' : 'into the net').padEnd(30)} -> ${c.fell ? 'stopped and fell' : 'a flight'}`);
}

// --- which stretch of the flight a handed-in track gets measured on -------------------
// build() works on tracks alone, so this needs no pixels. A serve's own spin is the stretch
// off the paddle; after the bounce the ball carries less. A capture whose tracks only begin
// after the bounce must not put the after-bounce spin up as the serve's (real capture M6).
const seg = await p.evaluate(() => {
  const build = window.SpinMeter._internals.serveBuilder(480, 270, 4, 60, true);  // from the tracker
  const arc = (from, n, x0, y0, vx, vy, r = 6) =>
    Array.from({ length: n }, (_, k) => [from + k, x0 + vx * k, y0 + vy * k + 0.004 * k * k, r]);
  const kinds = (b) => (b ? b.parts.map((q) => q.kind) : null);
  const out = (b) => (b ? { kinds: b.parts.map((q) => q.kind), afterOnly: !!b.afterBounceOnly } : null);
  // a flight that bounces inside the track: off the paddle, then after the bounce
  const withBounce = [...arc(60, 40, 100, 300, 6, 2), ...arc(100, 40, 340, 400, 6, -2)];
  // the same flight, but the track only starts after the bounce (the M6 shape): a near track
  // falls to the table at about frame 68 and comes back up, and the pick carries on from there
  const nearBounce = [...arc(60, 9, 100, 622, 6, 4.5), ...arc(69, 24, 154, 656, 6, -3)];
  const pickAfter = arc(90, 50, 330, 590, 6, -2);
  // a flight with no bounce anywhere: it must stay a plain flight
  const plain = arc(60, 55, 100, 260, 6, 1);
  // the bounce is inside the track, but too few frames before it to measure: only the
  // after-bounce stretch gets made. The engine's own search has nothing better in that
  // footage, so this must NOT be marked as a track that missed the serve.
  const shortBefore = [...arc(80, 5, 390, 282, 6, 2.2), ...arc(85, 35, 430, 295, 6, -1.6)];
  // The ball merged with the paddle at the hit: one point far too big, low on the screen, and
  // four frames before the next one. It must go BEFORE the frames between are filled in — the
  // fill drags its neighbours toward it and they are ball-sized, so nothing later can see the
  // problem. So this case runs the real pipeline: drop, then fill, then build.
  const I2 = window.SpinMeter._internals;
  const fill = (t) => {              // what measureCapture does between the tracker's points
    const o = [];
    for (let k = 0; k < t.length; k++) {
      o.push(t[k]);
      const a = t[k], b = t[k + 1];
      if (b) for (let f = a[0] + 1; f < b[0]; f++) { const u = (f - a[0]) / (b[0] - a[0]);
        o.push([f, a[1] + u * (b[1] - a[1]), a[2] + u * (b[2] - a[2]), a[3] + u * (b[3] - a[3])]); }
    }
    return o;
  };
  const cleanHit = [...arc(64, 21, 300, 250, 6, 2.2), ...arc(85, 35, 430, 295, 6, -1.6)];
  const mergedRaw = [[60, 280, 279, 14], ...cleanHit];      // the merge, 4 frames before the rest
  const mergedHit = fill(I2.dropMerged(mergedRaw));
  const mergedUnfixed = fill(mergedRaw);                     // what it did before: the fill drags 61-63
  return {
    withBounce: out(build(withBounce, [withBounce])),
    afterBounce: out(build(pickAfter, [pickAfter, nearBounce])),
    plain: out(build(plain, [plain])),
    shortBefore: out(build(shortBefore, [shortBefore])),
    mergedHit: out(build(mergedHit, [mergedHit])),
    mergedUnfixed: out(build(mergedUnfixed, [mergedUnfixed])),
    droppedPoints: mergedRaw.length - I2.dropMerged(mergedRaw).length,
  };
});
const segCases = [
  { name: 'bounce inside the track', got: seg.withBounce, expect: 'off the paddle, not flagged',
    want: (o) => o && o.kinds[0] === 'paddle' && !o.afterOnly },
  { name: 'track starts after the bounce', got: seg.afterBounce, expect: 'after the bounce, FLAGGED',
    want: (o) => o && o.kinds[0] === 'bounce' && o.afterOnly },
  { name: 'no bounce anywhere', got: seg.plain, expect: 'a plain flight',
    want: (o) => o && o.kinds[0] === 'flight' && !o.afterOnly },
  { name: 'too few frames before the bounce', got: seg.shortBefore, expect: 'after the bounce, NOT flagged',
    want: (o) => o && o.kinds[0] === 'bounce' && !o.afterOnly },
  { name: 'ball merged with the paddle at the hit', got: seg.mergedHit, expect: 'off the paddle',
    want: (o) => o && o.kinds[0] === 'paddle' },
];
// the merge must be dropped from the tracker's own points, not after the fill
if (seg.droppedPoints !== 1) { console.log(`FAIL  dropMerged removed ${seg.droppedPoints} points, expected 1`); }
else console.log('ok    dropMerged removes the merged point      from the tracker\'s own points -> 1 dropped');
if (seg.mergedUnfixed && seg.mergedUnfixed.kinds[0] === 'paddle')
  console.log('note  dropping after the fill would also have worked on this case; it does not on real serve 3');
let bad = 0;
for (const c of segCases) {
  const pass = c.want(c.got);
  if (!pass) bad++;
  console.log(`${pass ? 'ok  ' : 'FAIL'}  ${c.name.padEnd(38)} ${c.expect.padEnd(30)} -> ${c.got ? c.got.kinds.join(', ') + (c.got.afterOnly ? '  [flagged]' : '') : 'nothing built'}`);
}
for (const c of out) {
  const pass = c.want === null ? c.got === null : c.got !== null;
  if (!pass) bad++;
  console.log(`${pass ? 'ok  ' : 'FAIL'}  ${c.name.padEnd(38)} ${c.want === null ? 'expect a ball' : 'expect rejected'} -> ${c.got === null ? 'ball' : '"' + c.got + '"'}`);
}
const total = out.length + segCases.length + 1 + netOut.length;
bad += netBad;
if (seg.droppedPoints !== 1) bad++;
console.log(`\n${total - bad}/${total} as expected`);
await b.close(); srv.close();
process.exit(bad ? 1 : 0);
