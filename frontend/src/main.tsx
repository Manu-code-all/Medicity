import React from "react";
import ReactDOM from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter } from "react-router-dom";
import { ApiError } from "./api/client";
import { AuthProvider } from "./auth/AuthContext";
import { App } from "./App";
import "./styles.css";
import "./workspace.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Retrying a 4xx just repeats a request the server already rejected on
      // its merits. Only transient failures are worth a second attempt.
      retry: (failureCount, error) => {
        if (error instanceof ApiError && error.status < 500) return false;
        return failureCount < 2;
      },
      refetchOnWindowFocus: false,
    },
    mutations: {
      // Booking is not idempotent: an automatic retry could create a second
      // appointment when the first actually succeeded but the response was lost.
      retry: false,
    },
  },
});

// `npm run preview:ui` shows the signed-in screens from recorded demo data,
// with no backend. The branch is removed from production builds.
const ready =
  import.meta.env.DEV && import.meta.env.VITE_PREVIEW
    ? import("./dev/preview").then((m) => m.install())
    : Promise.resolve();

void ready.then(() => ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      {/* Navigation as a transition: while the next page's code downloads,
          the current page stays on screen instead of a loading message. */}
      <BrowserRouter future={{ v7_startTransition: true }}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
));
