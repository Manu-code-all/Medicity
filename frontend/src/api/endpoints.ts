import { request } from "./client";
import type {
  Attachment,
  ClinicCredentials,
  ClinicListing,
  ClinicProfile,
  ClinicProfileInput,
  ClinicReport,
  ClinicStaffMember,
  DeskPatientHit,
  DeskPatientRef,
  DeskToday,
  DeskVisit,
  NewClinicDoctor,
  NewClinicReceptionist,
  ClinicRecordsView,
  ConsentStatus,
  ConsentType,
  EmailCodeSent,
  ImportReport,
  DeletionSummary,
  DispenseResult,
  LowStockItem,
  PharmacistAccount,
  PharmacyPrescription,
  DoctorLeave,
  Practice,
  PracticeUpdate,
  LiveStatus,
  DiagnosisCode,
  FollowUpThread,
  Insurer,
  WaitlistEntry,
  Intake,
  VideoTicket,
  VisitType,
  QueueDesk,
  QueueStatus,
  QueueToken,
  DoctorReview,
  AnswerLineInput,
  Appointment,
  CodeSent,
  Comparison,
  Course,
  Doctor,
  DoctorProfile,
  DoctorRegistration,
  DoctorSuggestions,
  DoctorVisitDetail,
  DoctorVisitSummary,
  FamilyMember,
  HoursSaved,
  HoursWindow,
  Medicine,
  NearbyStore,
  EmailPreference,
  NotificationList,
  Page,
  PatientHistory,
  PatientProfile,
  ProfileUpdate,
  PortalSummary,
  Prescription,
  PrescriptionDraft,
  QueueEntry,
  RequestSummary,
  ScanDraft,
  Slot,
  Specialty,
  StockView,
  StoreInput,
  StoreInsights,
  StoreProfile,
  StoreReservation,
  StoreView,
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

  /** Same reply whether or not the address has an account, and it does not wait for the email to go out. */
  sendEmailCode: (email: string) =>
    request<CodeSent>("/api/v1/auth/otp/email/send", { method: "POST", body: { email } }),

  verifyEmailCode: (email: string, code: string) =>
    request<TokenPair>("/api/v1/auth/otp/email/verify", { method: "POST", body: { email, code } }),

  register: (input: {
    email: string;
    password: string;
    fullName: string;
    phone?: string;
    dateOfBirth: string;
    gender?: string;
    acceptedTerms: boolean;
    acceptedHealthData: boolean;
  }) =>
    request<TokenPair>("/api/v1/auth/register", {
      method: "POST",
      body: input,
    }),

  /** A doctor signs up; they are not listed or bookable until the registration number is checked. */
  registerDoctor: (input: DoctorRegistration) =>
    request<TokenPair>("/api/v1/auth/register/doctor", { method: "POST", body: input }),

  /** A chemist and their store, in one step. The store waits for a licence check. */
  registerChemist: (input: {
    email: string;
    password: string;
    fullName: string;
    store: StoreInput;
    acceptedTerms: boolean;
  }) =>
    request<TokenPair>("/api/v1/auth/register/chemist", {
      method: "POST",
      body: input,
    }),
};

export const doctors = {
  search: (specialization?: string, nameQuery?: string, insurance?: string, clinicId?: string) => {
    const params = new URLSearchParams();
    if (clinicId) params.set("clinicId", clinicId);
    if (specialization) params.set("specialization", specialization);
    if (nameQuery) params.set("q", nameQuery);
    if (insurance) params.set("insurance", insurance);
    return request<Page<Doctor>>(`/api/v1/doctors?${params}`);
  },

  /** Specialisations someone can be booked in, with how many doctors practise each. */
  specialties: () => request<Specialty[]>("/api/v1/doctors/specialties"),

  /** One listed doctor, as the directory shows them. */
  get: (doctorId: string) => request<Doctor>(`/api/v1/doctors/${doctorId}`),

  /** Whether the doctor is on time today (signed-in users). */
  liveStatus: (doctorId: string) => request<LiveStatus>(`/api/v1/doctors/${doctorId}/live-status`),

  /** Insurers and schemes the directory can filter by. */
  insurers: () => request<Insurer[]>("/api/v1/doctors/insurers"),

  /** For the search box: specialisations and up to five doctors matching `q`. */
  suggest: (q: string) => request<DoctorSuggestions>(`/api/v1/doctors/suggest?q=${encodeURIComponent(q)}`),

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
  book: (slotId: string, reason: string, idempotencyKey: string, visitType: VisitType = "IN_PERSON", intake: Intake | null = null) =>
    request<Appointment>("/api/v1/appointments", {
      method: "POST",
      body: { slotId, reason, visitType, intake },
      headers: { "Idempotency-Key": idempotencyKey },
    }),

  cancel: (id: string, reason: string) =>
    request<Appointment>(`/api/v1/appointments/${id}/cancel`, {
      method: "POST",
      body: { reason },
    }),

  /** Moves a visit to another time with the same doctor; both happen or neither. */
  reschedule: (id: string, slotId: string) =>
    request<Appointment>(`/api/v1/appointments/${id}/reschedule`, {
      method: "POST",
      body: { slotId },
    }),

};

/**
 * The signed-in patient's own records. No id is ever sent: the server resolves
 * "me" from the token, so there is nothing here a caller could change to read
 * another patient's history.
 */
export const portal = {
  profile: () => request<PatientProfile>("/api/v1/patients/me"),

  updateProfile: (update: ProfileUpdate) =>
    request<PatientProfile>("/api/v1/patients/me", { method: "PUT", body: update }),

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

  /** A photo of the handwritten slip, for a completed visit. */
  uploadScan: (visitId: string, photo: File) => {
    const form = new FormData();
    form.append("photo", photo);
    return request<{ scanId: string }>(`/api/v1/doctors/me/visits/${visitId}/scans`, { method: "POST", body: form });
  },

  /** A draft read from the photo, to correct and confirm. Issues nothing. */
  readScan: (scanId: string) => request<ScanDraft>(`/api/v1/doctors/me/scans/${scanId}/read`, { method: "POST" }),

  patientHistory: (patientId: string) =>
    request<PatientHistory>(`/api/v1/doctors/me/patients/${patientId}/history`),
};

export const pharmacy = {
  medicines: () => request<Page<Medicine>>("/api/v1/pharmacy/medicines?size=100"),

  /** The hospital pharmacy counter (pharmacists only). */
  search: (q: string) => request<Page<Medicine>>(`/api/v1/pharmacy/medicines?size=10&q=${encodeURIComponent(q)}`),
  findPrescriptions: (q: string) =>
    request<PharmacyPrescription[]>(`/api/v1/pharmacy/prescriptions?q=${encodeURIComponent(q)}`),
  dispense: (prescriptionId: string) =>
    request<DispenseResult>(`/api/v1/pharmacy/prescriptions/${prescriptionId}/dispense`, { method: "POST" }),
  lowStock: () => request<LowStockItem[]>("/api/v1/pharmacy/stock/low"),
  restock: (medicineId: string, quantity: number) =>
    request<Medicine>(`/api/v1/pharmacy/medicines/${medicineId}/restock`, { method: "POST", body: { quantity } }),
};

/** The signed-in user's notifications. Scoped by the token, like the portal. */
export const notifications = {
  mine: () => request<NotificationList>("/api/v1/notifications"),
  markRead: (id: string) => request<void>(`/api/v1/notifications/${id}/read`, { method: "POST" }),
  markAllRead: () => request<void>("/api/v1/notifications/read-all", { method: "POST" }),
  emailPreference: () => request<EmailPreference>("/api/v1/notifications/email-preference"),
  setEmailPreference: (enabled: boolean) =>
    request<EmailPreference>("/api/v1/notifications/email-preference", { method: "PUT", body: { enabled } }),
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
  pendingDoctors: () => request<DoctorProfile[]>("/api/v1/admin/doctors/pending"),
  verifyDoctor: (id: string) => request<DoctorProfile>(`/api/v1/admin/doctors/${id}/verify`, { method: "POST" }),
  pharmacists: () => request<PharmacistAccount[]>("/api/v1/admin/pharmacists"),
  createPharmacist: (input: { email: string; fullName: string; temporaryPassword: string }) =>
    request<PharmacistAccount>("/api/v1/admin/pharmacists", { method: "POST", body: input }),
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

/** What the account has agreed to, a copy of its data, and deleting it. */
export const privacy = {
  consents: () => request<ConsentStatus>("/api/v1/consents/me"),

  /** Agree to the current version of these texts. */
  accept: (types: ConsentType[]) =>
    request<ConsentStatus>("/api/v1/consents/me", { method: "POST", body: { types } }),

  /** Everything held about the account and the family it manages. */
  exportData: () => request<Record<string, unknown>>("/api/v1/patients/me/export"),

  /** Irreversible. Needs the password again. */
  deleteAccount: (password: string) =>
    request<DeletionSummary>("/api/v1/patients/me/delete-account", { method: "POST", body: { password } }),
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
    /** The account holder may agree to Medicity keeping this person's health records. */
    guardianConsent: boolean;
  }) => request<FamilyMember>("/api/v1/patients/me/family", { method: "POST", body: input }),
};

/** The signed-in doctor's own account: profile and weekly hours. */
export const doctorAccount = {
  profile: () => request<DoctorProfile>("/api/v1/doctors/me/profile"),

  hours: () => request<HoursWindow[]>("/api/v1/doctors/me/hours"),

  /** Fee, bio, insurers and prices, with every insurer that can be chosen. */
  practice: () => request<Practice>("/api/v1/doctors/me/practice"),

  /** Replaces all of it; a price left out is withdrawn. */
  savePractice: (update: PracticeUpdate) =>
    request<Practice>("/api/v1/doctors/me/practice", { method: "PUT", body: update }),

  /** Replaces the week; unbooked future slots are replaced, booked ones kept. */
  saveHours: (days: HoursWindow[]) =>
    request<HoursSaved>("/api/v1/doctors/me/hours", { method: "PUT", body: { days } }),

  /** Upcoming days off, each with the visits already booked on it. */
  leave: () => request<DoctorLeave[]>("/api/v1/doctors/me/leave"),

  addLeave: (day: string, note: string) =>
    request<DoctorLeave>("/api/v1/doctors/me/leave", { method: "POST", body: { day, note: note || null } }),

  /** The day's appointments open again. */
  removeLeave: (day: string) => request<HoursSaved>(`/api/v1/doctors/me/leave/${day}`, { method: "DELETE" }),
};

/** ICD-10 codes for the prescription writer (doctors only). */
export const diagnoses = {
  search: (q: string) => request<DiagnosisCode[]>(`/api/v1/diagnoses?${new URLSearchParams({ q })}`),
};

/** Reviews: written only for a completed visit, read by anyone. */
export const reviews = {
  submit: (appointmentId: string, rating: number, comment: string) =>
    request<void>(`/api/v1/appointments/${appointmentId}/review`, {
      method: "POST",
      body: { rating, comment: comment.trim() || null },
    }),
  forDoctor: (doctorId: string) => request<DoctorReview[]>(`/api/v1/doctors/${doctorId}/reviews`),
};

/** Same-day walk-in tokens (Door 2). */
export const queue = {
  status: (doctorId: string) => request<QueueStatus>(`/api/v1/doctors/${doctorId}/queue`),
  join: (doctorId: string, reason: string) =>
    request<QueueToken>(`/api/v1/doctors/${doctorId}/queue`, { method: "POST", body: { reason: reason.trim() || null } }),
  mine: () => request<QueueToken[]>("/api/v1/queue/mine"),
  leave: (tokenId: string) => request<QueueToken>(`/api/v1/queue/tokens/${tokenId}/leave`, { method: "POST" }),
  desk: () => request<QueueDesk>("/api/v1/doctors/me/queue"),
  callNext: () => request<QueueToken>("/api/v1/doctors/me/queue/next", { method: "POST" }),
  finish: (tokenId: string, seen: boolean) =>
    request<QueueToken>(`/api/v1/doctors/me/queue/tokens/${tokenId}/${seen ? "seen" : "missed"}`, { method: "POST" }),
  setOpen: (open: boolean) =>
    request<QueueStatus>(`/api/v1/doctors/me/queue/${open ? "open" : "close"}`, { method: "POST" }),
};

/** Video visits: a ticket for the signalling socket (patient or doctor of the visit). */
export const video = {
  ticket: (appointmentId: string) =>
    request<VideoTicket>(`/api/v1/appointments/${appointmentId}/video-ticket`, { method: "POST" }),
};

/** Waiting lists for a full day with a doctor. */
export const waitlist = {
  join: (doctorId: string, date: string) =>
    request<WaitlistEntry>(`/api/v1/doctors/${doctorId}/waitlist`, { method: "POST", body: { date } }),
  leave: (doctorId: string, date: string) =>
    request<void>(`/api/v1/doctors/${doctorId}/waitlist?${new URLSearchParams({ date })}`, { method: "DELETE" }),
  mine: () => request<WaitlistEntry[]>("/api/v1/patients/me/waitlist"),
};

/** Free follow-up questions after a visit (the visit's patient and doctor). */
export const followUps = {
  thread: (appointmentId: string) => request<FollowUpThread>(`/api/v1/appointments/${appointmentId}/followups`),
  post: (appointmentId: string, body: string) =>
    request<FollowUpThread>(`/api/v1/appointments/${appointmentId}/followups`, { method: "POST", body: { body } }),
};

/** Records a patient attaches to a visit for the doctor. */
export const attachments = {
  path: (appointmentId: string, attachmentId: string) => `/api/v1/appointments/${appointmentId}/attachments/${attachmentId}`,
  list: (appointmentId: string) => request<Attachment[]>(`/api/v1/appointments/${appointmentId}/attachments`),
  upload: (appointmentId: string, file: File, note: string) => {
    const form = new FormData();
    form.append("file", file);
    if (note.trim()) form.append("note", note.trim());
    return request<Attachment>(`/api/v1/appointments/${appointmentId}/attachments`, { method: "POST", body: form });
  },
  remove: (appointmentId: string, attachmentId: string) =>
    request<void>(`/api/v1/appointments/${appointmentId}/attachments/${attachmentId}`, { method: "DELETE" }),
};

/** Choosing a password of one's own, required once for accounts a clinic made. */
export const account = {
  changePassword: (currentPassword: string, newPassword: string) =>
    request<TokenPair>("/api/v1/account/change-password", { method: "POST", body: { currentPassword, newPassword } }),
};

/** The public list of clinics, for the directory filter. */
export const clinics = {
  list: () => request<ClinicListing[]>("/api/v1/clinics"),
};

/** A clinic's administrator: its profile, people and numbers. The clinic is always the caller's own. */
export const clinicAdmin = {
  profile: () => request<ClinicProfile>("/api/v1/clinics/me"),
  saveProfile: (input: ClinicProfileInput) => request<ClinicProfile>("/api/v1/clinics/me", { method: "PUT", body: input }),
  staff: () => request<ClinicStaffMember[]>("/api/v1/clinics/me/staff"),
  addDoctor: (input: NewClinicDoctor) =>
    request<ClinicCredentials>("/api/v1/clinics/me/doctors", { method: "POST", body: input }),
  addReceptionist: (input: NewClinicReceptionist) =>
    request<ClinicCredentials>("/api/v1/clinics/me/receptionists", { method: "POST", body: input }),
  resetPassword: (userId: string) =>
    request<ClinicCredentials>(`/api/v1/clinics/me/staff/${userId}/reset-password`, { method: "POST" }),
  setEnabled: (userId: string, enabled: boolean) =>
    request<ClinicStaffMember>(`/api/v1/clinics/me/staff/${userId}/${enabled ? "enable" : "disable"}`, { method: "POST" }),
  report: (from: string, to: string) => request<ClinicReport>(`/api/v1/clinics/me/report?from=${from}&to=${to}`),
};

/** The front desk: the clinic's day, and acting for people on the phone or at the counter. */
export const desk = {
  today: (date?: string) => request<DeskToday>(`/api/v1/clinics/me/desk/today${date ? `?date=${date}` : ""}`),
  arrived: (id: string) => request<DeskVisit>(`/api/v1/clinics/me/desk/visits/${id}/arrived`, { method: "POST" }),
  noShow: (id: string) => request<DeskVisit>(`/api/v1/clinics/me/desk/visits/${id}/no-show`, { method: "POST" }),
  cancel: (id: string, reason?: string) =>
    request<DeskVisit>(`/api/v1/clinics/me/desk/visits/${id}/cancel`, { method: "POST", body: { reason: reason || null } }),
  book: (slotId: string, patient: DeskPatientRef, reason?: string) =>
    request<DeskVisit>("/api/v1/clinics/me/desk/visits", {
      method: "POST",
      body: { slotId, patient, reason: reason || null },
    }),
  walkIn: (doctorId: string, patient: DeskPatientRef, reason?: string) =>
    request<QueueToken>("/api/v1/clinics/me/desk/walk-ins", {
      method: "POST",
      body: { doctorId, patient, reason: reason || null },
    }),
  findPatients: (q: string) => request<DeskPatientHit[]>(`/api/v1/clinics/me/desk/patients?q=${encodeURIComponent(q)}`),
};

const IMPORT = "/api/v1/doctors/me/import/patients";

/** A doctor moving an old patient list in from Excel or CSV: check first, then import. */
export const clinicImport = {
  /** The empty CSV to fill in. */
  template: () => request<Blob>(`${IMPORT}/template`, { as: "blob" }),

  /** Checks every row and says what an import would do. Saves nothing. */
  dryRun: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return request<ImportReport>(`${IMPORT}/dry-run`, { method: "POST", body: form });
  },

  /** Imports the file that was checked; `fileSha256` is the hash the check returned. */
  confirm: (file: File, fileSha256: string) => {
    const form = new FormData();
    form.append("file", file);
    form.append("fileSha256", fileSha256);
    return request<ImportReport>(`${IMPORT}/confirm`, { method: "POST", body: form });
  },

  /** Every problem in the file, as a CSV. */
  errorReport: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return request<Blob>(`${IMPORT}/error-report`, { method: "POST", body: form, as: "blob" });
  },
};

/** The patient's side: prove the email, see which clinics hold a record for it, say yes or no. */
export const clinicRecords = {
  view: () => request<ClinicRecordsView>("/api/v1/patients/me/clinic-records"),

  sendCode: () => request<EmailCodeSent>("/api/v1/patients/me/clinic-records/email-code", { method: "POST" }),

  verifyCode: (code: string) =>
    request<void>("/api/v1/patients/me/clinic-records/email-code/verify", { method: "POST", body: { code } }),

  accept: (id: string) => request<void>(`/api/v1/patients/me/clinic-records/${id}/accept`, { method: "POST" }),

  decline: (id: string) => request<void>(`/api/v1/patients/me/clinic-records/${id}/decline`, { method: "POST" }),
};
