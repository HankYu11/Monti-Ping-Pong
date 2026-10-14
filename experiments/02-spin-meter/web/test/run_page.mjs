import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import http from 'http'; import fs from 'fs'; import path from 'path';
const [page_path, ...files] = process.argv.slice(2);
const dir = path.dirname(page_path);
const srv = http.createServer((q, s) => { const f = path.join(dir, decodeURIComponent(q.url.split('?')[0])); s.setHeader('content-type', f.endsWith('.html') ? 'text/html; charset=utf-8' : 'application/octet-stream'); fs.createReadStream(f).on('error', () => { s.statusCode = 404; s.end(); }).pipe(s); }).listen(8766);
const b = await chromium.launch();
const p = await b.newPage();
await p.route('https://cdn.jsdelivr.net/**', (r) => r.fulfill({ path: process.env.MP4BOX, contentType: 'application/javascript' }));
await p.route('https://fonts.googleapis.com/**', (r) => r.fulfill({ body: '', contentType: 'text/css' }));
p.on('pageerror', e => console.log('page error:', e.message));
p.on('console', m => { if (m.type() === 'error') console.log('console error:', m.text()); });
await p.goto('http://127.0.0.1:8766/' + path.basename(page_path));
await p.click('details.more summary'); await p.selectOption('#fps', '240');
for (const f of files) {
  const t0 = Date.now();
  const before = await p.evaluate(() => JSON.parse(localStorage.getItem('serve-spin-meter.session.v1') || '[]').length);
  await p.setInputFiles('#file', f);
  await p.waitForFunction((n) => JSON.parse(localStorage.getItem('serve-spin-meter.session.v1') || '[]').length > n, before, { timeout: 900000 });
  const e = await p.evaluate(() => { const l = JSON.parse(localStorage.getItem('serve-spin-meter.session.v1')); return l[l.length - 1]; });
  console.log(f.split('/').pop(), ((Date.now() - t0) / 1000).toFixed(1) + 's', e.error ? 'ERROR ' + e.error :
    `${(e.rpm/60).toFixed(2)} rps | ${e.type} | lift ${e.lift.toFixed(1)} curve ${e.curve.toFixed(1)} gyro ${e.gyro.toFixed(1)} | ${e.reliable ? 'clear' : 'low'}`);
}
await p.screenshot({ path: process.env.SHOT, fullPage: true });
await p.setViewportSize({ width: 400, height: 900 });
await p.screenshot({ path: process.env.SHOT.replace('.png', '_phone.png'), fullPage: true });
await b.close(); srv.close();
