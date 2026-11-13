// What the app says a serve was, over every serve a phone has measured.
//
//   node test/labels.mjs
//
// The spin is a mixture: a component along the flight (gyro), one that lifts or drops the ball
// (lift) and one that curves it (curve). The label names the ones above a share of the total,
// and those shares are a judgement call that this file pins to real serves rather than taste.
//
// It came from a player serving what they meant as sidespin and being told "backspin". The
// reading was right -- re-measuring the original clip whole crop gives the same numbers -- but
// 45% of that serve's spin was along its own flight line and the label said nothing, because
// gyro was only named above half. Six of these eighteen were in that position.
//
// Each row is what a phone reported: the session, the serve, and the components in rev/s.
import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import path from 'path';

const SERVES = [
  // session 20261112 (0.11.1 and 0.12.0)
  ['12nov', 1, 26.05, 24.59, 6.35, -5.78, 'backspin'],
  ['12nov', 2, 51.45, -50.08, 1.25, -11.75, 'topspin'],
  ['12nov', 3, 51.20, 20.47, -24.63, 39.94, 'side-backspin (curves left) + gyro'],
  // session 20261113 morning (0.12.0, every capture copied out)
  ['13am', 1, 5.50, -5.00, -2.10, -1.21, 'side-topspin (curves left)'],
  ['13am', 2, 32.92, 31.93, -0.94, -8.17, 'backspin'],
  ['13am', 3, 1.43, -1.10, 0.90, 0.45, 'no spin'],
  // 24.8% of the total is sideways: a hair under the quarter, so the label leaves "side" off.
  // Left where it falls rather than moving the bar to catch it -- one serve on the line is not
  // evidence for a threshold, and the note below says where the rest of the spin went.
  ['13am', 4, 45.55, 39.10, 11.30, -20.43, 'backspin + gyro'],
  ['13am', 5, 59.25, -55.40, 3.60, 20.75, 'topspin + gyro'],
  ['13am', 6, 54.00, 29.70, -24.60, 37.84, 'side-backspin (curves left) + gyro'],
  ['13am', 7, 36.40, 31.70, 2.70, -17.69, 'backspin + gyro'],
  // session 20261113 evening (0.12.3, the ring)
  ['13pm', 1, 37.85, 37.30, 4.80, -4.28, 'backspin'],
  ['13pm', 2, 64.35, 59.10, 1.30, -25.46, 'backspin + gyro'],
  ['13pm', 3, 54.55, -53.10, 3.20, 11.91, 'topspin'],
  ['13pm', 4, 63.10, 60.70, -12.90, 11.41, 'backspin'],
  ['13pm', 5, 52.20, -21.00, -37.90, 29.14, 'side-topspin (curves left) + gyro'],
  ['13pm', 6, 98.75, -97.90, 5.90, -11.60, 'topspin'],
  // the two the player set out to serve as sidespin: 5 above, and 8, which is mostly backspin
  // with a corkscrew and used to come back as the bare word "backspin"
  ['13pm', 7, 37.01, 32.00, 9.90, -15.77, 'side-backspin (curves right) + gyro'],
  ['13pm', 8, 61.40, 54.30, -7.90, 27.63, 'backspin + gyro'],
];

// the serves that should carry the corkscrew note: a third or more along the flight, and not
// enough curve to have been named sidespin
const WANT_NOTE = new Set(['13am/4', '13am/5', '13am/7', '13pm/2', '13pm/8']);

const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(path.join(WEB, 'engine.js'), 'utf8');
const sandbox = {};
new Function('window', src)(sandbox);
const { spinLabel, gyroNote } = sandbox.SpinMeter._internals || {};
if (!spinLabel) { console.error('engine.js does not expose spinLabel; add it to SpinMeter._internals'); process.exit(2); }

let bad = 0;
console.log(`${'serve'.padEnd(10)}${'rps'.padEnd(7)}${'lift'.padEnd(8)}${'curve'.padEnd(8)}${'gyro'.padEnd(8)}label`);
for (const [sess, n, rps, lift, curve, gyro, want] of SERVES) {
  const got = spinLabel(rps, { lift, curve, gyro });
  const key = `${sess}/${n}`;
  const note = gyroNote(rps, { lift, curve, gyro });
  const wantNote = WANT_NOTE.has(key);
  const ok = got === want && !!note === wantNote;
  if (!ok) bad++;
  console.log(`${key.padEnd(10)}${rps.toFixed(1).padEnd(7)}${lift.toFixed(1).padEnd(8)}${curve.toFixed(1).padEnd(8)}${gyro.toFixed(1).padEnd(8)}${got}` +
    (note ? '  [corkscrew note]' : '') + (ok ? '' : `\n   WANTED ${want}${wantNote ? ' [corkscrew note]' : ''}`));
}
console.log(`\n${SERVES.length - bad}/${SERVES.length} labelled as expected`);
process.exit(bad ? 1 : 0);
