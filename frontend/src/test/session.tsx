import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { AuthContext, type AuthContextValue, type Session } from "../auth/context";

/**
 * Renders a workspace layout for `session` at `path`, with `child` as its
 * index page. Pass `session: null` for a visitor.
 */
export function renderLayout(layout: ReactNode, path: string, session: Session | null, child: ReactNode = <p>Page body</p>) {
  const value = { session, logout: () => undefined } as unknown as AuthContextValue;
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <AuthContext.Provider value={value}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path={path} element={layout}>
              <Route index element={child} />
            </Route>
            <Route path="/" element={<p>Home page</p>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}
