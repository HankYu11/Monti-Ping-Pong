import { s } from './theme';

// 60-second pitch for city sports / education bureaus. Scenes 1–3 reuse the commercial's shots;
// scenes 4–6 show the coach side (示意畫面, not built yet) and what a bureau gets.
export const GOV_SCENES = {
  invisible: [0, s(7.2)],
  setup: [s(7.2), s(14.6)],
  hud: [s(14.6), s(26.4)],
  coach: [s(26.4), s(38.0)],
  matrix: [s(38.0), s(45.0)],
  bureau: [s(45.0), s(52.0)],
  end: [s(52.0), s(60.0)],
} as const;

// Voice: zh-TW HsiaoChen (edge-tts), trimmed. Durations measured from the WAVs in public/vo_gov.
export const GOV_VO = [
  { file: 'vo_gov/g1', at: 0.6, dur: 6.06, text: '發球的旋轉，肉眼看不見。基層教練，只能靠經驗判斷。' },
  { file: 'vo_gov/g2', at: 7.6, dur: 6.44, text: '旋轉教練：一支手機架在球桌旁，每秒 240 格，看清每一顆球。' },
  { file: 'vo/app', at: 16.2, dur: 4.66, text: '（App 語音）2450 RPM, strong spin, backspin.' },
  { file: 'vo_gov/g3', at: 21.2, dur: 4.87, text: '學生發球，幾秒內就聽到轉速、旋轉種類和強弱。' },
  { file: 'vo_gov/g4', at: 26.9, dur: 7.48, text: '每一顆發球都會自動記錄。教練打開學員名單，就能看到每個人一週一週的進步。' },
  { file: 'vo_gov/g5', at: 38.4, dur: 4.96, text: '系統會指出，誰該加強哪一種發球，讓指導更精準。' },
  { file: 'vo_gov/g6', at: 45.4, dur: 4.88, text: '對局處來說，各訓練站的訓練成效，也能用數據呈現。' },
  { file: 'vo_gov/g7', at: 52.6, dur: 5.19, text: '旋轉教練。讓基層桌球的每一次進步，都看得見。' },
];

// Music build (37.4 s into the track) lands on the HUD reveal.
export const GOV_MUSIC_OFFSET_S = 37.4 - 14.6;
