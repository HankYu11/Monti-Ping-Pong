import { bundle } from '@remotion/bundler';
import { renderStill, selectComposition, openBrowser } from '@remotion/renderer';
import path from 'node:path';
const frames = process.argv.slice(2).map(Number);
const serveUrl = await bundle({ entryPoint: path.resolve('src/index.ts') });
const browser = await openBrowser('chrome', { browserExecutable: '/opt/pw-browsers/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell', chromiumOptions: { gl: 'swangle' } });
const composition = await selectComposition({ serveUrl, id: 'Commercial', puppeteerInstance: browser, chromiumOptions: { gl: 'swangle' } });
for (const f of frames) {
  const t0 = Date.now();
  await renderStill({ onBrowserLog: (l) => { if (/stl|error|Error/.test(l.text)) console.log('LOG', l.text); }, composition, serveUrl, frame: f, output: `out/stills/f${String(f).padStart(4, '0')}.jpg`, imageFormat: 'jpeg', jpegQuality: 85, puppeteerInstance: browser, chromiumOptions: { gl: 'swangle' }, scale: 0.5 });
  console.log('frame', f, Date.now() - t0, 'ms');
}
await browser.close({ silent: true });
