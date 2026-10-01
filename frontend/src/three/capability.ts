import { useEffect, useState } from "react";
import { prefersReducedMotion } from "../lib/reveal";

/**
 * Whether this device should draw the 3D scenes at all. The flat page is the
 * default and is complete on its own; 3D is an upgrade offered only where it
 * will run smoothly and the person has not asked for less motion.
 *
 * No: reduced motion, Data Saver, no WebGL, fewer than four processor cores,
 * or (where the browser reports it) under 4 GB of memory. The memory and core
 * counts are rough, which is why the test is conservative.
 */
export function canDraw3D(): boolean {
  if (typeof window === "undefined") return false;
  if (prefersReducedMotion()) return false;
  const nav = navigator as Navigator & { deviceMemory?: number; connection?: { saveData?: boolean } };
  if (nav.connection?.saveData) return false;
  if ((nav.hardwareConcurrency ?? 4) < 4) return false;
  if (nav.deviceMemory !== undefined && nav.deviceMemory < 4) return false;
  try {
    const canvas = document.createElement("canvas");
    return Boolean(canvas.getContext("webgl2") ?? canvas.getContext("webgl"));
  } catch {
    return false;
  }
}

/** `canDraw3D`, re-checked when the person changes the reduced motion setting. */
export function useCan3D(): boolean {
  const [can, setCan] = useState(canDraw3D);
  useEffect(() => {
    const query = window.matchMedia?.("(prefers-reduced-motion: reduce)");
    if (!query) return;
    const onChange = () => setCan(canDraw3D());
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);
  return can;
}
