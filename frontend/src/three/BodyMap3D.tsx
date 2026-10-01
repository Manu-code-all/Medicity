import { useFrame, useThree } from "@react-three/fiber";
import { RoundedBoxGeometry } from "three/examples/jsm/geometries/RoundedBoxGeometry.js";
import { useEffect, useMemo, useRef, useState } from "react";
import * as THREE from "three";
import type { BodyView } from "../components/bodymap/taxonomy";
import { BODY_TAXONOMY } from "../components/bodymap/taxonomy";
import { SILHOUETTES } from "../components/bodymap/silhouettes";
import { Stage } from "./Stage";
import { ENAMEL } from "./palette";

interface Part {
  /** The area this piece belongs to in each view; null when that view does not offer it. */
  ids: { front: string | null; back: string | null };
  shape: "sphere" | "box" | "capsule" | "cyl";
  size: [number, number, number];
  at: [number, number, number];
  rot?: [number, number, number];
}

/** A stylised body from simple solids. Parts that share an area light up together. */
const PARTS: Part[] = [
  { ids: { front: "head_face", back: "head_face" }, shape: "sphere", size: [0.62, 0, 0], at: [0, 3.35, 0] },
  { ids: { front: "neck_throat", back: "cervical_spine" }, shape: "cyl", size: [0.22, 0.4, 0], at: [0, 2.72, 0] },
  { ids: { front: "chest", back: "thoracic_lumbar" }, shape: "box", size: [1.5, 1.0, 0.72], at: [0, 2.0, 0] },
  { ids: { front: "abdomen_upper", back: "thoracic_lumbar" }, shape: "box", size: [1.4, 0.62, 0.68], at: [0, 1.2, 0] },
  { ids: { front: "abdomen_lower", back: "thoracic_lumbar" }, shape: "box", size: [1.4, 0.62, 0.68], at: [0, 0.55, 0] },
  { ids: { front: "pelvis_groin", back: "thoracic_lumbar" }, shape: "box", size: [1.42, 0.55, 0.7], at: [0, -0.05, 0] },
  { ids: { front: null, back: "flanks" }, shape: "box", size: [0.22, 1.2, 0.5], at: [-0.8, 0.95, 0] },
  { ids: { front: null, back: "flanks" }, shape: "box", size: [0.22, 1.2, 0.5], at: [0.8, 0.95, 0] },
  // Arms: shoulder, upper arm, elbow, forearm, on each side.
  ...[-1, 1].flatMap<Part>((s) => [
    { ids: { front: "joints_upper", back: "joints_upper" }, shape: "sphere", size: [0.32, 0, 0], at: [s * 0.98, 2.35, 0] },
    { ids: { front: "joints_upper", back: "joints_upper" }, shape: "capsule", size: [0.2, 0.85, 0], at: [s * 1.1, 1.7, 0], rot: [0, 0, s * 0.06] },
    { ids: { front: "joints_upper", back: "joints_upper" }, shape: "sphere", size: [0.22, 0, 0], at: [s * 1.16, 1.12, 0] },
    { ids: { front: "joints_upper", back: "joints_upper" }, shape: "capsule", size: [0.17, 0.85, 0], at: [s * 1.22, 0.5, 0], rot: [0, 0, s * 0.05] },
  ]),
  // Legs: thigh, knee, shin, on each side.
  ...[-1, 1].flatMap<Part>((s) => [
    { ids: { front: "joints_lower", back: "joints_lower" }, shape: "capsule", size: [0.3, 1.2, 0], at: [s * 0.4, -1.15, 0] },
    { ids: { front: "joints_lower", back: "joints_lower" }, shape: "sphere", size: [0.3, 0, 0], at: [s * 0.4, -2.0, 0] },
    { ids: { front: "joints_lower", back: "joints_lower" }, shape: "capsule", size: [0.26, 1.3, 0], at: [s * 0.4, -3.0, 0] },
  ]),
];

const BASE = new THREE.Color("#e9ebe4");
const HOVER = new THREE.Color(ENAMEL.patientLine);
const CHOSEN = new THREE.Color(ENAMEL.patientBand);

/**
 * The tappable body, in 3D. Same contract as the flat drawing (`view`,
 * `selected`, `onSelect`), so the guide around it does not change: drag to
 * turn the body, tap an area to choose it, and it settles front or back.
 * Keyboard and screen reader users get a real button for every area.
 */
export function BodyMap3D({
  view,
  onViewChange,
  selected,
  onSelect,
  fallback,
}: {
  view: BodyView;
  onViewChange: (view: BodyView) => void;
  selected: string | null;
  onSelect: (regionId: string) => void;
  fallback: React.ReactNode;
}) {
  const [hovered, setHovered] = useState<string | null>(null);
  const ids = SILHOUETTES[view].map((s) => s.id);
  const label = hovered ? BODY_TAXONOMY[hovered]?.label : selected ? BODY_TAXONOMY[selected]?.label : null;

  return (
    <div className="b3d">
      <div className="b3d__stage">
        <Stage
          label={`Body, ${view} view. Drag to turn it and tap where it hurts. The same areas are listed as buttons below.`}
          fallback={fallback}
          camera={{ position: [0, -0.7, 16.5], fov: 34 }}
        >
          <Body view={view} onViewChange={onViewChange} selected={selected} hovered={hovered} setHovered={setHovered} onSelect={onSelect} />
        </Stage>
      </div>
      <p className="b3d__hint" aria-live="polite">
        {label ?? "Drag to turn. Tap where it hurts."}
      </p>
      <ul className="sr-only">
        {ids.map((id) => (
          <li key={id}>
            <button type="button" aria-pressed={selected === id} onClick={() => onSelect(id)}>
              {BODY_TAXONOMY[id]?.label}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

function Body({
  view,
  onViewChange,
  selected,
  hovered,
  setHovered,
  onSelect,
}: {
  view: BodyView;
  onViewChange: (view: BodyView) => void;
  selected: string | null;
  hovered: string | null;
  setHovered: (id: string | null) => void;
  onSelect: (id: string) => void;
}) {
  const group = useRef<THREE.Group>(null);
  const yaw = useRef(view === "back" ? Math.PI : 0);
  const target = useRef(yaw.current);
  const gl = useThree((s) => s.gl);
  const viewRef = useRef(view);
  viewRef.current = view;

  // The view buttons turn the body by the shortest way round.
  useEffect(() => {
    const want = view === "back" ? Math.PI : 0;
    const turns = Math.round((yaw.current - want) / (2 * Math.PI));
    target.current = want + turns * 2 * Math.PI;
  }, [view]);

  // Dragging turns the body; letting go settles it front or back. Vertical drags are left to the page.
  useEffect(() => {
    const el = gl.domElement;
    el.style.touchAction = "pan-y";
    let from: number | null = null;
    let start = 0;
    const down = (e: PointerEvent) => {
      from = e.clientX;
      start = target.current;
      el.setPointerCapture(e.pointerId);
    };
    const move = (e: PointerEvent) => {
      if (from === null) return;
      target.current = start + (e.clientX - from) * 0.012;
    };
    const up = () => {
      if (from === null) return;
      from = null;
      const settled = Math.round(target.current / Math.PI) * Math.PI;
      target.current = settled;
      const facingBack = Math.abs(Math.round(settled / Math.PI)) % 2 === 1;
      const next: BodyView = facingBack ? "back" : "front";
      if (next !== viewRef.current) onViewChange(next);
    };
    el.addEventListener("pointerdown", down);
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
    return () => {
      el.removeEventListener("pointerdown", down);
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
      el.removeEventListener("pointercancel", up);
    };
  }, [gl, onViewChange]);

  useFrame(({ clock }, delta) => {
    yaw.current = THREE.MathUtils.damp(yaw.current, target.current, 7, delta);
    if (group.current) {
      group.current.rotation.y = yaw.current;
      group.current.position.y = Math.sin(clock.elapsedTime * 1.1) * 0.05;
    }
  });

  return (
    <>
      <ambientLight intensity={1.5} />
      <directionalLight position={[4, 8, 6]} intensity={1.4} />
      <directionalLight position={[-5, 2, -6]} intensity={0.9} />
      <group ref={group}>
        {PARTS.map((part, i) => (
          <PartMesh
            key={i}
            part={part}
            id={part.ids[view]}
            selected={selected}
            hovered={hovered}
            setHovered={setHovered}
            onSelect={onSelect}
          />
        ))}
      </group>
      <Floor />
    </>
  );
}

/** One solid of the body. It eases toward its colour: hover and choice feel soft rather than switched. */
function PartMesh({
  part,
  id,
  selected,
  hovered,
  setHovered,
  onSelect,
}: {
  part: Part;
  id: string | null;
  selected: string | null;
  hovered: string | null;
  setHovered: (id: string | null) => void;
  onSelect: (id: string) => void;
}) {
  const material = useRef<THREE.MeshStandardMaterial>(null);
  const gl = useThree((s) => s.gl);
  useFrame(() => {
    const goal = id && id === selected ? CHOSEN : id && id === hovered ? HOVER : BASE;
    material.current?.color.lerp(goal, 0.2);
  });
  return (
    <mesh
      position={part.at}
      rotation={part.rot ?? [0, 0, 0]}
      onPointerOver={(e) => {
        if (!id) return;
        e.stopPropagation();
        setHovered(id);
        gl.domElement.style.cursor = "pointer";
      }}
      onPointerOut={() => {
        setHovered(null);
        gl.domElement.style.cursor = "grab";
      }}
      onClick={(e) => {
        // A drag that ends on a part is a turn, not a choice.
        if (!id || e.delta > 6) return;
        e.stopPropagation();
        onSelect(id);
      }}
    >
      <Solid part={part} />
      <meshStandardMaterial ref={material} color={BASE} roughness={0.85} />
    </mesh>
  );
}

/** A box with softened edges, built once per size and disposed with the part. */
function SoftBox({ size }: { size: [number, number, number] }) {
  const geometry = useMemo(() => new RoundedBoxGeometry(size[0], size[1], size[2], 4, 0.14), [size]);
  useEffect(() => () => geometry.dispose(), [geometry]);
  return <primitive object={geometry} attach="geometry" />;
}

/** Geometry for a part, by name. */
function Solid({ part }: { part: Part }) {
  const [a, b] = part.size;
  switch (part.shape) {
    case "sphere":
      return <sphereGeometry args={[a, 32, 24]} />;
    case "box":
      return <SoftBox size={part.size} />;
    case "cyl":
      return <cylinderGeometry args={[a, a, b, 24]} />;
    default:
      return <capsuleGeometry args={[a, b, 8, 16]} />;
  }
}

/** A flat disc to stand on, so the body does not float in nothing. */
function Floor() {
  const ring = useMemo(() => new THREE.RingGeometry(1.7, 1.85, 64), []);
  useEffect(() => () => ring.dispose(), [ring]);
  return (
    <mesh geometry={ring} rotation-x={-Math.PI / 2} position={[0, -4.35, 0]}>
      <meshBasicMaterial color={ENAMEL.rule} />
    </mesh>
  );
}
