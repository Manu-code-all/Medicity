import { Canvas } from "@react-three/fiber";
import { useEffect, useRef, useState, type ReactNode } from "react";

/**
 * The one canvas wrapper every scene uses, so the rules are kept in one place:
 * the pixel ratio is capped, drawing stops when the scene is off screen or the
 * tab is hidden, and a lost WebGL context swaps the scene for its flat
 * fallback instead of leaving a blank box.
 */
export function Stage({
  children,
  fallback,
  className,
  label,
  camera,
}: {
  children: ReactNode;
  fallback: ReactNode;
  className?: string;
  /** What the scene shows, for people who cannot see it. The real controls are HTML beside it. */
  label: string;
  camera?: { position: [number, number, number]; fov: number };
}) {
  const host = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(true);
  const [hidden, setHidden] = useState(() => document.visibilityState === "hidden");
  const [lost, setLost] = useState(false);

  useEffect(() => {
    const el = host.current;
    if (!el || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(([entry]) => setVisible(entry?.isIntersecting ?? true), { rootMargin: "120px" });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const onChange = () => setHidden(document.visibilityState === "hidden");
    document.addEventListener("visibilitychange", onChange);
    return () => document.removeEventListener("visibilitychange", onChange);
  }, []);

  if (lost) return <>{fallback}</>;

  return (
    <div ref={host} className={className} role="img" aria-label={label}>
      <Canvas
        flat
        dpr={[1, 1.75]}
        frameloop={visible && !hidden ? "always" : "never"}
        camera={camera ?? { position: [0, 0, 6], fov: 40 }}
        gl={{ antialias: true, powerPreference: "default", alpha: true }}
        onCreated={({ gl }) => {
          gl.domElement.addEventListener("webglcontextlost", (e) => {
            e.preventDefault();
            setLost(true);
          });
        }}
      >
        {children}
      </Canvas>
    </div>
  );
}
