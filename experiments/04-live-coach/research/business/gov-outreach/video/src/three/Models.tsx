import React, { useMemo } from 'react';
import * as THREE from 'three';
import { STLLoader } from 'three/examples/jsm/loaders/STLLoader.js';
import { staticFile } from 'remotion';
import { ballTexture } from './ballTexture';

export const BALL_R = 0.02; // metres

export const Ball: React.FC<{ rot?: THREE.Euler | [number, number, number]; kind?: 'marked' | 'plain' | 'half' | 'dots1' | 'dots2'; scale?: number; position?: [number, number, number]; emissive?: number }> = ({ rot = [0, 0, 0], kind = 'marked', scale = 1, position = [0, 0, 0], emissive = 0 }) => {
  const tex = useMemo(() => ballTexture(kind), [kind]);
  return (
    <mesh position={position} rotation={rot as any} scale={scale} castShadow>
      <sphereGeometry args={[BALL_R, 96, 64]} />
      <meshPhysicalMaterial map={tex} roughness={0.38} clearcoat={0.5} clearcoatRoughness={0.35} emissive={'#ffffff'} emissiveIntensity={emissive} emissiveMap={tex} />
    </mesh>
  );
};

// Table: 2.74 x 1.525 m, top at 0.76 m. Blue top, 2 cm white edges, centre line, net 15.25 cm.
export const Table: React.FC = () => {
  const blue = '#1d4f8f';
  const white = '#eef3f6';
  const L = 2.74, W = 1.525, H = 0.76, T = 0.025;
  const line = (w: number, d: number, x: number, z: number) => (
    <mesh position={[x, H + 0.0006, z]} rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
      <planeGeometry args={[w, d]} />
      <meshStandardMaterial color={white} roughness={0.6} />
    </mesh>
  );
  const legs = [[-1.15, -0.6], [1.15, -0.6], [-1.15, 0.6], [1.15, 0.6]];
  return (
    <group>
      <mesh position={[0, H - T / 2, 0]} castShadow receiveShadow>
        <boxGeometry args={[L, T, W]} />
        <meshStandardMaterial color={blue} roughness={0.55} metalness={0.05} />
      </mesh>
      {line(L, 0.02, 0, W / 2 - 0.01)}
      {line(L, 0.02, 0, -W / 2 + 0.01)}
      {line(0.02, W, L / 2 - 0.01, 0)}
      {line(0.02, W, -L / 2 + 0.01, 0)}
      {line(L, 0.003, 0, 0)}
      {/* net */}
      <mesh position={[0, H + 0.076, 0]} rotation={[0, Math.PI / 2, 0]}>
        <planeGeometry args={[W + 0.3, 0.1525]} />
        <meshStandardMaterial color={'#0b1622'} transparent opacity={0.72} side={THREE.DoubleSide} />
      </mesh>
      <mesh position={[0, H + 0.1525, 0]}>
        <boxGeometry args={[0.012, 0.012, W + 0.3]} />
        <meshStandardMaterial color={white} />
      </mesh>
      {[-1, 1].map((k) => (
        <mesh key={k} position={[0, H + 0.076, k * (W / 2 + 0.15)]}>
          <boxGeometry args={[0.03, 0.17, 0.03]} />
          <meshStandardMaterial color={'#1a1f26'} metalness={0.6} roughness={0.4} />
        </mesh>
      ))}
      {legs.map(([x, z], i) => (
        <mesh key={i} position={[x, (H - T) / 2, z]} castShadow>
          <boxGeometry args={[0.05, H - T, 0.05]} />
          <meshStandardMaterial color={'#151a20'} metalness={0.5} roughness={0.5} />
        </mesh>
      ))}
    </group>
  );
};

// A phone in landscape on a small tripod, facing the table side-on.
export const PhoneRig: React.FC<{ position: [number, number, number]; yaw: number; lensGlow?: number }> = ({ position, yaw, lensGlow = 0 }) => {
  const h = 0.86; // lens height about table height + 10 cm
  return (
    <group position={position} rotation={[0, yaw, 0]}>
      {/* tripod legs */}
      {[0, 1, 2].map((k) => {
        const a = (k * 2 * Math.PI) / 3;
        return (
          <mesh key={k} position={[Math.cos(a) * 0.12, h / 2 - 0.05, Math.sin(a) * 0.12]} rotation={[Math.sin(a) * 0.28, 0, -Math.cos(a) * 0.28]} castShadow>
            <cylinderGeometry args={[0.006, 0.008, h - 0.04, 12]} />
            <meshStandardMaterial color={'#20262e'} metalness={0.7} roughness={0.35} />
          </mesh>
        );
      })}
      <mesh position={[0, h - 0.03, 0]}>
        <cylinderGeometry args={[0.012, 0.012, 0.08, 16]} />
        <meshStandardMaterial color={'#2a3039'} metalness={0.7} roughness={0.3} />
      </mesh>
      {/* phone body (landscape), screen faces away from the table (+x), lens faces the table (-x) */}
      <group position={[0, h + 0.03, 0]}>
        <mesh castShadow>
          <boxGeometry args={[0.009, 0.075, 0.162]} />
          <meshPhysicalMaterial color={'#0e1217'} metalness={0.4} roughness={0.25} clearcoat={1} />
        </mesh>
        <mesh position={[0.0047, 0, 0]} rotation={[0, Math.PI / 2, 0]}>
          <planeGeometry args={[0.154, 0.068]} />
          <meshBasicMaterial map={texCache['hud.png'] ?? null} color={texCache['hud.png'] ? '#ffffff' : '#04121c'} toneMapped={false} />
        </mesh>
        {/* camera bar + lens */}
        <mesh position={[-0.0055, 0.018, 0.05]}>
          <boxGeometry args={[0.003, 0.03, 0.05]} />
          <meshStandardMaterial color={'#151a20'} metalness={0.6} roughness={0.3} />
        </mesh>
        <mesh position={[-0.0072, 0.018, 0.04]} rotation={[0, 0, Math.PI / 2]}>
          <cylinderGeometry args={[0.007, 0.007, 0.002, 32]} />
          <meshStandardMaterial color={'#05080c'} emissive={'#5ff2ff'} emissiveIntensity={0.2 + lensGlow * 2} />
        </mesh>
      </group>
    </group>
  );
};

const stlCache: Record<string, THREE.BufferGeometry> = {};
const texCache: Record<string, THREE.Texture> = {};
export function preloadTexture(name: string): Promise<void> {
  return new Promise((res) => {
    if (texCache[name]) return res();
    new THREE.TextureLoader().load(staticFile(name), (tx) => { tx.colorSpace = THREE.SRGBColorSpace; tx.anisotropy = 8; texCache[name] = tx; res(); }, undefined, () => res());
  });
}
// Loaded once before the first frame (see Commercial.tsx), so every frame has the parts.
export function preloadSTL(names: string[]): Promise<void> {
  return Promise.all(names.map((name) => new Promise<void>((res) => {
    if (stlCache[name]) return res();
    new STLLoader().load(staticFile('stl/' + name + '.stl'), (g) => {
      g.computeVertexNormals();
      g.center();
      g.computeBoundingBox();
      stlCache[name] = g;
      res();
    }, undefined, () => res());
  }))).then(() => undefined);
}

// anchor: 'top' puts the part's highest point (STL +z) at the group origin, 'bottom' its lowest.
export const PrintedPart: React.FC<{ name: string; anchor?: 'top' | 'bottom'; position?: [number, number, number]; color?: string; opacity?: number }> = ({ name, anchor = 'bottom', position = [0, 0, 0], color = '#2f6fd6', opacity = 1 }) => {
  const geo = stlCache[name];
  if (!geo) return null;
  const bb = geo.boundingBox!;
  const dy = -(anchor === 'top' ? bb.max.z : bb.min.z) * 0.001;
  return (
    <group position={position}>
      <mesh geometry={geo} position={[0, dy, 0]} rotation={[-Math.PI / 2, 0, 0]} scale={0.001} castShadow={opacity >= 1} receiveShadow>
        <meshPhysicalMaterial color={color} roughness={0.62} clearcoat={0.05} transparent={opacity < 1} opacity={opacity} />
      </mesh>
    </group>
  );
};
