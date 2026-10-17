/* Serve Spin Meter — browser engine.
 * A port of experiments/02-spin-meter/spinmeter.py (--auto --method pole). Same steps:
 *   1. find the serve's flight (small round moving blob that travels sideways)
 *   2. per frame: find the ball, smooth its path, measure where the black half points
 *   3. scan every spin rate for the best-fitting rotating pole -> rate + axis
 *   4. split the spin by its effect on the flight (Magnus): back/top, side, gyro
 * Everything runs locally; the video never leaves the device.
 */
(function spinEngine(global) {
  "use strict";

  // ------------------------------------------------------------ small numeric helpers
  function percentile(arr, p) {               // numpy default (linear interpolation)
    const a = Float64Array.from(arr).sort();
    if (!a.length) return NaN;
    const idx = (p / 100) * (a.length - 1);
    const lo = Math.floor(idx), hi = Math.ceil(idx);
    return a[lo] + (a[hi] - a[lo]) * (idx - lo);
  }
  const median = (arr) => percentile(arr, 50);
  function selectInPlace(a, lo, hi, k) {      // k-th smallest of a[lo..hi] (quickselect, reorders a)
    while (hi > lo) {
      const piv = a[(lo + hi) >> 1];
      let i = lo, j = hi;
      while (i <= j) {
        while (a[i] < piv) i++;
        while (a[j] > piv) j--;
        if (i <= j) { const t = a[i]; a[i] = a[j]; a[j] = t; i++; j--; }
      }
      if (k <= j) hi = j; else if (k >= i) lo = i; else return a[k];
    }
    return a[k];
  }
  function medianInPlace(a, n) {              // same value as median() on the first n items, without sorting
    const k = (n - 1) >> 1, m = selectInPlace(a, 0, n - 1, k);
    if (n % 2) return m;
    let m2 = Infinity;
    for (let i = k + 1; i < n; i++) if (a[i] < m2) m2 = a[i];
    return m + (m2 - m) * 0.5;
  }
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
  const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
  const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  const norm = (a) => Math.hypot(a[0], a[1], a[2]);
  const scale = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
  const matvec = (M, v) => [dot(M[0], v), dot(M[1], v), dot(M[2], v)];

  function solve3(A, b) {                      // Cramer's rule with a tiny ridge for safety
    const m = A.map((r) => r.slice());
    const tr = m[0][0] + m[1][1] + m[2][2];
    for (let i = 0; i < 3; i++) m[i][i] += 1e-12 * tr + 1e-15;
    const det = (M) => M[0][0] * (M[1][1] * M[2][2] - M[1][2] * M[2][1]) -
      M[0][1] * (M[1][0] * M[2][2] - M[1][2] * M[2][0]) + M[0][2] * (M[1][0] * M[2][1] - M[1][1] * M[2][0]);
    const D = det(m);
    const out = [0, 0, 0];
    for (let c = 0; c < 3; c++) {
      const mc = m.map((r, i) => r.map((v, j) => (j === c ? b[i] : v)));
      out[c] = det(mc) / D;
    }
    return out;
  }

  function polyfit(x, y, deg) {               // least squares, returns coefficients high->low like numpy
    const n = deg + 1;
    const A = Array.from({ length: n }, () => new Array(n).fill(0));
    const b = new Array(n).fill(0);
    for (let k = 0; k < x.length; k++) {
      const p = [];
      for (let j = 0; j < n; j++) p.push(Math.pow(x[k], deg - j));
      for (let i = 0; i < n; i++) {
        b[i] += p[i] * y[k];
        for (let j = 0; j < n; j++) A[i][j] += p[i] * p[j];
      }
    }
    if (n === 3) return solve3(A, b);
    // n === 2
    const det = A[0][0] * A[1][1] - A[0][1] * A[1][0];
    return [(b[0] * A[1][1] - A[0][1] * b[1]) / det, (A[0][0] * b[1] - A[1][0] * b[0]) / det];
  }
  const polyval = (c, x) => c.reduce((acc, v) => acc * x + v, 0);

  function gaussKernel(sigma, size) {
    const k = [], h = (size - 1) / 2;
    let s = 0;
    for (let i = 0; i < size; i++) { const v = Math.exp(-((i - h) ** 2) / (2 * sigma * sigma)); k.push(v); s += v; }
    return k.map((v) => v / s);
  }
  function reflect101(i, n) {
    if (n === 1) return 0;
    while (i < 0 || i >= n) { if (i < 0) i = -i; if (i >= n) i = 2 * n - 2 - i; }
    return i;
  }
  function blur1d(arr, sigma, size) {
    const k = gaussKernel(sigma, size), h = (size - 1) / 2, n = arr.length, out = new Float64Array(n);
    for (let i = 0; i < n; i++) { let s = 0; for (let j = 0; j < size; j++) s += k[j] * arr[reflect101(i + j - h, n)]; out[i] = s; }
    return out;
  }
  function blur2d(map, nb, sigma) {           // 3x3 Gaussian, reflect-101 borders (cv2 default)
    const k = gaussKernel(sigma, 3), out = new Float64Array(nb * nb), tmp = new Float64Array(nb * nb);
    for (let y = 0; y < nb; y++) for (let x = 0; x < nb; x++) {
      let s = 0; for (let j = 0; j < 3; j++) s += k[j] * map[y * nb + reflect101(x + j - 1, nb)]; tmp[y * nb + x] = s;
    }
    for (let y = 0; y < nb; y++) for (let x = 0; x < nb; x++) {
      let s = 0; for (let j = 0; j < 3; j++) s += k[j] * tmp[reflect101(y + j - 1, nb) * nb + x]; out[y * nb + x] = s;
    }
    return out;
  }

  // ------------------------------------------------------------ binary image helpers
  function morph(mask, W, H, kw, kh, op) {    // op: "erode" | "dilate"; OpenCV anchor = k//2
    // separable, with running counts over the window (pixels outside the image are ignored)
    const ax = Math.floor(kw / 2), ay = Math.floor(kh / 2), isE = op === "erode";
    const tmp = new Uint8Array(W * H), out = new Uint8Array(W * H);
    if (kw === 2 && kh === 2) {                                // hot path (scan pass): window = this pixel and the one before
      for (let y = 0; y < H; y++) {
        const r = y * W;
        tmp[r] = mask[r];
        if (isE) for (let x = 1; x < W; x++) tmp[r + x] = mask[r + x] & mask[r + x - 1];
        else for (let x = 1; x < W; x++) tmp[r + x] = mask[r + x] | mask[r + x - 1];
      }
      out.set(tmp.subarray(0, W));
      if (isE) for (let p = W; p < W * H; p++) out[p] = tmp[p] & tmp[p - W];
      else for (let p = W; p < W * H; p++) out[p] = tmp[p] | tmp[p - W];
      return out;
    }
    const lox = -ax, hix = kw - 1 - ax;
    for (let y = 0; y < H; y++) {                              // rows
      const r = y * W;
      let sum = 0;
      for (let j = 0; j <= Math.min(hix, W - 1); j++) sum += mask[r + j];
      for (let x = 0; x < W; x++) {
        const n = Math.min(W - 1, x + hix) - Math.max(0, x + lox) + 1;
        tmp[r + x] = isE ? (sum === n ? 1 : 0) : (sum > 0 ? 1 : 0);
        if (x + lox >= 0) sum -= mask[r + x + lox];
        if (x + hix + 1 < W) sum += mask[r + x + hix + 1];
      }
    }
    const loy = -ay, hiy = kh - 1 - ay, col = new Int32Array(W);  // columns, walked row by row
    for (let j = 0; j <= Math.min(hiy, H - 1); j++) for (let x = 0, r = j * W; x < W; x++) col[x] += tmp[r + x];
    for (let y = 0; y < H; y++) {
      const n = Math.min(H - 1, y + hiy) - Math.max(0, y + loy) + 1, r = y * W;
      if (isE) for (let x = 0; x < W; x++) out[r + x] = col[x] === n ? 1 : 0;
      else for (let x = 0; x < W; x++) out[r + x] = col[x] > 0 ? 1 : 0;
      if (y + loy >= 0) for (let x = 0, q = (y + loy) * W; x < W; x++) col[x] -= tmp[q + x];
      if (y + hiy + 1 < H) for (let x = 0, q = (y + hiy + 1) * W; x < W; x++) col[x] += tmp[q + x];
    }
    return out;
  }
  const opening = (m, W, H, k) => morph(morph(m, W, H, k, k, "erode"), W, H, k, k, "dilate");
  const closing = (m, W, H, k) => morph(morph(m, W, H, k, k, "dilate"), W, H, k, k, "erode");

  function components(mask, W, H, keepPixels) {   // 8-connected; stats like cv2
    const lab = new Int32Array(W * H), comps = [], stack = new Int32Array(W * H);
    let next = 0;
    for (let p0 = 0; p0 < W * H; p0++) {
      if (!mask[p0] || lab[p0]) continue;
      next++;
      let sp = 0; stack[sp++] = p0; lab[p0] = next;
      let area = 0, sx = 0, sy = 0, x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
      const pix = keepPixels ? [] : null;
      while (sp) {
        const p = stack[--sp], x = p % W, y = (p - x) / W;
        area++; sx += x; sy += y;
        if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
        if (pix) pix.push(x, y);
        for (let dy = -1; dy <= 1; dy++) {
          const yy = y + dy; if (yy < 0 || yy >= H) continue;
          for (let dx = -1; dx <= 1; dx++) {
            const xx = x + dx; if (xx < 0 || xx >= W) continue;
            const q = yy * W + xx;
            if (mask[q] && !lab[q]) { lab[q] = next; stack[sp++] = q; }
          }
        }
      }
      comps.push({ x: x0, y: y0, w: x1 - x0 + 1, h: y1 - y0 + 1, area, cx: sx / area, cy: sy / area, pix });
    }
    return comps;
  }

  function minEnclosingCircle(pix) {          // Welzl-style incremental, on pixel coordinates
    const n = pix.length / 2, P = [];
    for (let i = 0; i < n; i++) P.push([pix[2 * i], pix[2 * i + 1]]);
    for (let i = n - 1; i > 0; i--) { const j = (i * 2654435761) % (i + 1); [P[i], P[j]] = [P[j], P[i]]; }
    const inC = (c, p) => Math.hypot(p[0] - c[0], p[1] - c[1]) <= c[2] + 1e-7;
    const c2 = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, Math.hypot(a[0] - b[0], a[1] - b[1]) / 2];
    const c3 = (a, b, c) => {
      const d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]));
      if (Math.abs(d) < 1e-12) { const cs = [c2(a, b), c2(a, c), c2(b, c)]; return cs.sort((u, v) => v[2] - u[2])[0]; }
      const ux = ((a[0] ** 2 + a[1] ** 2) * (b[1] - c[1]) + (b[0] ** 2 + b[1] ** 2) * (c[1] - a[1]) + (c[0] ** 2 + c[1] ** 2) * (a[1] - b[1])) / d;
      const uy = ((a[0] ** 2 + a[1] ** 2) * (c[0] - b[0]) + (b[0] ** 2 + b[1] ** 2) * (a[0] - c[0]) + (c[0] ** 2 + c[1] ** 2) * (b[0] - a[0])) / d;
      return [ux, uy, Math.hypot(a[0] - ux, a[1] - uy)];
    };
    let c = [P[0][0], P[0][1], 0];
    for (let i = 1; i < n; i++) {
      if (inC(c, P[i])) continue;
      c = [P[i][0], P[i][1], 0];
      for (let j = 0; j < i; j++) {
        if (inC(c, P[j])) continue;
        c = c2(P[i], P[j]);
        for (let k = 0; k < j; k++) if (!inC(c, P[k])) c = c3(P[i], P[j], P[k]);
      }
    }
    return c;
  }

  // ------------------------------------------------------------ image ops on gray frames
  function medianFrames(frames, size) {       // per-pixel median across frames
    const n = frames.length, out = new Float32Array(size), buf = new Float64Array(n);
    for (let p = 0; p < size; p++) {
      for (let k = 0; k < n; k++) buf[k] = frames[k][p];
      out[p] = medianInPlace(buf, n);
    }
    return out;
  }
  function deflickerOne(g, bg, W, H, rowStep) {   // per-row brightness ratio, smoothed vertically
    const ratio = new Float64Array(H), tmp = new Float64Array(Math.ceil(W / rowStep));
    for (let y = 0; y < H; y++) {
      let n = 0;
      for (let x = 0; x < W; x += rowStep) tmp[n++] = g[y * W + x] / Math.max(bg[y * W + x], 1);
      ratio[y] = medianInPlace(tmp, n);
    }
    const sm = blur1d(ratio, 9, 73);
    const out = new Float32Array(W * H);
    for (let y = 0; y < H; y++) { const r = Math.max(sm[y], 0.2); for (let x = 0; x < W; x++) out[y * W + x] = g[y * W + x] / r; }
    return out;
  }

  // ------------------------------------------------------------ MP4 metadata
  function parseMp4(buf) {
    const dv = new DataView(buf), info = { rotation: 0 };
    const type = (o) => String.fromCharCode(dv.getUint8(o), dv.getUint8(o + 1), dv.getUint8(o + 2), dv.getUint8(o + 3));
    let keys = [];
    function walk(start, end, path) {
      let o = start;
      while (o + 8 <= end) {
        let size = dv.getUint32(o); const t = type(o + 4); let hdr = 8;
        if (size === 1) { size = Number(dv.getBigUint64(o + 8)); hdr = 16; } else if (size === 0) size = end - o;
        if (size < hdr || o + size > end) break;
        const b = o + hdr, e = o + size;
        if (["moov", "trak", "mdia", "minf", "stbl", "udta", "edts"].includes(t)) walk(b, e, path.concat(t));
        else if (t === "meta") {
          const qt = type(b + 4) === "hdlr" ? b : b + 4;  // QuickTime-style or ISO full box
          walk(qt, e, path.concat(t));
        } else if (t === "hdlr" && path.includes("mdia")) info._handler = type(b + 8);
        else if (t === "mdhd") {
          const v = dv.getUint8(b);
          const ts = v === 1 ? dv.getUint32(b + 20) : dv.getUint32(b + 12);
          const dur = v === 1 ? Number(dv.getBigUint64(b + 24)) : dv.getUint32(b + 16);
          info._mdhd = { ts, dur };
        } else if (t === "tkhd") {
          const v = dv.getUint8(b), m = b + (v === 1 ? 4 + 32 + 8 + 8 : 4 + 20 + 8 + 8);
          const a = dv.getInt32(m) / 65536, bb = dv.getInt32(m + 4) / 65536;
          info._rot = Math.round(Math.atan2(bb, a) * 180 / Math.PI);
        } else if (t === "stsz") {
          const count = dv.getUint32(b + 8);
          if (info._handler === "vide" && !info.sampleCount) {
            info.sampleCount = count;
            if (info._mdhd) info.playbackFps = count / (info._mdhd.dur / info._mdhd.ts);
            info.rotation = info._rot || 0;
          }
        } else if (t === "keys") {
          const n = dv.getUint32(b + 4); let p = b + 8; keys = [];
          for (let i = 0; i < n && p + 8 <= e; i++) {
            const ks = dv.getUint32(p); let s = "";
            for (let j = p + 8; j < p + ks; j++) s += String.fromCharCode(dv.getUint8(j));
            keys.push(s); p += ks;
          }
        } else if (t === "ilst") {
          let p = b;
          while (p + 8 <= e) {
            const isz = dv.getUint32(p), idx = dv.getUint32(p + 4);
            const d = p + 8;
            if (type(d + 4) === "data") {
              const dtype = dv.getUint32(d + 8) & 0xffffff;
              const name = keys[idx - 1] || "";
              if (name === "com.android.capture.fps" && dtype === 23) info.captureFps = dv.getFloat32(d + 16);
            }
            if (isz < 8) break;
            p += isz;
          }
        }
        o += size;
      }
    }
    try { walk(0, buf.byteLength, []); } catch (e) { /* best effort */ }
    delete info._handler; delete info._mdhd; delete info._rot;
    return info;
  }

  // ------------------------------------------------------------ video frame access
  // Preferred: decode the file once, front to back (WebCodecs + mp4box.js). Seeking frame by
  // frame makes the browser re-decode from the last keyframe every time, which is ~100x slower.
  function makeCanvas(W, H) {
    const c = document.createElement("canvas"); c.width = W; c.height = H;
    return { c, ctx: c.getContext("2d", { willReadFrequently: true }) };
  }
  // gray as in cv2.cvtColor(BGR2GRAY), rounded; colour as centred chroma (U = blue - gray, V = red - gray)
  function rgbToColor(d, n) {
    const g = new Float32Array(n), u = new Float32Array(n), v = new Float32Array(n);
    for (let p = 0, q = 0; p < n; p++, q += 4) {
      const y = 0.299 * d[q] + 0.587 * d[q + 1] + 0.114 * d[q + 2];
      g[p] = Math.round(y); u[p] = 0.564 * (d[q + 2] - y); v[p] = 0.713 * (d[q] - y);
    }
    return { g, u, v };
  }
  function toColor(ctx, src, W, H, roi) {
    ctx.drawImage(src, 0, 0, W, H);
    const [x, y, w, h] = roi;
    return rgbToColor(ctx.getImageData(x, y, w, h).data, w * h);
  }

  // What happened inside, for "Details for troubleshooting" on the page (reset per video).
  let DBG = {};
  async function webCodecsReader(buf) {
    if (typeof VideoDecoder === "undefined" || typeof MP4Box === "undefined") return null;
    const mp4 = MP4Box.createFile();
    let info = null; const samples = [];
    mp4.onReady = (i) => { info = i; };
    mp4.onSamples = (id, user, ss) => { for (const x of ss) samples.push(x); };
    buf.fileStart = 0;                        // no copy: long multi-serve videos can be hundreds of MB
    mp4.appendBuffer(buf); mp4.flush();
    if (!info || !info.videoTracks.length) return null;
    const track = info.videoTracks[0];
    mp4.setExtractionOptions(track.id, null, { nbSamples: track.nb_samples });
    mp4.start(); mp4.flush();
    if (!samples.length) return null;
    const trak = mp4.getTrackById(track.id), entry = trak.mdia.minf.stbl.stsd.entries[0];
    const box = entry.avcC || entry.hvcC || entry.av1C || null;
    let description;
    if (box) { const ds = new DataStream(undefined, 0, DataStream.BIG_ENDIAN); box.write(ds); description = new Uint8Array(ds.buffer, 8); }
    const W = track.track_width || track.video.width, H = track.track_height || track.video.height;
    const config = { codec: track.codec, codedWidth: track.video.width, codedHeight: track.video.height };
    if (description) config.description = description;
    // Prefer a software decoder: its frames sit in normal memory, so the brightness plane can be read
    // directly. Hardware decoders (phones) often hand out GPU frames that need the slower canvas path.
    let sup = { supported: false };
    try { sup = await VideoDecoder.isConfigSupported(Object.assign({}, config, { hardwareAcceleration: "prefer-software" })); } catch (e) { /* older browsers */ }
    if (sup.supported) config.hardwareAcceleration = "prefer-software";
    else { sup = await VideoDecoder.isConfigSupported(config); if (!sup.supported) return null; }
    DBG.decoder = { codec: config.codec, size: `${config.codedWidth}x${config.codedHeight}`, softwarePreferred: config.hardwareAcceleration === "prefer-software" };
    // presentation index of every sample (decode order may differ when B-frames are used)
    const ts = samples.map((x) => Math.round(1e6 * x.cts / x.timescale));
    const order = ts.map((t, k) => [t, k]).sort((u, v) => u[0] - v[0]);
    const presOfTs = new Map(), decOfPres = new Array(samples.length);
    order.forEach(([t, k], i) => { presOfTs.set(t, i); decOfPres[i] = k; });
    const Wc = track.video.width, Hc = track.video.height;
    return {
      kind: "decoder", n: samples.length, W: Wc, H: Hc,
      async run(from, to, cb) {
        to = Math.min(to, samples.length);
        let dFrom = Infinity, dTo = -1;
        for (let i = from; i < to; i++) { dFrom = Math.min(dFrom, decOfPres[i]); dTo = Math.max(dTo, decOfPres[i]); }
        let k0 = dFrom; while (k0 > 0 && !samples[k0].is_sync) k0--;
        const k1 = Math.min(samples.length - 1, dTo + 8);
        let failure = null, pending = 0, wake = null;
        const kick = () => { if (wake) { const w = wake; wake = null; w(); } };
        const wait = () => new Promise((r) => { wake = r; setTimeout(kick, 50); });
        const dec = new VideoDecoder({
          output: (frame) => {
            const i = presOfTs.get(frame.timestamp);
            const F = (DBG.frames = DBG.frames || { decoded: 0, unmatched: 0, formats: {} });
            F.decoded++; F.formats[frame.format || "opaque"] = (F.formats[frame.format || "opaque"] || 0) + 1;
            if (i === undefined) F.unmatched++;
            if (i === undefined || i < from || i >= to || failure) { frame.close(); return; }
            pending++;
            Promise.resolve().then(() => cb(i, frame)).catch((e) => { failure = failure || e; })
              .finally(() => { frame.close(); pending--; kick(); });
          },
          error: (e) => { failure = e; kick(); },
        });
        dec.ondequeue = kick;
        dec.configure(config);
        for (let k = k0; k <= k1 && !failure; k++) {
          while ((dec.decodeQueueSize > 4 || pending > 2) && !failure) await wait();
          const x = samples[k];
          dec.decode(new EncodedVideoChunk({ type: x.is_sync ? "key" : "delta", timestamp: ts[k],
            duration: Math.round(1e6 * x.duration / x.timescale), data: x.data }));
        }
        if (!failure) { try { await dec.flush(); } catch (e) { failure = failure || e; } }
        while (pending > 0) await wait();
        try { dec.close(); } catch (e) { /* already closed after an error */ }
        if (failure) throw failure;
      },
      close() {},
    };
  }

  async function seekReader(file, playbackFps, nFramesHint) {   // fallback: slow but universal
    const url = URL.createObjectURL(file);
    const video = document.createElement("video");
    video.muted = true; video.playsInline = true; video.preload = "auto"; video.src = url;
    await new Promise((res, rej) => {
      video.onloadeddata = res;
      video.onerror = () => rej(new Error("This browser can't play the video file."));
    });
    const n = nFramesHint || Math.round(video.duration * playbackFps);
    const seek = (t) => new Promise((res) => {
      let done = false; const fin = () => { if (!done) { done = true; res(); } };
      video.addEventListener("seeked", () => {
        if (video.requestVideoFrameCallback) { video.requestVideoFrameCallback(() => fin()); setTimeout(fin, 120); } else fin();
      }, { once: true });
      video.currentTime = t;
    });
    return {
      kind: "seek", n, W: video.videoWidth, H: video.videoHeight,
      async run(from, to, cb) { for (let i = from; i < Math.min(to, n); i++) { await seek((i + 0.5) / playbackFps); await cb(i, video); } },
      close() { URL.revokeObjectURL(url); },
    };
  }

  function halfColor(ctx, src, W, H) {        // scan passes: let the canvas shrink the frame (4x fewer pixels)
    const w = Math.floor(W / 2), h = Math.floor(H / 2);
    ctx.imageSmoothingEnabled = true; ctx.imageSmoothingQuality = "medium";
    ctx.drawImage(src, 0, 0, w, h);
    return Object.assign(rgbToColor(ctx.getImageData(0, 0, w, h).data, w * h), { w, h });
  }

  // Fast path: read the decoded VideoFrame's planes directly, no canvas round trip.
  // Brightness = Y mapped from video range to 0-255. Colour = the two chroma planes, centred on 0:
  // they tell a black ball half from a blue table of the same brightness.
  const PLANE_FORMATS = new Set(["I420", "I420A", "I422", "I444", "NV12"]);
  let planesBroken = false;
  async function framePlanes(frame, W, H) {  // -> { buf, offset, stride, lut, full, u, v }, or null when this frame can't be read this way
    if (planesBroken || typeof VideoFrame === "undefined" || !(frame instanceof VideoFrame)) return null;
    if (!PLANE_FORMATS.has(frame.format) || frame.visibleRect.width !== W || frame.visibleRect.height !== H) return null;
    try {
      const buf = new Uint8Array(frame.allocationSize());
      const layout = await frame.copyTo(buf);
      const full = !!(frame.colorSpace && frame.colorSpace.fullRange);
      DBG.fullRange = full; DBG.chroma = frame.format;
      const P = { buf, offset: layout[0].offset, stride: layout[0].stride, lut: full ? LUT_FULL : LUT_VIDEO, full, u: null, v: null };
      // chroma at half size both ways (I420, NV12); other layouts are read as brightness only
      if (frame.format === "I420" || frame.format === "I420A") {
        P.u = { offset: layout[1].offset, stride: layout[1].stride, step: 1 };
        P.v = { offset: layout[2].offset, stride: layout[2].stride, step: 1 };
      } else if (frame.format === "NV12") {
        P.u = { offset: layout[1].offset, stride: layout[1].stride, step: 2 };
        P.v = { offset: layout[1].offset + 1, stride: layout[1].stride, step: 2 };
      }
      return P;
    } catch (e) { planesBroken = true; return null; }
  }
  const LUT_FULL = Float32Array.from({ length: 256 }, (_, v) => v);
  const LUT_VIDEO = Float32Array.from({ length: 256 }, (_, v) => clamp(Math.round((v - 16) * 255 / 219), 0, 255));
  const CHROMA_FULL = Float32Array.from({ length: 256 }, (_, v) => v - 128);
  const CHROMA_VIDEO = Float32Array.from({ length: 256 }, (_, v) => (v - 128) * 255 / 224);
  const CHROMA_GAIN = 1.5;                    // a colour change counts this much against a brightness change
  function lumaHalf({ buf, offset, stride, lut }, W, H) {   // 2x2 average, rounded (cv2 INTER_AREA)
    const w = Math.floor(W / 2), h = Math.floor(H / 2), out = new Float32Array(w * h);
    for (let y = 0; y < h; y++) {
      const r0 = offset + 2 * y * stride, r1 = r0 + stride;
      for (let x = 0; x < w; x++) {
        const c = 2 * x;
        out[y * w + x] = ((lut[buf[r0 + c]] + lut[buf[r0 + c + 1]] + lut[buf[r1 + c]] + lut[buf[r1 + c + 1]]) * 0.25 + 0.5) | 0;
      }
    }
    return { g: out, w, h };
  }
  function chromaHalf(P, c, W, H) {          // the chroma plane is already half size
    const w = Math.floor(W / 2), h = Math.floor(H / 2), out = new Float32Array(w * h), lut = P.full ? CHROMA_FULL : CHROMA_VIDEO;
    for (let y = 0; y < h; y++) { const r = c.offset + y * c.stride; for (let x = 0; x < w; x++) out[y * w + x] = lut[P.buf[r + x * c.step]]; }
    return out;
  }
  function lumaCrop({ buf, offset, stride, lut }, roi) {
    const [x0, y0, w, h] = roi, out = new Float32Array(w * h);
    for (let y = 0; y < h; y++) { const r = offset + (y0 + y) * stride + x0; for (let x = 0; x < w; x++) out[y * w + x] = lut[buf[r + x]]; }
    return out;
  }
  function chromaCrop(P, c, roi) {           // full-size crop from the half-size chroma plane
    const [x0, y0, w, h] = roi, out = new Float32Array(w * h), lut = P.full ? CHROMA_FULL : CHROMA_VIDEO;
    for (let y = 0; y < h; y++) { const r = c.offset + ((y0 + y) >> 1) * c.stride; for (let x = 0; x < w; x++) out[y * w + x] = lut[P.buf[r + ((x0 + x) >> 1) * c.step]]; }
    return out;
  }
  const readStats = { direct: 0, canvas: 0 };
  async function grabHalf(ctx, src, W, H) {   // half-size frame for the scan passes: { g, u, v } (u, v may be null)
    const P = await framePlanes(src, W, H);
    readStats[P ? "direct" : "canvas"]++;
    if (!P) return halfColor(ctx, src, W, H);
    return { g: lumaHalf(P, W, H).g, u: P.u ? chromaHalf(P, P.u, W, H) : null, v: P.v ? chromaHalf(P, P.v, W, H) : null };
  }
  async function grabColor(ctx, src, W, H, roi) {
    const P = await framePlanes(src, W, H);
    readStats[P ? "direct" : "canvas"]++;
    if (!P) return toColor(ctx, src, W, H, roi);
    return { g: lumaCrop(P, roi), u: P.u ? chromaCrop(P, P.u, roi) : null, v: P.v ? chromaCrop(P, P.v, roi) : null };
  }
  function colourDiff(g, bg, col, p) {        // how different a pixel is from the background, in brightness or colour
    let d = Math.abs(g - bg);
    if (col) d = Math.max(d, CHROMA_GAIN * Math.abs(col.u[p] - col.bgU[p]), CHROMA_GAIN * Math.abs(col.v[p] - col.bgV[p]));
    return d;
  }

  // ------------------------------------------------------------ scan pass: candidates in one frame
  function scanFrame(job, ctx) {             // job: { buf, offset, stride, full, pu, pv } (planes) or { g, u, v } (half size)
    const { sw, sh, W, H } = ctx;
    const sec = ctx.sections.find((q) => job.i >= q.from && job.i < q.to) || ctx.sections[ctx.sections.length - 1];
    const { bgf, bg, bgU, bgV } = sec;
    let { g, u, v } = job;
    if (!g) {
      const P = { buf: job.buf, offset: job.offset, stride: job.stride, lut: job.full ? LUT_FULL : LUT_VIDEO, full: job.full };
      g = lumaHalf(P, W, H).g;
      if (job.pu) { u = chromaHalf(P, job.pu, W, H); v = chromaHalf(P, job.pv, W, H); }
    }
    const col = u && bgU ? { u, v, bgU, bgV } : null;
    const d = deflickerOne(g, bgf, sw, sh, 2);
    const mask = new Uint8Array(sw * sh);
    for (let p = 0; p < mask.length; p++) mask[p] = colourDiff(clamp(Math.floor(d[p]), 0, 255), bg[p], col, p) > 30 ? 1 : 0;
    const m2 = opening(mask, sw, sh, 2);
    const fc = [];
    for (const c of components(m2, sw, sh, false)) {
      if (c.area < 12 || c.area > 2500) continue;
      if (Math.max(c.w, c.h) / Math.max(1, Math.min(c.w, c.h)) > 1.8 || c.area / (c.w * c.h) < 0.5) continue;
      fc.push([c.cx, c.cy, Math.sqrt(c.area / Math.PI)]);
    }
    return fc;
  }

  // Worker threads built from this same file. Returns null (search on the main thread) when
  // workers are unavailable, e.g. blocked by the page's security policy.
  async function scanPool(ctx) {
    if (typeof Worker === "undefined" || typeof Blob === "undefined" || typeof document === "undefined") return null;
    const n = Math.max(1, Math.min(4, (navigator.hardwareConcurrency || 2) - 1));
    if (n < 2) return null;
    let url = null;
    const workers = [];
    try {
      url = URL.createObjectURL(new Blob(["(" + spinEngine.toString() + ")(self);"], { type: "text/javascript" }));
      for (let k = 0; k < n; k++) workers.push(new Worker(url));
      await Promise.all(workers.map((w) => new Promise((res, rej) => {
        const t = setTimeout(() => rej(new Error("worker timeout")), 3000);
        w.onmessage = (e) => { if (e.data && e.data.ready) { clearTimeout(t); res(); } };
        w.onerror = (e) => { clearTimeout(t); rej(e); };
        w.postMessage({ init: ctx });
      })));
    } catch (e) {
      workers.forEach((w) => w.terminate());
      if (url) URL.revokeObjectURL(url);
      return null;
    }
    const idle = workers.slice(), waiting = [];
    let failure = null, busy = 0;
    const free = (w) => { idle.push(w); busy--; const r = waiting.shift(); if (r) r(); };
    workers.forEach((w) => { w.onerror = (e) => { failure = failure || new Error("worker failed: " + (e.message || e)); free(w); }; });
    return {
      async submit(job, store) {
        while (!idle.length) { if (failure) throw failure; await new Promise((r) => waiting.push(r)); }
        if (failure) throw failure;
        const w = idle.pop(); busy++;
        w.onmessage = (e) => { store(e.data.i, e.data.fc); free(w); };
        w.postMessage({ job }, [job.buf ? job.buf.buffer : job.g.buffer]);
      },
      async drain() { while (busy > 0) { if (failure) throw failure; await new Promise((r) => waiting.push(r)); } if (failure) throw failure; },
      close() { workers.forEach((w) => w.terminate()); URL.revokeObjectURL(url); },
    };
  }
  if (typeof document === "undefined" && typeof WorkerGlobalScope !== "undefined" && global instanceof WorkerGlobalScope) {
    let wctx = null;
    global.onmessage = (e) => {
      if (e.data.init) { wctx = e.data.init; global.postMessage({ ready: true }); return; }
      const job = e.data.job;
      global.postMessage({ i: job.i, fc: scanFrame(job, wctx) });
    };
  }

  // ------------------------------------------------------------ bounces
  // A bounce shows as a sudden change of the ball's velocity in the picture. A ball coming
  // toward the camera also speeds up in the picture, but smoothly, so compare the velocity
  // over a few frames just before and just after each point.
  function velocity(tr, a, b) {               // least-squares velocity of tr[a..b) in px per frame
    const n = b - a; let st = 0, sx = 0, sy = 0, stt = 0, stx = 0, sty = 0;
    for (let k = a; k < b; k++) { const t = tr[k][0], x = tr[k][1], y = tr[k][2]; st += t; sx += x; sy += y; stt += t * t; stx += t * x; sty += t * y; }
    const den = n * stt - st * st || 1;
    return [(n * stx - st * sx) / den, (n * sty - st * sy) / den];
  }
  function bounceIndex(tr, k = 5) {           // index of the first frame after a bounce, or -1
    let best = -1, bestDv = 0;
    for (let b = k; b <= tr.length - k; b++) {
      const v0 = velocity(tr, b - k, b), v1 = velocity(tr, b, b + k);
      const dv = Math.hypot(v1[0] - v0[0], v1[1] - v0[1]), sp = 0.5 * (Math.hypot(...v0) + Math.hypot(...v1));
      if (dv > Math.max(2, 0.5 * sp) && dv > bestDv) { bestDv = dv; best = b; }
    }
    return best;
  }
  // A kink is either the hit (a falling toss suddenly flies off fast) or a bounce (the ball keeps
  // most of its speed but changes direction).
  function kinkKind(tr, b, k = 5) {
    return Math.hypot(...velocity(tr, b, b + k)) > 1.6 * Math.hypot(...velocity(tr, b - k, b)) ? "contact" : "bounce";
  }
  function splitAtKinks(tr, kind = "start", depth = 0) {   // [{ pts, kind }] in time order; kind = how the piece begins
    const b = depth < 3 ? bounceIndex(tr) : -1;
    if (b < 0) return [{ pts: tr, kind }];
    return splitAtKinks(tr.slice(0, b), kind, depth + 1).concat(splitAtKinks(tr.slice(b), kinkKind(tr, b), depth + 1));
  }

  // ------------------------------------------------------------ 1. find the flights (one per serve)
  async function findFlights(R, progress, opt) {
    const skipAfter = opt.skipAfterContact ?? 4, maxFrames = opt.maxFrames ?? 60, minLen = 12;
    const nFrames = R.n, { ctx } = makeCanvas(R.W, R.H);
    const stride = Math.max(4, Math.ceil(nFrames / 40));
    const samples = [];
    let sw = Math.floor(R.W / 2), sh = Math.floor(R.H / 2);
    // background samples, kept small: brightness as bytes, colour as signed bytes
    const keep = (k, x) => samples.push({ k, g: Uint8Array.from(x.g, (v) => clamp(Math.round(v), 0, 255)),
      u: x.u ? Int8Array.from(x.u, (v) => clamp(Math.round(v), -128, 127)) : null, v: x.v ? Int8Array.from(x.v, (v) => clamp(Math.round(v), -128, 127)) : null });
    if (R.kind === "seek") {
      for (let i = 0; i < nFrames; i += stride) await R.run(i, i + 1, async (k, src) => keep(k, await grabHalf(ctx, src, R.W, R.H)));
    } else {
      await R.run(0, nFrames, async (k, src) => {
        if (k % stride === 0) keep(k, await grabHalf(ctx, src, R.W, R.H));
        if (k % 8 === 0) progress("Learning the background", 0.1 * k / nFrames);
      });
    }
    const withColour = samples.every((s) => s.u);
    function background(set) {               // still background from a set of samples
      const bgf = medianFrames(set.map((s) => s.g), sw * sh);
      const defl = set.map(({ g }) => { const d = deflickerOne(g, bgf, sw, sh, 2); for (let p = 0; p < d.length; p++) d[p] = clamp(Math.floor(d[p]), 0, 255); return d; });
      const bg = Uint8Array.from(medianFrames(defl, sw * sh), Math.trunc);
      const bgU = withColour ? Int8Array.from(medianFrames(set.map((s) => s.u), sw * sh), Math.round) : null;
      const bgV = withColour ? Int8Array.from(medianFrames(set.map((s) => s.v), sw * sh), Math.round) : null;
      return { bgf, bg, bgU, bgV, defl };
    }
    const whole = background(samples), { bgf, bg, defl } = whole;
    // (one background per section of the video was tried, for a phone still being set down at
    // the start: with ~10 samples per section, the server's body leaked into the background)
    const sections = [{ from: 0, to: nFrames, bgf: whole.bgf, bg: whole.bg, bgU: whole.bgU, bgV: whole.bgV }];
    if (opt.onScene) {                        // setup check: overall light, and how much of the view keeps changing
      let sum = 0; for (let p = 0; p < bg.length; p++) sum += bg[p];
      const moving = defl.map((d) => { let n = 0; for (let p = 0; p < d.length; p++) if (Math.abs(d[p] - bg[p]) > 30) n++; return n / d.length; });
      opt.onScene({ light: sum / bg.length, moving: median(moving) });
    }
    const cands = new Array(nFrames).fill(null).map(() => []);
    // the per-frame search runs on worker threads when the browser allows it, else here
    const scanCtx = { sections, sw, sh, W: R.W, H: R.H };
    const pool = await scanPool(scanCtx);
    let doneN = 0;
    const store = (i, fc) => { cands[i] = fc; if (++doneN % 4 === 0) progress("Looking for the ball", 0.1 + 0.75 * doneN / nFrames); };
    try {
      await R.run(0, nFrames, async (i, src) => {
        const P = await framePlanes(src, R.W, R.H);
        readStats[P ? "direct" : "canvas"]++;
        const job = P ? { i, buf: P.buf, offset: P.offset, stride: P.stride, full: P.full, pu: P.u, pv: P.v } : Object.assign({ i }, halfColor(ctx, src, R.W, R.H));
        if (pool) await pool.submit(job, store);
        else store(i, scanFrame(job, scanCtx));
      });
      if (pool) await pool.drain();
    } finally { if (pool) pool.close(); }
    // greedy linking with a constant-velocity guess
    let tracks = [], active = [];
    // Best matches first (close to the predicted spot, similar size), so a speck of noise near
    // the flight can't take the ball away from its own track.
    cands.forEach((cs, i) => {
      const used = new Set(), pairs = [];
      active.forEach((tr, ti) => {
        const [f1, x1, y1, r1] = tr[tr.length - 1];
        let px = x1, py = y1;
        if (tr.length > 1) {
          const [f0, x0, y0] = tr[tr.length - 2], df = Math.max(1, f1 - f0);
          px = x1 + (x1 - x0) / df * (i - f1); py = y1 + (y1 - y0) / df * (i - f1);
        }
        const gate = Math.max(25.0, 3 * r1);   // a ball coming closer grows and speeds up in the picture
        cs.forEach((c, j) => {
          const dd = Math.hypot(c[0] - px, c[1] - py);
          if (dd < gate) pairs.push([dd + 8 * Math.abs(Math.log(c[2] / r1)), ti, j]);
        });
      });
      pairs.sort((a, b) => a[0] - b[0]);
      const took = new Set();
      for (const [, ti, j] of pairs) {
        if (took.has(ti) || used.has(j)) continue;
        took.add(ti); used.add(j); active[ti].push([i, ...cs[j]]);
      }
      const still = active.filter((tr) => i - tr[tr.length - 1][0] <= 2);
      tracks = tracks.concat(active.filter((tr) => i - tr[tr.length - 1][0] > 2));
      active = still.concat(cs.map((c, j) => (used.has(j) ? null : [[i, ...c]])).filter(Boolean));
    });
    tracks = tracks.concat(active);
    if (opt.onTracks) opt.onTracks(   // for tests: every track of 8+ frames, and the per-frame candidates
      tracks.filter((tr) => tr.length >= 8).map((tr) => tr.map((q) => q.map((v) => Math.round(v * 10) / 10))), cands);
    // A flight may go across the picture, toward the camera or away from it. Not a flight: a
    // toss (mostly upward), a ball dropping straight down without coming closer, slow movers.
    const why = {};
    const no = (reason) => { why[reason] = (why[reason] || 0) + 1; return false; };
    const good = tracks.filter((tr) => {
      if (tr.length < minLen) return tr.length >= 6 ? no("short") : false;
      const a = tr[0], b = tr[tr.length - 1], dx = b[1] - a[1], dy = b[2] - a[2];
      const disp = Math.hypot(dx, dy);
      if (disp < 30) return no("barely moves");
      // specks from flickering lights and compression: smaller than any ball the meter could read
      if (median(tr.map((p) => p[3])) < 2.5) return no("tiny");
      // a flight moves steadily: not a flickering light or a jittery spot that drifts
      if (disp / Math.max(1, b[0] - a[0]) < 1.0) return no("too slow");
      let path = 0;
      for (let k = 1; k < tr.length; k++) path += Math.hypot(tr[k][1] - tr[k - 1][1], tr[k][2] - tr[k - 1][2]);
      if (disp < 0.6 * path) return no("zigzag");
      if (dy < 0 && -dy > Math.abs(dx)) return no("upward (toss)");
      const grow = median(tr.slice(-4).map((p) => p[3])) / Math.max(1e-6, median(tr.slice(0, 4).map((p) => p[3])));
      if (dy > 2 * Math.abs(dx) && grow < 1.3 && grow > 1 / 1.3) return no("straight drop");
      return true;
    });
    const fmtTr = (tr) => `${tr[0][0]}-${tr[tr.length - 1][0]} (${tr.length}) ${tr[0].slice(1, 3).map(Math.round)}->${tr[tr.length - 1].slice(1, 3).map(Math.round)} r${tr[0][3].toFixed(1)}-${tr[tr.length - 1][3].toFixed(1)}`;
    DBG.search = { framesWithCandidates: cands.filter((c) => c.length).length, candidatesPerFrame: +(cands.reduce((s, c) => s + c.length, 0) / Math.max(1, nFrames)).toFixed(1),
      tracks: tracks.filter((tr) => tr.length >= 6).length, rejected: why, accepted: good.length,
      longestMovers: tracks.filter((tr) => tr.length >= 8 && Math.hypot(tr[tr.length - 1][1] - tr[0][1], tr[tr.length - 1][2] - tr[0][2]) >= 30)
        .sort((x, y) => y.length - x.length).slice(0, 5).map(fmtTr), accepted5: good.slice(0, 5).map(fmtTr) };
    // One serve per flight. Tracks closer than `gap` frames in time are pieces of the same
    // flight (or the ball coming back); keep the longest. Serves are seconds apart.
    // Turn every candidate into a serve (stretches to measure), then keep one per time window:
    // the one with the most measurable frames of the biggest ball. Picking the longest track
    // instead let a long noise track push the real serve out and then yield nothing.
    const gap = opt.serveGap ?? 120, built = [];
    for (let tr of good) {
      // the moving part: split where the ball stops (held, caught, picked up)
      const runs = []; let cur = [tr[0]];
      for (let k = 1; k < tr.length; k++) {
        const a = tr[k - 1], b = tr[k], step = Math.hypot(b[1] - a[1], b[2] - a[2]) / Math.max(1, b[0] - a[0]);
        if (step > 1.0) cur.push(b); else { if (cur.length > 1) runs.push(cur); cur = [b]; }
      }
      runs.push(cur);
      tr = runs.reduce((a, b) => (b.length > a.length ? b : a));
      // Cut the flight at its kinks. The serve starts at the hit: a toss falling into view before
      // it is dropped. Then measure two stretches, since the bounce changes the spin:
      //   off the paddle - from just after the hit to the first bounce (the serve's own spin)
      //   after the bounce - up to the next kink (what the receiver faces)
      const pieces = splitAtKinks(tr);
      let s0 = 0;
      for (let k = 0; k < pieces.length && pieces[k].kind !== "bounce"; k++) if (pieces[k].kind === "contact") s0 = k;
      const first = pieces[s0], next = pieces[s0 + 1], sawBounce = !!next && next.kind === "bounce";
      const parts = [], notes = [];
      const note = (phase, text, fix, frames) => notes.push({ phase, text, fix, frames });
      // the first frames after the hit still have the paddle next to the ball
      const off = first.pts.slice(skipAfter, skipAfter + maxFrames);
      const hitSeen = first.kind === "contact";
      const from = hitSeen ? "the hit" : edgeOf(first.pts[0]) ? `the ball came into the picture (${edgeOf(first.pts[0])} edge)` : "the ball was first seen";
      if (off.length >= 8) parts.push(makePart(sawBounce ? "paddle" : "flight", off));
      else if (sawBounce) note("paddle", `Off the paddle: not measured. Only ${first.pts.length} frames from when ${from} to the bounce (needs ${skipAfter + 8}).`,
        edgeOf(first.pts[0]) ? "Turn the camera so the paddle is in the picture." :
          "The ball was hidden or blurred near the paddle, or the serve bounced very soon. Film from the side so the paddle doesn't cover the ball.", first.pts.length);
      if (sawBounce) {
        const after = next.pts.slice(2, 2 + maxFrames), last = next.pts[next.pts.length - 1];
        if (after.length >= 8) parts.push(makePart("bounce", after));
        else note("bounce", `After the bounce: not measured. Only ${next.pts.length} frames after the bounce (needs 10), then the ball ${edgeOf(last) ? `left the picture (${edgeOf(last)} edge)` : "was lost"}.`,
          edgeOf(last) ? "Widen the view past the bounce point: move the camera back a little or turn it toward the bounce." : "Keep the background behind the bounce even, and add light.", next.pts.length);
      } else {
        const last = first.pts[first.pts.length - 1];
        note("flight", `No bounce seen: the ball ${edgeOf(last) ? `left the picture (${edgeOf(last)} edge)` : "was lost"} before bouncing, so this can't be placed before or after the bounce.`,
          edgeOf(last) ? "Get the first bounce in the picture: turn the camera toward it or move back a little." : "Keep the background around the bounce even, and add light.");
      }
      if (!parts.length) continue;
      const frames = parts.reduce((n, q) => n + q.track.length, 0), size = median(parts[0].track.map((p) => p[3]));
      built.push({ start: parts[0].start, end: parts[parts.length - 1].end, parts, notes, score: frames * size });
    }
    const segs = [];
    for (const sv of built.sort((a, b) => b.score - a.score))
      if (segs.every((f) => sv.start > f.end + gap || sv.end < f.start - gap)) segs.push(sv);
    DBG.search.serves = segs.map((sv) => `${sv.start}-${sv.end} score ${Math.round(sv.score)}`);
    function edgeOf(p) {                     // which edge of the picture a track point touches, or ""
      const m = 3 * p[3] + 6;
      return p[1] < m ? "left" : p[1] > sw - m ? "right" : p[2] < m ? "top" : p[2] > sh - m ? "bottom" : "";
    }
    function makePart(kind, pts) {           // a stretch to measure: its frames, a crop around it, and where the ball was
      const xs = pts.map((p) => p[1] / 0.5), ys = pts.map((p) => p[2] / 0.5);
      const r = Math.max(...pts.map((p) => p[3])) / 0.5, pad = Math.max(3 * r, 4 * median(pts.map((p) => p[3])) / 0.5);
      const x0 = Math.trunc(Math.max(0, Math.min(...xs) - pad)), y0 = Math.trunc(Math.max(0, Math.min(...ys) - pad));
      const x1 = Math.trunc(Math.min(sw / 0.5, Math.max(...xs) + pad)), y1 = Math.trunc(Math.min(sh / 0.5, Math.max(...ys) + pad));
      return { kind, start: pts[0][0], end: pts[pts.length - 1][0] + 1, roi: [x0, y0, x1 - x0, y1 - y0],
        track: pts.map((p) => [p[0], p[1] / 0.5, p[2] / 0.5, p[3] / 0.5]) };
    }
    segs.sort((a, b) => a.start - b.start);
    segs.ballFrames = cands.filter((c) => c.length).length;   // for the "no serve found" diagnosis
    return segs;
  }

  // ------------------------------------------------------------ 2. ball + pole per frame
  // The ball in one frame: pixels that differ from the background in brightness or colour.
  // With a predicted position (from the flight search), take the blob nearest to it.
  function findBall(g, bg, W, H, minR, col = null, pred = null) {
    const mask = new Uint8Array(W * H);
    for (let p = 0; p < mask.length; p++) mask[p] = colourDiff(g[p], bg[p], col, p) > 25 ? 1 : 0;
    const m = opening(closing(mask, W, H, 5), W, H, 3);
    let best = null, bestScore = Infinity;
    for (const c of components(m, W, H, true)) {
      if (c.w < 2 * minR || c.h < 2 * minR) continue;
      const aspect = Math.max(c.w, c.h) / Math.min(c.w, c.h);
      const fill = c.area / (Math.PI * (Math.max(c.w, c.h) / 2) ** 2);
      if (aspect > 1.6 || fill < 0.6) continue;
      let score = -c.area;
      if (pred) {
        const d = Math.hypot(c.cx - pred[0], c.cy - pred[1]);
        if (d > 2 * pred[2] + 15) continue;
        score = d;
      }
      if (score < bestScore) { bestScore = score; best = c; }
    }
    if (!best) return null;
    const [cx, cy, r] = minEnclosingCircle(best.pix);
    return [cx, cy, Math.min(r, Math.sqrt(best.area / Math.PI) * 1.05), Math.max(best.w, best.h) / Math.min(best.w, best.h)];
  }

  function smoothFit(balls, tol, factor) {    // robust quadratic fits of x(t), y(t)
    const t = [], X = [], Y = [];
    balls.forEach((b, i) => { if (b) { t.push(i); X.push(b[0]); Y.push(b[1]); } });
    let keep = t.map(() => true), cx, cy;
    for (let it = 0; it < 3; it++) {
      const tk = t.filter((_, k) => keep[k]);
      cx = polyfit(tk, X.filter((_, k) => keep[k]), 2);
      cy = polyfit(tk, Y.filter((_, k) => keep[k]), 2);
      const res = t.map((ti, k) => Math.hypot(polyval(cx, ti) - X[k], polyval(cy, ti) - Y[k]));
      const thr = Math.max(tol, factor * median(res.filter((_, k) => keep[k])));
      keep = res.map((v) => v < thr);
    }
    return { cx, cy, t, keep };
  }

  let ROBUST = true;                          // weighted, outlier-resistant spin fit (false: the 2.3 fit, for comparison)
  let WHITE_REF = "auto";                     // "auto" | "ball" | "wall" (the last two for tests)
  function sizeFit(balls) {                  // robust straight-line fit of 1/radius over time -> radius at frame i
    const t = [], q = [];
    balls.forEach((b, i) => { if (b) { t.push(i); q.push(1 / b[2]); } });
    let keep = t.map(() => true), c = [0, median(q)];
    for (let it = 0; it < 3; it++) {
      const tk = t.filter((_, k) => keep[k]), qk = q.filter((_, k) => keep[k]);
      if (tk.length >= 4) c = polyfit(tk, qk, 1);
      const res = t.map((ti, k) => Math.abs(polyval(c, ti) - q[k]));
      const thr = Math.max(1e-3, 2.5 * median(res.filter((_, k) => keep[k])));
      keep = res.map((v) => v <= thr);
    }
    const lo = Math.min(...q), hi = Math.max(...q);
    return (i) => 1 / clamp(polyval(c, i), lo, hi);
  }
  function measurePole(frames, balls, W, H, fps, inner = 0.88, nb = 10, stats = {}) {
    const idx = balls.map((b, i) => (b ? i : -1)).filter((i) => i >= 0);
    if (idx.length < 8) return null;
    const { cx: cxp, cy: cyp } = smoothFit(balls, 2.5, 2.5);
    // ball size per frame: a ball coming closer grows (1/size changes linearly with time)
    const rAt = sizeFit(balls);
    const rows = [], Dall = [];
    for (const i of idx) {
      const cx = polyval(cxp, i), cy = polyval(cyp, i), r = rAt(i);
      const x0 = Math.trunc(Math.max(0, cx - r - 1)), x1 = Math.trunc(Math.min(W, cx + r + 2));
      const y0 = Math.trunc(Math.max(0, cy - r - 1)), y1 = Math.trunc(Math.min(H, cy + r + 2));
      const g = frames[i], vals = [], UX = [], UY = [], ring = [];
      const X0 = Math.trunc(Math.max(0, cx - 2.6 * r)), X1 = Math.trunc(Math.min(W, cx + 2.6 * r + 1));
      const Y0 = Math.trunc(Math.max(0, cy - 2.6 * r)), Y1 = Math.trunc(Math.min(H, cy + 2.6 * r + 1));
      for (let y = Y0; y < Y1; y++) for (let x = X0; x < X1; x++) {
        const dx = x - cx, dy = y - cy, rr = Math.hypot(dx, dy);
        if (rr <= inner * r) { vals.push(g[y * W + x]); UX.push(dx / r); UY.push(dy / r); }
        else if (rr >= 1.6 * r && rr <= 2.5 * r) ring.push(g[y * W + x]);
      }
      if (vals.length < 20) continue;
      const even = ring.length >= 20 && percentile(ring, 75) - percentile(ring, 25) < 0.15 * median(ring);
      rows.push({ i, vals, UX, UY, top: percentile(vals, 92), wall: ring.length >= 20 ? median(ring) : NaN, even });
    }
    // Darkness is measured against a reference level. Behind the ball, an even background (a
    // plain wall, the table top) works well and per frame cancels light flicker. In front of a
    // busy background (net, logos, people) use the ball's own white half instead: its typical
    // brightest part over the flight (a frame showing mostly the black half has no white to go by).
    const ballWhite = median(rows.map((rw) => rw.top));
    const useWall = WHITE_REF === "wall" || (WHITE_REF === "auto" && rows.filter((rw) => rw.even).length >= 0.8 * rows.length);
    stats.reference = useWall ? "background" : "ball";
    for (const rw of rows) {
      const ref = useWall && rw.wall > 0 ? rw.wall : Math.max(rw.top, 0.9 * ballWhite);
      rw.D = rw.vals.map((v) => clamp(1 - v / Math.max(ref, 1), 0, 1));
      for (const v of rw.D) Dall.push(v);
    }
    if (rows.length < 8) return null;
    stats.contrast = percentile(Dall, 95) - percentile(Dall, 10);
    if (stats.contrast < 0.15) return { error: "The marking is too faint to read. Use more light and a fully black half." };
    // per-spot white/black levels (removes the lighting gradient across the ball)
    const binsLo = Array.from({ length: nb * nb }, () => []);
    const bin = (u) => clamp(Math.trunc((u + 1) / 2 * nb), 0, nb - 1);
    for (const rw of rows) for (let k = 0; k < rw.D.length; k++) binsLo[bin(rw.UX[k]) * nb + bin(rw.UY[k])].push(rw.D[k]);
    const g10 = percentile(Dall, 10), g95 = percentile(Dall, 95);
    let lo = new Float64Array(nb * nb).fill(g10), hi = new Float64Array(nb * nb).fill(g95);
    for (let q = 0; q < nb * nb; q++) if (binsLo[q].length >= 30) { lo[q] = percentile(binsLo[q], 10); hi[q] = percentile(binsLo[q], 95); }
    lo = blur2d(lo, nb, 0.8); hi = blur2d(hi, nb, 0.8);
    const t = [], B = [], Wt = [];
    for (const rw of rows) {
      let sum = 0, sx = 0, sy = 0;
      for (let k = 0; k < rw.D.length; k++) {
        const q = bin(rw.UX[k]) * nb + bin(rw.UY[k]);
        const s = clamp((rw.D[k] - lo[q]) / Math.max(hi[q] - lo[q], 0.05), 0, 1);
        sum += s; sx += s * rw.UX[k]; sy += s * rw.UY[k];
      }
      const f = sum / rw.D.length, bz = clamp(2 * f - 1, -0.98, 0.98);
      const mx = sum > 1e-6 ? sx / sum : 0, my = sum > 1e-6 ? sy / sum : 0, n = Math.hypot(mx, my);
      const rho = Math.sqrt(1 - bz * bz);
      let b = n > 1e-6 ? [rho * mx / n, rho * my / n, bz] : [0, 0, Math.sign(bz)];
      b = scale(b, 1 / norm(b));
      t.push(rw.i); B.push(b);
      const st = balls[rw.i] && balls[rw.i][3] ? balls[rw.i][3] : 1;   // smeared frames count less
      Wt.push(1 / (1 + 6 * Math.max(0, st - 1.15) ** 2));
    }
    return fitRotatingVector(t, B, fps, ROBUST ? Wt : null);
  }

  // ------------------------------------------------------------ 3. rate scan
  // Fit a vector turning at a steady rate to the pole directions B(t). wts: how much each frame
  // counts (blurred frames less). With ROBUST, frames that disagree strongly with a first fit are
  // down-weighted and the fit is repeated, so a few bad frames (ball crossing the net, half
  // hidden) can't pull the rate. Also returns the best few rates (candidates) with their fits.
  function fitRotatingVector(t, B, fps, wts = null) {
    const n = t.length;
    function scan(W) {
      const sw = W.reduce((s, v) => s + v, 0);
      const mean = [0, 1, 2].map((d) => B.reduce((s, b, k) => s + W[k] * b[d], 0) / sw);
      let variance = 0;
      B.forEach((b, k) => { for (let d = 0; d < 3; d++) variance += W[k] * (b[d] - mean[d]) ** 2; });
      if (variance / sw < 0.02) return { noSpin: true, variance, sw };
      let best = null; const resid = [], rates = [];
      for (let k = 0; ; k++) {
        const rps = 0.5 + k * 0.05;
        if (rps >= fps / 2) break;
        const w = 2 * Math.PI * rps / fps;
        const X = t.map((ti) => [1, Math.cos(w * ti), Math.sin(w * ti)]);
        const A = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
        X.forEach((x, k2) => { for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) A[i][j] += W[k2] * x[i] * x[j]; });
        const coef = [0, 1, 2].map((d) => {
          const bvec = [0, 0, 0];
          X.forEach((x, k2) => { for (let i = 0; i < 3; i++) bvec[i] += W[k2] * x[i] * B[k2][d]; });
          return solve3(A, bvec);               // [c_d, P_d, Q_d]
        });
        let res = 0;
        X.forEach((x, k2) => { for (let d = 0; d < 3; d++) { const p = coef[d][0] * x[0] + coef[d][1] * x[1] + coef[d][2] * x[2]; res += W[k2] * (p - B[k2][d]) ** 2; } });
        rates.push(rps); resid.push(res);
        if (!best || res < best.res) best = { res, rps, coef, w };
      }
      return { best, rates, resid, variance, sw };
    }
    let W = wts ? wts.slice() : t.map(() => 1);
    let S = scan(W);
    if (S.noSpin) return { rps: 0, axis: [0, 0, 1], score: 1, runner: NaN, r2: null, swing: Math.sqrt(S.variance / S.sw), noSpin: true, candidates: [] };
    if (ROBUST && n >= 8) {
      const { coef, w } = S.best;
      const err = t.map((ti, k) => {
        const x = [1, Math.cos(w * ti), Math.sin(w * ti)];
        let e = 0; for (let d = 0; d < 3; d++) e += (coef[d][0] * x[0] + coef[d][1] * x[1] + coef[d][2] * x[2] - B[k][d]) ** 2;
        return Math.sqrt(e);
      });
      const c = Math.max(0.05, 2 * median(err));
      W = W.map((v, k) => v / (1 + (err[k] / c) ** 2));
      const S2 = scan(W);
      if (!S2.noSpin) S = S2;
    }
    const { best, rates, resid, variance } = S;
    const P = [best.coef[0][1], best.coef[1][1], best.coef[2][1]], Q = [best.coef[0][2], best.coef[1][2], best.coef[2][2]];
    let a = cross(P, Q); a = scale(a, 1 / (norm(a) + 1e-12));
    const r2 = 1 - best.res / variance;
    let far = Infinity;
    rates.forEach((rr, k) => { if (Math.abs(rr - best.rps) > 2.0 && resid[k] < far) far = resid[k]; });
    const runner = far < Infinity ? 1 - far / variance : NaN;
    const swing = 0.5 * (norm(P) + norm(Q));
    let score = Math.max(0, r2) * Math.min(1, swing / 0.4);
    if (Number.isFinite(runner) && runner > r2 - 0.05) score *= 0.5;
    // the best few distinct rates (local minima of the misfit), for checks against other evidence
    const candidates = [];
    for (let k = 1; k < resid.length - 1; k++) if (resid[k] <= resid[k - 1] && resid[k] <= resid[k + 1]) candidates.push({ rps: rates[k], r2: 1 - resid[k] / variance });
    candidates.sort((x, y) => y.r2 - x.r2);
    return { rps: best.rps, axis: a, score, runner, r2, swing, candidates: candidates.slice(0, 5) };
  }

  // ------------------------------------------------------------ 4. geometry + labels
  function uprightRotation(ax, ay) {
    if (Math.hypot(ax, ay) < 1e-6) return [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
    if (Math.abs(ay) >= Math.abs(ax)) return ay > 0 ? [[1, 0, 0], [0, 1, 0], [0, 0, 1]] : [[-1, 0, 0], [0, -1, 0], [0, 0, 1]];
    return ax > 0 ? [[0, -1, 0], [1, 0, 0], [0, 0, 1]] : [[0, 1, 0], [-1, 0, 0], [0, 0, 1]];
  }
  // centre: where the crop's (0, 0) sits relative to the middle of the picture, in pixels
  function flightGeometry(balls, fps, frameWidth, hfov, centre = [0, 0]) {
    const { cx: px, cy: py, t, keep } = smoothFit(balls, 2.5, 3);
    const rr = balls.filter(Boolean).map((b) => b[2]);
    const tm = t.reduce((s, v) => s + v, 0) / t.length;
    const vx = 2 * px[0] * tm + px[1], vy = 2 * py[0] * tm + py[1];
    const ax = 2 * px[0], ay = 2 * py[0];
    const tk = t.filter((_, k) => keep[k]), lk = rr.map(Math.log).filter((_, k) => keep[k]);
    const dlnr = polyfit(tk, lk, 1)[0];
    const fpx = (frameWidth / 2) / Math.tan((hfov * Math.PI / 180) / 2);
    // 3D direction in camera axes (x right, y down, z toward the camera). A ball coming closer
    // also drifts away from the picture's centre, so take that part out of the sideways motion.
    const u = (polyval(px, tm) + centre[0]) / fpx, v = (polyval(py, tm) + centre[1]) / fpx;
    let f = [vx / fpx - u * dlnr, vy / fpx - v * dlnr, dlnr]; f = scale(f, 1 / norm(f));
    const mmpx = 40.0 / (2 * median(rr)), toMs = mmpx / 1000 * fps;
    const dist = fpx * mmpx;                  // ball distance in mm (40 mm ball)
    // Which way is down: the ball's acceleration in space (gravity, plus a little lift), from its
    // 3D path (distance from its size). Side-on this is the same as the curve in the picture;
    // for a ball coming toward the camera the picture's curve is mostly perspective.
    const rAt = sizeFit(balls), T3 = [], X3 = [], Y3 = [];
    balls.forEach((b, i) => { if (b) { const k = 20 / rAt(i); T3.push(i); X3.push((b[0] + centre[0]) * k); Y3.push((b[1] + centre[1]) * k); } });
    const tk3 = T3.filter((_, k) => keep[k]);
    const a3 = [2 * polyfit(tk3, X3.filter((_, k) => keep[k]), 2)[0], 2 * polyfit(tk3, Y3.filter((_, k) => keep[k]), 2)[0]];   // mm per frame^2
    const turn = uprightRotation(a3[0], a3[1]);
    f = matvec(turn, f);
    const acc = matvec(turn, [a3[0], a3[1], 0]);
    return {
      f, turn, rotatedDeg: ((Math.round(Math.atan2(turn[1][0], turn[0][0]) * 180 / Math.PI) % 360) + 360) % 360,
      speed: dist * Math.hypot(vx / fpx - u * dlnr, vy / fpx - v * dlnr, dlnr) * fps / 1000, towardCameraDeg: Math.asin(clamp(f[2], -1, 1)) * 180 / Math.PI,
      downAccel: acc[1] * fps * fps / 1000, mmPerPx: mmpx,
    };
  }
  function spinComponents(rps, axis, f) {
    const w = scale(axis, rps);
    let u = [0, -1, 0]; u = [u[0] - dot(u, f) * f[0], u[1] - dot(u, f) * f[1], u[2] - dot(u, f) * f[2]]; u = scale(u, 1 / norm(u));
    const right = scale(cross(f, u), -1), magnus = cross(w, f);
    const lift = dot(magnus, u), curve = dot(magnus, right), gyro = dot(w, f);
    return { lift, curve, gyro, tilt: Math.atan2(curve, lift) * 180 / Math.PI };
  }
  function spinLabel(total, c) {
    if (total < 3) return "no spin";
    const parts = [];
    if (Math.abs(c.curve) >= 0.35 * total) parts.push("side");
    if (Math.abs(c.lift) >= 0.35 * total) parts.push(c.lift > 0 ? "back" : "top");
    if (!parts.length) return Math.abs(c.gyro) >= 0.5 * total ? "gyro (corkscrew)" : "mixed";
    let label = parts.join("-") + "spin";
    if (Math.abs(c.curve) >= 0.35 * total) label += ` (curves ${c.curve > 0 ? "right" : "left"})`;
    if (Math.abs(c.gyro) >= 0.5 * total) label += " + gyro";
    return label;
  }

  // ------------------------------------------------------------ setup check
  // Each check: { key, status: "ok" | "warn" | "bad", title, text }. Thresholds come from the
  // test serves: good light ≈ 135–145 brightness, ball 21–28 px across, all read correctly.
  const chk = (key, status, title, text = "") => ({ key, status, title, text });
  function videoChecks(meta, R, opts) {
    const out = [], cf = meta.captureFps, pf = meta.playbackFps;
    if (opts.captureFps) out.push(chk("fps", opts.captureFps >= 200 ? "ok" : "warn", `Frame rate set by hand: ${Math.round(opts.captureFps)} fps`));
    else if (cf >= 200 || (!cf && pf >= 200)) out.push(chk("fps", "ok", `Slow motion, ${Math.round(cf || pf)} fps`));
    else if (cf >= 100) out.push(chk("fps", "warn", `Only ${Math.round(cf)} fps`, `Spins above about ${Math.round(cf / 4 * 60).toLocaleString("en-US")} RPM can't be measured. Use 240 fps slow motion.`));
    else if (cf) out.push(chk("fps", "bad", `Not slow motion (${Math.round(cf)} fps)`, "Record with the camera's slow-motion mode at 240 fps."));
    else out.push(chk("fps", "warn", "Frame rate not in the file", "Edited or shared copies lose it, so 240 fps is assumed. Use the original video, or set the rate under Settings."));
    const rot = Math.abs(meta.rotation || 0) % 180 === 90, w = rot ? R.H : R.W, h = rot ? R.W : R.H;
    if (h > w) out.push(chk("orient", "bad", "Portrait video", "Hold the phone sideways (landscape) so the ball flies across the long side of the picture."));
    else if (Math.min(w, h) < 720) out.push(chk("orient", "warn", `Low resolution (${w}×${h})`, "Use 1080p so the marking stays sharp."));
    else out.push(chk("orient", "ok", `Landscape, ${w}×${h}`));
    return out;
  }
  function sceneChecks(s) {
    const out = [], L = Math.round(s.light);
    if (s.light < 50) out.push(chk("light", "bad", "Too dark", `Brightness ${L} of 255. Add light: daylight or more lamps.`));
    else if (s.light < 85) out.push(chk("light", "warn", "Dim", `Brightness ${L} of 255. More light gives a sharper marking.`));
    else out.push(chk("light", "ok", "Enough light", `Brightness ${L} of 255`));
    const m = Math.round(100 * s.moving);
    if (s.moving > 0.3) out.push(chk("steady", "bad", "Camera moving, or a busy view", `${m}% of the picture keeps changing. Put the phone on a tripod or lean it on something solid.`));
    else if (s.moving > 0.12) out.push(chk("steady", "warn", "Lots of movement in view", `${m}% of the picture keeps changing. Fine if it's people in the background; keep the phone still.`));
    else out.push(chk("steady", "ok", "Camera steady"));
    return out;
  }
  function flightCheck(segs) {
    if (segs.length) return chk("flight", "ok", segs.length > 1 ? `${segs.length} serves found` : "Serve found");
    if (segs.ballFrames < 12) return chk("flight", "bad", "No ball found", "Nothing ball-sized moved in view. Check the light and the distance (ball 25–50 px across).");
    return chk("flight", "bad", "No clear flight", "Something ball-sized moved, but not along a clear flight. Keep the phone still, with the ball's flight over the table in view.");
  }
  function serveChecks(s) {
    const out = [];
    if (s.ballR) {
      const d = Math.round(2 * s.ballR);
      if (s.ballR < 7) out.push(chk("size", "bad", `Ball too small (${d} px across)`, "Move the camera closer: about 1–1.5 m from the ball's path, so the ball is 25–50 px across."));
      else if (s.ballR < 10) out.push(chk("size", "warn", `Ball small (${d} px across)`, "Move a little closer: 25–50 px across works best."));
      else out.push(chk("size", "ok", `Ball size good (${d} px across)`));
    }
    if (s.frames < 20) out.push(chk("frames", "warn", `Ball seen in only ${s.frames} frames`, "Keep more of the flight in view."));
    if (s.contrast !== undefined) {
      const c = `Contrast ${Math.round(100 * s.contrast)}%. `;
      if (s.contrast < 0.15) out.push(chk("marks", "bad", "Marking not visible", c + "Use more light, and colour the black half fully."));
      else if (s.contrast < 0.3) out.push(chk("marks", "warn", "Marking faint", c + "More light, or a darker marker, makes the reading surer."));
      else out.push(chk("marks", "ok", "Marking clear", `Contrast ${Math.round(100 * s.contrast)}%`));
    }
    return out;
  }

  // A clear reading: the spin fit is good AND clearly ahead of the best quite different spin rate.
  // (A short stretch can fit several rates almost equally well; then it's a guess.)
  function isClear(r) {
    const lead = Number.isFinite(r.runner) ? r.score - r.runner : 1;
    return r.score >= 0.5 && lead >= (r.score >= 0.75 ? 0.1 : 0.2);
  }

  // What in the footage stood in the way of a clear reading, each with what to change.
  // r: the spin fit (null when there was none). Only problems are listed.
  function diagnose(s, r) {
    const out = [], add = (text, fix) => out.push({ text, fix });
    const clear = r && isClear(r);
    if (s.total && s.frames < 0.7 * s.total)
      add(`The ball was found in only ${s.frames} of ${s.total} frames.`, "Something kept hiding or blending with it: keep the paddle and body out of its path, and the background behind it even (tilt the camera so the table is behind the ball).");
    if (s.ballR && s.ballR < 10)
      add(`The ball is only ${Math.round(2 * s.ballR)} px across.`, "Move the camera closer: 1–1.5 m from the ball's path, so it is 30–50 px across.");
    if (s.stretch && s.stretch > 1.35)
      add(`The ball is smeared by motion blur (stretched ${s.stretch.toFixed(1)}× along its path).`, "Add light: daylight or extra lamps make the camera's shutter faster.");
    if (s.contrast !== undefined && s.contrast < 0.3)
      add(`The black half is faint (contrast ${Math.round(100 * s.contrast)}%).`, "More light, and colour the black half fully and darkly.");
    if (r && !clear && Number.isFinite(r.runner) && r.runner > r.score - 0.2)
      add("The spin rate is ambiguous: a quite different rate fits the marking about as well.", "Usually blur or a small ball: see the other points. More frames of the ball also help.");
    if (r && !clear && !out.length)
      add(`The marking couldn't be followed reliably (fit ${r.score.toFixed(2)}, needs 0.75).`, "Check that the ball's marking is sharp in the slow-motion video; more light helps most.");
    return out;
  }

  // ------------------------------------------------------------ 3. measure one serve
  async function measureSegment(R, seg, fps, hfov) {
    const [rx, ry, rw, rh] = seg.roi, { ctx } = makeCanvas(R.W, R.H);
    let frames = new Array(seg.end - seg.start);
    await R.run(seg.start, seg.end, async (i, src) => { frames[i - seg.start] = await grabColor(ctx, src, R.W, R.H, seg.roi); });
    for (let k = 0; k < frames.length; k++) if (!frames[k]) frames[k] = frames[k - 1] || frames.find(Boolean);
    const colour = frames.every((f) => f.u);
    const bgU = colour ? medianFrames(frames.map((f) => f.u), rw * rh) : null;
    const bgV = colour ? medianFrames(frames.map((f) => f.v), rw * rh) : null;
    let gray = frames.map((f) => f.g);
    let bg = medianFrames(gray, rw * rh);
    gray = gray.map((g) => deflickerOne(g, bg, rw, rh, 1));
    bg = medianFrames(gray, rw * rh);
    // where the flight search saw the ball, in this crop's pixels
    const seen = new Map((seg.track || []).map((p) => [p[0] - seg.start, [p[1] - rx, p[2] - ry, p[3]]]));
    const predict = (k) => {
      if (!seen.size) return null;
      for (let d = 0; d < 6; d++) { if (seen.has(k - d)) return seen.get(k - d); if (seen.has(k + d)) return seen.get(k + d); }
      return null;
    };
    const cols = frames.map((f) => (colour ? { u: f.u, v: f.v, bgU, bgV } : null));
    const balls = gray.map((g, k) => findBall(g, bg, rw, rh, 5.0, cols[k], predict(k)));
    frames = gray;
    const found = balls.filter(Boolean), stats = { frames: found.length, total: balls.length };
    // Does the picture around the ball stay still during the serve? Share of pixels away from the
    // ball that differ from the background, per frame; a moving camera makes most of them differ.
    const changed = gray.map((g, k) => {
      const b = balls[k], col = cols[k];
      let n = 0, m = 0;
      for (let y = 0; y < rh; y += 2) for (let x = 0; x < rw; x += 2) {
        if (b && Math.hypot(x - b[0], y - b[1]) < 2 * b[2]) continue;
        const p = y * rw + x; m++; if (colourDiff(g[p], bg[p], col, p) > 25) n++;
      }
      return n / Math.max(1, m);
    });
    stats.bgChange = median(changed);
    if (found.length) { stats.ballR = median(found.map((b) => b[2])); stats.stretch = median(found.map((b) => b[3])); }
    const fail = (error) => ({ error, phase: seg.kind, nFrames: balls.length, segment: seg, checks: serveChecks(stats), issues: diagnose(stats, null) });
    if (found.length < 4) return fail("The ball was found in too few frames.");
    const r = measurePole(frames, balls, rw, rh, fps, undefined, undefined, stats);
    if (!r) return fail("The ball was found in too few frames in a row.");
    if (r.error) return fail(r.error);
    const geo = flightGeometry(balls, fps, R.W, hfov, [rx - R.W / 2, ry - R.H / 2]);
    const axis = matvec(geo.turn, r.axis);
    const c = spinComponents(r.rps, axis, geo.f);
    return {
      rps: r.rps, rpm: r.rps * 60, spinType: spinLabel(r.rps, c),
      backspin: Math.max(0, c.lift), topspin: Math.max(0, -c.lift), sidespin: Math.abs(c.curve),
      sideCurves: Math.abs(c.curve) >= 1 ? (c.curve > 0 ? "right" : "left") : "none",
      gyro: Math.abs(c.gyro), lift: c.lift, curve: c.curve, tilt: c.tilt,
      // clear: a high fit, or a good fit well ahead of the best other spin rate
      axis, fit: r.score, reliable: isClear(r), runnerUp: r.runner, candidates: r.candidates,
      phase: seg.kind, reference: stats.reference, nFrames: balls.length, issues: diagnose(stats, r), bgChange: stats.bgChange,
      flight: { speed: geo.speed, towardCameraDeg: geo.towardCameraDeg, downAccel: geo.downAccel, rotatedDeg: geo.rotatedDeg, dir: geo.f },
      ballRadiusPx: median(found.map((b) => b[2])), startSec: seg.start / fps,
      segment: seg, crops: makeCrops(frames, balls, rw, rh, geo.turn), checks: serveChecks(stats),
    };
  }

  // ------------------------------------------------------------ main entry
  // Returns { serves: [...], meta, ... } where each serve is a reading or { error }. The fields of
  // the first good reading are also copied to the top level, so a one-serve video reads as before.
  async function analyze(file, opts = {}) {
    WHITE_REF = opts.whiteRef || "auto";
    ROBUST = opts.robust === true;            // off by default until checked on the labelled clips
    DBG = {};
    planesBroken = !!opts.forceCanvas;        // tests: read frames through a canvas, as some phones must
    const progress = opts.onProgress || (() => {});
    const hfov = opts.hfov || 68, tStart = performance.now();
    readStats.direct = readStats.canvas = 0;
    progress("Reading the video file", 0.01);
    const buf = await file.arrayBuffer();
    const meta = parseMp4(buf);
    const captureFps = opts.captureFps || meta.captureFps || null;
    const fps = captureFps || 240;
    let R = null;
    if (!opts.forceSeek) { try { R = await webCodecsReader(buf); } catch (e) { R = null; } }
    if (!R) R = await seekReader(file, opts.playbackFps || meta.playbackFps || 30, meta.sampleCount);
    const checks = videoChecks(meta, R, opts), emit = () => { if (opts.onChecks) opts.onChecks(checks.slice()); };
    emit();
    try {
      const segs = await findFlights(R, progress, Object.assign({ serveGap: Math.round(0.5 * fps),
        onScene: (sc) => { checks.push(...sceneChecks(sc)); emit(); } }, opts));
      checks.push(flightCheck(segs)); emit();
      const metaOut = { captureFps, nFrames: R.n, W: R.W, H: R.H, reader: R.kind, rotation: meta.rotation };
      DBG.file = { name: file.name, size: file.size, frames: R.n, reader: R.kind, captureFps: meta.captureFps || null, rotation: meta.rotation };
      const timing = () => Object.assign(metaOut, { debug: Object.assign(DBG, { read: Object.assign({}, readStats) }), seconds: (performance.now() - tStart) / 1000,
        frameRead: R.kind === "seek" ? "seek" : readStats.direct >= readStats.canvas ? "direct" : "canvas" });
      if (!segs.length) return { error: "No serve flight found. Keep the phone still, with the ball's flight over the table in view.", serves: [], checks, meta: timing() };
      const serves = [];
      for (let k = 0; k < segs.length; k++) {
        progress(segs.length > 1 ? `Measuring serve ${k + 1} of ${segs.length}` : "Measuring the spin", 0.86 + 0.14 * k / segs.length);
        const readings = [];
        for (const part of segs[k].parts) {
          const one = await measureSegment(R, part, fps, hfov);
          readings.push(Object.assign(one, { phase: part.kind }));
        }
        // the headline: off the paddle when it reads clearly, else the clearest stretch
        const ok = readings.filter((r) => !r.error);
        const head = ok.find((r) => r.phase !== "bounce" && r.reliable) || ok.find((r) => r.reliable) ||
          ok.find((r) => r.phase !== "bounce") || ok[0] || readings[0];
        const one = Object.assign({}, head, { phases: readings, notes: segs[k].notes, clear: ok.some((r) => r.reliable) });
        // something that isn't a ball for most of its frames, or a short stretch that can't be read,
        // is most likely not a serve (a ball rolling, picked up, a hand)
        const notBall = one.error && /found in too few/.test(one.error);
        if (one.error && segs.length > 1 && (notBall || segs[k].end - segs[k].start < 20)) continue;
        serves.push(one);
      }
      progress("Done", 1);
      // Not every ball movement is a serve. A ball bounced on the table before serving is slow;
      // a ball sent back to the server goes the other way, which only the user can tell us
      // (opts.serveDir: "any", "toward", "away", "ltr", "rtl"). Direction is taken from the
      // stretch with the most frames; a short stretch's direction and speed are unreliable.
      const skipped = { slow: 0, way: 0 };
      const wayOk = {
        // serves toward the camera came in at 9-30 degrees, balls sent back went at -1 to -43
        any: () => true, toward: (f) => f[2] > 0.05, away: (f) => f[2] < -0.05, ltr: (f) => f[0] > 0.1, rtl: (f) => f[0] < -0.1,
      }[opts.serveDir || "any"] || (() => true);
      const motion = (sv) => (sv.phases || [sv]).filter((r) => !r.error && r.flight).sort((x, y) => y.nFrames - x.nFrames)[0] || null;
      for (let k = serves.length - 1; k >= 0; k--) {
        const m = motion(serves[k]);
        if (!m) continue;
        if (m.flight.speed < 1.5) { skipped.slow++; serves.splice(k, 1); }
        else if (!wayOk(m.flight.dir)) { skipped.way++; serves.splice(k, 1); }
      }
      // With a clear serve in the video, a short unclear stretch with no bounce is something else
      // that moved (a hand, a ball being picked up), not a serve.
      if (serves.some((sv) => sv.clear))
        for (let k = serves.length - 1; k >= 0; k--) {
          const sv = serves[k], n = (sv.phases || [sv]).reduce((t, r) => t + (r.nFrames || 0), 0);
          if (!sv.clear && n < 25 && (sv.phases || [sv]).every((r) => r.phase === "flight")) serves.splice(k, 1);
        }
      // One serve per video (the default): keep the best one, a clear reading first, then the
      // one with the most frames; the rest count as other ball movements.
      if (opts.single !== false && serves.length > 1) {
        const frames = (sv) => (sv.phases || [sv]).reduce((t, r) => t + (r.nFrames || 0), 0);
        const best = serves.slice().sort((a, b) => (b.clear - a.clear) || (frames(b) - frames(a)))[0];
        skipped.other = (skipped.other || 0) + serves.length - 1;
        serves.splice(0, serves.length, best);
      }
      if (serves.length !== segs.length) {      // dropped stretches: count only the serves shown
        const fc = checks.findIndex((c) => c.key === "flight");
        if (fc >= 0) checks[fc] = flightCheck(Object.assign(serves.map((s) => s.segment), { ballFrames: segs.ballFrames }));
      }
      if (!serves.length) {
        const why = skipped.way ? ` ${skipped.way} ball${skipped.way > 1 ? "s" : ""} went the other way than set under Settings ("Serves go").` : "";
        const slow = skipped.slow ? ` ${skipped.slow} slow ball${skipped.slow > 1 ? "s" : ""} (under 1.5 m/s, like bouncing it on the table) didn't count.` : "";
        return { error: "No serve flight found." + why + slow + (why || slow ? "" : " Keep the phone still, with the ball's flight over the table in view."), serves: [], checks, skipped, meta: timing() };
      }
      const first = serves.find((s) => !s.error);
      const base = { serves, fps, captureFpsDetected: !!meta.captureFps, checks, meta: timing(),
        skipped };
      return first ? Object.assign({}, first, base) : Object.assign({ error: serves[0].error, segment: serves[0].segment }, base);
    } finally {
      R.close();
    }
  }

  function makeCrops(frames, balls, W, H, turn) {   // small gray crops of the ball, shown upright
    const out = [];
    balls.forEach((b, i) => {
      if (!b) return;
      const half = Math.ceil(b[2] * 1.4), size = 2 * half, data = new Uint8ClampedArray(size * size);
      for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
        // upright pixel (x, y) -> source pixel via the inverse of the orientation turn
        const ux = x - half, uy = y - half;
        const sx = turn[0][0] * ux + turn[1][0] * uy, sy = turn[0][1] * ux + turn[1][1] * uy;
        const X = Math.round(b[0] + sx), Y = Math.round(b[1] + sy);
        data[y * size + x] = X >= 0 && Y >= 0 && X < W && Y < H ? frames[i][Y * W + X] : 0;
      }
      out.push({ size, data });
    });
    return out;
  }

  global.SpinMeter = { analyze, parseMp4, _internals: { webCodecsReader, splitAtKinks, framePlanes, lumaHalf, chromaHalf, percentile, polyfit, minEnclosingCircle, fitRotatingVector, spinComponents, spinLabel } };
})(typeof window !== "undefined" ? window : globalThis);
