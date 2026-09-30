import { lazy, type ComponentType } from "react";

/**
 * A page loaded on first visit. Pages are named exports, and React.lazy
 * wants a default one, so this picks the export out by name.
 */
export function lazyPage<M extends Record<string, unknown>, K extends keyof M & string>(
  load: () => Promise<M>,
  name: K,
) {
  return lazy(() => load().then((m) => ({ default: m[name] as ComponentType<object> })));
}
