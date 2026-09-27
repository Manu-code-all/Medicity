import { request } from "./client";
import type {
  AnswerLineInput,
  Appointment,
  Comparison,
  Course,
  Doctor,
  FamilyMember,
  DoctorVisitDetail,
  DoctorVisitSummary,
  Medicine,
  NearbyStore,
  NotificationList,
  PatientHistory,
  PrescriptionDraft,
  Page,
  PatientProfile,
  PortalSummary,
  QueueEntry,
  RequestSummary,
  StockView,
  StoreInsights,
  StoreReservation,
  StoreView,
  Prescription,
  Slot,
  StoreInput,
  StoreProfile,
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

  /** A chemist and their store, in one step. The store waits for a licence check. */
  registerChemist: (input: { email: string; password: string; fullName: string; store: StoreInput }) =>
    request<TokenPair>("/api/v1/auth/register/chemist", {
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

  /** Each prescribed medicine: started when, runs out when. Running out first. */
  courses: () => request<Course[]>("/api/v1/patients/me/courses"),
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

/** The signed-in user's notifications. Scoped by the token, like the portal. */
export const notifications = {
  mine: () => request<NotificationList>("/api/v1/notifications"),
  markRead: (id: string) => request<void>(`/api/v1/notifications/${id}/read`, { method: "POST" }),
  markAllRead: () => request<void>("/api/v1/notifications/read-all", { method: "POST" }),
};

export const stores = {
  nearby: (lat: number, lng: number, radiusM: number) =>
    request<NearbyStore[]>(`/api/v1/stores/nearby?lat=${lat}&lng=${lng}&radiusM=${radiusM}`),

  /** The signed-in chemist's own store; "me" comes from the token. */
  mine: () => request<StoreProfile>("/api/v1/stores/me"),

  update: (input: StoreInput) => request<StoreProfile>("/api/v1/stores/me", { method: "PUT", body: input }),
};

export const admin = {
  pendingStores: () => request<StoreProfile[]>("/api/v1/admin/stores/pending"),
  verifyStore: (id: string) => request<StoreProfile>(`/api/v1/admin/stores/${id}/verify`, { method: "POST" }),
};

/** The patient's questions to nearby chemists. */
export const medicineRequests = {
  ask: (input: { prescriptionId: string; latitude: number; longitude: number; radiusM: number; medicineIds?: string[] | undefined }) =>
    request<Comparison>("/api/v1/patients/me/medicine-requests", { method: "POST", body: input }),

  mine: () => request<RequestSummary[]>("/api/v1/patients/me/medicine-requests"),

  get: (id: string) => request<Comparison>(`/api/v1/patients/me/medicine-requests/${id}`),

  close: (id: string) => request<Comparison>(`/api/v1/patients/me/medicine-requests/${id}/close`, { method: "POST" }),

  /** Holds what one store said it has; the response carries the pick-up code. */
  reserve: (id: string, storeId: string) =>
    request<Comparison>(`/api/v1/patients/me/medicine-requests/${id}/reserve`, { method: "POST", body: { storeId } }),

  cancelReservation: (reservationId: string) =>
    request<void>(`/api/v1/patients/me/reservations/${reservationId}/cancel`, { method: "POST" }),
};

/** Questions sent to the signed-in chemist's store. */
export const storeQueue = {
  list: (show: "pending" | "answered") => request<QueueEntry[]>(`/api/v1/stores/me/requests?show=${show}`),

  get: (id: string) => request<StoreView>(`/api/v1/stores/me/requests/${id}`),

  answer: (id: string, input: { note: string; lines: AnswerLineInput[] }) =>
    request<StoreView>(`/api/v1/stores/me/requests/${id}/answer`, { method: "POST", body: input }),
};

/** What the signed-in chemist's store must keep aside, and hand over against the patient's code. */
export const storeReservations = {
  list: (show: "held" | "done") => request<StoreReservation[]>(`/api/v1/stores/me/reservations?show=${show}`),

  collect: (id: string, code: string) =>
    request<void>(`/api/v1/stores/me/reservations/${id}/collect`, { method: "POST", body: { code } }),
};

/** The signed-in chemist's demand insights and optional live stock. */
export const storeWorkspace = {
  insights: () => request<StoreInsights>("/api/v1/stores/me/insights"),

  stock: () => request<StockView>("/api/v1/stores/me/stock"),

  /** Replaces the whole list: a medicine left out is out of stock. */
  replaceStock: (items: { medicineId: string; quantity: number; unitPrice: number }[]) =>
    request<StockView>("/api/v1/stores/me/stock", { method: "PUT", body: { items } }),

  autoAnswer: (enabled: boolean) =>
    request<StockView>("/api/v1/stores/me/auto-answer", { method: "PUT", body: { enabled } }),
};

/** The account holder and the family members they manage. */
export const family = {
  list: () => request<FamilyMember[]>("/api/v1/patients/me/family"),

  add: (input: {
    fullName: string;
    relationship: string;
    dateOfBirth: string;
    gender: string;
    bloodGroup?: string | undefined;
  }) => request<FamilyMember>("/api/v1/patients/me/family", { method: "POST", body: input }),
};
