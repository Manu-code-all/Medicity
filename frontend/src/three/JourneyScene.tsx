import { useFrame } from "@react-three/fiber";
import { useEffect, useMemo, useRef, type MutableRefObject } from "react";
import * as THREE from "three";
import { ENAMEL } from "./palette";

/** The transit line as a curve across a small city. Five stations sit along it. */
const POINTS: [number, number, number][] = [
  [-5, 0, 16], [0, 0, 0], [7, 0, -12], [2, 0, -25], [-9, 0, -36], [-6, 0, -50], [5, 0, -62], [11, 0, -76], [3, 0, -92],
];
const STATIONS = [0.1, 0.3, 0.5, 0.7, 0.88];
const TUBE_SEGMENTS = 420;
const TUBE_RADIAL = 8;

function seeded(seed: number) {
  let s = seed;
  return () => {
    s = (s * 1664525 + 1013904223) % 4294967296;
    return s / 4294967296;
  };
}

/**
 * Scroll becomes travel: `progress` (0 to 1) moves the camera along the line,
 * and the line fills in the role's colour behind it, station by station. The
 * scene draws no text; the words are HTML that scrolls over it.
 */
export function JourneyScene({ progress }: { progress: MutableRefObject<number> }) {
  const curve = useMemo(() => new THREE.CatmullRomCurve3(POINTS.map((p) => new THREE.Vector3(...p)), false, "catmullrom", 0.5), []);
  const tube = useMemo(() => new THREE.TubeGeometry(curve, TUBE_SEGMENTS, 0.42, TUBE_RADIAL, false), [curve]);
  const perSegment = TUBE_RADIAL * 6;
  const fill = useRef<THREE.Mesh>(null);
  const rings = useRef<(THREE.Mesh | null)[]>([]);
  const smooth = useRef(0);
  const look = useMemo(() => new THREE.Vector3(), []);
  const ahead = useMemo(() => new THREE.Vector3(), []);
  const here = useMemo(() => new THREE.Vector3(), []);
  const reached = useMemo(() => new THREE.Color(ENAMEL.patientLine), []);
  const waiting = useMemo(() => new THREE.Color(ENAMEL.inkMuted), []);

  useEffect(() => () => tube.dispose(), [tube]);

  useFrame(({ camera, pointer }, delta) => {
    // Ease toward the scroll position so a flick of the wheel glides, not jumps.
    smooth.current = THREE.MathUtils.damp(smooth.current, progress.current, 3.2, delta);
    // The camera stops short of the end so the last station stays in view.
    const p = Math.min(Math.max(smooth.current, 0), 1) * 0.9;

    curve.getPointAt(p, here);
    curve.getPointAt(Math.min(p + 0.07, 1), ahead);
    const dir = ahead.clone().sub(here).setY(0).normalize();
    camera.position.set(here.x - dir.x * 9 + pointer.x * 1.4, 5.2 + pointer.y * 0.5, here.z - dir.z * 9);
    look.set(ahead.x, 0.8, ahead.z);
    camera.lookAt(look);

    if (fill.current) {
      const segments = Math.floor(TUBE_SEGMENTS * p);
      fill.current.geometry.setDrawRange(0, segments * perSegment);
    }
    STATIONS.forEach((t, i) => {
      const ring = rings.current[i];
      if (!ring) return;
      const mat = ring.material as THREE.MeshStandardMaterial;
      mat.color.lerp(p >= t - 0.01 ? reached : waiting, 0.12);
    });
  });

  // Blocks of the city: kept clear of the line so the route reads at a glance.
  const blocks = useMemo(() => {
    const rand = seeded(7);
    const samples = Array.from({ length: 240 }, (_, i) => curve.getPointAt(i / 239));
    const items: { x: number; z: number; w: number; d: number; h: number; tint: number }[] = [];
    for (let i = 0; i < 520 && items.length < 190; i++) {
      const x = (rand() - 0.5) * 70;
      const z = -rand() * 105 + 8;
      const clear = samples.every((s) => Math.hypot(s.x - x, s.z - z) > 9);
      if (!clear) continue;
      items.push({ x, z, w: 1.6 + rand() * 3, d: 1.6 + rand() * 3, h: 0.8 + rand() * rand() * 5.5, tint: rand() });
    }
    return items;
  }, [curve]);

  const blockMesh = useRef<THREE.InstancedMesh>(null);
  useEffect(() => {
    const mesh = blockMesh.current;
    if (!mesh) return;
    const m = new THREE.Matrix4();
    const c = new THREE.Color();
    blocks.forEach((b, i) => {
      m.compose(new THREE.Vector3(b.x, b.h / 2, b.z), new THREE.Quaternion(), new THREE.Vector3(b.w, b.h, b.d));
      mesh.setMatrixAt(i, m);
      mesh.setColorAt(i, c.set(b.tint > 0.55 ? ENAMEL.surface : ENAMEL.raised));
    });
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  }, [blocks]);

  const stationPoints = useMemo(
    () =>
      STATIONS.map((t) => {
        const at = curve.getPointAt(t);
        const tangent = curve.getTangentAt(t);
        const side = new THREE.Vector3(-tangent.z, 0, tangent.x).normalize();
        return { at, side };
      }),
    [curve],
  );

  return (
    <>
      <color attach="background" args={[ENAMEL.ground]} />
      <fog attach="fog" args={[ENAMEL.ground, 40, 120]} />
      <ambientLight intensity={1.25} />
      <directionalLight position={[18, 30, 12]} intensity={1.5} />
      <hemisphereLight args={["#ffffff", ENAMEL.rule, 0.5]} />

      <mesh rotation-x={-Math.PI / 2} position={[0, -0.01, -40]}>
        <planeGeometry args={[300, 300]} />
        <meshBasicMaterial color={ENAMEL.ground} />
      </mesh>

      <instancedMesh ref={blockMesh} args={[undefined, undefined, blocks.length]}>
        <boxGeometry args={[1, 1, 1]} />
        <meshStandardMaterial roughness={1} />
      </instancedMesh>

      {/* The line: the whole route in rule grey, then the part travelled in the line colour. */}
      <mesh geometry={tube} scale={[1, 0.28, 1]} position={[0, 0.12, 0]}>
        <meshStandardMaterial color={ENAMEL.rule} roughness={0.9} />
      </mesh>
      <mesh ref={fill} geometry={tube} scale={[1, 0.3, 1]} position={[0, 0.14, 0]}>
        <meshStandardMaterial color={ENAMEL.patientLine} roughness={0.7} />
      </mesh>

      {stationPoints.map(({ at, side }, i) => (
        <group key={i} position={[at.x, 0, at.z]}>
          <mesh position={[0, 0.2, 0]}>
            <cylinderGeometry args={[1.6, 1.6, 0.3, 40]} />
            <meshStandardMaterial color={ENAMEL.surface} roughness={0.8} />
          </mesh>
          <mesh
            ref={(el) => {
              rings.current[i] = el;
            }}
            position={[0, 0.42, 0]}
            rotation-x={Math.PI / 2}
          >
            <torusGeometry args={[1.35, 0.32, 14, 48]} />
            <meshStandardMaterial color={ENAMEL.inkMuted} roughness={0.6} />
          </mesh>
          <group position={[side.x * 6.2, 0, side.z * 6.2]} rotation-y={Math.atan2(-side.x, -side.z)}>
            <Landmark kind={i} />
          </group>
        </group>
      ))}
    </>
  );
}

/** One small building or object for each station, built from boxes and discs. */
function Landmark({ kind }: { kind: number }) {
  const white = { color: ENAMEL.surface, roughness: 0.85 } as const;
  const teal = { color: ENAMEL.patientBand, roughness: 0.7 } as const;
  const gold = { color: ENAMEL.chemist, roughness: 0.7 } as const;
  const ink = { color: ENAMEL.ink, roughness: 0.7 } as const;

  switch (kind) {
    case 0: // Book: a clinic with a cross
      return (
        <group>
          <mesh position={[0, 1.6, 0]}><boxGeometry args={[4.2, 3.2, 3.4]} /><meshStandardMaterial {...white} /></mesh>
          <mesh position={[0, 2.2, 1.75]}><boxGeometry args={[1.5, 0.45, 0.12]} /><meshStandardMaterial {...teal} /></mesh>
          <mesh position={[0, 2.2, 1.75]}><boxGeometry args={[0.45, 1.5, 0.12]} /><meshStandardMaterial {...teal} /></mesh>
          <mesh position={[0, 0.8, 1.75]}><boxGeometry args={[1, 1.6, 0.1]} /><meshStandardMaterial {...ink} /></mesh>
        </group>
      );
    case 1: // Visit: a waiting room clock
      return (
        <group>
          <mesh position={[0, 1.5, 0]}><boxGeometry args={[3.2, 3, 3.2]} /><meshStandardMaterial {...white} /></mesh>
          <mesh position={[0, 2.1, 1.62]} rotation-x={Math.PI / 2}><cylinderGeometry args={[0.95, 0.95, 0.14, 32]} /><meshStandardMaterial {...teal} /></mesh>
          <mesh position={[0, 2.1, 1.72]}><boxGeometry args={[0.1, 0.7, 0.05]} /><meshStandardMaterial color="#ffffff" /></mesh>
          <mesh position={[0.22, 2.0, 1.72]} rotation-z={-0.9}><boxGeometry args={[0.1, 0.5, 0.05]} /><meshStandardMaterial color="#ffffff" /></mesh>
        </group>
      );
    case 2: // Prescription: a leaf of paper
      return (
        <group rotation-y={0.25}>
          <mesh position={[0, 2.4, 0]}><boxGeometry args={[3, 4.4, 0.25]} /><meshStandardMaterial {...white} /></mesh>
          {[0, 1, 2, 3].map((n) => (
            <mesh key={n} position={[-0.2, 3.5 - n * 0.75, 0.16]}><boxGeometry args={[2 - (n === 3 ? 0.8 : 0), 0.16, 0.05]} /><meshStandardMaterial {...teal} /></mesh>
          ))}
          <mesh position={[1.0, 0.6, 0.16]}><boxGeometry args={[0.7, 0.7, 0.05]} /><meshStandardMaterial {...gold} /></mesh>
        </group>
      );
    case 3: // Chemists nearby: a shop with an awning
      return (
        <group>
          <mesh position={[0, 1.4, 0]}><boxGeometry args={[4, 2.8, 3]} /><meshStandardMaterial {...white} /></mesh>
          {[-1.5, -0.5, 0.5, 1.5].map((x, n) => (
            <mesh key={x} position={[x, 2.75, 1.7]} rotation-x={-0.35}><boxGeometry args={[1, 0.14, 1.1]} /><meshStandardMaterial {...(n % 2 ? white : gold)} /></mesh>
          ))}
          <mesh position={[0, 0.9, 1.52]}><boxGeometry args={[1.2, 1.8, 0.1]} /><meshStandardMaterial {...ink} /></mesh>
          <mesh position={[0, 3.6, 0]}><sphereGeometry args={[0.42, 20, 20]} /><meshStandardMaterial {...gold} /></mesh>
        </group>
      );
    default: // Pick up: a counter and six code cells
      return (
        <group>
          <mesh position={[0, 0.7, 0]}><boxGeometry args={[4.4, 1.4, 1.6]} /><meshStandardMaterial {...white} /></mesh>
          {[0, 1, 2, 3, 4, 5].map((n) => (
            <mesh key={n} position={[-2 + n * 0.8, 1.9, 0]}><boxGeometry args={[0.6, 0.9, 0.5]} /><meshStandardMaterial {...(n % 2 ? ink : teal)} /></mesh>
          ))}
        </group>
      );
  }
}
