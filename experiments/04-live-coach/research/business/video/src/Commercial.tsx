import React, { useEffect, useState } from 'react';
import { AbsoluteFill, Audio, Sequence, continueRender, delayRender, interpolate, staticFile } from 'remotion';
import '@fontsource/noto-sans-tc/400.css';
import '@fontsource/noto-sans-tc/500.css';
import '@fontsource/noto-sans-tc/700.css';
import '@fontsource/noto-sans-tc/900.css';
import { FPS, MUSIC_OFFSET_S, SCENES, VO, s } from './theme';
import { Captions, Grain, SceneFade } from './components';
import { preloadSTL, preloadTexture } from './three/Models';
import { HookScene, InvisibleScene, MarkingScene, SetupScene } from './scenes/Scenes3D';
import { EndScene, FeaturesScene, HudScene, ScaleScene } from './scenes/Scenes2D';

const fontCss = `
@font-face { font-family: Oxanium; src: url(${staticFile('fonts/Oxanium.ttf')}); font-weight: 200 800; }
@font-face { font-family: "IBM Plex Mono"; src: url(${staticFile('fonts/IBMPlexMono-Regular.ttf')}); font-weight: 400; }
@font-face { font-family: "IBM Plex Mono"; src: url(${staticFile('fonts/IBMPlexMono-Medium.ttf')}); font-weight: 500 700; }
`;

// Wait for every font (and every Chinese glyph used) before the first frame is captured.
const useAssets = () => {
  const [h] = useState(() => delayRender('fonts and models'));
  const [ready, setReady] = useState(false);
  // let the scenes mount with everything loaded before the first frame is taken
  useEffect(() => { if (ready) requestAnimationFrame(() => continueRender(h)); }, [ready, h]);
  useEffect(() => {
    const zh = VO.map((v) => v.text).join('') + '轉速旋轉強弱幾秒內說給你聽世界頂尖選手的發球平均還差轉一分鐘標記半顆塗黑三個色點紅綠藍離線運作不必買專用球整班輪流量測全在手機上完成一般練習球自己標記一支手機一整班目前支援可拍攝的手機讓每一顆發球都有數字搶先體驗經銷合作洽詢奇斯科技無弱中強示意渲染杯座模板資料世界桌球錦標賽八強看不見每秒格看清';
    Promise.all([
      preloadSTL(['cup', 'cap']),
      preloadTexture('hud.png'),
      ...[400, 500, 700, 900].map((w) => document.fonts.load(`${w} 40px "Noto Sans TC"`, zh)),
      document.fonts.load('300 40px Oxanium', '0123456789'),
      document.fonts.load('700 40px Oxanium', 'SERVE'),
      document.fonts.load('400 40px "IBM Plex Mono"', 'A'),
      document.fonts.load('500 40px "IBM Plex Mono"', 'A'),
    ]).then(() => setReady(true));
  }, [h]);
  return ready;
};

const duck = (f: number) => {
  // music sits under the voice, rises between lines and at the reveal, fades at the very end
  const t = f / FPS;
  const speaking = VO.some((v) => t >= v.at - 0.25 && t <= v.at + v.dur + 0.2);
  const base = speaking ? 0.16 : 0.42;
  const fadeIn = interpolate(t, [0, 1.2], [0, 1], { extrapolateRight: 'clamp' });
  const fadeOut = interpolate(t, [57.0, 60.0], [1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  return base * fadeIn * fadeOut;
};

export const Commercial: React.FC = () => {
  const ready = useAssets();
  const scenes: [keyof typeof SCENES, React.FC][] = [
    ['hook', HookScene], ['invisible', InvisibleScene], ['setup', SetupScene], ['hud', HudScene],
    ['scale', ScaleScene], ['marking', MarkingScene], ['features', FeaturesScene], ['end', EndScene],
  ];
  return (
    <AbsoluteFill style={{ background: '#000' }}>
      <style>{fontCss}</style>
      {ready && scenes.map(([k, Comp]) => {
        const [a, b] = SCENES[k];
        const from = Math.max(0, a - 6);
        return (
          <Sequence key={k} from={from} durationInFrames={b - from + 6} name={k}>
            <SceneFade len={b - from + 6} inF={k === 'hook' ? 1 : 12} outF={k === 'end' ? 1 : 10}>
              <Comp />
            </SceneFade>
          </Sequence>
        );
      })}
      <Captions />
      <Grain />
      {/* sound */}
      <Audio src={staticFile('music.mp3')} startFrom={s(MUSIC_OFFSET_S)} volume={duck} />
      {VO.map((v) => (
        <Sequence key={v.file} from={s(v.at)} durationInFrames={s(v.dur + 0.5)}>
          <Audio src={staticFile(`vo/${v.file}.wav`)} volume={v.file === 'app' ? 0.85 : 1} />
        </Sequence>
      ))}
      {(['invisible', 'setup', 'hud', 'scale', 'marking', 'features', 'end'] as const).map((k) => (
        <Sequence key={'w' + k} from={SCENES[k][0] - 10} durationInFrames={30}>
          <Audio src={staticFile('sfx/whoosh.wav')} volume={0.35} />
        </Sequence>
      ))}
      <Sequence from={SCENES.hud[0] + 48} durationInFrames={20}><Audio src={staticFile('sfx/ding.wav')} volume={0.4} /></Sequence>
      <Sequence from={SCENES.end[0]} durationInFrames={20}><Audio src={staticFile('sfx/hit.wav')} volume={0.6} /></Sequence>
    </AbsoluteFill>
  );
};
