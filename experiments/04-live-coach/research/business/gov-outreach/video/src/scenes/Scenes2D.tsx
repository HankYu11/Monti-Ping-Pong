import React from 'react';
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { C, F } from '../theme';
import { clamp, ease, Footnote, Rise, Vignette } from '../components';
import { LogoBall, PhoneView } from './Scenes3D';

/* ---------- 4. The app: the HUD as v0.9.2 draws it (HudScreen.kt / the Paparazzi snapshot) ---------- */
// The phone screen is 2400x1080; everything inside is laid out in those units and scaled down.
const SW = 2400, SH = 1080;

const HexButton: React.FC<{ label: string; w: number; color: string; fill?: string; big?: boolean }> = ({ label, w, color, fill = 'rgba(95,242,255,0.10)', big }) => {
  const h = big ? 132 : 92;
  const c = h / 2;
  return (
    <svg width={w} height={h} style={{ overflow: 'visible' }}>
      <polygon points={`${c},2 ${w - c},2 ${w - 2},${h / 2} ${w - c},${h - 2} ${c},${h - 2} 2,${h / 2}`} fill={fill} stroke={color} strokeWidth={4} />
      <text x={w / 2} y={h / 2 + (big ? 15 : 11)} textAnchor="middle" fontFamily="Oxanium" fontWeight={600} fontSize={big ? 44 : 34} letterSpacing={big ? 8 : 4} fill={color}>{label}</text>
    </svg>
  );
};

const Scale: React.FC<{ rpm: number; show: number; width: number }> = ({ rpm, show, width }) => {
  // bands as in the app: no spin <600, weak 600-1500, medium 1500-2400, strong 2400+; pro avg 2760, pro max 3750
  const max = 4000;
  const x = (v: number) => (v / max) * width;
  const bands = [[0, 600, 'NO SPIN'], [600, 1500, 'WEAK'], [1500, 2400, 'MEDIUM'], [2400, max, 'STRONG']] as const;
  const level = rpm < 600 ? 0 : rpm < 1500 ? 1 : rpm < 2400 ? 2 : 3;
  return (
    <div style={{ position: 'relative', width, height: 110, opacity: show }}>
      {bands.map(([a, b, n], i) => (
        <React.Fragment key={n}>
          <div style={{ position: 'absolute', left: x(a) + 4, top: 40, width: x(b) - x(a) - 8, height: 20, background: i === level ? C.cyan : i < level ? 'rgba(95,242,255,0.47)' : 'rgba(95,242,255,0.18)', boxShadow: i === level ? '0 0 24px rgba(95,242,255,0.6)' : 'none' }} />
          <div style={{ position: 'absolute', left: x(a), width: x(b) - x(a), top: 76, textAlign: 'center', fontFamily: F.mono, fontSize: 26, letterSpacing: 2, color: i === level ? C.white : C.cyanDim, fontWeight: i === level ? 600 : 400 }}>{n}</div>
        </React.Fragment>
      ))}
      {[[2760, 'PRO AVG'], [3750, 'PRO MAX']].map(([v, n]) => (
        <React.Fragment key={n as string}>
          <div style={{ position: 'absolute', left: x(v as number) - 2, top: 30, width: 4, height: 34, background: C.amber }} />
          <div style={{ position: 'absolute', left: x(v as number) - 120, width: 240, top: 0, textAlign: 'center', fontFamily: F.mono, fontSize: 22, color: C.amber }}>{n}</div>
        </React.Fragment>
      ))}
      <svg style={{ position: 'absolute', left: x(rpm) - 16, top: 24 }} width={32} height={22}><polygon points="0,0 32,0 16,20" fill={C.white} /></svg>
    </div>
  );
};

export const HudScene: React.FC = () => {
  const f = useCurrentFrame();
  const { width, height, fps } = useVideoConfig();
  const serveAt = 6; // the serve crosses the table
  const measuring = f >= serveAt + 22 && f < 48;
  const landed = f >= 48;
  const count = Math.round(interpolate(f, [48, 62], [0, 2450], { ...clamp, easing: ease }));
  const pop = spring({ frame: f - 48, fps, config: { damping: 12, stiffness: 160 } });
  // phone settles in, then the view pushes in on the reading while the voice explains it
  const enter = spring({ frame: f, fps, config: { damping: 20, stiffness: 90 } });
  const push = interpolate(f, [60, 340], [1, 1.05]);
  const pw = 1640, ph = (pw * SH) / SW;
  const k = pw / SW;
  const state = landed ? 'READY: SERVE' : measuring ? 'GOT IT: MEASURING SERVE #6…' : 'READY: SERVE';
  const tone = measuring ? C.amber : C.cyan;
  const rows = landed
    ? [['06', '2450', 'BACKSPIN'], ['05', '2770', 'SIDE-BACKSPIN (CURVES RIGHT)'], ['04', '----', 'NO READ'], ['03', '1910', 'BACKSPIN']]
    : [['05', '2770', 'SIDE-BACKSPIN (CURVES RIGHT)'], ['04', '----', 'NO READ'], ['03', '1910', 'BACKSPIN']];
  return (
    <AbsoluteFill style={{ background: 'radial-gradient(ellipse at 50% 40%, #12253b 0%, #04080d 75%)', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ transform: `translateY(70px) scale(${(0.76 + 0.1 * enter) * push})`, opacity: enter }}>
        <div style={{ width: pw + 36, height: ph + 36, borderRadius: 64, background: 'linear-gradient(145deg,#2b333d,#0c1015 60%)', padding: 18, boxShadow: '0 60px 120px rgba(0,0,0,0.6), 0 0 0 2px #3a444f inset' }}>
          <div style={{ position: 'relative', width: pw, height: ph, borderRadius: 48, overflow: 'hidden', background: '#000' }}>
            <PhoneView width={pw} height={ph} serveAt={serveAt} />
            {/* the HUD's own shading towards the reading */}
            <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(90deg, rgba(0,8,14,0) 38%, rgba(0,8,14,0.78) 100%)' }} />
            <div style={{ position: 'absolute', left: 0, top: 0, width: SW, height: SH, transform: `scale(${k})`, transformOrigin: '0 0' }}>
              <div style={{ position: 'absolute', left: 130, top: 48, fontFamily: F.mono, fontWeight: 500, fontSize: 34, letterSpacing: 4, color: tone }}>● {state}</div>
              {/* reading */}
              <div style={{ position: 'absolute', right: 128, top: 92, width: 760 }}>
                <div style={{ fontFamily: F.mono, fontSize: 30, letterSpacing: 6, color: C.cyanDim }}>SERVE {landed ? '06' : '05'} · SPIN RATE</div>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, transform: `scale(${landed ? 0.94 + 0.06 * pop : 1})`, transformOrigin: 'left bottom' }}>
                  <div style={{ fontFamily: F.num, fontWeight: 300, fontSize: 210, lineHeight: 1.05, color: C.cyan, textShadow: '0 0 30px rgba(95,242,255,0.55)', fontVariantNumeric: 'tabular-nums' }}>{landed ? count : 2770}</div>
                  <div style={{ fontFamily: F.num, fontSize: 56, color: C.cyan }}>RPM</div>
                </div>
                <div style={{ fontFamily: F.num, fontWeight: 700, fontSize: 50, letterSpacing: 6, color: C.white, textAlign: 'right' }}>{landed ? 'STRONG · BACKSPIN' : 'STRONG · SIDE-BACKSPIN'}</div>
                <div style={{ marginTop: 26 }}><Scale rpm={landed ? count : 2770} show={1} width={760} /></div>
                <div style={{ marginTop: 28, fontFamily: F.mono, fontSize: 34, color: C.white, textAlign: 'right', lineHeight: 1.45, opacity: landed ? interpolate(f, [62, 72], [0, 1], clamp) : 0 }}>
                  Strong backspin. Keep this contact.<br />ball 4.7 m/s · answer in 2.1 s
                </div>
                <div style={{ marginTop: 26, fontFamily: F.mono, fontSize: 32, color: C.cyan, lineHeight: 1.55 }}>
                  {rows.map((r, i) => (
                    <div key={r[0]} style={{ display: 'flex', gap: 34, opacity: i === 0 && landed ? interpolate(f, [56, 66], [0, 1], clamp) : 0.9 - i * 0.12 }}>
                      <span>{r[0]}</span><span style={{ width: 92 }}>{r[1]}</span><span>{r[2]}</span>
                    </div>
                  ))}
                </div>
              </div>
              <div style={{ position: 'absolute', left: 130, bottom: 210, fontFamily: F.mono, fontSize: 28, lineHeight: 1.5, color: 'rgba(95,242,255,0.6)' }}>App 0.9.2<br />Camera 1920×1080 @ 240 fps<br />Engine ready</div>
              <div style={{ position: 'absolute', left: 130, bottom: 70, display: 'flex', gap: 30 }}>
                <HexButton label="TEST VIDEO" w={300} color={C.cyan} />
                <HexButton label="AI COACH: OFF" w={360} color={C.cyan} />
                <HexButton label="REPORT" w={220} color={C.cyan} />
              </div>
              <div style={{ position: 'absolute', right: 128, bottom: 56, display: 'flex', gap: 40 }}>
                <HexButton label="NEXT SERVE" w={440} color={C.cyan} big />
                <HexButton label="STOP" w={480} color={C.red} fill="rgba(255,95,109,0.14)" big />
              </div>
            </div>
          </div>
        </div>
      </div>
      <Vignette strength={0.5} />
      <AbsoluteFill style={{ paddingTop: 52, alignItems: 'center', opacity: interpolate(f, [150, 172], [0, 1], clamp) }}>
        <div style={{ fontFamily: F.zh, fontWeight: 900, fontSize: 60, color: C.white, letterSpacing: 4 }}>轉速・旋轉・強弱　<span style={{ color: C.cyan }}>幾秒內說給你聽</span></div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 5. Against the world's best ---------- */
export const ScaleScene: React.FC = () => {
  const f = useCurrentFrame();
  const W = 1500, max = 4000;
  const x = (v: number) => (v / max) * W;
  const draw = interpolate(f, [4, 40], [0, 1], { ...clamp, easing: ease });
  const you = interpolate(f, [30, 60], [0, 2450], { ...clamp, easing: ease });
  const proO = interpolate(f, [70, 85], [0, 1], clamp);
  const gapO = interpolate(f, [110, 125], [0, 1], clamp);
  const bands = [[0, 600, 'NO SPIN', '無旋轉'], [600, 1500, 'WEAK', '弱'], [1500, 2400, 'MEDIUM', '中'], [2400, max, 'STRONG', '強']] as const;
  return (
    <AbsoluteFill style={{ background: 'radial-gradient(ellipse at 50% 55%, #0f2135 0%, #04080d 75%)', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ position: 'absolute', top: 120, textAlign: 'center', width: '100%' }}>
        <Rise at={60} style={{ fontFamily: F.num, fontWeight: 300, fontSize: 170, color: C.amber, lineHeight: 1, textShadow: '0 0 40px rgba(255,211,106,0.35)' }}>2,760 <span style={{ fontSize: 60 }}>RPM</span></Rise>
        <Rise at={70} style={{ fontFamily: F.zh, fontWeight: 700, fontSize: 50, color: C.white, marginTop: 14 }}>世界頂尖選手的發球平均</Rise>
      </div>
      <div style={{ position: 'relative', width: W, height: 220, marginTop: 300 }}>
        <div style={{ position: 'absolute', inset: 0, clipPath: `inset(0 ${(1 - draw) * 100}% 0 0)` }}>
          {bands.map(([a, b, n, zh], i) => (
            <React.Fragment key={n}>
              <div style={{ position: 'absolute', left: x(a) + 5, top: 90, width: x(b) - x(a) - 10, height: 34, background: i === 3 ? C.cyan : `rgba(95,242,255,${0.18 + i * 0.12})` }} />
              <div style={{ position: 'absolute', left: x(a), width: x(b) - x(a), top: 140, textAlign: 'center', fontFamily: F.mono, fontSize: 28, letterSpacing: 3, color: C.cyanDim }}>{n}</div>
              <div style={{ position: 'absolute', left: x(a), width: x(b) - x(a), top: 180, textAlign: 'center', fontFamily: F.zh, fontSize: 28, color: 'rgba(240,248,250,0.6)' }}>{zh}</div>
            </React.Fragment>
          ))}
        </div>
        {/* you */}
        <div style={{ position: 'absolute', left: x(you) - 20, top: 46, opacity: interpolate(f, [30, 36], [0, 1], clamp) }}>
          <svg width={40} height={36}><polygon points="0,0 40,0 20,34" fill={C.white} /></svg>
          <div style={{ position: 'absolute', top: -58, left: -80, width: 200, textAlign: 'center', fontFamily: F.num, fontSize: 40, color: C.white }}>{Math.round(you)}</div>
        </div>
        {/* pro average */}
        <div style={{ position: 'absolute', left: x(2760) - 3, top: 70, width: 6, height: 74, background: C.amber, opacity: proO, boxShadow: '0 0 18px rgba(255,211,106,0.8)' }} />
        <div style={{ position: 'absolute', left: x(3750) - 2, top: 76, width: 4, height: 62, background: C.amber, opacity: proO * 0.6 }} />
        <div style={{ position: 'absolute', left: x(3750) - 100, width: 200, top: 30, textAlign: 'center', fontFamily: F.mono, fontSize: 22, color: C.amber, opacity: proO * 0.7 }}>PRO MAX 3,750</div>
        {/* gap */}
        <div style={{ position: 'absolute', left: x(2450), width: x(2760) - x(2450), top: 104, height: 6, background: C.amber, opacity: gapO }} />
        <div style={{ position: 'absolute', left: x(2450) - 60, width: x(2760) - x(2450) + 120, top: -125, textAlign: 'center', fontFamily: F.zh, fontWeight: 700, fontSize: 34, color: C.amber, opacity: gapO }}>還差 310 轉</div>
      </div>
      <Vignette strength={0.5} />
      <Footnote at={60}>2,760 RPM：2009 世界桌球錦標賽八強的發球平均（Tamaki et al.）</Footnote>
    </AbsoluteFill>
  );
};

/* ---------- 7. Three reasons ---------- */
const Icon: React.FC<{ kind: 'offline' | 'ball' | 'team' }> = ({ kind }) => {
  const s = { fill: 'none', stroke: C.cyan, strokeWidth: 5, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };
  return (
    <svg width={120} height={120} viewBox="0 0 120 120">
      {kind === 'offline' && (<>
        <rect x={34} y={14} width={52} height={92} rx={10} {...s} />
        <path d="M48 36 Q60 26 72 36 M52 46 Q60 40 68 46" {...s} />
        <circle cx={60} cy={56} r={3} fill={C.cyan} />
        <path d="M26 20 L94 100" stroke={C.amber} strokeWidth={6} strokeLinecap="round" />
      </>)}
      {kind === 'ball' && (<>
        <circle cx={60} cy={60} r={40} {...s} />
        <path d="M20 60 A40 40 0 0 0 100 60 Z" fill={C.cyan} opacity={0.85} />
        <circle cx={44} cy={42} r={7} fill={C.red} /><circle cx={62} cy={34} r={7} fill="rgb(34,170,72)" /><circle cx={78} cy={44} r={7} fill="rgb(36,84,214)" />
      </>)}
      {kind === 'team' && (<>
        <circle cx={40} cy={42} r={12} {...s} /><circle cx={80} cy={42} r={12} {...s} /><circle cx={60} cy={36} r={14} {...s} />
        <path d="M18 92 Q40 64 60 74 Q80 64 102 92" {...s} />
      </>)}
    </svg>
  );
};

export const FeaturesScene: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const cards = [
    { k: 'offline' as const, zh: '離線運作', en: 'NO INTERNET NEEDED', sub: '量測全在手機上完成' },
    { k: 'ball' as const, zh: '不必買專用球', en: 'MARK YOUR OWN BALLS', sub: '一般練習球，自己標記' },
    { k: 'team' as const, zh: '整班輪流', en: 'NEXT SERVE', sub: '一支手機，一整班' },
  ];
  return (
    <AbsoluteFill style={{ background: 'linear-gradient(160deg, #0b1b2d 0%, #04080d 80%)', alignItems: 'center', justifyContent: 'center' }}>
      <svg style={{ position: 'absolute', inset: 0, opacity: 0.12 }} width="100%" height="100%">
        {Array.from({ length: 25 }).map((_, i) => <line key={i} x1={i * 80 - (f % 80)} y1={0} x2={i * 80 - (f % 80) - 300} y2={1080} stroke={C.cyan} strokeWidth={1} />)}
      </svg>
      <div style={{ display: 'flex', gap: 48 }}>
        {cards.map((c, i) => {
          const p = spring({ frame: f - 8 - i * 14, fps, config: { damping: 15, stiffness: 110 } });
          return (
            <div key={c.k} style={{ width: 480, height: 560, borderRadius: 28, background: 'rgba(13,27,44,0.85)', border: '2px solid rgba(95,242,255,0.28)', boxShadow: '0 40px 80px rgba(0,0,0,0.45)', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 22, opacity: p, transform: `translateY(${(1 - p) * 80}px)` }}>
              <Icon kind={c.k} />
              <div style={{ fontFamily: F.zh, fontWeight: 900, fontSize: 58, color: C.white }}>{c.zh}</div>
              <div style={{ fontFamily: F.mono, fontSize: 24, letterSpacing: 4, color: C.cyanDim }}>{c.en}</div>
              <div style={{ fontFamily: F.zh, fontSize: 32, color: 'rgba(240,248,250,0.75)' }}>{c.sub}</div>
            </div>
          );
        })}
      </div>
      <Footnote at={30}>目前支援可拍攝 240 fps 的 Android 手機</Footnote>
    </AbsoluteFill>
  );
};

/* ---------- 8. End card ---------- */
export const EndScene: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = spring({ frame: f - 4, fps, config: { damping: 18, stiffness: 90 } });
  return (
    <AbsoluteFill style={{ background: 'radial-gradient(ellipse at 50% 42%, #10243a 0%, #03060a 70%)', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', transform: `translateY(${(1 - p) * 40}px)`, opacity: p }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 34 }}>
          <LogoBall size={170} />
          <div>
            <div style={{ fontFamily: F.num, fontWeight: 700, fontSize: 112, letterSpacing: 6, color: C.white, lineHeight: 1 }}>SERVE COACH</div>
            <div style={{ fontFamily: F.num, fontWeight: 600, fontSize: 48, letterSpacing: 26, color: C.cyan, marginTop: 10 }}>● LIVE</div>
          </div>
        </div>
        <Rise at={20} style={{ fontFamily: F.zh, fontWeight: 700, fontSize: 60, color: C.white, marginTop: 64 }}>讓每一顆發球，都有數字。</Rise>
        <Rise at={36} style={{ fontFamily: F.zh, fontWeight: 500, fontSize: 34, color: C.cyan, marginTop: 34, letterSpacing: 2 }}>Android 搶先體驗　・　經銷合作洽詢 support@handyla.co</Rise>
      </div>
      <div style={{ position: 'absolute', bottom: 64, fontFamily: F.zh, fontSize: 26, letterSpacing: 4, color: 'rgba(240,248,250,0.55)', opacity: interpolate(f, [40, 55], [0, 1], clamp) }}>奇斯科技 QI SI TECHNOLOGY</div>
      <Vignette strength={0.5} />
    </AbsoluteFill>
  );
};
