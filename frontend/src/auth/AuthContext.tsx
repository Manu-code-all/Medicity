import { useCallback, useMemo, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { tokenStore } from "../api/client";
import { auth as authApi } from "../api/endpoints";
import type { TokenPair } from "../api/types";
import { AuthContext, SESSION_KEY, type Session } from "./context";

function readStoredSession(): Session | null {
  const raw = localStorage.getItem(SESSION_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Session;
  } catch {
    // Corrupted entry — drop it rather than crashing the app on boot.
    localStorage.removeItem(SESSION_KEY);
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  // Read synchronously during the first render. Deferring this to an effect
  // would flash the logged-out UI for one frame on every page load.
  const [session, setSession] = useState<Session | null>(readStoredSession);
  const queryClient = useQueryClient();

  const adopt = useCallback((pair: TokenPair): Session => {
    tokenStore.save(pair);
    // A different account may be signing in on this browser; nothing cached
    // for the previous one may be shown to the new one.
    queryClient.clear();
    const next: Session = {
      userId: pair.userId,
      fullName: pair.fullName,
      role: pair.role,
    };
    // Display state only. It is never trusted for authorization — the server
    // re-derives the role from the token on every request, so editing this
    // value in devtools changes nothing but the greeting.
    localStorage.setItem(SESSION_KEY, JSON.stringify(next));
    setSession(next);
    return next;
  }, [queryClient]);

  const login = useCallback(
    async (email: string, password: string) => {
      return adopt(await authApi.login(email, password));
    },
    [adopt],
  );

  const register = useCallback(
    async (input: Parameters<typeof authApi.register>[0]) => {
      adopt(await authApi.register(input));
    },
    [adopt],
  );

  const logout = useCallback(() => {
    tokenStore.clear();
    localStorage.removeItem(SESSION_KEY);
    // Cached queries hold medical records. On a shared computer, leaving them
    // in memory would show one patient's history to whoever signs in next.
    queryClient.clear();
    setSession(null);
  }, [queryClient]);

  const value = useMemo(
    () => ({ session, login, register, logout }),
    [session, login, register, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
