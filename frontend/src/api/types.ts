export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  code: string;
  path: string;
  fieldErrors?: Record<string, string>;
  incidentId?: string;
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  userId: string;
  role: Role;
  fullName: string;
}

export type Role = "PATIENT" | "DOCTOR" | "ADMIN" | "CHEMIST";

export type AppointmentStatus = "BOOKED" | "COMPLETED" | "CANCELLED" | "NO_SHOW";

export interface Doctor {
  id: string;
  fullName: string;
  specialization: string;
  consultationFee: number;
  yearsExperience: number;
  bio: string | null;
  /** The next few open times, soonest first (empty in search-box suggestions). */
  nextSlots: Slot[];
  /** Average of reviews from completed visits; null when there are none. */
  rating?: number | null;
  reviewCount?: number;
  /** Insurers and schemes the clinic accepts. */
  insurers?: string[];
  /** Charges beyond the consultation fee; "every visit" ones are added to it. */
  prices?: ProcedurePrice[];
}

export interface Specialty {
  name: string;
  doctors: number;
}

export interface DoctorSuggestions {
  specialties: Specialty[];
  doctors: Doctor[];
}

export interface Slot {
  id: string;
  startsAt: string;
  endsAt: string;
}

export interface Appointment {
  id: string;
  slotId: string;
  patientId: string;
  status: AppointmentStatus;
  scheduledAt: string;
  reason: string | null;
  cancelledAt: string | null;
  /** The visit this one replaced, when the patient moved it. */
  rescheduledFrom: string | null;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

// --- Patient portal ------------------------------------------------------

export interface PatientProfile {
  patientId: string;
  /** Set when this is a family member the account manages. */
  relationship: string | null;
  fullName: string;
  email: string;
  phone: string | null;
  dateOfBirth: string;
  gender: "MALE" | "FEMALE" | "OTHER" | "UNDISCLOSED";
  bloodGroup: string | null;
  addressLine: string | null;
  city: string | null;
  emergencyContact: string | null;
  memberSince: string;
}

export interface Visit {
  id: string;
  status: AppointmentStatus;
  scheduledAt: string;
  endsAt: string;
  reason: string | null;
  doctorId: string;
  doctorName: string;
  specialization: string;
  cancelledAt: string | null;
  cancelReason: string | null;
  /** The patient has already reviewed this visit. */
  reviewed?: boolean;
  visitType?: VisitType;
}

export interface PortalSummary {
  upcoming: number;
  completed: number;
  cancelled: number;
  missed: number;
  prescriptions: number;
  nextVisit: Visit | null;
}

export interface PrescriptionItem {
  medicineId: string;
  medicine: string;
  strength: string | null;
  form: string;
  dosage: string;
  frequency: string;
  durationDays: number;
  quantity: number;
  genericName: string;
  /** The doctor allows a cheaper brand with the same ingredients and strength. */
  substitutionAllowed: boolean;
}

export interface Prescription {
  id: string;
  appointmentId: string;
  issuedAt: string;
  doctorName: string;
  specialization: string;
  diagnosis: string;
  /** ICD-10, when the doctor chose one. */
  diagnosisCode?: string | null;
  notes: string | null;
  /** True when this prescription corrects an earlier one, which it replaces. */
  revised: boolean;
  /** When the pharmacy filled it; null until then. */
  dispensedAt: string | null;
  /** When it was last collected from a neighbourhood store, and which; null if never. */
  collectedAt: string | null;
  collectedFrom: string | null;
  /** The doctor's handwritten original is attached. */
  hasPhoto: boolean;
  items: PrescriptionItem[];
}

export type VisitScope = "upcoming" | "past";

// --- Doctor workspace ----------------------------------------------------

export interface PatientBrief {
  id: string;
  fullName: string;
  age: number;
  gender: PatientProfile["gender"];
  bloodGroup: string | null;
}

export interface DoctorVisitSummary {
  visitType?: VisitType;
  id: string;
  status: AppointmentStatus;
  scheduledAt: string;
  endsAt: string;
  reason: string | null;
  patient: PatientBrief;
  prescriptionId: string | null;
  dispensedAt: string | null;
}

export interface DoctorVisitDetail {
  visitType?: VisitType;
  intake?: Intake | null;
  id: string;
  status: AppointmentStatus;
  scheduledAt: string;
  endsAt: string;
  reason: string | null;
  cancelReason: string | null;
  /** Whether the start time has passed, so the visit can be closed. */
  started: boolean;
  patient: PatientBrief;
  prescription: Prescription | null;
}

export interface PatientHistory {
  patient: PatientBrief;
  visits: Visit[];
  prescriptions: Prescription[];
}

export interface PrescriptionDraftItem {
  medicineId: string;
  dosage: string;
  frequency: string;
  durationDays: number;
  quantity: number;
  substitutionAllowed: boolean;
}

export interface PrescriptionDraft {
  diagnosis: string;
  diagnosisCode?: string | null;
  notes: string;
  items: PrescriptionDraftItem[];
  /** The photographed slip this was typed from, when the doctor started from one. */
  scanId?: string | undefined;
}

export interface Medicine {
  id: string;
  name: string;
  genericName: string;
  form: string;
  strength: string | null;
  quantityOnHand: number;
  lowStock: boolean;
}

export interface AppNotification {
  id: string;
  kind: string;
  title: string;
  body: string;
  link: string | null;
  /** The moment the notification is about, such as a visit's start; formatted in the viewer's time zone. */
  occursAt: string | null;
  createdAt: string;
  read: boolean;
}

export interface NotificationList {
  unread: number;
  items: AppNotification[];
}

// --- Chemists' stores -----------------------------------------------------

/** Local wall-clock times, "HH:mm:ss", in the store's own zone. */
export interface StoreHoursInfo {
  opensAt: string;
  closesAt: string;
  open24h: boolean;
  openNow: boolean;
}

export interface NearbyStore extends StoreHoursInfo {
  id: string;
  name: string;
  phone: string;
  addressLine: string;
  city: string;
  latitude: number;
  longitude: number;
  distanceM: number;
  holdHours: number;
}

export interface StoreProfile extends StoreHoursInfo {
  id: string;
  name: string;
  licenceNumber: string;
  phone: string;
  addressLine: string;
  city: string;
  latitude: number;
  longitude: number;
  holdHours: number;
  verified: boolean;
  verifiedAt: string | null;
  ownerName: string;
  ownerEmail: string;
  registeredAt: string;
}

/** What a chemist sends to create or edit their store. Times are "HH:mm". */
export interface StoreInput {
  name: string;
  licenceNumber: string;
  phone: string;
  addressLine: string;
  city: string;
  latitude: number;
  longitude: number;
  opensAt: string;
  closesAt: string;
  open24h: boolean;
  holdHours: number;
}

// --- Asking nearby chemists ---------------------------------------------

export type Availability = "YES" | "PARTIAL" | "NO";
export type RequestStatus = "OPEN" | "RESERVED" | "CLOSED" | "EXPIRED";

export interface RequestItem {
  medicineId: string;
  name: string;
  genericName: string;
  strength: string | null;
  form: string;
  quantity: number;
  substitutionAllowed: boolean;
}

export interface AnswerLine {
  medicineId: string;
  availability: Availability;
  quantityAvailable: number;
  unitPrice: number | null;
  substituteMedicineId: string | null;
  substituteName: string | null;
  substituteStrength: string | null;
}

export interface StoreAnswer {
  storeId: string;
  name: string;
  addressLine: string;
  phone: string;
  distanceM: number;
  openNow: boolean;
  holdHours: number;
  answered: boolean;
  /** Answered from the store's live stock, not by the chemist. */
  automatic: boolean;
  note: string | null;
  answeredAt: string | null;
  lines: AnswerLine[];
  medicinesAvailable: number;
  complete: boolean;
  total: number | null;
  cheapestComplete: boolean;
  nearestComplete: boolean;
}

export interface Comparison {
  id: string;
  status: RequestStatus;
  createdAt: string;
  expiresAt: string;
  prescriptionId: string;
  doctorName: string;
  diagnosis: string;
  storesAsked: number;
  radiusM: number;
  items: RequestItem[];
  /** Best first: everything, then more medicines, then cheaper, then nearer. */
  stores: StoreAnswer[];
  /** The latest reservation on this question; null if none. */
  reservation: PatientReservation | null;
}

export type ReservationStatus = "HELD" | "COLLECTED" | "EXPIRED" | "CANCELLED";

export interface PatientReservation {
  id: string;
  storeId: string;
  storeName: string;
  storePhone: string;
  storeAddress: string;
  /** Only while held. Show it at the counter. */
  pickupCode: string | null;
  status: ReservationStatus;
  total: number;
  complete: boolean;
  expiresAt: string;
  collectedAt: string | null;
}

export interface HandOver {
  name: string;
  strength: string | null;
  prescribedAs: string;
  quantity: number;
  asked: number;
  unitPrice: number;
}

export interface StoreReservation {
  id: string;
  requestId: string;
  status: ReservationStatus;
  patientName: string;
  total: number;
  complete: boolean;
  createdAt: string;
  expiresAt: string;
  collectedAt: string | null;
  codeLocked: boolean;
  lines: HandOver[];
}

export interface RequestSummary {
  id: string;
  status: RequestStatus;
  createdAt: string;
  expiresAt: string;
  prescriptionId: string;
  diagnosis: string;
  doctorName: string;
  medicines: number;
  storesAsked: number;
  answers: number;
}

export interface QueueEntry {
  id: string;
  createdAt: string;
  expiresAt: string;
  requestStatus: RequestStatus;
  myStatus: "PENDING" | "ANSWERED";
  answeredAt: string | null;
  distanceM: number;
  patientName: string;
  doctorName: string;
  medicines: number;
}

export interface Equivalent {
  id: string;
  name: string;
  strength: string | null;
  listPrice: number;
}

export interface StoreItem {
  medicineId: string;
  name: string;
  genericName: string;
  strength: string | null;
  form: string;
  quantity: number;
  substitutionAllowed: boolean;
  dosage: string;
  frequency: string;
  durationDays: number;
  equivalents: Equivalent[];
}

export interface StoreView {
  id: string;
  status: RequestStatus;
  createdAt: string;
  expiresAt: string;
  patientName: string;
  distanceM: number;
  prescription: {
    doctorName: string;
    specialization: string;
    doctorRegistration: string;
    issuedAt: string;
    revised: boolean;
    hospitalDispensedAt: string | null;
    hasPhoto: boolean;
  };
  items: StoreItem[];
  myStatus: "PENDING" | "ANSWERED";
  myNote: string | null;
  answeredAt: string | null;
  myAnswer: AnswerLine[];
}

export interface AnswerLineInput {
  medicineId: string;
  availability: Availability;
  quantity: number | null;
  unitPrice: number | null;
  substituteMedicineId: string | null;
}

// --- Store insights and live stock -----------------------------------------

export interface MedicineDemand {
  medicineId: string;
  name: string;
  strength: string | null;
  form: string;
  asked: number;
  patients: number;
  units: number;
  had: number;
  partly: number;
  saidNo: number;
  unanswered: number;
  wentElsewhere: number;
  considerStocking: boolean;
}

export interface StoreInsights {
  from: string;
  to: string;
  summary: {
    questionsReceived: number;
    answered: number;
    answeredAutomatically: number;
    reservations: number;
    collected: number;
    medianMinutesToAnswer: number | null;
  };
  medicines: MedicineDemand[];
}

export interface StockLine {
  medicineId: string;
  name: string;
  strength: string | null;
  form: string;
  quantity: number;
  unitPrice: number;
}

export interface StockView {
  autoAnswer: boolean;
  updatedAt: string | null;
  fresh: boolean;
  items: StockLine[];
}

// --- Medicine courses and refills -------------------------------------------

export type CourseStatus = "RUNNING_OUT" | "TAKING" | "NOT_STARTED" | "FINISHED";

export interface Course {
  prescriptionItemId: string;
  prescriptionId: string;
  medicine: string;
  strength: string | null;
  frequency: string;
  durationDays: number;
  issuedAt: string;
  /** When the medicines were handed over; null if not yet. */
  startedAt: string | null;
  startedWhere: string | null;
  firstDay: string | null;
  lastDay: string | null;
  /** 0 on the last day; negative once finished. */
  daysLeft: number;
  /** Taken long-term: running out means asking again. */
  ongoing: boolean;
  status: CourseStatus;
}

// --- Family ---------------------------------------------------------------

export type Relationship = "PARENT" | "CHILD" | "SPOUSE" | "SIBLING" | "OTHER";

export interface FamilyMember {
  patientId: string;
  fullName: string;
  /** Null for the account holder. */
  relationship: Relationship | null;
  self: boolean;
  age: number;
  gender: PatientProfile["gender"];
  bloodGroup: string | null;
}

// --- Handwritten prescription photos -------------------------------------------

export interface ScanReadLine {
  writtenAs: string | null;
  medicine: string | null;
  strength: string | null;
  dosage: string | null;
  frequency: string | null;
  durationDays: number | null;
  quantity: number | null;
  confidence: number | null;
}

export interface ScanDraft {
  scanId: string;
  status: "DRAFTED" | "FAILED" | "UNAVAILABLE";
  /** Why there is no draft, when there is none. */
  problem: string | null;
  diagnosis: string | null;
  lines: { read: ScanReadLine; match: { medicineId: string; name: string; strength: string | null } | null }[];
  unreadable: string[];
}

// --- Signing in with a mobile number -------------------------------------------

/** SMS: texted. DEMO: a public demo account, code shown here. UNAVAILABLE: no SMS provider yet. */
export interface CodeSent {
  delivery: "SMS" | "DEMO" | "UNAVAILABLE";
  sentTo: string | null;
  demoCode: string | null;
  expiresInSeconds: number;
}

// --- Doctors who sign up themselves -----------------------------------------------

export interface DoctorRegistration {
  email: string;
  password: string;
  fullName: string;
  phone: string;
  specialization: string;
  medicalCouncil: string;
  registrationNumber: string;
  qualification: string;
  yearsExperience: number;
  consultationFee: number;
  bio?: string | undefined;
}

export interface DoctorProfile {
  id: string;
  fullName: string;
  email: string;
  specialization: string;
  medicalCouncil: string | null;
  registrationNumber: string;
  qualification: string | null;
  yearsExperience: number;
  consultationFee: number;
  verified: boolean;
  verifiedAt: string | null;
  registeredAt: string;
}

/** One working window: ISO weekday (1 is Monday), "HH:mm" or "HH:mm:ss" times. */
export interface HoursWindow {
  weekday: number;
  startsAt: string;
  endsAt: string;
  slotMinutes: number;
}

/** A doctor's day off (a date in India), and the visits already booked on it. */
export interface DoctorLeave {
  day: string;
  note: string | null;
  bookedVisits: number;
}

export interface HoursSaved {
  hours: HoursWindow[];
  slotsOpened: number;
}

/** An ICD-10 code from the prescription writer's search. */
export interface DiagnosisCode {
  code: string;
  title: string;
}

/** A review from a completed visit; the reviewer is a first name and an initial. */
export interface DoctorReview {
  rating: number;
  comment: string | null;
  reviewer: string;
  createdAt: string;
}

/** A doctor's walk-in line today. */
export interface QueueStatus {
  open: boolean;
  closedReason: string | null;
  waiting: number;
  nowServing: number | null;
  minutesPerPatient: number;
  /** For someone joining now. */
  estimatedWaitMinutes: number;
}

export interface QueueToken {
  id: string;
  tokenNo: number;
  status: "WAITING" | "CALLED" | "SEEN" | "MISSED" | "LEFT";
  doctorId: string;
  doctorName: string;
  specialization: string;
  patientName: string;
  reason: string | null;
  ahead: number;
  estimatedWaitMinutes: number;
  joinedAt: string;
  calledAt: string | null;
}

export interface QueueDesk {
  status: QueueStatus;
  tokens: QueueToken[];
}

export type VisitType = "IN_PERSON" | "VIDEO";

/** A one-time pass into a video visit's signalling socket. */
export interface VideoTicket {
  ticket: string;
  side: "PATIENT" | "DOCTOR";
  expiresAt: string;
  iceServers: string[];
  opensAt: string;
  closesAt: string;
}

/** The body guide's answers, attached to a booking when the patient chooses. */
export interface Intake {
  area: string;
  symptoms: string[];
  since: string | null;
  suggested: string | null;
}

/** A day someone is waiting for with a doctor. */
export interface WaitlistEntry {
  id: string;
  doctorId: string;
  doctorName: string;
  specialization: string;
  patientId: string;
  patientName: string;
  /** YYYY-MM-DD, India time. */
  date: string;
  status: "ACTIVE" | "NOTIFIED" | "FULFILLED" | "LEFT";
}

export interface ProcedurePrice {
  procedure: string;
  priceInr: number;
  everyVisit: boolean;
}

export interface Insurer {
  name: string;
  kind: "PRIVATE" | "PUBLIC" | "GOVERNMENT";
}

/** What a doctor edits about their practice. */
export interface PracticeUpdate {
  consultationFee: number;
  bio: string | null;
  yearsExperience: number;
  insurers: string[];
  prices: ProcedurePrice[];
}

export interface Practice extends PracticeUpdate {
  availableInsurers: Insurer[];
}

export interface FollowUpMessage {
  id: string;
  sender: "PATIENT" | "DOCTOR";
  body: string;
  sentAt: string;
}

/** A visit's free follow-up thread. */
export interface FollowUpThread {
  messages: FollowUpMessage[];
  questionsLeft: number;
  closesAt: string;
  open: boolean;
  awaitingDoctor: boolean;
}

/** A report or earlier prescription attached to a visit. */
export interface Attachment {
  id: string;
  fileName: string;
  contentType: "application/pdf" | "image/jpeg" | "image/png" | "image/webp";
  sizeBytes: number;
  note: string | null;
  uploadedAt: string;
}

/** Whether a doctor is running on time today (an estimate). */
export interface LiveStatus {
  state: "ON_TIME" | "RUNNING_LATE";
  delayMinutes: number;
  visitInProgress: boolean;
  asOf: string;
}
