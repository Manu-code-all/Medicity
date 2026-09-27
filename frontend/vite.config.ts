/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Proxying in dev keeps the browser on a single origin, so cookies and
      // CORS behave the same locally as they do behind the production reverse
      // proxy. Without it, dev-only CORS quirks show up as bugs that do not exist.
      "/api": {
        target: process.env.VITE_API_BASE_URL ?? "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  build: {
    sourcemap: true,
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    // Form tests type every character through user-event; with files running
    // in parallel, the longest ones need more than the 5 s default.
    testTimeout: 15_000,
    // Each test file gets fresh module state: client.ts keeps the in-flight
    // refresh in a module variable, and it must not leak between files.
    isolate: true,
    restoreMocks: true,
    // Tests stub fetch and navigator.locks; each must start from the real ones.
    unstubGlobals: true,
  },
});
