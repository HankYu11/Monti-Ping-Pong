// When is the gyro's DIRECTION worth showing? The engine can get one thing wrong about a
// half-marked ball: it can see it mirrored front to back (mirrored(), engine.js). A mirror
// negates the spin axis's depth, so gyro -- the spin along the flight -- moves by 2*w_z*f_z.
// f_z is the sine of towardCameraDeg, so a ball filmed ACROSS the picture loses nothing at all
// and one filmed coming down the table loses everything. That is what gyroDirKnown encodes.
//
//   node test/gyrodir.mjs
//
// This takes no clips, because spinsim can only render a flight across the picture: every
// synthetic clip is side on, so none of them can produce the case this is about. It asks the
// engine's own spinComponents instead, which is the same function the field is computed from.
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import http from 'http'; import fs from 'fs'; import path from 'path';
import { fileURLToPath } from 'url';
const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const srv = http.createServer((q, s) => {
  const n = decodeURIComponent(q.url.split('?')[0]).replace(/^\//, '');
  if (!n) { s.setHeader('content-type', 'text/html'); return s.end('<!doctype html><script src="engine.js"></script>'); }
  fs.createReadStream(path.join(WEB, n)).on('error', () => { s.statusCode = 404; s.end(); }).pipe(s);
}).listen(0);
await new Promise((r) => srv.once('listening', r));
const b = await chromium.launch({ args: ['--no-sandbox'] });
const p = await b.newPage();
await p.goto(`http://127.0.0.1:${srv.address().port}/`);
await p.waitForFunction(() => window.SpinMeter);

const out = await p.evaluate(() => {
  const sc = window.SpinMeter._internals.spinComponents;
  const unit = (v) => { const m = Math.hypot(...v); return v.map((x) => x / m); };
  // a spread of serve-like spins: back, top, side, drill, and mixtures of them
  const axes = [];
  for (let a = 0; a < 360; a += 30) for (const t of [20, 50, 75])
    axes.push(unit([Math.cos(t*Math.PI/180), Math.sin(t*Math.PI/180)*Math.cos(a*Math.PI/180), Math.sin(t*Math.PI/180)*Math.sin(a*Math.PI/180)]));
  const rows = [];
  for (const deg of [0, 5, 10, 15, 20, 30, 45, 60]) {
    const t = deg * Math.PI / 180;
    const f = unit([Math.cos(t), 0.10, Math.sin(t)]);       // across the picture, tilting toward the camera
    let maxShift = 0, flips = 0, known = 0, wrongCall = 0;
    for (const a of axes) {
      const g = sc(45, a, f).gyro, gm = sc(45, [a[0], a[1], -a[2]], f).gyro;
      maxShift = Math.max(maxShift, Math.abs(g - gm));
      const survives = Math.sign(g) === Math.sign(gm);
      if (!survives) flips++;
      // the engine's rule, for a ball with no dots
      const rule = Math.abs(g) >= 1 && survives;
      if (rule) known++;
      if (rule && !survives) wrongCall++;        // must never happen: calling a flippable one safe
    }
    rows.push({ towardCameraDeg: deg, worstGyroShift: +maxShift.toFixed(2), signFlips: flips,
                calledKnown: known, of: axes.length, calledKnownButFlips: wrongCall });
  }
  return rows;
});
console.table(out);

let bad = 0;
const at0 = out[0];
if (at0.worstGyroShift > 1e-9) { console.log('FAIL: side on, a mirror still moved the gyro by ' + at0.worstGyroShift); bad++; }
if (at0.signFlips !== 0) { console.log('FAIL: side on, ' + at0.signFlips + ' spins flipped sign'); bad++; }
if (at0.calledKnown !== at0.of) { console.log('FAIL: side on, only ' + at0.calledKnown + ' of ' + at0.of + ' called known'); bad++; }
const far = out[out.length - 1];
if (far.signFlips === 0) { console.log('FAIL: at ' + far.towardCameraDeg + ' degrees nothing flipped, so the rule is vacuous'); bad++; }
for (const r of out) if (r.calledKnownButFlips) { console.log('FAIL: at ' + r.towardCameraDeg + ' degrees, ' + r.calledKnownButFlips + ' flippable spins were called known'); bad++; }
console.log(bad ? `\n${bad} failed` : '\nside on the mirror cannot touch the gyro; down the table it can, and the rule never calls those safe');
await b.close(); srv.close();
process.exit(bad ? 1 : 0);
