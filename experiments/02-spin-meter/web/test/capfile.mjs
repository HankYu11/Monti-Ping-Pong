// The one reader for a .cap, whole-crop or windowed, shared by the harnesses that measure one.
//
// It is a string because it has to run inside the page: a capture is tens or hundreds of
// megabytes of typed arrays, and handing that back across the Playwright boundary would cost
// more than the measurement. Each harness evaluates this once to define window.__readCap, then
// parses and measures in a single page call.
//
// It lived in three copies before, and two of them silently read a windowed capture as a
// whole-crop one -- the header says `windows`, the bytes are the background, the per-frame row
// brightness and the windows, and reading them as frames gives noise that still parses.
export const READ_CAP = `window.__readCap = async (url) => {
  const b = new Uint8Array(await (await fetch(url)).arrayBuffer());
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const hLen = dv.getInt32(0);
  const head = JSON.parse(new TextDecoder().decode(b.subarray(4, 4 + hLen)));
  let o = 4 + hLen;
  const cw = head.rect[2], ch = head.rect[3];
  const frameBytes = cw * ch + 2 * ((cw / 2) * (ch / 2));
  const cap = Object.assign({}, head, { frames: [], bytes: b.length });
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
  cap.n = cap.frames.length;
  return cap;
};`;
