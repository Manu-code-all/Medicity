import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";
import { afterEach } from "vitest";

// findBy* and waitFor give up after 1 s by default. Files run in parallel on
// shared CI runners, where a busy machine can need longer; a real failure
// still fails, just a little later.
configure({ asyncUtilTimeout: 3000 });

afterEach(() => {
  cleanup();
  localStorage.clear();
});
