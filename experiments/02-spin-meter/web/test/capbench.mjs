// Compare the two ways the engine can measure a live capture, on identical pixels:
//   useTrack:false  the engine searches the crop for the ball itself   (the app's setting today)
//   useTrack:true   it measures along the track the live tracker already found
// Reports wall-clock and the reading, so the speed-up and any drift are both visible.
//
//   node test/capbench.mjs clip.mp4 [clip2.mp4 ...]
// Needs <clip>.json from spinsim.py (ground truth + the track).
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import http from 'http'; import fs from 'fs'; import path from 'path';
import { execFileSync } from 'child_process';
import { fileURLToPath } from 'url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(HERE, '..');
const TMP = fs.mkdtempSync('/tmp/capbench-');
const argv = process.argv.slice(2);
let reps = 3, files = [];
for (let i = 0; i < argv.length; i++) { if (argv[i] === '--reps') reps = +argv[++i]; else files.push(argv[i]); }
if (!files.length) { console.error('usage: node test/capbench.mjs [--reps n] clip.mp4...'); process.exit(2); }

// Build a capture the way the app does: crop the picture to where the ball flew, raw I420.
function makeCapture(mp4, truth) {
  const probe = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height', '-of', 'json', mp4]).toString()).streams[0];
  const { width: W, height: H } = probe;
  const t = truth.track, pad = 2.2 * t[0][3] + 8;
  let x0 = Math.min(...t.map(p => p[1])) - pad, x1 = Math.max(...t.map(p => p[1])) + pad;
  let y0 = Math.min(...t.map(p => p[2])) - pad, y1 = Math.max(...t.map(p => p[2])) + pad;
  x0 = Math.max(0, Math.floor(x0 / 2) * 2); y0 = Math.max(0, Math.floor(y0 / 2) * 2);
  let cw = Math.min(W - x0, Math.ceil((x1 - x0) / 2) * 2), ch = Math.min(H - y0, Math.ceil((y1 - y0) / 2) * 2);
  const raw = path.join(TMP, path.basename(mp4) + '.i420');
  execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-i', mp4,
    '-vf', `crop=${cw}:${ch}:${x0}:${y0}`, '-pix_fmt', 'yuv420p', '-f', 'rawvideo', raw]);
  const frameBytes = cw * ch * 1.5;
  const n = fs.statSync(raw).size / frameBytes;
  if (!Number.isInteger(n)) throw new Error('frame size mismatch');
  return { raw, rect: [x0, y0, cw, ch], W, H, n, frameBytes };
}

const srv = http.createServer((q, s) => {
  const name = decodeURIComponent(q.url.split('?')[0]).replace(/^\//, '');
  if (name === '') { s.setHeader('content-type', 'text/html; charset=utf-8');
    return s.end('<!doctype html><meta charset=utf-8><script src="engine.js"></script>'); }
  const f = name.startsWith('raw/') ? path.join(TMP, name.slice(4)) : path.join(WEB, name);
  s.setHeader('content-type', f.endsWith('.js') ? 'application/javascript' : 'application/octet-stream');
  fs.createReadStream(f).on('error', () => { s.statusCode = 404; s.end(); }).pipe(s);
}).listen(0);
await new Promise(r => srv.once('listening', r));
const PORT = srv.address().port;
const browser = await chromium.launch({ args: ['--no-sandbox'] });
const page = await browser.newPage();
page.on('pageerror', e => console.log('page error:', e.message));
await page.goto(`http://127.0.0.1:${PORT}/`);
await page.waitForFunction(() => typeof window.SpinMeter !== 'undefined');

console.log('clip                      mode        rps      d(true)  axis    time    frames');
const summary = [];
for (const mp4 of files) {
  const truth = JSON.parse(fs.readFileSync(mp4.replace(/\.mp4$/, '.json'), 'utf8'));
  const cap = makeCapture(mp4, truth);
  const out = await page.evaluate(async ({ url, cap, track, reps }) => {
    const buf = new Uint8Array(await (await fetch(url)).arrayBuffer());
    const fb = cap.frameBytes, frames = [];
    for (let i = 0; i < cap.n; i++) frames.push(buf.subarray(i * fb, (i + 1) * fb));
    const res = {};
    // a hand sliding along the bottom of the picture: the shape that made 0.5 give up on
    // measuring along the track. The engine must refuse it and search for itself.
    const bad = track.map((q, k) => [q[0], cap.rect[0] + 60 + 40 * Math.sin(k / 7),
                                     cap.rect[1] + cap.rect[3] - 20 + 3 * Math.sin(k / 4), q[3] * 2.2]);
    for (const useTrack of [false, true, 'bad']) {
      const times = []; let last = null;
      for (let k = 0; k < reps; k++) {
        const t0 = performance.now();
        last = await window.SpinMeter.measureCapture(
          { rect: cap.rect, W: cap.W, H: cap.H, frames, full: true, fps: 240,
            track: useTrack === 'bad' ? bad : track, near: [] },
          { useTrack: !!useTrack });
        times.push(performance.now() - t0);
      }
      res[useTrack === 'bad' ? 'badtrack' : useTrack ? 'track' : 'search'] = {
        ms: Math.min(...times), rps: last.rps, axis: last.axis,
        reliable: last.reliable, error: last.error || null, nFrames: last.nFrames,
        refused: !!(last.meta && last.meta.debug && last.meta.debug.trackRefused),
        time: last.meta && last.meta.debug && last.meta.debug.time || null };
    }
    return res;
  }, { url: `http://127.0.0.1:${PORT}/raw/${path.basename(cap.raw)}`, cap, track: truth.track, reps });

  const name = path.basename(mp4, '.mp4');
  for (const mode of ['search', 'track', 'badtrack']) {
    const r = out[mode];
    const ang = (a) => { if (!a || !truth.axis) return '-';
      const n = v => { const m = Math.hypot(...v) || 1; return v.map(x => x / m); };
      const p = n(a), q = n(truth.axis);
      return (Math.acos(Math.min(1, Math.abs(p[0]*q[0]+p[1]*q[1]+p[2]*q[2]))) * 180 / Math.PI).toFixed(1) + 'd'; };
    console.log(`${name.padEnd(24)} ${mode.padEnd(10)} ` + (r.error
      ? `ERROR ${r.error.slice(0, 40)}`
      : `${r.rps.toFixed(2).padStart(7)} ${(r.rps - truth.rps).toFixed(2).padStart(8)} ${ang(r.axis).padStart(6)} ${(r.ms/1000).toFixed(2).padStart(6)}s ${String(r.nFrames).padStart(6)}`));
  }
  const bt = out.badtrack;
  const okFallback = bt.refused && !bt.error && Math.abs(Math.abs(bt.rps) - Math.abs(truth.rps)) <= 0.5;
  console.log(`${''.padEnd(24)} ${'bad track'.padEnd(10)} ${okFallback ? 'refused, searched instead, reading kept' : 'FALLBACK FAILED (refused=' + bt.refused + ' err=' + bt.error + ')'}`);
  const s = out.search, t = out.track;
  if (!s.error && !t.error) {
    summary.push({ name, speedup: s.ms / t.ms, drift: Math.abs(t.rps - s.rps) });
    console.log(`${''.padEnd(24)} ${'->'.padEnd(10)} speed-up ${(s.ms / t.ms).toFixed(2)}x | drift vs search ${Math.abs(t.rps - s.rps).toFixed(2)} rps\n`);
  } else console.log('');
}
if (summary.length) {
  const sp = summary.map(r => r.speedup), dr = summary.map(r => r.drift);
  console.log(`${summary.length} clips | mean speed-up ${(sp.reduce((a,b)=>a+b,0)/sp.length).toFixed(2)}x` +
    ` (min ${Math.min(...sp).toFixed(2)}x) | max drift ${Math.max(...dr).toFixed(2)} rps`);
}
await browser.close(); srv.close(); fs.rmSync(TMP, { recursive: true, force: true });
