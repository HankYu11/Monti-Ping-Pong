import * as THREE from 'three';

// The marking the spin meter reads best (experiments/05-ball-marker/README.md):
// one half black; on the white half three 8 mm dots (red, green, blue), 1 cm from the line, 120° apart.
// Ball 40 mm: 1 cm along the surface = 0.5 rad of latitude; a 4 mm dot radius = 0.2 rad.
const cache: Record<string, THREE.CanvasTexture> = {};

export function ballTexture(kind: 'marked' | 'plain' | 'half' | 'dots1' | 'dots2' = 'marked'): THREE.CanvasTexture {
  if (cache[kind]) return cache[kind];
  const W = 1024, H = 512;
  const canvas = document.createElement('canvas');
  canvas.width = W; canvas.height = H;
  const ctx = canvas.getContext('2d')!;
  const img = ctx.createImageData(W, H);
  const dotLat = 0.5, dotR = 0.2;
  const dots = [0, 1, 2].map((k) => {
    const lon = (k * 2 * Math.PI) / 3;
    return new THREE.Vector3(Math.cos(dotLat) * Math.cos(lon), Math.sin(dotLat), Math.cos(dotLat) * Math.sin(lon));
  });
  const cols = [[226, 38, 46], [34, 170, 72], [36, 84, 214]];
  const v = new THREE.Vector3();
  for (let y = 0; y < H; y++) {
    const lat = Math.PI / 2 - (y / H) * Math.PI;
    for (let x = 0; x < W; x++) {
      const lon = (x / W) * 2 * Math.PI;
      v.set(Math.cos(lat) * Math.cos(lon), Math.sin(lat), Math.cos(lat) * Math.sin(lon));
      let c = [244, 244, 240];
      if (kind !== 'plain') {
        // soft edge over ~0.01 rad so the line doesn't alias
        const edge = THREE.MathUtils.clamp((lat + 0.006) / 0.012, 0, 1);
        c = [244 * edge + 18 * (1 - edge), 244 * edge + 18 * (1 - edge), 240 * edge + 20 * (1 - edge)];
        const nDots = kind === 'marked' ? 3 : kind === 'dots2' ? 2 : kind === 'dots1' ? 1 : 0;
        dots.slice(0, nDots).forEach((d, i) => {
          const ang = Math.acos(THREE.MathUtils.clamp(v.dot(d), -1, 1));
          const a = THREE.MathUtils.clamp((dotR - ang) / 0.01 + 0.5, 0, 1);
          if (a > 0) c = c.map((ch, j) => ch * (1 - a) + cols[i][j] * a);
        });
      }
      const o = (y * W + x) * 4;
      img.data[o] = c[0]; img.data[o + 1] = c[1]; img.data[o + 2] = c[2]; img.data[o + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = 8;
  cache[kind] = tex;
  return tex;
}
