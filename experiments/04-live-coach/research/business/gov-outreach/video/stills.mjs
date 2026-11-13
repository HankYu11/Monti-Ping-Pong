// node stills.mjs GovPitch 100 800 ...   -> out/stills/GovPitch-f0100.jpg (half size, for checking)
// node stills.mjs StillSetup 0 --full    -> out/stills/StillSetup-f0000.png (full size, for the handout)
import { bundle } from '@remotion/bundler';
import { renderStill, selectComposition, openBrowser } from '@remotion/renderer';
import path from 'node:path';
const [id, ...rest] = process.argv.slice(2);
const full = rest.includes('--full');
const frames = rest.filter((a) => a !== '--full').map(Number);
const serveUrl = await bundle({ entryPoint: path.resolve('src/index.ts') });
const exe = '/opt/pw-browsers/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell';
const browser = await openBrowser('chrome', { browserExecutable: exe, chromiumOptions: { gl: 'swangle' } });
const composition = await selectComposition({ serveUrl, id, puppeteerInstance: browser, chromiumOptions: { gl: 'swangle' } });
for (const f of frames) {
  const out = `out/stills/${id}-f${String(f).padStart(4, '0')}.${full ? 'png' : 'jpg'}`;
  await renderStill({ composition, serveUrl, frame: f, output: out, imageFormat: full ? 'png' : 'jpeg', ...(full ? {} : { jpegQuality: 85 }), puppeteerInstance: browser, chromiumOptions: { gl: 'swangle' }, scale: full ? 1 : 0.5,
    onBrowserLog: (l) => { if (/error|Error/.test(l.text)) console.log('LOG', l.text); } });
  console.log('wrote', out);
}
await browser.close({ silent: true });
