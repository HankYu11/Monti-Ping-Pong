// A moving ball is longer than it is wide: does the measuring still take it as the ball?
//
//   node test/smear.mjs
//
// The shutter stays open while the ball moves, so a ball that moves L pixels during the exposure
// leaves a smear D wide and up to D + L long. At 240 fps that is a tenth of the ball per m/s with
// the shutter open the whole frame: the owner's 7 m/s serves measured 1.4 long, and the engine's
// fixed limit of 1.6 dropped the ball from about 10 m/s on (rendered serve at 13 m/s, shutter open
// the whole frame: 16 frames found, and measured along the live tracker's track a WRONG reading,
// 59 rev/s for a true 40). findBall now takes the smear the flight predicts (ballShape).
//
// This draws smeared balls the way a camera does -- each pixel as bright as the share of the
// exposure the ball covered it -- at speeds from still to past the point where the ball is longer
// smeared than it is round, in every direction, and asks findBall for them with and without the
// predicted smear. And it checks the other half: things that are not the ball (a long bar, a ball
// smeared the wrong way for the flight) are still turned away, and with no smear given the shape
// test is exactly the old one. Exits non-zero on any failure.
import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import path from 'path';

const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sandbox = {};
new Function('window', readFileSync(path.join(WEB, 'engine.js'), 'utf8'))(sandbox);
const { ballShape, findBall } = sandbox.SpinMeter._internals || {};
if (!ballShape || !findBall) { console.error('engine.js does not expose ballShape and findBall in SpinMeter._internals'); process.exit(2); }

let bad = 0;
const check = (ok, what) => { if (!ok) { bad++; console.log('FAIL ' + what); } };

// A frame of W x H: background `bg`, and a disc of diameter D whose centre moves from c - s/2 to
// c + s/2 while the shutter is open, `contrast` brighter than the background where it covers a
// pixel the whole time. Sampled finely enough that the smear's faint ends are as faint as they are.
function smeared(W, H, cx, cy, D, sx, sy, contrast = 120, bgLevel = 70) {
  const g = new Float32Array(W * H), bg = new Float32Array(W * H).fill(bgLevel);
  const N = 64, sub = 4, r2 = (D / 2) ** 2;
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let cover = 0;
    for (let t = 0; t < N; t++) {
      const bx = cx + sx * ((t + 0.5) / N - 0.5), by = cy + sy * ((t + 0.5) / N - 0.5);
      for (let a = 0; a < sub; a++) for (let b = 0; b < sub; b++) {
        const px = x + (a + 0.5) / sub - 0.5, py = y + (b + 0.5) / sub - 0.5;
        if ((px - bx) ** 2 + (py - by) ** 2 <= r2) cover++;
      }
    }
    g[y * W + x] = bgLevel + contrast * cover / (N * sub * sub);
  }
  return { g, bg };
}
function bar(W, H, cx, cy, len, wid, bgLevel = 70) {   // a still bright bar, len across, wid down
  const g = new Float32Array(W * H).fill(bgLevel), bg = new Float32Array(W * H).fill(bgLevel);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++)
    if (Math.abs(x - cx) <= len / 2 && Math.abs(y - cy) <= wid / 2) g[y * W + x] = bgLevel + 120;
  return { g, bg };
}

// 1. with no smear given, the test is the one it always was: aspect at most 1.6, and 60% of the
//    circle round the long side
for (let w = 3; w <= 60; w++) for (let h = 3; h <= 60; h += 3) for (const fill of [0.3, 0.55, 0.62, 0.8, 1]) {
  const area = Math.round(fill * w * h);
  const old = !(Math.max(w, h) / Math.min(w, h) > 1.6 || area / (Math.PI * (Math.max(w, h) / 2) ** 2) < 0.6);
  check(ballShape(w, h, area, null) === old && ballShape(w, h, area, [0, 0]) === old, `no smear changes the old rule at ${w}x${h} area ${area}`);
}

// 2. smeared balls, every direction, from still to smeared longer than the ball (L = 1.6 D):
//    found where the predicted smear is given, at its true centre, with its true width across
const W = 140, H = 100, D = 30;
const rows = [];
for (const L of [0, 0.4, 0.8, 1.0, 1.2, 1.6]) {
  for (const deg of [0, 30, 45, 60, 90, 150]) {
    const sx = L * D * Math.cos(deg * Math.PI / 180), sy = L * D * Math.sin(deg * Math.PI / 180);
    const cx = 70.3, cy = 50.6, { g, bg } = smeared(W, H, cx, cy, D, sx, sy);
    const pred = [cx + 3, cy - 2, D / 2];             // where the track says, a few pixels out
    const withS = findBall(g, bg, W, H, 5, null, pred, [sx, sy]);
    const without = findBall(g, bg, W, H, 5, null, pred, null);
    rows.push([L, deg, without ? without[3].toFixed(2) : '-', withS ? withS[3].toFixed(2) : '-', withS ? (2 * withS[4]).toFixed(1) : '-']);
    check(!!withS, `smear ${L} D at ${deg} deg: not found with the smear given`);
    if (withS) {
      check(Math.hypot(withS[0] - cx, withS[1] - cy) < 1.5, `smear ${L} D at ${deg} deg: centre off by ${Math.hypot(withS[0] - cx, withS[1] - cy).toFixed(1)} px`);
      check(Math.abs(2 * withS[4] - D) < 0.2 * D, `smear ${L} D at ${deg} deg: width across ${(2 * withS[4]).toFixed(1)} px for a ${D} px ball`);
    }
    // anything the old rule took, the new one takes too
    if (without) check(!!withS, `smear ${L} D at ${deg} deg: found without the smear but not with it`);
  }
}
console.log('smear (x ball)  direction  long/short old rule  ... with the smear  width across');
for (const r of rows) console.log(`${String(r[0]).padEnd(16)}${String(r[1]).padEnd(11)}${String(r[2]).padEnd(25)}${String(r[3]).padEnd(17)}${r[4]}`);

// 3. what is not the ball is still turned away
{
  const pred = [70, 50, D / 2];
  // a ball smeared along x, where the flight says it moved along y: long the wrong way
  let f = smeared(W, H, 70, 50, D, 1.2 * D, 0);
  check(!findBall(f.g, f.bg, W, H, 5, null, pred, [0, 1.2 * D]), 'a smear across the flight was taken');
  check(!findBall(f.g, f.bg, W, H, 5, null, pred, null), 'a 2.2x smear was taken with no smear predicted');
  // a still bar three times as long as it is wide, with and without a flight along it far too
  // slow to have smeared a ball that long
  f = bar(W, H, 70, 50, 54, 18);
  check(!findBall(f.g, f.bg, W, H, 5, null, pred, null), 'a still bar was taken');
  check(!findBall(f.g, f.bg, W, H, 5, null, pred, [8, 0]), 'a bar longer than the flight explains was taken');
  check(!findBall(f.g, f.bg, W, H, 5, null, pred, [0, 40]), 'a bar lying across the flight was taken');
}

console.log(bad ? `\n${bad} failed` : '\nall passed');
process.exit(bad ? 1 : 0);
