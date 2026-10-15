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
  function toGray(ctx, src, W, H, roi) {      // gray as in cv2.cvtColor(BGR2GRAY): rounded
    ctx.drawImage(src, 0, 0, W, H);
    const [x, y, w, h] = roi || [0, 0, W, H];
    const d = ctx.getImageData(x, y, w, h).data, out = new Float32Array(w * h);
    for (let p = 0, q = 0; p < out.length; p++, q += 4) out[p] = Math.round(0.299 * d[q] + 0.587 * d[q + 1] + 0.114 * d[q + 2]);
    return out;
  }

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

  function halfGray(ctx, src, W, H) {         // scan passes: let the canvas shrink the frame (4x fewer pixels)
    const w = Math.floor(W / 2), h = Math.floor(H / 2);
    ctx.imageSmoothingEnabled = true; ctx.imageSmoothingQuality = "medium";
    ctx.drawImage(src, 0, 0, w, h);
    const d = ctx.getImageData(0, 0, w, h).data, out = new Float32Array(w * h);
    for (let p = 0, q = 0; p < out.length; p++, q += 4) out[p] = Math.round(0.299 * d[q] + 0.587 * d[q + 1] + 0.114 * d[q + 2]);
    return { g: out, w, h };
  }

  // Fast path: read the brightness (Y) plane straight out of a decoded VideoFrame, no canvas round trip.
  // Gray = Y mapped from video range to 0-255, which is what the canvas gives for a gray ball on a gray wall.
  const LUMA_FORMATS = new Set(["I420", "I420A", "I422", "I444", "NV12"]);
  let lumaBroken = false;
  async function lumaPlane(frame, W, H) {    // -> { buf, offset, stride, lut }, or null when this frame can't be read this way
    if (lumaBroken || typeof VideoFrame === "undefined" || !(frame instanceof VideoFrame)) return null;
    if (!LUMA_FORMATS.has(frame.format) || frame.visibleRect.width !== W || frame.visibleRect.height !== H) return null;
    try {
      const buf = new Uint8Array(frame.allocationSize());
      const layout = await frame.copyTo(buf);
      const full = frame.colorSpace && frame.colorSpace.fullRange;
      return { buf, offset: layout[0].offset, stride: layout[0].stride, lut: full ? LUT_FULL : LUT_VIDEO };
    } catch (e) { lumaBroken = true; return null; }
  }
  const LUT_FULL = Float32Array.from({ length: 256 }, (_, v) => v);
  const LUT_VIDEO = Float32Array.from({ length: 256 }, (_, v) => clamp(Math.round((v - 16) * 255 / 219), 0, 255));
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
  function lumaCrop({ buf, offset, stride, lut }, roi) {
    const [x0, y0, w, h] = roi, out = new Float32Array(w * h);
    for (let y = 0; y < h; y++) { const r = offset + (y0 + y) * stride + x0; for (let x = 0; x < w; x++) out[y * w + x] = lut[buf[r + x]]; }
    return out;
  }
  const readStats = { direct: 0, canvas: 0 };
  async function grabHalf(ctx, src, W, H) {   // half-size gray frame for the scan passes
    const L = await lumaPlane(src, W, H);
    readStats[L ? "direct" : "canvas"]++;
    return L ? lumaHalf(L, W, H) : halfGray(ctx, src, W, H);
  }
  async function grabGray(ctx, src, W, H, roi) {
    const L = await lumaPlane(src, W, H);
    readStats[L ? "direct" : "canvas"]++;
    return L ? lumaCrop(L, roi) : toGray(ctx, src, W, H, roi);
  }

  // ------------------------------------------------------------ scan pass: candidates in one frame
  function scanFrame(job, ctx) {             // job: { buf, offset, stride, full } (Y plane) or { g } (half-size gray)
    const { bgf, bg, sw, sh, W, H } = ctx;
    const g = job.g || lumaHalf({ buf: job.buf, offset: job.offset, stride: job.stride, lut: job.full ? LUT_FULL : LUT_VIDEO }, W, H).g;
    const d = deflickerOne(g, bgf, sw, sh, 2);
    const mask = new Uint8Array(sw * sh);
    for (let p = 0; p < mask.length; p++) mask[p] = Math.abs(clamp(Math.floor(d[p]), 0, 255) - bg[p]) > 30 ? 1 : 0;
    const m2 = opening(mask, sw, sh, 2);
    const fc = [];
    for (const c of components(m2, sw, sh, false)) {
      if (c.area < 12 || c.area > 900) continue;
      if (Math.max(c.w, c.h) / Math.max(1, Math.min(c.w, c.h)) > 1.7 || c.area / (c.w * c.h) < 0.5) continue;
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

  // ------------------------------------------------------------ 1. find the flights (one per serve)
  async function findFlights(R, progress, opt) {
    const skipAfter = opt.skipAfterContact ?? 12, maxFrames = opt.maxFrames ?? 40, minLen = 12;
    const nFrames = R.n, { ctx } = makeCanvas(R.W, R.H);
    const stride = Math.max(4, Math.ceil(nFrames / 40));
    const samples = [];
    let sw = Math.floor(R.W / 2), sh = Math.floor(R.H / 2);
    if (R.kind === "seek") {
      for (let i = 0; i < nFrames; i += stride) await R.run(i, i + 1, async (k, src) => samples.push((await grabHalf(ctx, src, R.W, R.H)).g));
    } else {
      await R.run(0, nFrames, async (k, src) => {
        if (k % stride === 0) samples.push((await grabHalf(ctx, src, R.W, R.H)).g);
        if (k % 8 === 0) progress("Learning the background", 0.1 * k / nFrames);
      });
    }
    const bgf = medianFrames(samples, sw * sh);
    const defl = samples.map((g) => { const d = deflickerOne(g, bgf, sw, sh, 2); for (let p = 0; p < d.length; p++) d[p] = clamp(Math.floor(d[p]), 0, 255); return d; });
    const bg = medianFrames(defl, sw * sh);
    for (let p = 0; p < bg.length; p++) bg[p] = Math.trunc(bg[p]);
    if (opt.onScene) {                        // setup check: overall light, and how much of the view keeps changing
      let sum = 0; for (let p = 0; p < bg.length; p++) sum += bg[p];
      const moving = defl.map((d) => { let n = 0; for (let p = 0; p < d.length; p++) if (Math.abs(d[p] - bg[p]) > 30) n++; return n / d.length; });
      opt.onScene({ light: sum / bg.length, moving: median(moving) });
    }
    const cands = new Array(nFrames).fill(null).map(() => []);
    // the per-frame search runs on worker threads when the browser allows it, else here
    const pool = await scanPool({ bgf, bg, sw, sh, W: R.W, H: R.H });
    let doneN = 0;
    const store = (i, fc) => { cands[i] = fc; if (++doneN % 4 === 0) progress("Looking for the ball", 0.1 + 0.75 * doneN / nFrames); };
    try {
      await R.run(0, nFrames, async (i, src) => {
        const L = await lumaPlane(src, R.W, R.H);
        readStats[L ? "direct" : "canvas"]++;
        const job = L ? { i, buf: L.buf, offset: L.offset, stride: L.stride, full: L.lut === LUT_FULL } : { i, g: halfGray(ctx, src, R.W, R.H).g };
        if (pool) await pool.submit(job, store);
        else store(i, scanFrame(job, { bgf, bg, sw, sh, W: R.W, H: R.H }));
      });
      if (pool) await pool.drain();
    } finally { if (pool) pool.close(); }
    // greedy linking with a constant-velocity guess
    let tracks = [], active = [];
    cands.forEach((cs, i) => {
      const used = new Set();
      for (const tr of active) {
        const [f1, x1, y1] = tr[tr.length - 1];
        let px = x1, py = y1;
        if (tr.length > 1) {
          const [f0, x0, y0] = tr[tr.length - 2], df = Math.max(1, f1 - f0);
          px = x1 + (x1 - x0) / df * (i - f1); py = y1 + (y1 - y0) / df * (i - f1);
        }
        let best = null, bd = 25.0;
        cs.forEach((c, j) => { const dd = Math.hypot(c[0] - px, c[1] - py); if (!used.has(j) && dd < bd) { best = j; bd = dd; } });
        if (best !== null) { used.add(best); tr.push([i, ...cs[best]]); }
      }
      const still = active.filter((tr) => i - tr[tr.length - 1][0] <= 2);
      tracks = tracks.concat(active.filter((tr) => i - tr[tr.length - 1][0] > 2));
      active = still.concat(cs.map((c, j) => (used.has(j) ? null : [[i, ...c]])).filter(Boolean));
    });
    tracks = tracks.concat(active);
    const good = tracks.filter((tr) => {
      if (tr.length < minLen) return false;
      const dx = tr[tr.length - 1][1] - tr[0][1], dy = tr[tr.length - 1][2] - tr[0][2];
      return Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy);
    });
    // One serve per flight. Tracks closer than `gap` frames in time are pieces of the same
    // flight (or the ball coming back); keep the longest. Serves are seconds apart.
    const gap = opt.serveGap ?? 120, flights = [];
    for (const tr of good.slice().sort((a, b) => b.length - a.length)) {
      const s = tr[0][0], e = tr[tr.length - 1][0];
      if (flights.every((f) => s > f[f.length - 1][0] + gap || e < f[0][0] - gap)) flights.push(tr);
    }
    const segs = [];
    for (let tr of flights) {
      const sign = Math.sign(tr[tr.length - 1][1] - tr[0][1]);
      const runs = []; let cur = [tr[0]];
      for (let k = 1; k < tr.length; k++) {
        const a = tr[k - 1], b = tr[k], step = (b[1] - a[1]) / Math.max(1, b[0] - a[0]);
        if (sign * step > 1.5) cur.push(b); else { if (cur.length > 1) runs.push(cur); cur = [b]; }
      }
      runs.push(cur);
      tr = runs.reduce((a, b) => (b.length > a.length ? b : a)).slice(skipAfter, skipAfter + maxFrames);
      if (tr.length < 8) continue;
      const xs = tr.map((p) => p[1] / 0.5), ys = tr.map((p) => p[2] / 0.5);
      const r = median(tr.map((p) => p[3])) / 0.5, pad = 4 * r;
      const x0 = Math.trunc(Math.max(0, Math.min(...xs) - pad)), y0 = Math.trunc(Math.max(0, Math.min(...ys) - pad));
      const x1 = Math.trunc(Math.min(sw / 0.5, Math.max(...xs) + pad)), y1 = Math.trunc(Math.min(sh / 0.5, Math.max(...ys) + pad));
      segs.push({ start: tr[0][0], end: tr[tr.length - 1][0] + 1, roi: [x0, y0, x1 - x0, y1 - y0] });
    }
    segs.sort((a, b) => a.start - b.start);
    segs.ballFrames = cands.filter((c) => c.length).length;   // for the "no serve found" diagnosis
    return segs;
  }

  // ------------------------------------------------------------ 2. ball + pole per frame
  function findBall(g, bg, W, H, minR) {
    const mask = new Uint8Array(W * H);
    for (let p = 0; p < mask.length; p++) mask[p] = Math.abs(g[p] - bg[p]) > 25 ? 1 : 0;
    const m = opening(closing(mask, W, H, 5), W, H, 3);
    let best = null;
    for (const c of components(m, W, H, true)) {
      if (c.w < 2 * minR || c.h < 2 * minR) continue;
      const aspect = Math.max(c.w, c.h) / Math.min(c.w, c.h);
      const fill = c.area / (Math.PI * (Math.max(c.w, c.h) / 2) ** 2);
      if (aspect > 1.6 || fill < 0.6) continue;
      if (!best || c.area > best.area) best = c;
    }
    if (!best) return null;
    const [cx, cy, r] = minEnclosingCircle(best.pix);
    return [cx, cy, Math.min(r, Math.sqrt(best.area / Math.PI) * 1.05)];
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

  function measurePole(frames, balls, W, H, fps, inner = 0.92, nb = 10, stats = {}) {
    const idx = balls.map((b, i) => (b ? i : -1)).filter((i) => i >= 0);
    if (idx.length < 8) return null;
    const { cx: cxp, cy: cyp } = smoothFit(balls, 2.5, 2.5);
    const r = median(idx.map((i) => balls[i][2]));
    const rows = [], Dall = [], walls = [];
    for (const i of idx) {
      const cx = polyval(cxp, i), cy = polyval(cyp, i);
      const x0 = Math.trunc(Math.max(0, cx - 2.6 * r)), x1 = Math.trunc(Math.min(W, cx + 2.6 * r + 1));
      const y0 = Math.trunc(Math.max(0, cy - 2.6 * r)), y1 = Math.trunc(Math.min(H, cy + 2.6 * r + 1));
      const g = frames[i], D = [], UX = [], UY = [], ring = [];
      for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
        const dx = x - cx, dy = y - cy, rr = Math.hypot(dx, dy);
        if (rr >= 1.6 * r && rr <= 2.5 * r) ring.push(g[y * W + x]);
      }
      let discN = 0;
      for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) { const rr = Math.hypot(x - cx, y - cy); if (rr <= inner * r) discN++; }
      if (discN < 20 || ring.length < 20) continue;
      const wall = median(ring);
      walls.push(wall);
      for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) {
        const dx = x - cx, dy = y - cy;
        if (Math.hypot(dx, dy) <= inner * r) {
          const v = clamp(1 - g[y * W + x] / Math.max(wall, 1), 0, 1);
          D.push(v); UX.push(dx / r); UY.push(dy / r); Dall.push(v);
        }
      }
      rows.push({ i, D, UX, UY });
    }
    if (rows.length < 8) return null;
    stats.wall = median(walls); stats.contrast = percentile(Dall, 95) - percentile(Dall, 10);
    if (stats.contrast < 0.15) return { error: "The marking is too faint to read. Use more light and a fully black half." };
    // per-spot white/black levels (removes the lighting gradient across the ball)
    const binsLo = Array.from({ length: nb * nb }, () => []);
    const bin = (u) => clamp(Math.trunc((u + 1) / 2 * nb), 0, nb - 1);
    for (const rw of rows) for (let k = 0; k < rw.D.length; k++) binsLo[bin(rw.UX[k]) * nb + bin(rw.UY[k])].push(rw.D[k]);
    const g10 = percentile(Dall, 10), g95 = percentile(Dall, 95);
    let lo = new Float64Array(nb * nb).fill(g10), hi = new Float64Array(nb * nb).fill(g95);
    for (let q = 0; q < nb * nb; q++) if (binsLo[q].length >= 30) { lo[q] = percentile(binsLo[q], 10); hi[q] = percentile(binsLo[q], 95); }
    lo = blur2d(lo, nb, 0.8); hi = blur2d(hi, nb, 0.8);
    const t = [], B = [];
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
    }
    return fitRotatingVector(t, B, fps);
  }

  // ------------------------------------------------------------ 3. rate scan
  function fitRotatingVector(t, B, fps) {
    const n = t.length, mean = [0, 1, 2].map((d) => B.reduce((s, b) => s + b[d], 0) / n);
    let variance = 0;
    for (const b of B) for (let d = 0; d < 3; d++) variance += (b[d] - mean[d]) ** 2;
    if (variance / n < 0.02) return { rps: 0, axis: [0, 0, 1], score: 1, runner: NaN, r2: null, swing: Math.sqrt(variance / n), noSpin: true };
    let best = null, resid = [], rates = [];
    for (let k = 0; ; k++) {
      const rps = 0.5 + k * 0.05;
      if (rps >= fps / 2) break;
      const w = 2 * Math.PI * rps / fps;
      const X = t.map((ti) => [1, Math.cos(w * ti), Math.sin(w * ti)]);
      const A = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
      for (const x of X) for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) A[i][j] += x[i] * x[j];
      const coef = [0, 1, 2].map((d) => {
        const bvec = [0, 0, 0];
        X.forEach((x, k2) => { for (let i = 0; i < 3; i++) bvec[i] += x[i] * B[k2][d]; });
        return solve3(A, bvec);                 // [c_d, P_d, Q_d]
      });
      let res = 0;
      X.forEach((x, k2) => { for (let d = 0; d < 3; d++) { const p = coef[d][0] * x[0] + coef[d][1] * x[1] + coef[d][2] * x[2]; res += (p - B[k2][d]) ** 2; } });
      rates.push(rps); resid.push(res);
      if (!best || res < best.res) best = { res, rps, coef };
    }
    const P = [best.coef[0][1], best.coef[1][1], best.coef[2][1]], Q = [best.coef[0][2], best.coef[1][2], best.coef[2][2]];
    let a = cross(P, Q); a = scale(a, 1 / (norm(a) + 1e-12));
    const r2 = 1 - best.res / variance;
    let far = Infinity;
    rates.forEach((rr, k) => { if (Math.abs(rr - best.rps) > 2.0 && resid[k] < far) far = resid[k]; });
    const runner = far < Infinity ? 1 - far / variance : NaN;
    const swing = 0.5 * (norm(P) + norm(Q));
    let score = Math.max(0, r2) * Math.min(1, swing / 0.4);
    if (Number.isFinite(runner) && runner > r2 - 0.05) score *= 0.5;
    return { rps: best.rps, axis: a, score, runner, r2, swing };
  }

  // ------------------------------------------------------------ 4. geometry + labels
  function uprightRotation(ax, ay) {
    if (Math.hypot(ax, ay) < 1e-6) return [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
    if (Math.abs(ay) >= Math.abs(ax)) return ay > 0 ? [[1, 0, 0], [0, 1, 0], [0, 0, 1]] : [[-1, 0, 0], [0, -1, 0], [0, 0, 1]];
    return ax > 0 ? [[0, -1, 0], [1, 0, 0], [0, 0, 1]] : [[0, 1, 0], [-1, 0, 0], [0, 0, 1]];
  }
  function flightGeometry(balls, fps, frameWidth, hfov) {
    const { cx: px, cy: py, t, keep } = smoothFit(balls, 2.5, 3);
    const rr = balls.filter(Boolean).map((b) => b[2]);
    const tm = t.reduce((s, v) => s + v, 0) / t.length;
    const vx = 2 * px[0] * tm + px[1], vy = 2 * py[0] * tm + py[1];
    const ax = 2 * px[0], ay = 2 * py[0];
    const tk = t.filter((_, k) => keep[k]), lk = rr.map(Math.log).filter((_, k) => keep[k]);
    const dlnr = polyfit(tk, lk, 1)[0];
    const fpx = (frameWidth / 2) / Math.tan((hfov * Math.PI / 180) / 2);
    let f = [vx / fpx, vy / fpx, dlnr]; f = scale(f, 1 / norm(f));
    const mmpx = 40.0 / (2 * median(rr)), toMs = mmpx / 1000 * fps;
    const turn = uprightRotation(ax, ay);
    f = matvec(turn, f);
    const acc = matvec(turn, [ax, ay, 0]);
    return {
      f, turn, rotatedDeg: ((Math.round(Math.atan2(turn[1][0], turn[0][0]) * 180 / Math.PI) % 360) + 360) % 360,
      speed: Math.hypot(vx, vy) * toMs, towardCameraDeg: Math.asin(clamp(f[2], -1, 1)) * 180 / Math.PI,
      downAccel: acc[1] * toMs * fps, mmPerPx: mmpx,
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
    if (s.moving > 0.3) out.push(chk("steady", "bad", "Camera moving, or a busy view", `${m}% of the picture keeps changing. Use a tripod and a plain wall behind the flight.`));
    else if (s.moving > 0.12) out.push(chk("steady", "warn", "Lots of movement in view", `${m}% of the picture keeps changing. Keep people and other balls out of the background.`));
    else out.push(chk("steady", "ok", "Camera steady"));
    return out;
  }
  function flightCheck(segs) {
    if (segs.length) return chk("flight", "ok", segs.length > 1 ? `${segs.length} serves found` : "Serve found");
    if (segs.ballFrames < 12) return chk("flight", "bad", "No ball found", "Nothing ball-sized moved in view. Check the light, the distance (ball about 50 px across) and the plain wall behind the flight.");
    return chk("flight", "bad", "No flight across the picture", "Something ball-sized moved, but not sideways across the view. Serve across the camera's view.");
  }
  function serveChecks(s) {
    const out = [];
    if (s.ballR) {
      const d = Math.round(2 * s.ballR);
      if (s.ballR < 7) out.push(chk("size", "bad", `Ball too small (${d} px across)`, "Move the camera closer: about 1–1.5 m from the ball's path, so the ball is 25–50 px across."));
      else if (s.ballR < 10) out.push(chk("size", "warn", `Ball small (${d} px across)`, "Move a little closer: 25–50 px across works best."));
      else out.push(chk("size", "ok", `Ball size good (${d} px across)`));
    }
    if (s.frames < 20) out.push(chk("frames", "warn", `Ball seen in only ${s.frames} frames`, "Keep more of the flight in view before the first bounce."));
    if (s.contrast !== undefined) {
      const c = `Contrast ${Math.round(100 * s.contrast)}%. `;
      if (s.contrast < 0.15) out.push(chk("marks", "bad", "Marking not visible", c + "Use more light, and colour the black half fully."));
      else if (s.contrast < 0.3) out.push(chk("marks", "warn", "Marking faint", c + "More light, or a darker marker, makes the reading surer."));
      else out.push(chk("marks", "ok", "Marking clear", `Contrast ${Math.round(100 * s.contrast)}%`));
    }
    if (s.wall !== undefined) {
      if (s.wall > 215) out.push(chk("wall", "warn", "Wall too bright", "The white half blends into the wall. Use a mid-tone wall."));
      else if (s.wall < 35) out.push(chk("wall", "warn", "Wall too dark", "The black half blends into the wall. Use a mid-tone wall."));
      else out.push(chk("wall", "ok", "Wall behind the ball works"));
    }
    return out;
  }

  // ------------------------------------------------------------ 3. measure one serve
  async function measureSegment(R, seg, fps, hfov) {
    const [rx, ry, rw, rh] = seg.roi, { ctx } = makeCanvas(R.W, R.H);
    let frames = new Array(seg.end - seg.start);
    await R.run(seg.start, seg.end, async (i, src) => { frames[i - seg.start] = await grabGray(ctx, src, R.W, R.H, seg.roi); });
    frames = frames.filter(Boolean);
    let bg = medianFrames(frames, rw * rh);
    frames = frames.map((g) => deflickerOne(g, bg, rw, rh, 1));
    bg = medianFrames(frames, rw * rh);
    const balls = frames.map((g) => findBall(g, bg, rw, rh, 6.0));
    const found = balls.filter(Boolean), stats = { frames: found.length };
    if (found.length) stats.ballR = median(found.map((b) => b[2]));
    if (found.length < 4) return { error: "The ball was found in too few frames.", segment: seg, checks: serveChecks(stats) };
    const r = measurePole(frames, balls, rw, rh, fps, undefined, undefined, stats);
    if (!r) return { error: "The ball was found in too few consecutive frames.", segment: seg, checks: serveChecks(stats) };
    if (r.error) return { error: r.error, segment: seg, checks: serveChecks(stats) };
    const geo = flightGeometry(balls, fps, R.W, hfov);
    const axis = matvec(geo.turn, r.axis);
    const c = spinComponents(r.rps, axis, geo.f);
    return {
      rps: r.rps, rpm: r.rps * 60, spinType: spinLabel(r.rps, c),
      backspin: Math.max(0, c.lift), topspin: Math.max(0, -c.lift), sidespin: Math.abs(c.curve),
      sideCurves: Math.abs(c.curve) >= 1 ? (c.curve > 0 ? "right" : "left") : "none",
      gyro: Math.abs(c.gyro), lift: c.lift, curve: c.curve, tilt: c.tilt,
      axis, fit: r.score, reliable: r.score >= 0.75, runnerUp: r.runner,
      flight: { speed: geo.speed, towardCameraDeg: geo.towardCameraDeg, downAccel: geo.downAccel, rotatedDeg: geo.rotatedDeg },
      ballRadiusPx: median(found.map((b) => b[2])), startSec: seg.start / fps,
      segment: seg, crops: makeCrops(frames, balls, rw, rh, geo.turn), checks: serveChecks(stats),
    };
  }

  // ------------------------------------------------------------ main entry
  // Returns { serves: [...], meta, ... } where each serve is a reading or { error }. The fields of
  // the first good reading are also copied to the top level, so a one-serve video reads as before.
  async function analyze(file, opts = {}) {
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
      const timing = () => Object.assign(metaOut, { seconds: (performance.now() - tStart) / 1000,
        frameRead: R.kind === "seek" ? "seek" : readStats.direct >= readStats.canvas ? "direct" : "canvas" });
      if (!segs.length) return { error: "No serve flight found. The ball must fly sideways across the frame against a plain background.", serves: [], checks, meta: timing() };
      const serves = [];
      for (let k = 0; k < segs.length; k++) {
        progress(segs.length > 1 ? `Measuring serve ${k + 1} of ${segs.length}` : "Measuring the spin", 0.86 + 0.14 * k / segs.length);
        serves.push(await measureSegment(R, segs[k], fps, hfov));
      }
      progress("Done", 1);
      const first = serves.find((s) => !s.error);
      const base = { serves, fps, captureFpsDetected: !!meta.captureFps, checks, meta: timing() };
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

  global.SpinMeter = { analyze, parseMp4, _internals: { webCodecsReader, percentile, polyfit, minEnclosingCircle, fitRotatingVector, spinComponents, spinLabel } };
})(typeof window !== "undefined" ? window : globalThis);
