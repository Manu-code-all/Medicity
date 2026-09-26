import { vi } from "vitest";

export interface RecordedCall {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: unknown;
}

type Responder = (call: RecordedCall) => Response | Promise<Response>;

/**
 * Replaces global fetch with `respond`, recording every call. Each test states
 * exactly what the server answers, so a test reads as a conversation.
 */
export function mockFetch(respond: Responder) {
  const calls: RecordedCall[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const call: RecordedCall = {
        url: String(input),
        method: init.method ?? "GET",
        headers: { ...(init.headers as Record<string, string> | undefined) },
        body: typeof init.body === "string" ? JSON.parse(init.body) : undefined,
      };
      calls.push(call);
      return respond(call);
    }),
  );
  return calls;
}

export function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}
