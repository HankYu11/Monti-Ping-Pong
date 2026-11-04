import fs from 'fs';
globalThis.document = { createElement: () => ({ getContext: () => ({}) }) };
new Function(fs.readFileSync(process.argv[2], 'utf8'))();
const [tag] = process.argv.slice(3);
for (const f of process.argv.slice(4)) {
  const buf = fs.readFileSync(f), hl = buf.readUInt32BE(0), head = JSON.parse(buf.subarray(4, 4 + hl).toString());
  const [, , cw, ch] = head.rect, per = cw * ch + 2 * (cw / 2) * (ch / 2);
  const frames = Array.from({ length: head.n }, (_, k) => new Uint8Array(buf.subarray(4 + hl + k * per, 4 + hl + (k + 1) * per)));
  let tracks = null;
  const res = await SpinMeter.measureCapture({ W: head.W, H: head.H, rect: head.rect, frames, full: head.full, fps: head.fps }, { single: true, serveDir: 'any', robust: true, useTrack: false, onTracks: (t) => { tracks = t; } });
  const sv = res.serves && res.serves[0];
  console.log(JSON.stringify({ file: f.split('/').pop(), setup: tag, rect: head.rect, fps: head.fps, n: head.n,
    phases: sv ? sv.phases.filter((r) => !r.error).map((r) => ({ phase: r.phase, rps: r.rps, clear: !!r.reliable, type: r.spinType, camAxis: r.camAxis, lift: r.lift, curve: r.curve, gyro: r.gyro,
      balls: r.ballsFull, seg: { start: r.segment.start, end: r.segment.end, hit: r.segment.hit, roi: r.segment.roi, track: r.segment.track } })) : [],
    tracks: (tracks || []).filter((t) => t.length >= 12) }));
}
