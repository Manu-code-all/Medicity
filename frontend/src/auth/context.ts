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
  /** Resolves with the new session, so the caller can route by role. */
  login: (email: string, password: string) => Promise<Session>;
  /** Signs in with an email address and the code sent to it. */
  loginWithEmailCode: (email: string, code: string) => Promise<Session>;
  register: (input: Parameters<typeof authApi.register>[0]) => Promise<void>;
  registerChemist: (input: Parameters<typeof authApi.registerChemist>[0]) => Promise<void>;
  registerDoctor: (input: Parameters<typeof authApi.registerDoctor>[0]) => Promise<void>;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export const SESSION_KEY = "medicity.session";

/** Where each role lands after signing in. */
export function homeFor(role: Role): string {
  switch (role) {
    case "PATIENT":
      return "/portal";
    case "DOCTOR":
      return "/doctor";
    case "CHEMIST":
      return "/store";
    case "ADMIN":
      return "/admin/stores";
    default:
      return "/";
  }
}

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
