import React, { useEffect, useState } from 'react';
import { AbsoluteFill, Audio, Sequence, continueRender, delayRender, interpolate, staticFile, useCurrentFrame } from 'remotion';
import '@fontsource/noto-sans-tc/400.css';
import '@fontsource/noto-sans-tc/500.css';
import '@fontsource/noto-sans-tc/700.css';
import '@fontsource/noto-sans-tc/900.css';
import { C, F, FPS, s } from './theme';
import { GOV_MUSIC_OFFSET_S, GOV_SCENES, GOV_VO } from './govTheme';
import { Grain, SceneFade, clamp } from './components';
import { preloadTexture } from './three/Models';
import { InvisibleScene, SetupScene } from './scenes/Scenes3D';
import { HudScene } from './scenes/Scenes2D';
import { BureauScene, CoachScene, GovEndScene, MatrixScene, StillServe, StillSetup } from './scenes/GovScenes';

const fontCss = `
@font-face { font-family: Oxanium; src: url(${staticFile('fonts/Oxanium.ttf')}); font-weight: 200 800; }
@font-face { font-family: "IBM Plex Mono"; src: url(${staticFile('fonts/IBMPlexMono-Regular.ttf')}); font-weight: 400; }
@font-face { font-family: "IBM Plex Mono"; src: url(${staticFile('fonts/IBMPlexMono-Medium.ttf')}); font-weight: 500 700; }
`;

const ZH = GOV_VO.map((v) => v.text).join('') +
  '旋轉看不見每秒格看清每一顆球轉速強弱幾秒內說給你聽每位學員的進步一目了然誰該加強哪一種發球一眼看出各訓練站成效用數據呈現' +
  '教練端國小桌球訓練站示意畫面學員下旋平均趨勢六五四三年級第週世界級選手平均進步各類發球側旋上旋側下旋系統提示偏弱本週加強摩擦向上先穩定再加' +
  '試辦成果總覽量測發球數使用站開發中與數據皆為虛構用以說明報表形式讓基層的每一次都看得見歡迎安排現場示範余承翰奇斯科技－';

const useAssets = () => {
  const [h] = useState(() => delayRender('fonts and textures'));
  const [ready, setReady] = useState(false);
  useEffect(() => { if (ready) requestAnimationFrame(() => continueRender(h)); }, [ready, h]);
  useEffect(() => {
    Promise.all([
      preloadTexture('hud.png'),
      ...[400, 500, 700, 900].map((w) => document.fonts.load(`${w} 40px "Noto Sans TC"`, ZH)),
      document.fonts.load('300 40px Oxanium', '0123456789'),
      document.fonts.load('700 40px Oxanium', 'SERVE'),
      document.fonts.load('400 40px "IBM Plex Mono"', 'A'),
      document.fonts.load('500 40px "IBM Plex Mono"', 'A'),
    ]).then(() => setReady(true));
  }, [h]);
  return ready;
};

const duck = (f: number) => {
  const t = f / FPS;
  const speaking = GOV_VO.some((v) => t >= v.at - 0.25 && t <= v.at + v.dur + 0.2);
  const base = speaking ? 0.14 : 0.38;
  const fadeIn = interpolate(t, [0, 1.2], [0, 1], { extrapolateRight: 'clamp' });
  const fadeOut = interpolate(t, [57.0, 60.0], [1, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  return base * fadeIn * fadeOut;
};

const Captions: React.FC = () => {
  const f = useCurrentFrame();
  const t = f / FPS;
  const line = GOV_VO.find((v) => !v.file.endsWith('g7') && t >= v.at - 0.1 && t <= v.at + v.dur + 0.35);
  if (!line) return null;
  const o = interpolate(t, [line.at - 0.1, line.at + 0.1, line.at + line.dur + 0.15, line.at + line.dur + 0.35], [0, 1, 1, 0], clamp);
  return (
    <AbsoluteFill style={{ justifyContent: 'flex-end', alignItems: 'center', paddingBottom: 54, pointerEvents: 'none' }}>
      <div style={{ opacity: o, fontFamily: F.zh, fontWeight: 500, fontSize: 38, color: C.white, letterSpacing: 1, padding: '10px 26px', background: 'rgba(2,6,11,0.6)', borderRadius: 10, textShadow: '0 2px 8px rgba(0,0,0,0.6)' }}>{line.text}</div>
    </AbsoluteFill>
  );
};

export const GovPitch: React.FC = () => {
  const ready = useAssets();
  const scenes: [keyof typeof GOV_SCENES, React.FC][] = [
    ['invisible', InvisibleScene], ['setup', SetupScene], ['hud', HudScene], ['coach', CoachScene],
    ['matrix', MatrixScene], ['bureau', BureauScene], ['end', GovEndScene],
  ];
  return (
    <AbsoluteFill style={{ background: '#000' }}>
      <style>{fontCss}</style>
      {ready && scenes.map(([k, Comp]) => {
        const [a, b] = GOV_SCENES[k];
        const from = Math.max(0, a - 6);
        return (
          <Sequence key={k} from={from} durationInFrames={b - from + 6} name={k}>
            <SceneFade len={b - from + 6} inF={k === 'invisible' ? 1 : 12} outF={k === 'end' ? 1 : 10}>
              <Comp />
            </SceneFade>
          </Sequence>
        );
      })}
      <Captions />
      <Grain />
      <Audio src={staticFile('music.mp3')} startFrom={s(GOV_MUSIC_OFFSET_S)} volume={duck} />
      {GOV_VO.map((v) => (
        <Sequence key={v.file} from={s(v.at)} durationInFrames={s(v.dur + 0.5)}>
          <Audio src={staticFile(`${v.file}.wav`)} volume={v.file === 'vo/app' ? 0.85 : 1} />
        </Sequence>
      ))}
      {(['setup', 'hud', 'coach', 'matrix', 'bureau', 'end'] as const).map((k) => (
        <Sequence key={'w' + k} from={GOV_SCENES[k][0] - 10} durationInFrames={30}>
          <Audio src={staticFile('sfx/whoosh.wav')} volume={0.3} />
        </Sequence>
      ))}
      <Sequence from={GOV_SCENES.hud[0] + 48} durationInFrames={20}><Audio src={staticFile('sfx/ding.wav')} volume={0.4} /></Sequence>
      <Sequence from={GOV_SCENES.end[0]} durationInFrames={20}><Audio src={staticFile('sfx/hit.wav')} volume={0.5} /></Sequence>
    </AbsoluteFill>
  );
};

// Stills for the handout reuse the same asset loading.
const withAssets = (C2: React.FC): React.FC => () => {
  const ready = useAssets();
  return <AbsoluteFill><style>{fontCss}</style>{ready && <C2 />}</AbsoluteFill>;
};
export const StillSetupComp = withAssets(StillSetup);
export const StillServeComp = withAssets(StillServe);
