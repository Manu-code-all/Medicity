import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AuthContext, type AuthContextValue } from "../auth/context";
import { json, mockFetch } from "../test/fetchMock";
import { VideoRoomPage } from "./VideoRoomPage";

function renderRoom(role: "PATIENT" | "DOCTOR" = "PATIENT") {
  const value = { session: { userId: "u1", fullName: "Meera Nair", role } } as AuthContextValue;
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthContext.Provider value={value}>
        <MemoryRouter initialEntries={["/visits/v9/video"]}>
          <Routes>
            <Route path="/visits/:appointmentId/video" element={<VideoRoomPage />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

describe("VideoRoomPage", () => {
  it("asks for a ticket for this visit and explains when the room is not open yet", async () => {
    const calls = mockFetch(() =>
      json(422, { code: "VIDEO_NOT_OPEN", detail: "The video room opens 15 minutes before the visit." }),
    );
    renderRoom();

    expect(await screen.findByRole("alert")).toHaveTextContent("opens 15 minutes before");
    expect(calls[0]?.url).toBe("/api/v1/appointments/v9/video-ticket");
    expect(calls[0]?.method).toBe("POST");
    expect(screen.getByRole("link", { name: "Back to the visit" })).toHaveAttribute("href", "/portal/visits");
  });

  it("says plainly when the browser cannot use a camera", async () => {
    mockFetch(() =>
      json(200, {
        ticket: "t", side: "DOCTOR", expiresAt: "2030-01-01T00:01:00Z", iceServers: ["stun:stun.l.google.com:19302"],
        opensAt: "2030-01-01T00:00:00Z", closesAt: "2030-01-01T01:00:00Z",
      }),
    );
    renderRoom("DOCTOR");

    expect(await screen.findByRole("alert")).toHaveTextContent("cannot use a camera");
    expect(screen.getByRole("link", { name: "Back to the visit" })).toHaveAttribute("href", "/doctor/visits/v9");
  });
});
