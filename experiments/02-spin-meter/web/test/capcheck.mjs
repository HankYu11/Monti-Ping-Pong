// Measure rebuilt real captures and compare against what the phone itself reported for them.
//
//   node test/capcheck.mjs serve01.json serve02.json ...      (<serve>.cap beside each)
//
// A session records its own reading per serve. test/capfromclip.py rebuilds the capture that
// reading came from. So a real serve, with real flicker, real motion blur, a real player and a
// real marking, becomes a regression test with an answer -- which rendered clips cannot be,
// however good they are. The engine should give the phone's number back.
//
// Exits non-zero if any serve drifts more than the tolerances below.
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import http from 'http'; import fs from 'fs'; import path from 'path';
import { fileURLToPath } from 'url';
import { READ_CAP } from './capfile.mjs';
const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
// The rate, the kind of spin, the marking and whether the gyro's direction is claimed must match
// exactly -- those are what the player is shown. The three numbers the spin is split into get a
// tolerance, because a different cut of the same serve is allowed to move them a little: see
// --split.
const RPS_TOL = 0.5;
const SPLIT_TOL_DEFAULT = 1.0;

// --ext .win3.cap measures a different cut of the same serves against the same answers, which is
// how the windowed capture is held to the reading the phone gave for the whole crop.
const argv = process.argv.slice(2);
let ext = '.cap', splitTol = SPLIT_TOL_DEFAULT;
const metas = [];
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--ext') ext = argv[++i];
  else if (argv[i] === '--split') splitTol = +argv[++i];
  else metas.push(argv[i]);
}
if (!metas.length) { console.error('usage: node test/capcheck.mjs [--ext .win3.cap] serve01.json ...'); process.exit(2); }
const caps = metas.map((m) => m.replace(/\.json$/, ext));
for (const c of caps) if (!fs.existsSync(c)) { console.error(`missing ${c} -- run test/capfromclip.py first`); process.exit(2); }

const srv = http.createServer((q, s) => {
  const n = decodeURIComponent(q.url.split('?')[0]).replace(/^\//, '');
  if (n === '') { s.setHeader('content-type', 'text/html'); return s.end('<!doctype html><script src="engine.js"></script>'); }
  const m = n.match(/^cap(\d+)$/);
  const f = m ? path.resolve(caps[+m[1]]) : path.join(WEB, n);
  fs.createReadStream(f).on('error', () => { s.statusCode = 404; s.end(); }).pipe(s);
}).listen(0);
await new Promise(r => srv.once('listening', r));
const base = `http://127.0.0.1:${srv.address().port}`;
const browser = await chromium.launch({ args: ['--no-sandbox'] });
const page = await browser.newPage();
page.on('pageerror', e => console.log('page error:', e.message));
await page.goto(base + '/');
await page.waitForFunction(() => typeof window.SpinMeter !== 'undefined');
await page.evaluate(READ_CAP);

console.log(`${path.basename(ext)} · rate within ${RPS_TOL}, split within ${splitTol} rev/s`);
console.log(`${'serve'.padEnd(10)}${'rev/s (phone)'.padEnd(22)}${'gyro (phone)'.padEnd(22)}${'dir'.padEnd(10)}${'type'.padEnd(28)}ms`);
let bad = 0, clearN = 0;
for (let i = 0; i < metas.length; i++) {
  const file = JSON.parse(fs.readFileSync(metas[i], 'utf8'));
  const want = file.result.serve;
  want.clear = !!want.clear;
  const wantSeg = (((file.data || {}).phases || [])[0] || {}).segment;
  want.seg = wantSeg ? [wantSeg.start, wantSeg.end] : null;
  const got = await page.evaluate(async (url) => {
    const cap = await window.__readCap(url);
    const t = performance.now();
    const res = await window.SpinMeter.measureCapture(cap, { useTrack: true, single: true, serveDir: 'any', robust: true });
    const r = (res.serves || [])[0];                 // the app reads serves[0]; so does this
    if (!r) return { ms: performance.now() - t, error: res.error || 'no serve found' };
    return { ms: performance.now() - t, rps: r.rps, gyro: r.gyro, dir: !!r.gyroDirKnown, type: r.spinType,
             lift: r.lift, curve: r.curve, clear: !!r.reliable, marking: r.marking, error: r.error || null,
             seg: r.segment ? [r.segment.start, r.segment.end] : null };
  }, `${base}/cap${i}`);
  const dR = got.error ? NaN : Math.abs(got.rps - want.rps), dG = got.error ? NaN : Math.abs(got.gyro - want.gyro);
  const dL = got.error ? NaN : Math.abs(got.lift - want.lift), dC = got.error ? NaN : Math.abs(got.curve - want.curve);
  // A serve the engine could not read is only held to saying so again. Comparing one unreadable
  // answer against another compares noise: session 20261112's serve 2 fits 0.09 where a serve it
  // can read fits 0.7 to 0.9, and any change to which pixels are looked at moves that number
  // freely. What must not change is the verdict the player is shown.
  // If the engine measured a different stretch of the flight, the spin split is a different
  // measurement and not a drifted one, so only the things that do not depend on which frames were
  // used are held to. It happens when the engine refuses the live track and searches the crop
  // itself: a capture cut down to windows is a cleaner picture to search, and on session
  // 20261112's serve 4 the search then found 44 frames of flight where the whole crop gave 23,
  // and fitted them better (0.81 against 0.79). Those serves print with `s`.
  const sameSeg = !want.seg || !got.seg || (want.seg[0] === got.seg[0] && want.seg[1] === got.seg[1]);
  const ok = !got.error && got.clear === want.clear && (!want.clear ||
             (dR <= RPS_TOL && got.dir === want.gyroDirKnown && got.type === want.type &&
              got.marking === want.marking &&
              (!sameSeg || (dG <= splitTol && dL <= splitTol && dC <= splitTol))));
  if (!ok) bad++;
  if (want.clear) clearN++;
  const name = path.basename(metas[i]).replace(/\.json$/, '');
  if (got.error) { console.log(`! ${name.padEnd(8)}${got.error}`); continue; }
  console.log(`${(ok ? (!want.clear ? '~ ' : sameSeg ? '  ' : 's ') : '! ') + name.padEnd(8)}` +
    `${(got.rps.toFixed(2) + ' (' + want.rps.toFixed(2) + ')').padEnd(22)}` +
    `${(got.gyro.toFixed(2) + ' (' + want.gyro.toFixed(2) + ')').padEnd(22)}` +
    `${(String(got.dir) + (got.dir === want.gyroDirKnown ? '' : ' (' + want.gyroDirKnown + ')')).padEnd(10)}` +
    `${String(got.type).padEnd(28)}${got.ms.toFixed(0)}`);
  if (got.error) console.log(`    error: ${got.error}`);
  else if (!ok || !sameSeg) console.log(`    drift rev/s ${dR.toFixed(2)}, lift ${dL.toFixed(2)}, curve ${dC.toFixed(2)},` +
    ` gyro ${dG.toFixed(2)}${sameSeg ? '' : `; measured frames ${got.seg} against ${want.seg}`}` +
    `${ok ? '' : `; phone said "${want.type}" (${want.marking})`}`);
}
const unclear = metas.length - clearN;
console.log(bad ? `\n${bad} of ${metas.length} serves no longer read as the phone read them` :
  `\nall ${metas.length} serves agree with the phone` +
  (unclear ? ` (${unclear} marked "~": unreadable then and unreadable now, which is all that is asked of them)` : ''));
await browser.close(); srv.close();
process.exitCode = bad ? 1 : 0;
