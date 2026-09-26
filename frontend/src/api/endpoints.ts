import { request } from "./client";
import type {
  Appointment,
  Doctor,
  DoctorVisitDetail,
  DoctorVisitSummary,
  Medicine,
  PatientHistory,
  PrescriptionDraft,
  Page,
  PatientProfile,
  PortalSummary,
  Prescription,
  Slot,
  TokenPair,
  Visit,
  VisitScope,
} from "./types";

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
  /**
   * `idempotencyKey` identifies this booking attempt. Sending the same key
   * again (a retry after a dropped response) returns the original booking
   * instead of a "you already have an appointment" error.
   */
  book: (slotId: string, reason: string, idempotencyKey: string) =>
    request<Appointment>("/api/v1/appointments", {
      method: "POST",
      body: { slotId, reason },
      headers: { "Idempotency-Key": idempotencyKey },
    }),

  cancel: (id: string, reason: string) =>
    request<Appointment>(`/api/v1/appointments/${id}/cancel`, {
      method: "POST",
      body: { reason },
    }),

};

/**
 * The signed-in patient's own records. No id is ever sent: the server resolves
 * "me" from the token, so there is nothing here a caller could change to read
 * another patient's history.
 */
export const portal = {
  profile: () => request<PatientProfile>("/api/v1/patients/me"),

  summary: () => request<PortalSummary>("/api/v1/patients/me/summary"),

  visits: (scope: VisitScope, page = 0, size = 10) =>
    request<Page<Visit>>(`/api/v1/patients/me/appointments?scope=${scope}&page=${page}&size=${size}`),

  prescriptions: () => request<Prescription[]>("/api/v1/patients/me/prescriptions"),
};

/** The signed-in doctor's own calendar and patients; "me" comes from the token. */
export const workspace = {
  visits: (from: string, to: string) =>
    request<DoctorVisitSummary[]>(
      `/api/v1/doctors/me/visits?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    ),

  visit: (id: string) => request<DoctorVisitDetail>(`/api/v1/doctors/me/visits/${id}`),

  complete: (id: string) =>
    request<DoctorVisitDetail>(`/api/v1/doctors/me/visits/${id}/complete`, { method: "POST" }),

  noShow: (id: string) =>
    request<DoctorVisitDetail>(`/api/v1/doctors/me/visits/${id}/no-show`, { method: "POST" }),

  prescribe: (visitId: string, draft: PrescriptionDraft) =>
    request<DoctorVisitDetail>(`/api/v1/doctors/me/visits/${visitId}/prescriptions`, {
      method: "POST",
      body: draft,
    }),

  correct: (prescriptionId: string, draft: PrescriptionDraft) =>
    request<DoctorVisitDetail>(`/api/v1/doctors/me/prescriptions/${prescriptionId}/corrections`, {
      method: "POST",
      body: draft,
    }),

  patientHistory: (patientId: string) =>
    request<PatientHistory>(`/api/v1/doctors/me/patients/${patientId}/history`),
};

export const pharmacy = {
  medicines: () => request<Page<Medicine>>("/api/v1/pharmacy/medicines?size=100"),
};
