import React from 'react';
import * as THREE from 'three';
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { ThreeCanvas } from '@remotion/three';
import { C, F } from '../theme';
import { clamp, ease, Footnote, Rise, Vignette } from '../components';
import { Cam, LogoBall, servePos, Studio } from './Scenes3D';
import { Ball, PhoneRig, Table } from '../three/Models';

/* Illustrative data (示意): six students, eight weeks of backspin averages. */
const STUDENTS = [
  { n: '學員 A', g: '六年級', w: [1720, 1780, 1850, 1900, 1980, 2050, 2110, 2180] },
  { n: '學員 B', g: '五年級', w: [1500, 1560, 1610, 1700, 1760, 1830, 1900, 1960] },
  { n: '學員 C', g: '五年級', w: [1320, 1410, 1380, 1520, 1610, 1700, 1760, 1880] },
  { n: '學員 D', g: '四年級', w: [1400, 1430, 1390, 1420, 1450, 1410, 1440, 1420] },
  { n: '學員 E', g: '四年級', w: [820, 880, 930, 960, 1010, 1060, 1100, 1150] },
  { n: '學員 F', g: '三年級', w: [610, 650, 700, 760, 800, 860, 910, 980] },
];

const Panel: React.FC<{ title: string; right?: string; children: React.ReactNode; style?: React.CSSProperties }> = ({ title, right, children, style }) => (
  <div style={{ background: 'rgba(10,22,36,0.94)', border: '2px solid rgba(95,242,255,0.22)', borderRadius: 26, overflow: 'hidden', boxShadow: '0 50px 110px rgba(0,0,0,0.55)', ...style }}>
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '20px 34px', background: 'rgba(95,242,255,0.07)', borderBottom: '2px solid rgba(95,242,255,0.14)' }}>
      <div style={{ fontFamily: F.zh, fontWeight: 700, fontSize: 30, color: C.white, letterSpacing: 2 }}>{title}</div>
      {right && <div style={{ fontFamily: F.zh, fontSize: 24, color: C.amber, letterSpacing: 2 }}>{right}</div>}
    </div>
    {children}
  </div>
);

const Headline: React.FC<{ at?: number; children: React.ReactNode }> = ({ at = 6, children }) => (
  <Rise at={at} style={{ fontFamily: F.zh, fontWeight: 900, fontSize: 64, color: C.white, letterSpacing: 4, textAlign: 'center' }}>{children}</Rise>
);

/* ---------- 4. Coach view: roster + one student's growth curve ---------- */
export const CoachScene: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = spring({ frame: f - 2, fps, config: { damping: 20, stiffness: 90 } });
  const sel = f >= 70; // 學員 C gets picked
  const draw = interpolate(f, [80, 200], [0, 1], { ...clamp, easing: ease });
  // chart geometry: 860 x 420, y 0–3000
  const CW = 860, CH = 420, L = 90, B = 40;
  const x = (i: number) => L + (i * (CW - L - 30)) / 7;
  const y = (v: number) => CH - B - (v / 3000) * (CH - B - 20);
  const pts = STUDENTS[2].w.map((v, i) => [x(i), y(v)] as const);
  const shown = draw * 7;
  const last = Math.min(7, Math.floor(shown));
  const frac = shown - last;
  const path: [number, number][] = pts.slice(0, last + 1).map((p) => [p[0], p[1]]);
  if (last < 7) path.push([pts[last][0] + (pts[last + 1][0] - pts[last][0]) * frac, pts[last][1] + (pts[last + 1][1] - pts[last][1]) * frac]);
  const tip = path[path.length - 1];
  const val = Math.round(interpolate(draw, [0, 1], [1320, 1880]));
  return (
    <AbsoluteFill style={{ background: 'radial-gradient(ellipse at 50% 30%, #12273f 0%, #04080d 75%)', alignItems: 'center', paddingTop: 70 }}>
      <Headline>每位學員的進步，<span style={{ color: C.cyan }}>一目了然</span></Headline>
      <div style={{ marginTop: 46, opacity: enter, transform: `translateY(${(1 - enter) * 60}px)` }}>
        <Panel title="旋轉教練・教練端　國小桌球訓練站" right="示意畫面">
          <div style={{ display: 'flex' }}>
            <div style={{ width: 560, borderRight: '2px solid rgba(95,242,255,0.12)' }}>
              <div style={{ display: 'flex', padding: '14px 34px', fontFamily: F.zh, fontSize: 22, color: C.cyanDim }}><span style={{ flex: 1 }}>學員</span><span style={{ width: 150, textAlign: 'right' }}>下旋平均</span><span style={{ width: 70, textAlign: 'right' }}>趨勢</span></div>
              {STUDENTS.map((s, i) => {
                const p = spring({ frame: f - 14 - i * 5, fps, config: { damping: 18, stiffness: 140 } });
                const on = sel && i === 2;
                const up = s.w[7] - s.w[0] > 100;
                return (
                  <div key={s.n} style={{ display: 'flex', alignItems: 'center', padding: '17px 34px', borderTop: '1px solid rgba(95,242,255,0.08)', background: on ? 'rgba(95,242,255,0.14)' : 'transparent', boxShadow: on ? 'inset 6px 0 0 ' + C.cyan : 'none', opacity: p, transform: `translateX(${(1 - p) * -30}px)` }}>
                    <span style={{ flex: 1, fontFamily: F.zh, fontSize: 30, fontWeight: on ? 700 : 500, color: C.white }}>{s.n}<span style={{ fontSize: 22, color: 'rgba(240,248,250,0.5)', marginLeft: 12 }}>{s.g}</span></span>
                    <span style={{ width: 150, textAlign: 'right', fontFamily: F.num, fontSize: 34, color: on ? C.cyan : C.white, fontVariantNumeric: 'tabular-nums' }}>{s.w[7].toLocaleString()}</span>
                    <span style={{ width: 70, textAlign: 'right', fontSize: 26, color: up ? 'rgb(120,230,150)' : 'rgba(240,248,250,0.4)' }}>{up ? '▲' : '－'}</span>
                  </div>
                );
              })}
            </div>
            <div style={{ width: 900, padding: '24px 20px 20px', opacity: interpolate(f, [70, 84], [0, 1], clamp) }}>
              <div style={{ fontFamily: F.zh, fontSize: 28, fontWeight: 700, color: C.white, paddingLeft: 20 }}>學員 C・下旋發球平均轉速 <span style={{ fontWeight: 400, fontSize: 22, color: C.cyanDim }}>第 1–8 週（RPM）</span></div>
              <svg width={CW} height={CH} style={{ display: 'block', marginTop: 10 }}>
                {[0, 1000, 2000, 3000].map((v) => (
                  <g key={v}>
                    <line x1={L} x2={CW - 30} y1={y(v)} y2={y(v)} stroke="rgba(95,242,255,0.12)" strokeWidth={1.5} />
                    <text x={L - 14} y={y(v) + 8} textAnchor="end" fontFamily="IBM Plex Mono" fontSize={20} fill="rgba(95,242,255,0.5)">{v}</text>
                  </g>
                ))}
                <line x1={L} x2={CW - 30} y1={y(2760)} y2={y(2760)} stroke={C.amber} strokeWidth={3} strokeDasharray="12 9" />
                <text x={CW - 30} y={y(2760) - 12} textAnchor="end" fontFamily="Noto Sans TC" fontSize={22} fill={C.amber}>世界級選手平均 2,760</text>
                <polygon points={[...path.map((p) => p.join(',')), `${tip[0]},${y(0)}`, `${L},${y(0)}`].join(' ')} fill="rgba(95,242,255,0.14)" />
                <polyline points={path.map((p) => p.join(',')).join(' ')} fill="none" stroke={C.cyan} strokeWidth={5} strokeLinejoin="round" strokeLinecap="round" style={{ filter: 'drop-shadow(0 0 8px rgba(95,242,255,0.6))' }} />
                <circle cx={tip[0]} cy={tip[1]} r={10} fill={C.cyan} />
                <text x={tip[0] - 16} y={tip[1] - 22} textAnchor="end" fontFamily="Oxanium" fontWeight={600} fontSize={36} fill={C.white}>{val.toLocaleString()}</text>
                {[0, 2, 4, 6, 7].map((i) => <text key={i} x={x(i)} y={CH - 6} textAnchor="middle" fontFamily="IBM Plex Mono" fontSize={20} fill="rgba(95,242,255,0.5)">W{i + 1}</text>)}
              </svg>
              <div style={{ marginLeft: 20, display: 'inline-block', fontFamily: F.zh, fontSize: 28, color: C.white, background: 'rgba(120,230,150,0.14)', border: '2px solid rgba(120,230,150,0.45)', borderRadius: 14, padding: '8px 20px', opacity: interpolate(f, [205, 218], [0, 1], clamp) }}>8 週進步 <b style={{ fontFamily: F.num, color: 'rgb(140,240,165)' }}>+42%</b></div>
            </div>
          </div>
        </Panel>
      </div>
      <Footnote at={20}>示意畫面：教練端開發中，學員與數據皆為虛構</Footnote>
    </AbsoluteFill>
  );
};

/* ---------- 5. Who needs what: students x serve types ---------- */
const TYPES = ['下旋', '側旋', '上旋', '側下旋'];
const MATRIX = [
  [2180, 1650, 1900, 1720], [1960, 1480, 1700, 1600], [1880, 880, 1620, 1500],
  [1420, 1300, 760, 1350], [1150, 1080, 1010, 690], [980, 720, 900, 810],
];
const WEAK = (v: number) => v < 900;

export const MatrixScene: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const enter = spring({ frame: f - 2, fps, config: { damping: 20, stiffness: 90 } });
  const flag = f >= 70;
  return (
    <AbsoluteFill style={{ background: 'radial-gradient(ellipse at 50% 30%, #12273f 0%, #04080d 75%)', alignItems: 'center', paddingTop: 70 }}>
      <Headline>誰該加強哪一種發球，<span style={{ color: C.amber }}>一眼看出</span></Headline>
      <div style={{ marginTop: 46, display: 'flex', gap: 40, alignItems: 'flex-start', opacity: enter, transform: `translateY(${(1 - enter) * 60}px)` }}>
        <Panel title="各類發球平均轉速（RPM）" right="示意畫面">
          <div style={{ padding: '10px 26px 20px' }}>
            <div style={{ display: 'grid', gridTemplateColumns: '200px repeat(4, 170px)', gap: 8 }}>
              <div />
              {TYPES.map((t) => <div key={t} style={{ textAlign: 'center', fontFamily: F.zh, fontSize: 26, color: C.cyanDim, padding: '8px 0' }}>{t}</div>)}
              {MATRIX.map((row, r) => (
                <React.Fragment key={r}>
                  <div style={{ fontFamily: F.zh, fontSize: 28, color: C.white, display: 'flex', alignItems: 'center' }}>{STUDENTS[r].n}</div>
                  {row.map((v, c) => {
                    const p = interpolate(f, [10 + (r * 4 + c) * 1.5, 22 + (r * 4 + c) * 1.5], [0, 1], clamp);
                    const weak = WEAK(v) && flag;
                    const a = 0.08 + (v / 2400) * 0.5;
                    return (
                      <div key={c} style={{ height: 66, borderRadius: 12, display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: F.num, fontSize: 32, fontVariantNumeric: 'tabular-nums', color: weak ? C.ink : C.white, background: weak ? C.amber : `rgba(95,242,255,${a})`, boxShadow: weak ? '0 0 26px rgba(255,211,106,0.6)' : 'none', opacity: p, transform: `scale(${0.9 + 0.1 * p})` }}>{v.toLocaleString()}</div>
                    );
                  })}
                </React.Fragment>
              ))}
            </div>
          </div>
        </Panel>
        <div style={{ width: 520, display: 'flex', flexDirection: 'column', gap: 24 }}>
          {[
            { at: 90, who: '學員 C', what: '側旋偏弱（880 轉）', todo: '本週加強側旋的摩擦' },
            { at: 112, who: '學員 D', what: '上旋偏弱（760 轉）', todo: '加強上旋的向上摩擦' },
            { at: 134, who: '學員 E', what: '側下旋偏弱（690 轉）', todo: '先穩定下旋，再加側旋' },
          ].map((t) => {
            const p = spring({ frame: f - t.at, fps, config: { damping: 16, stiffness: 120 } });
            return (
              <div key={t.who} style={{ background: 'rgba(255,211,106,0.1)', border: '2px solid rgba(255,211,106,0.45)', borderRadius: 20, padding: '20px 26px', opacity: p, transform: `translateX(${(1 - p) * 60}px)` }}>
                <div style={{ fontFamily: F.zh, fontSize: 24, color: C.amber, letterSpacing: 2 }}>系統提示</div>
                <div style={{ fontFamily: F.zh, fontSize: 32, fontWeight: 700, color: C.white, marginTop: 6 }}>{t.who}：{t.what}</div>
                <div style={{ fontFamily: F.zh, fontSize: 28, color: 'rgba(240,248,250,0.8)', marginTop: 4 }}>→ {t.todo}</div>
              </div>
            );
          })}
        </div>
      </div>
      <Footnote at={20}>示意畫面：教練端開發中，學員與數據皆為虛構</Footnote>
    </AbsoluteFill>
  );
};

/* ---------- 6. For the bureau: stations, week 1 vs week 12 ---------- */
const STATIONS = [
  { n: '訓練站 A', a: 1180, b: 1640, serves: 1260 },
  { n: '訓練站 B', a: 1050, b: 1520, serves: 1090 },
  { n: '訓練站 C', a: 1320, b: 1710, serves: 980 },
  { n: '訓練站 D', a: 940, b: 1380, serves: 1150 },
];

export const BureauScene: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const grow = interpolate(f, [40, 120], [0, 1], { ...clamp, easing: ease });
  const total = Math.round(interpolate(f, [20, 120], [0, STATIONS.reduce((s, x) => s + x.serves, 0)], { ...clamp, easing: ease }));
  const W = 900, max = 2000;
  return (
    <AbsoluteFill style={{ background: 'radial-gradient(ellipse at 50% 30%, #12273f 0%, #04080d 75%)', alignItems: 'center', paddingTop: 70 }}>
      <Headline>各訓練站的成效，<span style={{ color: C.cyan }}>用數據呈現</span></Headline>
      <div style={{ marginTop: 50, display: 'flex', gap: 40 }}>
        <Panel title="試辦成果總覽　第 1 週 → 第 12 週" right="示意畫面" style={{ width: 1180 }}>
          <div style={{ padding: '18px 34px 26px', display: 'flex', flexDirection: 'column', gap: 22 }}>
            <div style={{ display: 'flex', gap: 30, fontFamily: F.zh, fontSize: 22, color: C.cyanDim }}>
              <span><span style={{ display: 'inline-block', width: 22, height: 14, background: 'rgba(95,242,255,0.3)', marginRight: 8 }} />第 1 週平均轉速</span>
              <span><span style={{ display: 'inline-block', width: 22, height: 14, background: C.cyan, marginRight: 8 }} />第 12 週平均轉速</span>
            </div>
            {STATIONS.map((s, i) => {
              const p = spring({ frame: f - 8 - i * 6, fps, config: { damping: 18, stiffness: 130 } });
              const now = s.a + (s.b - s.a) * grow;
              const pct = Math.round(((now - s.a) / s.a) * 100);
              return (
                <div key={s.n} style={{ display: 'flex', alignItems: 'center', gap: 24, opacity: p }}>
                  <div style={{ width: 150, fontFamily: F.zh, fontSize: 30, color: C.white }}>{s.n}</div>
                  <div style={{ position: 'relative', width: W, height: 56 }}>
                    <div style={{ position: 'absolute', left: 0, top: 4, height: 20, width: (s.a / max) * W, background: 'rgba(95,242,255,0.3)', borderRadius: 4 }} />
                    <div style={{ position: 'absolute', left: 0, top: 30, height: 22, width: (now / max) * W, background: C.cyan, borderRadius: 4, boxShadow: '0 0 18px rgba(95,242,255,0.45)' }} />
                    <div style={{ position: 'absolute', left: (now / max) * W + 14, top: 18, fontFamily: F.num, fontSize: 30, color: C.white, whiteSpace: 'nowrap' }}>{Math.round(now).toLocaleString()} <span style={{ color: 'rgb(140,240,165)', fontSize: 26 }}>+{pct}%</span></div>
                  </div>
                </div>
              );
            })}
          </div>
        </Panel>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 30, width: 400 }}>
          <Panel title="量測發球數">
            <div style={{ padding: '24px 34px', fontFamily: F.num, fontSize: 96, color: C.cyan, lineHeight: 1, fontVariantNumeric: 'tabular-nums', textShadow: '0 0 30px rgba(95,242,255,0.4)' }}>{total.toLocaleString()}</div>
          </Panel>
          <Panel title="使用訓練站">
            <div style={{ padding: '24px 34px', fontFamily: F.num, fontSize: 96, color: C.white, lineHeight: 1 }}>4 <span style={{ fontFamily: F.zh, fontSize: 36 }}>站・12 週</span></div>
          </Panel>
        </div>
      </div>
      <Footnote at={20}>示意畫面：數據皆為虛構，用以說明報表形式</Footnote>
    </AbsoluteFill>
  );
};

/* ---------- 7. End card ---------- */
export const GovEndScene: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = spring({ frame: f - 4, fps, config: { damping: 18, stiffness: 90 } });
  return (
    <AbsoluteFill style={{ background: 'radial-gradient(ellipse at 50% 42%, #10243a 0%, #03060a 70%)', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', transform: `translateY(${(1 - p) * 40}px)`, opacity: p }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 40 }}>
          <LogoBall size={190} />
          <div>
            <div style={{ fontFamily: F.zh, fontWeight: 900, fontSize: 150, letterSpacing: 12, color: C.white, lineHeight: 1 }}>旋轉教練</div>
            <div style={{ fontFamily: F.mono, fontSize: 30, letterSpacing: 14, color: C.cyan, marginTop: 14 }}>SPIN COACH</div>
          </div>
        </div>
        <Rise at={20} style={{ fontFamily: F.zh, fontWeight: 700, fontSize: 58, color: C.white, marginTop: 64 }}>讓基層桌球的每一次進步，都看得見。</Rise>
        <Rise at={40} style={{ fontFamily: F.zh, fontWeight: 500, fontSize: 36, color: C.cyan, marginTop: 40, letterSpacing: 2 }}>歡迎安排現場示範　余承翰　0937-853-019　hank@handyla.co</Rise>
      </div>
      <div style={{ position: 'absolute', bottom: 64, fontFamily: F.zh, fontSize: 26, letterSpacing: 4, color: 'rgba(240,248,250,0.55)', opacity: interpolate(f, [40, 55], [0, 1], clamp) }}>奇斯科技 QI SI TECHNOLOGY</div>
      <Vignette strength={0.5} />
    </AbsoluteFill>
  );
};

/* ---------- Text-free stills for the one-page handout ---------- */
// Step 1: the phone on its tripod beside the table, lens glowing.
export const StillSetup: React.FC = () => {
  const { width, height } = useVideoConfig();
  return (
    <AbsoluteFill style={{ background: C.ink }}>
      <ThreeCanvas width={width} height={height} gl={{ antialias: true }} shadows>
        <Cam pos={[0.66, 0.97, -2.2]} look={[0.05, 0.86, -1.3]} fov={36} />
        <Studio />
        <pointLight position={[0.7, 1.4, -2.3]} intensity={5} color={'#dff6ff'} />
        <Table />
        <PhoneRig position={[0.2, 0, -1.65]} yaw={Math.PI / 2} lensGlow={1} />
      </ThreeCanvas>
      <Vignette strength={0.45} />
    </AbsoluteFill>
  );
};

// Step 2: a marked ball in flight over the table, with a light trail.
export const StillServe: React.FC = () => {
  const { width, height } = useVideoConfig();
  const u = 0.43;
  const trail = Array.from({ length: 14 }, (_, i) => Math.max(0, u - (i + 1) * 0.018));
  return (
    <AbsoluteFill style={{ background: C.ink }}>
      <ThreeCanvas width={width} height={height} gl={{ antialias: true }} shadows>
        <Cam pos={[-0.15, 1.18, 0.95]} look={[-0.2, 0.95, 0.1]} fov={30} />
        <Studio />
        <Table />
        {trail.map((uu, i) => (
          <mesh key={i} position={servePos(uu)}>
            <sphereGeometry args={[0.02 * (1 - i * 0.05), 20, 14]} />
            <meshBasicMaterial color={'#5ff2ff'} transparent opacity={0.4 - i * 0.026} />
          </mesh>
        ))}
        <group position={servePos(u)} scale={1.25}>
          <Ball kind="marked" rot={[0.5, 0.8, -0.6]} emissive={0.08} />
        </group>
      </ThreeCanvas>
      <Vignette strength={0.45} />
    </AbsoluteFill>
  );
};
