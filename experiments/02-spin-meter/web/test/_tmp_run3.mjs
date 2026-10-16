// Run SpinMeter.analyze on clips in headless Chromium and print every serve.
import { chromium } from '/opt/node-tools/node_modules/playwright/index.mjs';
import http from 'http'; import fs from 'fs'; import path from 'path';
const [page_path, ...files] = process.argv.slice(2);
const dir = path.dirname(page_path);
const srv = http.createServer((q, s) => {
  const u = decodeURIComponent(q.url.split('?')[0]);
  const f = u.startsWith('/clip/') ? files[+u.slice(6)] : path.join(dir, u);
  s.setHeader('content-type', f.endsWith('.html') ? 'text/html; charset=utf-8' : 'application/octet-stream');
  fs.createReadStream(f).on('error', () => { s.statusCode = 404; s.end(); }).pipe(s);
}).listen(+process.env.PORT || 8772);
const b = await chromium.launch();
const p = await b.newPage();
await p.route('https://cdn.jsdelivr.net/**', (r) => r.fulfill({ path: process.env.MP4BOX, contentType: 'application/javascript' }));
await p.route('https://fonts.googleapis.com/**', (r) => r.fulfill({ body: '', contentType: 'text/css' }));
p.on('pageerror', e => console.log('page error:', e.message));
await p.goto('http://127.0.0.1:8772/' + path.basename(page_path));
for (let k = 0; k < files.length; k++) {
  const out = await p.evaluate(async (k) => {
    const blob = await (await fetch('/clip/' + k)).blob();
    const res = await window.SpinMeter.analyze(new File([blob], 'c.mp4'), { captureFps: 240 });
    const f = (s) => s.error ? `ERROR ${s.error} seg ${s.segment ? s.segment.start + '-' + s.segment.end : ''}` :
      `${s.rps.toFixed(2)} rps | ${s.spinType} | lift ${s.lift.toFixed(1)} curve ${s.curve.toFixed(1)} gyro ${s.gyro.toFixed(1)} | fit ${s.fit.toFixed(3)} runner ${(+s.runnerUp).toFixed(3)} ${s.reliable ? 'CLEAR' : 'low'}${s.afterBounce ? ' | after bounce' : ''} | ${s.reference} | frames ${s.segment.start}-${s.segment.end} r ${s.ballRadiusPx.toFixed(1)} | speed ${s.flight.speed.toFixed(1)} toward ${s.flight.towardCameraDeg.toFixed(0)}`;
    return [`${res.meta.seconds.toFixed(0)} s, ${res.serves.length} serve(s)${res.error && !res.serves.length ? ' ERROR ' + res.error : ''}`]
      .concat((res.serves || []).flatMap((s, i) => [`  #${i + 1} [${s.phase}] ${f(s)}`].concat((s.phases || []).map((r) => `      ${r.phase.padEnd(7)} ${f(r)}${(r.issues||[]).length ? ' | issues: ' + r.issues.map((i) => i.text).join(' / ') : ''}`)).concat((s.notes || []).map((n) => `      note: ${n.text}`))))
      .concat((res.checks || []).filter((c) => c.status !== 'ok').map((c) => `  check: [${c.status}] ${c.title}`)).join('\n');
  }, k);
  console.log('==', path.basename(files[k]), out);
}
await b.close(); srv.close();
