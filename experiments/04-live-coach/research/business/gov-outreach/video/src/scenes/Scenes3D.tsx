import React from 'react';
import * as THREE from 'three';
import { AbsoluteFill, interpolate, useCurrentFrame, useVideoConfig } from 'remotion';
import { ThreeCanvas } from '@remotion/three';
import { useThree } from '@react-three/fiber';
import { Ball, PhoneRig, PrintedPart, Table, BALL_R } from '../three/Models';
import { C, F } from '../theme';
import { clamp, ease, Footnote, Rise, Vignette } from '../components';

const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

// A serve's path across the table, u in [0,1]: bounce on the server's side, over the net, bounce on the far side.
export function servePos(u: number): [number, number, number] {
  const z = 0.15;
  if (u < 0.25) { const s = u / 0.25; return [lerp(-1.45, -0.75, s), 0.78 + 0.14 * (1 - s * s), z]; }
  if (u < 0.75) { const s = (u - 0.25) / 0.5; return [lerp(-0.75, 0.75, s), 0.78 + 0.68 * s * (1 - s), z]; }
  const s = (u - 0.75) / 0.25; return [lerp(0.75, 1.5, s), 0.78 + 0.12 * (1 - (1 - s) * (1 - s)), z];
}

export const Cam: React.FC<{ pos: [number, number, number]; look: [number, number, number]; fov?: number }> = ({ pos, look, fov = 35 }) => {
  const { camera } = useThree();
  camera.position.set(...pos);
  (camera as THREE.PerspectiveCamera).fov = fov;
  (camera as THREE.PerspectiveCamera).near = 0.005;
  (camera as THREE.PerspectiveCamera).updateProjectionMatrix();
  camera.lookAt(...look);
  return null;
};

export const Studio: React.FC<{ floor?: boolean }> = ({ floor = true }) => (
  <>
    <ambientLight intensity={0.25} />
    <spotLight position={[0.5, 4, 1.5]} angle={0.7} penumbra={0.8} intensity={60} castShadow shadow-mapSize={[2048, 2048]} color={'#f4fbff'} />
    <pointLight position={[-3, 1.4, -2]} intensity={6} color={'#5ff2ff'} />
    <pointLight position={[3, 1.2, 2.5]} intensity={4} color={'#ffd36a'} />
    {floor && (
      <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[30, 30]} />
        <meshStandardMaterial color={'#0a1420'} roughness={0.9} />
      </mesh>
    )}
    <fog attach="fog" args={['#05090f', 3, 9]} />
  </>
);

/* ---------- 1. Hook: the marked ball, spinning, close up ---------- */
export const HookScene: React.FC = () => {
  const f = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const t = f / 30;
  const spin = t * 2 * Math.PI * 0.55;
  const camZ = interpolate(f, [0, 138], [0.17, 0.13], { ...clamp, easing: ease });
  return (
    <AbsoluteFill style={{ background: C.ink }}>
      <ThreeCanvas width={width} height={height} gl={{ antialias: true }} shadows={false}>
        <Cam pos={[0.035, 0.004, camZ]} look={[0.035, 0, 0]} fov={30} />
        <ambientLight intensity={0.05} />
        <directionalLight position={[-0.5, 0.9, 0.5]} intensity={3.2} />
        <pointLight position={[0.2, 0.06, -0.1]} intensity={2.2} color={'#5ff2ff'} />
        <pointLight position={[0.0, -0.12, 0.08]} intensity={0.35} color={'#ffd36a'} />
        <group position={[0.06, 0, 0]} rotation={[0.25, -0.55, 0.2]}>
          <group rotation={[spin, 0, 0]}>
            <Ball kind="marked" rot={[0, 0, 0.35]} />
          </group>
        </group>
      </ThreeCanvas>
      <Vignette strength={0.85} />
      <AbsoluteFill style={{ justifyContent: 'center', paddingLeft: 150 }}>
        <Rise at={18} style={{ fontFamily: F.zh, fontWeight: 900, fontSize: 104, color: C.white, lineHeight: 1.15 }}>你的發球，</Rise>
        <Rise at={30} style={{ fontFamily: F.zh, fontWeight: 900, fontSize: 104, color: C.cyan, lineHeight: 1.15, textShadow: '0 0 40px rgba(95,242,255,0.35)' }}>轉多快？</Rise>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 2. Spin is invisible: a plain ball flies past, too fast to read ---------- */
export const InvisibleScene: React.FC = () => {
  const f = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const cycle = 52;
  const k = Math.floor(f / cycle);
  const u = (f % cycle) / cycle;
  const trail = [0, 1, 2, 3, 4, 5].map((i) => Math.max(0, u - i * 0.012));
  return (
    <AbsoluteFill style={{ background: C.ink }}>
      <ThreeCanvas width={width} height={height} gl={{ antialias: true }} shadows>
        <Cam pos={[interpolate(f, [0, 168], [-0.3, 0.3]), 1.1, 2.3]} look={[0, 0.8, 0]} fov={30} />
        <Studio />
        <Table />
        {trail.map((uu, i) => (
          <group key={i} position={servePos(uu)}>
            <mesh>
              <sphereGeometry args={[BALL_R * (1 - i * 0.08), 24, 16]} />
              <meshBasicMaterial color={'#ffffff'} transparent opacity={i === 0 ? 0 : 0.35 - i * 0.05} />
            </mesh>
          </group>
        ))}
        <group position={servePos(u)}>
          <Ball kind="plain" rot={[0, 0, -f * 1.9 - k]} />
        </group>
      </ThreeCanvas>
      <Vignette />
      <AbsoluteFill style={{ justifyContent: 'flex-start', alignItems: 'center', paddingTop: 150 }}>
        <Rise at={10} style={{ fontFamily: F.zh, fontWeight: 900, fontSize: 96, color: C.white, letterSpacing: 6 }}>旋轉，看不見。</Rise>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- 3. The setup: a phone on a tripod at the side of the table, 240 fps ---------- */
export const SetupScene: React.FC = () => {
  const f = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const cam: [number, number, number] = [interpolate(f, [0, 192], [1.05, 0.7], { ...clamp, easing: ease }), interpolate(f, [0, 192], [1.3, 1.15], { ...clamp, easing: ease }), interpolate(f, [0, 192], [-2.75, -2.45], { ...clamp, easing: ease })];
  const u = ((f + 20) % 66) / 66;
  const pulses = [0, 1, 2].map((i) => ((f + i * 14) % 42) / 42);
  const lens: [number, number, number] = [0.24, 0.908, -1.635];
  return (
    <AbsoluteFill style={{ background: C.ink }}>
      <ThreeCanvas width={width} height={height} gl={{ antialias: true }} shadows>
        <Cam pos={cam} look={[-0.1, 0.82, -0.7]} fov={40} />
        <Studio />
        <Table />
        <PhoneRig position={[0.2, 0, -1.65]} yaw={Math.PI / 2} lensGlow={0.5 + 0.5 * Math.sin(f / 5)} />
        {pulses.map((p, i) => (
          <mesh key={i} position={lens} rotation={[0, 0, 0]} scale={0.01 + p * 0.3}>
            <ringGeometry args={[0.95, 1, 64]} />
            <meshBasicMaterial color={'#5ff2ff'} transparent opacity={(1 - p) * 0.45} side={THREE.DoubleSide} />
          </mesh>
        ))}
        <group position={servePos(u)}>
          <Ball kind="marked" rot={[0, 0, -f * 0.9]} emissive={0.05} />
        </group>
      </ThreeCanvas>
      <Vignette strength={0.6} />
      <AbsoluteFill style={{ padding: '110px 140px', justifyContent: 'flex-start' }}>
        <Rise at={12} style={{ fontFamily: F.num, fontWeight: 700, fontSize: 150, color: C.cyan, letterSpacing: 4, lineHeight: 1, textShadow: '0 0 50px rgba(95,242,255,0.4)' }}>240 FPS</Rise>
        <Rise at={22} style={{ fontFamily: F.zh, fontWeight: 700, fontSize: 52, color: C.white, marginTop: 18 }}>每秒 240 格，看清每一顆球</Rise>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

/* ---------- The table as the phone's camera sees it (behind the HUD) ---------- */
export const PhoneView: React.FC<{ width: number; height: number; serveAt: number }> = ({ width, height, serveAt }) => {
  const f = useCurrentFrame();
  const u = interpolate(f, [serveAt, serveAt + 24], [0, 1], clamp);
  const flying = f >= serveAt && f <= serveAt + 24;
  return (
    <ThreeCanvas width={width} height={height} gl={{ antialias: true }} shadows>
      <Cam pos={[0.0, 1.02, -1.75]} look={[0.0, 0.7, 0.3]} fov={55} />
      <Studio />
      <Table />
      {flying && (
        <group position={servePos(u)}>
          <Ball kind="marked" rot={[0, 0, -f * 0.9]} />
        </group>
      )}
    </ThreeCanvas>
  );
};

/* ---------- 6. Marking: cup, black half, stencil, three dots ---------- */
export const MarkingScene: React.FC = () => {
  const f = useCurrentFrame();
  const { width, height } = useVideoConfig();
  // phases (frames): drop 0-22, paint 22-70, flip 70-100, stencil down 100-125, dots 125-170, stencil up 170-200
  const drop = interpolate(f, [0, 22], [0.09, 0], { ...clamp, easing: ease });
  const painted = f >= 46;
  const flip = interpolate(f, [70, 100], [0, 1], { ...clamp, easing: ease });
  const lift = interpolate(f, [70, 82, 92, 100], [0, 0.05, 0.05, 0], clamp);
  const capY = interpolate(f, [100, 120, 150, 172], [0.08, 0, 0, 0.09], { ...clamp, easing: ease });
  const capO = interpolate(f, [100, 108, 162, 172], [0, 1, 1, 0], clamp);
  const dots = f >= 144 ? 'marked' : f >= 136 ? 'dots2' : f >= 128 ? 'dots1' : 'half';
  const kind = !painted ? 'plain' : dots;
  // black half is the ball's -y hemisphere: before the flip it faces up (rotated by pi), after it faces down.
  const rx = Math.PI * (1 - flip);
  const orbit = interpolate(f, [0, 258], [0.3, 1.5]);
  const camH = interpolate(f, [150, 200], [0.11, 0.15], { ...clamp, easing: ease });
  const camR = interpolate(f, [150, 200], [0.16, 0.1], { ...clamp, easing: ease });
  const cam: [number, number, number] = [Math.cos(orbit) * camR, camH, Math.sin(orbit) * camR];
  const paintSweep = interpolate(f, [22, 46], [0, 1], clamp);
  return (
    <AbsoluteFill style={{ background: 'radial-gradient(ellipse at 60% 45%, #10233a 0%, #05090f 70%)' }}>
      <ThreeCanvas width={width} height={height} gl={{ antialias: true }} shadows>
        <Cam pos={cam} look={[interpolate(f, [150, 200], [-0.035, -0.025], clamp), 0.0, 0]} fov={32} />
        <ambientLight intensity={0.08} />
        <spotLight position={[0.3, 0.6, 0.3]} angle={0.3} penumbra={0.9} intensity={3} castShadow />
        <pointLight position={[-0.3, 0.15, -0.2]} intensity={0.5} color={'#5ff2ff'} />
        <pointLight position={[0.25, 0.05, 0.3]} intensity={0.6} color={'#ffd36a'} />
        <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, -0.0225, 0]} receiveShadow>
          <planeGeometry args={[2, 2]} />
          <meshStandardMaterial color={'#03070c'} roughness={0.9} />
        </mesh>
        <group position={[0, -0.0225, 0]}>
          <PrintedPart name="cup" anchor="bottom" />
        </group>
        <group position={[0, drop + lift, 0]} rotation={[rx, 0, 0]}>
          <Ball kind={kind as any} />
        </group>
        {f >= 22 && f < 46 && (
          <mesh position={[0.012 - paintSweep * 0.024, 0.024, 0.0]} rotation={[0, 0, 0.4]}>
            <cylinderGeometry args={[0.0035, 0.0035, 0.06, 16]} />
            <meshStandardMaterial color={'#111'} metalness={0.3} roughness={0.4} />
          </mesh>
        )}
        <group position={[0, capY, 0]}>
          <PrintedPart name="cap" anchor="bottom" color={'#3b7be0'} opacity={capO} />
        </group>
      </ThreeCanvas>
      <Vignette strength={0.55} />
      <AbsoluteFill style={{ justifyContent: 'center', paddingLeft: 130 }}>
        <Rise at={4} style={{ fontFamily: F.mono, fontSize: 26, color: C.cyanDim, letterSpacing: 4 }}>MARK YOUR OWN BALLS</Rise>
        <Rise at={10} style={{ fontFamily: F.zh, fontWeight: 900, fontSize: 88, color: C.white, marginTop: 14 }}>一分鐘標記</Rise>
        <div style={{ marginTop: 36, display: 'flex', flexDirection: 'column', gap: 18 }}>
          <Step at={22} n="1" text="半顆塗黑" active={f >= 22 && f < 100} />
          <Step at={100} n="2" text="三個色點（紅・綠・藍）" active={f >= 100} />
          <Step at={176} n="✓" text="可以上場了" active={f >= 176} />
        </div>
      </AbsoluteFill>
      <Footnote at={20}>3D 列印標記工具：杯座＋色點模板（示意渲染）</Footnote>
    </AbsoluteFill>
  );
};

const Step: React.FC<{ at: number; n: string; text: string; active: boolean }> = ({ at, n, text, active }) => (
  <Rise at={at} dist={20} style={{ display: 'flex', alignItems: 'center', gap: 18 }}>
    <div style={{ width: 52, height: 52, borderRadius: 26, border: `2px solid ${active ? C.cyan : C.cyanDim}`, color: active ? C.ink : C.cyan, background: active ? C.cyan : 'transparent', fontFamily: F.num, fontWeight: 700, fontSize: 28, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{n}</div>
    <div style={{ fontFamily: F.zh, fontWeight: 700, fontSize: 44, color: active ? C.white : 'rgba(240,248,250,0.55)' }}>{text}</div>
  </Rise>
);

/* ---------- small spinning ball for the end card ---------- */
export const LogoBall: React.FC<{ size: number }> = ({ size }) => {
  const f = useCurrentFrame();
  return (
    <ThreeCanvas width={size} height={size} gl={{ antialias: true, alpha: true }}>
      <Cam pos={[0, 0, 0.09]} look={[0, 0, 0]} fov={30} />
      <ambientLight intensity={0.25} />
      <directionalLight position={[0.5, 0.7, 0.6]} intensity={2.2} />
      <pointLight position={[0.1, 0, -0.1]} intensity={0.7} color={'#5ff2ff'} />
      <group rotation={[0.4, 0, 0.3]}>
        <group rotation={[0, 0, -f * 0.09]}>
          <Ball kind="marked" rot={[Math.PI / 2, 0, 0]} />
        </group>
      </group>
    </ThreeCanvas>
  );
};
