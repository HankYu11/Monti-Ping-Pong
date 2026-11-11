// A capture as small windows round the ball instead of the whole crop, which is a fiftieth to a
// hundredth of the bytes. Builds both from the same clip and measures both, so the rebuild in
// the engine (windowsInto) can be checked against the capture it stands in for.
//
//   node test/windows.mjs [--k 3] clip.mp4 ...
// Needs <clip>.json from spinsim.py for the track.
//
// The three parts are not optional: blanking the rest outright fails on every real capture, one
// background frame fails where the player moves, and without the per-row brightness the rows
// outside the window stop flickering with the light and the correction inside it goes wrong.
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import http from 'http'; import fs from 'fs'; import path from 'path';
import { execFileSync } from 'child_process';
import { fileURLToPath } from 'url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(HERE, '..');
const TMP = fs.mkdtempSync('/tmp/windows-');
const argv = process.argv.slice(2);
let K = 3, files = [];
for (let i = 0; i < argv.length; i++) { if (argv[i] === '--k') K = +argv[++i]; else files.push(argv[i]); }
if (!files.length) { console.error('usage: node test/windows.mjs [--k 3] clip.mp4...'); process.exit(2); }

function makeCapture(mp4, truth) {
  const p = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-select_streams', 'v:0',
    '-show_entries', 'stream=width,height', '-of', 'json', mp4]).toString()).streams[0];
  const t = truth.track, pad = 2.2 * t[0][3] + 8;
  const x0 = Math.max(0, Math.floor((Math.min(...t.map(q => q[1])) - pad) / 2) * 2);
  const y0 = Math.max(0, Math.floor((Math.min(...t.map(q => q[2])) - pad) / 2) * 2);
  const cw = Math.min(p.width - x0, Math.ceil((Math.max(...t.map(q => q[1])) + pad - x0) / 2) * 2);
  const ch = Math.min(p.height - y0, Math.ceil((Math.max(...t.map(q => q[2])) + pad - y0) / 2) * 2);
  const raw = path.join(TMP, path.basename(mp4) + '.i420');
  execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-i', mp4,
    '-vf', `crop=${cw}:${ch}:${x0}:${y0}`, '-pix_fmt', 'yuv420p', '-f', 'rawvideo', raw]);
  const frameBytes = cw * ch * 1.5, n = fs.statSync(raw).size / frameBytes;
  if (!Number.isInteger(n)) throw new Error('frame size mismatch');
  return { raw, rect: [x0, y0, cw, ch], W: p.width, H: p.height, n, frameBytes };
}

const srv = http.createServer((q, s) => {
  const name = decodeURIComponent(q.url.split('?')[0]).replace(/^\//, '');
  if (name === '') { s.setHeader('content-type', 'text/html'); return s.end('<!doctype html><script src="engine.js"></script>'); }
  const f = name.startsWith('raw/') ? path.join(TMP, name.slice(4)) : path.join(WEB, name);
  fs.createReadStream(f).on('error', () => { s.statusCode = 404; s.end(); }).pipe(s);
}).listen(0);
await new Promise(r => srv.once('listening', r));
const PORT = srv.address().port;
const browser = await chromium.launch({ args: ['--no-sandbox'] });
const page = await browser.newPage();
page.on('pageerror', e => console.log('page error:', e.message));
await page.goto(`http://127.0.0.1:${PORT}/`);
await page.waitForFunction(() => typeof window.SpinMeter !== 'undefined');

console.log(`windows at ${K}x the ball's radius\n`);
console.log(`${'clip'.padEnd(13)}${'full'.padEnd(10)}${'windowed'.padEnd(10)}${'drift'.padEnd(8)}${'bytes full'.padEnd(12)}${'windowed'.padEnd(11)}saving`);
let bad = 0;
for (const mp4 of files) {
  const truth = JSON.parse(fs.readFileSync(mp4.replace(/\.mp4$/, '.json'), 'utf8'));
  const cap = makeCapture(mp4, truth);
  const r = await page.evaluate(async ({ url, cap, track, K }) => {
    const buf = new Uint8Array(await (await fetch(url)).arrayBuffer());
    const [, , cw, ch] = cap.rect, cw2 = cw >> 1, ch2 = ch >> 1;
    const ySize = cw * ch, cSize = cw2 * ch2, fb = ySize + 2 * cSize;
    const frames = [];
    for (let i = 0; i < cap.n; i++) frames.push(buf.subarray(i * fb, (i + 1) * fb));

    // the background: the per-pixel median of a spread of frames, which the phone computes once
    const pick = [], N = Math.min(15, frames.length);
    for (let k = 0; k < N; k++) pick.push(frames[Math.round(k * (frames.length - 1) / (N - 1))]);
    const bg = new Uint8Array(fb), tmp = new Uint8Array(N);
    for (let p = 0; p < fb; p++) {
      for (let k = 0; k < N; k++) tmp[k] = pick[k][p];
      const a = Array.from(tmp).sort((x, y) => x - y);
      bg[p] = a[N >> 1];
    }
    // how bright each row was, per frame, against the background: one number a row, under 1 KB
    const bgRow = new Float64Array(ch);
    for (let y = 0; y < ch; y++) { let s = 0; for (let x = 0; x < cw; x++) s += bg[y * cw + x]; bgRow[y] = s / cw || 1; }
    const rowBright = frames.map((f) => {
      const r = new Float32Array(ch);
      for (let y = 0; y < ch; y++) { let s = 0; for (let x = 0; x < cw; x++) s += f[y * cw + x]; r[y] = (s / cw) / bgRow[y]; }
      return r;
    });

    // one window per frame, round the tracker's ball, in the crop's pixels
    const [ox, oy] = cap.rect;
    const at = new Map(track.map((q) => [q[0], q]));
    const rMed = track.map((q) => q[3]).sort((a, b) => a - b)[track.length >> 1];
    let half = Math.ceil(K * rMed); half += half & 1;
    const wW = Math.min(cw, 2 * half), wH = Math.min(ch, 2 * half);
    const windows = [], wY = wW * wH, wW2 = wW >> 1, wH2 = wH >> 1, wC = wW2 * wH2;
    const wins = [];
    for (let i = 0; i < frames.length; i++) {
      let q = at.get(i);
      if (!q) { let d = 1; while (!q && d < 8) { q = at.get(i - d) || at.get(i + d); d++; } }
      if (!q) { windows.push(null); wins.push(null); continue; }
      let x0 = Math.round(q[1] - ox - wW / 2), y0 = Math.round(q[2] - oy - wH / 2);
      x0 = Math.max(0, Math.min(cw - wW, x0)) & ~1;
      y0 = Math.max(0, Math.min(ch - wH, y0)) & ~1;
      windows.push([x0, y0]);
      const w = new Uint8Array(wY + 2 * wC), f = frames[i];
      for (let y = 0; y < wH; y++) w.set(f.subarray((y0 + y) * cw + x0, (y0 + y) * cw + x0 + wW), y * wW);
      const cx0 = x0 >> 1, cy0 = y0 >> 1;
      for (let y = 0; y < wH2; y++) {
        w.set(f.subarray(ySize + (cy0 + y) * cw2 + cx0, ySize + (cy0 + y) * cw2 + cx0 + wW2), wY + y * wW2);
        w.set(f.subarray(ySize + cSize + (cy0 + y) * cw2 + cx0, ySize + cSize + (cy0 + y) * cw2 + cx0 + wW2), wY + wC + y * wW2);
      }
      wins.push(w);
    }

    const base = { rect: cap.rect, W: cap.W, H: cap.H, full: true, fps: 240, track, near: [] };
    const full = await window.SpinMeter.measureCapture(Object.assign({ frames }, base), { useTrack: true });
    const win = await window.SpinMeter.measureCapture(
      Object.assign({ frames: wins, windows, winW: wW, winH: wH, background: bg, rowBright }, base), { useTrack: true });
    const bytes = (a) => a.reduce((t, x) => t + (x ? x.length : 0), 0);
    return { full: full.rps, win: win.rps, fullErr: full.error || null, winErr: win.error || null,
             box: wW + 'x' + wH, bytesFull: bytes(frames), bytesWin: bytes(wins) + bg.length + ch * 4 * frames.length };
  }, { url: `http://127.0.0.1:${PORT}/raw/${path.basename(cap.raw)}`, cap, track: truth.track, K });

  const mb = (b) => (b / 1e6).toFixed(1) + ' MB';
  const name = path.basename(mp4, '.mp4');
  if (r.fullErr || r.winErr) {
    bad++;
    console.log(`${name.padEnd(13)}${(r.fullErr ? 'ERROR' : r.full.toFixed(2)).padEnd(10)}${(r.winErr ? 'ERROR' : r.win.toFixed(2)).padEnd(10)}${'-'.padEnd(8)}${mb(r.bytesFull).padEnd(12)}${mb(r.bytesWin).padEnd(11)}`);
    console.log(`   ${r.winErr || r.fullErr}`);
    continue;
  }
  const drift = Math.abs(r.win - r.full);
  if (drift > 0.5) bad++;
  console.log(`${name.padEnd(13)}${r.full.toFixed(2).padEnd(10)}${r.win.toFixed(2).padEnd(10)}${drift.toFixed(2).padEnd(8)}` +
    `${mb(r.bytesFull).padEnd(12)}${mb(r.bytesWin).padEnd(11)}${(r.bytesFull / r.bytesWin).toFixed(0)}x  (${r.box})`);
}
console.log(`\n${files.length - bad}/${files.length} rebuilt within 0.5 rev/s of the whole crop`);
await browser.close(); srv.close(); fs.rmSync(TMP, { recursive: true, force: true });
process.exit(bad ? 1 : 0);
