// Colours and fonts taken from the app's HUD (android/.../Hud.kt, assets/fonts), so the film looks like the product.
export const C = {
  cyan: 'rgb(95,242,255)',
  cyanDim: 'rgba(95,242,255,0.45)',
  cyanFaint: 'rgba(95,242,255,0.18)',
  amber: 'rgb(255,211,106)',
  red: 'rgb(255,95,109)',
  white: 'rgb(240,248,250)',
  navy: '#07111d',
  navy2: '#0d1b2c',
  ink: '#02060b',
};

export const F = {
  num: 'Oxanium, sans-serif',
  mono: '"IBM Plex Mono", monospace',
  zh: '"Noto Sans TC", "PingFang TC", sans-serif',
};

export const FPS = 30;
export const s = (sec: number) => Math.round(sec * FPS);

// Scene slots (frames). Each scene fades in over its first 12 frames, so neighbours overlap slightly.
export const SCENES = {
  hook: [0, s(4.6)],
  invisible: [s(4.6), s(10.2)],
  setup: [s(10.2), s(16.6)],
  hud: [s(16.6), s(28.0)],
  scale: [s(28.0), s(35.0)],
  marking: [s(35.0), s(43.6)],
  features: [s(43.6), s(51.4)],
  end: [s(51.4), s(60.0)],
} as const;

// Voice lines: file, start (s), on-screen caption. Durations are measured from the trimmed WAVs.
export const VO = [
  { file: 'l1', at: 0.8, dur: 2.05, text: '你的發球，到底轉多快？' },
  { file: 'l2', at: 5.0, dur: 4.38, text: '旋轉看不見。對手感覺得到，你卻不知道。' },
  { file: 'l3', at: 10.6, dur: 4.86, text: '把手機架在球桌旁，每秒 240 格，看清每一顆球。' },
  { file: 'app', at: 18.2, dur: 4.66, text: '（App 語音）2450 RPM, strong spin, backspin.' },
  { file: 'l4', at: 23.2, dur: 4.6, text: '轉速、旋轉種類、強弱，幾秒內直接說給你聽。' },
  { file: 'l5', at: 28.4, dur: 6.08, text: '世界頂尖選手的發球，平均約 2,760 轉。你還差多少？' },
  { file: 'l6', at: 35.6, dur: 6.69, text: '半顆塗黑，再點上三個色點。用 3D 列印的標記工具，一分鐘完成。' },
  { file: 'l7', at: 44.0, dur: 6.17, text: '不用網路，不必買專用球。教練一支手機，整班輪流練發球。' },
  { file: 'l8', at: 52.2, dur: 4.13, text: 'Serve Coach Live。讓每一顆發球，都有數字。' },
];

// Music: Mixkit "Rising Forest" (Mixkit Stock Music Free License). Its build at 37.4 s lands on the HUD reveal.
export const MUSIC_OFFSET_S = 37.4 - 16.6;
