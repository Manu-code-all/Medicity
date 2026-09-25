import { request } from "./client";
import type { Appointment, Doctor, Page, Slot, TokenPair } from "./types";

export const auth = {
  login: (email: string, password: string) =>
    request<TokenPair>("/api/v1/auth/login", {
      method: "POST",
      body: { email, password },
    }),

  register: (input: {
    email: string;
    password: string;
    fullName: string;
    phone?: string;
    dateOfBirth: string;
  }) =>
    request<TokenPair>("/api/v1/auth/register", {
      method: "POST",
      body: input,
    }),
};

export const doctors = {
  search: (specialization?: string, nameQuery?: string) => {
    const params = new URLSearchParams();
    if (specialization) params.set("specialization", specialization);
    if (nameQuery) params.set("q", nameQuery);
    return request<Page<Doctor>>(`/api/v1/doctors?${params}`);
  },

  slots: (doctorId: string, from: string, to: string) =>
    request<Slot[]>(
      `/api/v1/doctors/${doctorId}/slots?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    ),
};

export const appointments = {
  book: (slotId: string, reason: string) =>
    request<Appointment>("/api/v1/appointments", {
      method: "POST",
      body: { slotId, reason },
    }),

  cancel: (id: string, reason: string) =>
    request<Appointment>(`/api/v1/appointments/${id}/cancel`, {
      method: "POST",
      body: { reason },
    }),

  mine: (page = 0) => request<Page<Appointment>>(`/api/v1/appointments/mine?page=${page}`),
};
