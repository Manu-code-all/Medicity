import { createContext, useContext } from "react";
import type { auth as authApi } from "../api/endpoints";
import type { Role } from "../api/types";

export interface Session {
  userId: string;
  fullName: string;
  role: Role;
}

export interface AuthContextValue {
  session: Session | null;
  login: (email: string, password: string) => Promise<void>;
  register: (input: Parameters<typeof authApi.register>[0]) => Promise<void>;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export const SESSION_KEY = "medicity.session";

/**
 * Lives here rather than beside the provider so that AuthContext.tsx exports
 * only a component. Mixing component and non-component exports in one module
 * silently breaks React Fast Refresh for that file.
 */
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used inside <AuthProvider>");
  }
  return ctx;
}
