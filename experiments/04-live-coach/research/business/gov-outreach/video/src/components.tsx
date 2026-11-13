import React from 'react';
import { AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { C, F, FPS, VO } from './theme';

export const clamp = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;
export const ease = Easing.bezier(0.22, 1, 0.36, 1);

// Fades a scene in over its first frames and out over its last ones, with a slight scale settle.
export const SceneFade: React.FC<{ len: number; children: React.ReactNode; inF?: number; outF?: number; bg?: string }> = ({ len, children, inF = 12, outF = 10, bg }) => {
  const f = useCurrentFrame();
  const o = interpolate(f, [0, inF, len - outF, len], [0, 1, 1, 0], clamp);
  const sc = interpolate(f, [0, inF * 2], [1.03, 1], { ...clamp, easing: ease });
  return (
    <AbsoluteFill style={{ opacity: o, transform: `scale(${sc})`, background: bg }}>
      {children}
    </AbsoluteFill>
  );
};

// A word/line that rises in with a spring.
export const Rise: React.FC<{ at: number; children: React.ReactNode; style?: React.CSSProperties; dist?: number }> = ({ at, children, style, dist = 40 }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = spring({ frame: f - at, fps, config: { damping: 16, stiffness: 120, mass: 0.7 } });
  return (
    <div style={{ opacity: interpolate(f - at, [0, 8], [0, 1], clamp), transform: `translateY(${(1 - p) * dist}px)`, ...style }}>{children}</div>
  );
};

// Bottom captions from the voice lines (for viewers watching without sound).
export const Captions: React.FC = () => {
  const f = useCurrentFrame();
  const t = f / FPS;
  const line = VO.find((v) => v.file !== 'l8' && t >= v.at - 0.1 && t <= v.at + v.dur + 0.35);
  if (!line) return null;
  const o = interpolate(t, [line.at - 0.1, line.at + 0.1, line.at + line.dur + 0.15, line.at + line.dur + 0.35], [0, 1, 1, 0], clamp);
  return (
    <AbsoluteFill style={{ justifyContent: 'flex-end', alignItems: 'center', paddingBottom: 54, pointerEvents: 'none' }}>
      <div style={{ opacity: o, fontFamily: F.zh, fontWeight: 500, fontSize: 38, color: C.white, letterSpacing: 1, padding: '10px 26px', background: 'rgba(2,6,11,0.55)', borderRadius: 10, textShadow: '0 2px 8px rgba(0,0,0,0.6)' }}>
        {line.text}
      </div>
    </AbsoluteFill>
  );
};

export const Vignette: React.FC<{ strength?: number }> = ({ strength = 0.75 }) => (
  <AbsoluteFill style={{ background: `radial-gradient(ellipse at center, rgba(0,0,0,0) 45%, rgba(0,0,0,${strength}) 100%)`, pointerEvents: 'none' }} />
);

// Film grain keeps big dark gradients from banding after H.264 compression.
export const Grain: React.FC = () => {
  const f = useCurrentFrame();
  const seed = (f * 7919) % 1000;
  return (
    <AbsoluteFill style={{ pointerEvents: 'none', opacity: 0.06, mixBlendMode: 'overlay' }}>
      <svg width="100%" height="100%">
        <filter id={`g${seed}`}>
          <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" seed={seed} />
        </filter>
        <rect width="100%" height="100%" filter={`url(#g${seed})`} />
      </svg>
    </AbsoluteFill>
  );
};

export const Footnote: React.FC<{ children: React.ReactNode; at?: number }> = ({ children, at = 0 }) => {
  const f = useCurrentFrame();
  return (
    <div style={{ position: 'absolute', right: 60, bottom: 26, fontFamily: F.zh, fontSize: 18, color: 'rgba(240,248,250,0.5)', opacity: interpolate(f - at, [0, 10], [0, 1], clamp) }}>{children}</div>
  );
};
