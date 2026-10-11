import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { AuthContext, type AuthContextValue } from "../auth/context";

/** Renders a page with a fresh query cache and a router, as the app does. */
export function renderPage(page: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  // A signed-out visitor: pages that read the session (profile, privacy) render without the real provider.
  const auth = { session: null, logout: () => undefined } as unknown as AuthContextValue;
  return render(
    <QueryClientProvider client={client}>
      <AuthContext.Provider value={auth}>
        <MemoryRouter>{page}</MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}
