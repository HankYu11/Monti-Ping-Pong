// The 60 Hz light-flicker fix and the plain pole fit, on synthetic pole tracks: no clips, no
// browser, no dependencies. Plain `node test/synthpole.mjs`; exits non-zero on any failure, so it
// can gate a change (CI runs it on every PR touching the engine).
//
// A pole track is what measurePole hands the rate scan: per frame, the unit vector the black half
// points along. Built here from a known spin, so the right answer is known exactly. The flicker is
// added the way it reaches the pole on a real serve: a lean that swings with the lights. A 120 Hz
// flicker filmed by a "240 fps" camera that really delivers 238.25 (a Pixel 8) lands at
// 240 - 120 * 240 / 238.25 = 119.12 rev/s on the engine's clock: just under fps/2, inside the band
// flickerBands gives. Live 0.13.19 read a 57 rev/s backspin as 118.1 rev/s, clear, from that.
import { createRequire } from 'module';
import path from 'path';
import { fileURLToPath } from 'url';

const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
createRequire(import.meta.url)(path.join(WEB, 'engine.js'));   // sets globalThis.SpinMeter
const I = globalThis.SpinMeter._internals;
const FPS = 240;

let failed = 0;
function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? '  (' + detail + ')' : ''}`);
  if (!ok) failed++;
}
const within = (got, want, frac) => Math.abs(got - want) <= frac * want;

// ------------------------------------------------------------ synthetic tracks
const norm = (v) => Math.hypot(...v), unit = (v) => v.map((x) => x / norm(v));
function rotate(p, a, th) {                       // Rodrigues: p turned by th about unit axis a
  const c = Math.cos(th), s = Math.sin(th), d = a[0] * p[0] + a[1] * p[1] + a[2] * p[2];
  const x = [a[1] * p[2] - a[2] * p[1], a[2] * p[0] - a[0] * p[2], a[0] * p[1] - a[1] * p[0]];
  return p.map((v, i) => v * c + x[i] * s + a[i] * d * (1 - c));
}
// rps: the ball's spin. wobble: the lights' lean (0 = none), at wobbleRps on the engine's clock.
// gaps: frame numbers missing, as the phone's windowed captures drop some.
function poleTrack(rps, { n = 60, axis = [0.1, 0.2, 1], pole = [1, 0, 0.3], wobble = 0, wobbleRps = 119.12, gaps = [] } = {}) {
  const a = unit(axis), p0 = unit(pole), t = [], B = [];
  for (let k = 0; k < n; k++) {
    if (gaps.includes(k)) continue;
    let b = rotate(p0, a, 2 * Math.PI * rps * k / FPS);
    if (wobble) {
      const w = wobble * Math.cos(2 * Math.PI * wobbleRps * k / FPS + 0.4);
      b = [b[0] + w, b[1] + 0.3 * w, b[2] - 0.5 * w];   // a brighter frame: leans x and tilts z
    }
    t.push(k); B.push(unit(b));
  }
  return { t, B };
}
const ones = (t) => t.map(() => 1);
// what measureCapture adds before asking isClear: a half-marked ball whose pattern changes
const asReading = (fit) => ({ ...fit, method: 'half', change: 0.3, lights: I.lightFlicker(fit.rps, fit.plusMinus, FPS) });
const GAPS = [3, 4, 9, 15, 16, 22, 30, 31, 37, 44, 45, 51, 55];

// ------------------------------------------------------------ 1. a clean half-black ball
// Serves read 14-63 rev/s; the axes cover backspin, sidespin and a tilted mix.
for (const rps of [14, 30, 45, 57, 63]) {
  for (const axis of [[0, 0, 1], [0, 1, 0], [0.1, 0.2, 1]]) {
    const { t, B } = poleTrack(rps, { axis });
    const r = I.fitRotatingVector(t, B, FPS, ones(t));
    check(`clean ${rps} rev/s, axis ${axis}: within 2%`, within(r.rps, rps, 0.02), `read ${r.rps.toFixed(2)}`);
    check(`clean ${rps} rev/s, axis ${axis}: clear`, I.isClear(asReading(r)), `score ${r.score.toFixed(2)}`);
  }
}
{
  const { t, B } = poleTrack(40, { gaps: GAPS });
  const r = I.fitRotatingVector(t, B, FPS, ones(t));
  check('clean 40 rev/s with 13 frames missing: within 2%', within(r.rps, 40, 0.02), `read ${r.rps.toFixed(2)}`);
}

// ------------------------------------------------------------ 2. lightWobble
// No flicker: nothing in the band, so the pole must be left exactly as it was.
for (const rps of [14, 30, 57, 63]) {
  const { t, B } = poleTrack(rps);
  const w = I.lightWobble(t, B, FPS, null);
  check(`no flicker, ${rps} rev/s: pole left alone`, w && w.removed === false && !w.clean, `share ${w && w.share.toFixed(3)}`);
}
// The serve that read 118: the wobble is strong enough that the raw fit locks onto the lights.
for (const gaps of [[], GAPS]) {
  for (const wobble of [1.0, 1.6]) {
    const tag = `57 rev/s + 120 Hz wobble ${wobble}${gaps.length ? ', 13 gaps' : ''}`;
    const { t, B } = poleTrack(57, { wobble, gaps });
    const raw = I.fitRotatingVector(t, B, FPS, ones(t));
    check(`${tag}: raw fit reads the lights (precondition)`, raw.rps > 115, `raw ${raw.rps.toFixed(2)}`);
    check(`${tag}: guard refuses the raw reading`, I.lightFlicker(raw.rps, raw.plusMinus, FPS) === 120 && !I.isClear(asReading(raw)));
    const w = I.lightWobble(t, B, FPS, null);
    check(`${tag}: wobble found at 120 Hz and removed`, w && w.hz === 120 && w.removed === true && Array.isArray(w.clean) && w.clean.length === B.length,
      w ? `at ${w.rps.toFixed(2)} rev/s, share ${w.share.toFixed(2)}` : 'null');
    const r = I.fitRotatingVector(t, (w && w.clean) || B, FPS, ones(t));
    check(`${tag}: cleaned pole reads 57 within 2%`, within(r.rps, 57, 0.02), `read ${r.rps.toFixed(2)}, r2 ${r.r2.toFixed(2)}`);
    check(`${tag}: cleaned reading is clear`, I.isClear(asReading(r)));
    check(`${tag}: cleaned poles are unit vectors`, !!(w && w.clean) && w.clean.every((b) => Math.abs(norm(b) - 1) < 1e-9));
  }
}
// A weaker wobble the raw fit survives: removing it must not move the reading.
{
  const { t, B } = poleTrack(57, { wobble: 0.6, gaps: GAPS });
  const w = I.lightWobble(t, B, FPS, null);
  const r = I.fitRotatingVector(t, w.removed ? w.clean : B, FPS, ones(t));
  check('57 rev/s + weak wobble 0.6: still within 2%', within(r.rps, 57, 0.02), `read ${r.rps.toFixed(2)}, removed ${w.removed}`);
}
// 50 Hz mains: a 100 Hz flicker lands near 100 rev/s
{
  const { t, B } = poleTrack(35, { wobble: 1.3, wobbleRps: 100.7 });
  const w = I.lightWobble(t, B, FPS, null);
  check('35 rev/s + 100 Hz wobble: found at 100 Hz', w && w.hz === 100 && w.removed, w ? `at ${w.rps.toFixed(2)}` : 'null');
  const r = I.fitRotatingVector(t, (w && w.clean) || B, FPS, ones(t));
  check('35 rev/s + 100 Hz wobble: cleaned pole reads 35 within 2%', within(r.rps, 35, 0.02), `read ${r.rps.toFixed(2)}`);
}
// too few frames to fit anything: no verdict rather than a wrong one
check('lightWobble with 7 frames: null', I.lightWobble([0, 1, 2, 3, 4, 5, 6], poleTrack(30, { n: 7 }).B, FPS, null) === null);
check('lightWobble with no frame rate: null', I.lightWobble(...Object.values(poleTrack(30)), 0, null) === null);

// ------------------------------------------------------------ 3. the guard: lightFlicker / isClear
const bands = I.flickerBands(FPS);
check('flickerBands(240): a 100 Hz and a 120 Hz band', bands.length === 2 && bands[0].hz === 100 && bands[1].hz === 120);
check('120 Hz band at 240 fps is 117.6-120', Math.abs(bands[1].lo - 117.55) < 0.05 && bands[1].hi === 120, `${bands[1].lo.toFixed(2)}-${bands[1].hi}`);
for (const [rps, pm, want] of [[118.1, 1.95, 120], [119.12, 0.05, 120], [120, 0.05, 120], [116.9, 0.2, 120], [100, 0.05, 100], [98.5, 0.05, 100],
  [57, 0.65, 0], [63, 0.05, 0], [14, 0.05, 0], [110, 0.05, 0], [5, 0.05, 0], [NaN, 0.05, 0]]) {
  check(`lightFlicker(${rps} +/- ${pm}) = ${want}`, I.lightFlicker(rps, pm, FPS) === want, `got ${I.lightFlicker(rps, pm, FPS)}`);
}
// the band's own edge widens with the reading's uncertainty
check('lightFlicker: 116 rev/s is outside the band at +/-0.05...', I.lightFlicker(116, 0.05, FPS) === 0);
check('... and inside it at +/-1.5', I.lightFlicker(116, 1.5, FPS) === 120);
// at 120 fps a 120 Hz flicker folds to 0 and a 100 Hz one to 20: only 100 Hz has a band
check('flickerBands(120): only 100 Hz', I.flickerBands(120).map((b) => b.hz).join() === '100');
check('flickerBands(0): none', I.flickerBands(0).length === 0);

const good = { rps: 57, score: 0.9, runner: NaN, turns: 3, method: 'half', change: 0.3, lights: 0 };
check('isClear: a good reading', I.isClear(good) === true);
check('isClear: the same reading in the 120 Hz band', I.isClear({ ...good, lights: 120 }) === false);
check('isClear: ... and in the 100 Hz band', I.isClear({ ...good, lights: 100 }) === false);
check('isClear: under one turn', I.isClear({ ...good, turns: 0.6 }) === false);
check('isClear: a steady black disc', I.isClear({ ...good, change: 0.05 }) === false);
check('isClear: a close rival rate', I.isClear({ ...good, score: 0.7, runner: 0.6 }) === false);
check('isClear: a low score', I.isClear({ ...good, score: 0.4 }) === false);
// a real spin inside the band is refused too: the documented, cheaper mistake
{
  const { t, B } = poleTrack(119);
  const r = I.fitRotatingVector(t, B, FPS, ones(t));
  check('a real 119 rev/s spin is refused (in the band)', !I.isClear(asReading(r)), `read ${r.rps.toFixed(2)}`);
}

console.log(failed ? `\n${failed} FAILED` : '\nall passed');
process.exit(failed ? 1 : 0);
