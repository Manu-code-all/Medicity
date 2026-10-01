import { Html } from "@react-three/drei";
import { useFrame } from "@react-three/fiber";
import { useEffect, useMemo, useRef } from "react";
import * as THREE from "three";
import { DEMO_HOME, DEMO_STORES, offsetKm } from "../lib/demo";
import { Stage } from "./Stage";
import { ENAMEL } from "./palette";

/** World units per kilometre. */
const KM = 3.4;
const LOOP_SECONDS = 11;

function seeded(seed: number) {
  let s = seed;
  return () => {
    s = (s * 1664525 + 1013904223) % 4294967296;
    return s / 4294967296;
  };
}

interface Pin {
  name: string;
  x: number;
  z: number;
  detail: string;
  kind: "has" | "partly" | "waiting";
  /** Seconds into the loop at which the answer comes back. */
  answerAt: number;
}

const PINS: Pin[] = (() => {
  let n = 0;
  return DEMO_STORES.map((s) => {
    const { x, y } = offsetKm(s.lat, s.lng, DEMO_HOME);
    const answered = s.answer.kind !== "waiting";
    return {
      name: s.name,
      x: x * KM,
      z: -y * KM,
      detail: s.answer.kind === "waiting" ? "Asked, waiting" : `${s.answer.detail} · ${s.answer.price}`,
      kind: s.answer.kind,
      answerAt: answered ? 3.4 + n++ * 1.2 : Infinity,
    };
  });
})();

/**
 * The product's mechanism in a small tilted city: a question leaves home for
 * every verified chemist within 3 km and the answers come back one by one.
 * It is the same data as the flat map. It loops, then starts again.
 */
export function CityMap3D({ fallback }: { fallback: React.ReactNode }) {
  return (
    <Stage
      className="c3d"
      label="A map of chemists within 3 kilometres. A question goes out from home to each one and answers come back. The same answers are listed as text beside it."
      fallback={fallback}
      camera={{ position: [0, 15, 13.5], fov: 36 }}
    >
      <City />
    </Stage>
  );
}

function City() {
  const beams = useRef<(THREE.Line | null)[]>([]);
  const pillars = useRef<(THREE.Mesh | null)[]>([]);
  const home = useRef<THREE.Mesh>(null);
  const city = useRef<THREE.Group>(null);

  const blocks = useMemo(() => {
    const rand = seeded(11);
    const items: { x: number; z: number; w: number; d: number; h: number; tint: boolean }[] = [];
    for (let gx = -7; gx <= 7; gx++) {
      for (let gz = -6; gz <= 6; gz++) {
        // A street every third line each way.
        if (gx % 3 === 0 || gz % 3 === 0) continue;
        const x = gx * 1.5;
        const z = gz * 1.5;
        const near = [{ x: 0, z: 0 }, ...PINS].some((p) => Math.hypot(p.x - x, p.z - z) < 1.1);
        if (near) continue;
        items.push({ x, z, w: 1.05, d: 1.05, h: 0.25 + rand() * rand() * 2.2, tint: rand() > 0.5 });
      }
    }
    return items;
  }, []);

  const blockMesh = useRef<THREE.InstancedMesh>(null);
  useEffect(() => {
    const mesh = blockMesh.current;
    if (!mesh) return;
    const m = new THREE.Matrix4();
    const c = new THREE.Color();
    blocks.forEach((b, i) => {
      m.compose(new THREE.Vector3(b.x, b.h / 2, b.z), new THREE.Quaternion(), new THREE.Vector3(b.w, b.h, b.d));
      mesh.setMatrixAt(i, m);
      mesh.setColorAt(i, c.set(b.tint ? ENAMEL.surface : ENAMEL.raised));
    });
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  }, [blocks]);

  const lines = useMemo(
    () =>
      PINS.map(() => {
        const g = new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(0, 0.5, 0), new THREE.Vector3(0, 0.5, 0)]);
        return new THREE.Line(g, new THREE.LineBasicMaterial({ color: ENAMEL.patientLine }));
      }),
    [],
  );
  useEffect(
    () => () => {
      lines.forEach((l) => {
        l.geometry.dispose();
        (l.material as THREE.Material).dispose();
      });
    },
    [lines],
  );

  const answers = useMemo(
    () => ({ has: new THREE.Color(ENAMEL.patientLine), partly: new THREE.Color(ENAMEL.chemist), idle: new THREE.Color(ENAMEL.surface) }),
    [],
  );

  useFrame(({ clock, pointer }) => {
    const t = clock.elapsedTime % LOOP_SECONDS;
    if (city.current) {
      // Hand-held: the whole city leans a little with the pointer.
      city.current.rotation.y = THREE.MathUtils.lerp(city.current.rotation.y, pointer.x * 0.18, 0.04);
      city.current.rotation.x = THREE.MathUtils.lerp(city.current.rotation.x, -pointer.y * 0.05, 0.04);
    }
    if (home.current) home.current.scale.setScalar(1 + Math.sin(clock.elapsedTime * 2.2) * 0.04);
    PINS.forEach((pin, i) => {
      // The question travels out over the first 2 seconds, staggered by store.
      const out = THREE.MathUtils.clamp((t - 0.4 - i * 0.12) / 1.6, 0, 1);
      const line = lines[i];
      if (line) {
        const pos = line.geometry.attributes.position as THREE.BufferAttribute;
        pos.setXYZ(1, pin.x * out, 0.5, pin.z * out);
        pos.needsUpdate = true;
      }
      const pillar = pillars.current[i];
      if (pillar) {
        const back = t >= pin.answerAt;
        const goal = back ? (pin.kind === "has" ? answers.has : answers.partly) : answers.idle;
        (pillar.material as THREE.MeshStandardMaterial).color.lerp(goal, 0.15);
        const rise = back ? 1.9 : out >= 1 ? 1.35 : 1;
        pillar.scale.y = THREE.MathUtils.lerp(pillar.scale.y, rise, 0.12);
        pillar.position.y = (pillar.scale.y * 1.2) / 2;
      }
    });
  });

  return (
    <>
      <ambientLight intensity={1.1} />
      <directionalLight position={[8, 16, 6]} intensity={1.1} />
      <group ref={city}>
        <mesh rotation-x={-Math.PI / 2} position={[0, -0.01, 0]}>
          <circleGeometry args={[13, 64]} />
          <meshBasicMaterial color={ENAMEL.ground} />
        </mesh>
        <instancedMesh ref={blockMesh} args={[undefined, undefined, blocks.length]}>
          <boxGeometry args={[1, 1, 1]} />
          <meshStandardMaterial roughness={1} />
        </instancedMesh>

        {/* 1 km and 3 km: the same two rings as the flat map. */}
        {[1, 3].map((km) => (
          <mesh key={km} rotation-x={-Math.PI / 2} position={[0, 0.02, 0]}>
            <ringGeometry args={[km * KM - 0.03, km * KM + 0.03, 96]} />
            <meshBasicMaterial color={ENAMEL.inkMuted} transparent opacity={0.45} />
          </mesh>
        ))}

        <mesh ref={home} position={[0, 0.3, 0]}>
          <cylinderGeometry args={[0.55, 0.55, 0.5, 32]} />
          <meshStandardMaterial color={ENAMEL.patientBand} roughness={0.6} />
        </mesh>
        <mesh position={[0, 0.58, 0]} rotation-x={Math.PI / 2}>
          <torusGeometry args={[0.36, 0.1, 12, 32]} />
          <meshStandardMaterial color="#ffffff" />
        </mesh>

        {lines.map((line, i) => (
          <primitive
            key={i}
            object={line}
            ref={(el: THREE.Line | null) => {
              beams.current[i] = el;
            }}
          />
        ))}

        {PINS.map((pin, i) => (
          <group key={pin.name} position={[pin.x, 0, pin.z]}>
            <mesh
              ref={(el) => {
                pillars.current[i] = el;
              }}
              position={[0, 0.6, 0]}
              scale={[1, 1, 1]}
            >
              <cylinderGeometry args={[0.32, 0.32, 1.2, 24]} />
              <meshStandardMaterial color={ENAMEL.surface} roughness={0.6} />
            </mesh>
            <Html position={[0, 2.6, 0]} center distanceFactor={14} zIndexRange={[5, 0]}>
              <div className="c3d__label">
                <strong>{pin.name}</strong>
              </div>
            </Html>
          </group>
        ))}
      </group>
    </>
  );
}
