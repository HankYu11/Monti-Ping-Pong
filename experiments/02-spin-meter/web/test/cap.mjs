// Measure a capture file the phone or the desktop runner wrote (.cap), whole-crop or windowed.
// The two forms should read the same; this is how that gets checked outside a phone.
//
//   node test/cap.mjs serve.cap [more.cap ...]           measures each
//   node test/cap.mjs --pair a.cap a.win.cap             measures both and reports the drift
//
// A .cap is: a 4-byte length, the header JSON, then the frames. A windowed one puts the
// background frame and the per-frame row brightness between the header and the windows.
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import http from 'http'; import fs from 'fs'; import path from 'path';
import { fileURLToPath } from 'url';
const WEB = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const argv = process.argv.slice(2);
const pair = argv[0] === '--pair';
const files = pair ? argv.slice(1) : argv;
if (!files.length) { console.error('usage: node test/cap.mjs [--pair] file.cap ...'); process.exit(2); }

function readCap(file) {
  const b = fs.readFileSync(file);
  const hLen = b.readInt32BE(0);
  const head = JSON.parse(b.subarray(4, 4 + hLen).toString('utf8'));
  let o = 4 + hLen;
  const [, , cw, ch] = head.rect, frameBytes = cw * ch + 2 * ((cw / 2) * (ch / 2));
  const out = { head, frames: [] };
  if (head.windows) {
    out.background = new Uint8Array(b.subarray(o, o + frameBytes)); o += frameBytes;
    out.rowBright = [];
    for (let i = 0; i < head.n; i++) {
      const r = new Float32Array(ch);
      for (let y = 0; y < ch; y++) { r[y] = b.readFloatBE(o); o += 4; }
      out.rowBright.push(r);
    }
    const wb = head.winW * head.winH + 2 * ((head.winW / 2) * (head.winH / 2));
    for (let i = 0; i < head.n; i++) {
      if (!head.windows[i]) { out.frames.push(null); continue; }
      out.frames.push(new Uint8Array(b.subarray(o, o + wb))); o += wb;
    }
  } else {
    for (let i = 0; i < head.n; i++) { out.frames.push(new Uint8Array(b.subarray(o, o + frameBytes))); o += frameBytes; }
  }
  out.bytes = b.length;
  return out;
}

const served = new Map(files.map((f, i) => ['cap' + i, path.resolve(f)]));
const srv = http.createServer((q, s) => {
  const n = decodeURIComponent(q.url.split('?')[0]).replace(/^\//, '');
  if (n === '') { s.setHeader('content-type', 'text/html'); return s.end('<!doctype html><script src="engine.js"></script>'); }
  const f = served.has(n) ? served.get(n) : path.join(WEB, n);
  fs.createReadStream(f).on('error', () => { s.statusCode = 404; s.end(); }).pipe(s);
}).listen(0);
await new Promise(r => srv.once('listening', r));
const browser = await chromium.launch({ args: ['--no-sandbox'] });
const page = await browser.newPage();
page.on('pageerror', e => console.log('page error:', e.message));
await page.goto(`http://127.0.0.1:${srv.address().port}/`);
await page.waitForFunction(() => typeof window.SpinMeter !== 'undefined');

async function measure(file) {
  const key = 'cap' + files.indexOf(file);
  const r = await page.evaluate(async (url) => {
    const b = new Uint8Array(await (await fetch(url)).arrayBuffer());
    const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
    const hLen = dv.getInt32(0);
    const head = JSON.parse(new TextDecoder().decode(b.subarray(4, 4 + hLen)));
    let o = 4 + hLen;
    const cw = head.rect[2], ch = head.rect[3];
    const frameBytes = cw * ch + 2 * ((cw / 2) * (ch / 2));
    const cap = Object.assign({}, head, { frames: [] });
    if (head.windows) {
      cap.background = b.subarray(o, o + frameBytes); o += frameBytes;
      cap.rowBright = [];
      for (let i = 0; i < head.n; i++) {
        const r = new Float32Array(ch);
        for (let y = 0; y < ch; y++) { r[y] = dv.getFloat32(o); o += 4; }
        cap.rowBright.push(r);
      }
      const wb = head.winW * head.winH + 2 * ((head.winW / 2) * (head.winH / 2));
      for (let i = 0; i < head.n; i++) {
        if (!head.windows[i]) { cap.frames.push(null); continue; }
        cap.frames.push(b.subarray(o, o + wb)); o += wb;
      }
    } else {
      for (let i = 0; i < head.n; i++) { cap.frames.push(b.subarray(o, o + frameBytes)); o += frameBytes; }
    }
    const t0 = performance.now();
    const out = await window.SpinMeter.measureCapture(cap, { useTrack: true, single: true, serveDir: 'any', robust: true });
    return { ms: performance.now() - t0, rps: out.rps, error: out.error || null, phase: out.phase,
             refused: (out.meta && out.meta.debug && out.meta.debug.trackRefused) || false,
             bytes: b.length, windowed: !!head.windows, box: head.winW || 0 };
  }, `http://127.0.0.1:${srv.address().port}/${key}`);
  return r;
}

const mb = (b) => (b / 1e6).toFixed(2) + ' MB';
if (pair) {
  const [a, b] = [await measure(files[0]), await measure(files[1])];
  const say = (f, r) => `${path.basename(f).padEnd(26)}${(r.error ? 'ERROR' : r.rps.toFixed(2)).padEnd(9)}` +
    `${String(r.phase || '-').padEnd(9)}${mb(r.bytes).padEnd(10)}${r.windowed ? r.box + 'px windows' : 'whole crop'}`;
  console.log(`${'file'.padEnd(26)}${'rps'.padEnd(9)}${'stretch'.padEnd(9)}${'bytes'.padEnd(10)}form`);
  console.log(say(files[0], a)); console.log(say(files[1], b));
  if (a.error || b.error) { console.log(`\n${a.error || b.error}`); process.exitCode = 1; }
  else {
    const d = Math.abs(a.rps - b.rps);
    console.log(`\ndrift ${d.toFixed(2)} rev/s · ${(a.bytes / b.bytes).toFixed(0)}x fewer bytes`);
    if (d > 0.5) process.exitCode = 1;
  }
} else {
  console.log(`${'file'.padEnd(26)}${'rps'.padEnd(9)}${'stretch'.padEnd(9)}${'bytes'.padEnd(10)}form`);
  for (const f of files) {
    const r = await measure(f);
    console.log(`${path.basename(f).padEnd(26)}${(r.error ? 'ERROR' : r.rps.toFixed(2)).padEnd(9)}` +
      `${String(r.phase || '-').padEnd(9)}${mb(r.bytes).padEnd(10)}${r.windowed ? r.box + 'px windows' : 'whole crop'}`);
    if (r.error) console.log(`   ${r.error}`);
  }
}
await browser.close(); srv.close();
