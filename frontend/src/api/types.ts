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
  /** True for an account made by a clinic: it must choose its own password before anything else. */
  mustChangePassword?: boolean;
}

export type Role = "PATIENT" | "DOCTOR" | "ADMIN" | "CHEMIST" | "PHARMACIST" | "CLINIC_ADMIN" | "RECEPTIONIST";

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
  /** Where patients are seen; null until the doctor says. The app works out the distance. */
  clinicName?: string | null;
  clinicAddress?: string | null;
  clinicLatitude?: number | null;
  clinicLongitude?: number | null;
  /** Set when the doctor works at a registered clinic or hospital. */
  clinicId?: string | null;
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
  heightCm: number | null;
  weightKg: number | null;
  allergies: string | null;
  chronicConditions: string | null;
  currentMedications: string | null;
  homeLatitude: number | null;
  homeLongitude: number | null;
  memberSince: string;
}

/** What the profile form sends: the whole form, since the server replaces the record with it. */
export interface ProfileUpdate {
  bloodGroup: string | null;
  heightCm: number | null;
  weightKg: number | null;
  allergies: string | null;
  chronicConditions: string | null;
  currentMedications: string | null;
  emergencyContact: string | null;
  addressLine: string | null;
  city: string | null;
  homeLatitude: number | null;
  homeLongitude: number | null;
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

/** One prescription as the pharmacy counter sees it. */
export interface PharmacyPrescription {
  id: string;
  /** The first eight characters of the id: what the patient reads out. */
  reference: string;
  patientName: string;
  doctorName: string;
  diagnosis: string;
  issuedAt: string;
  dispensedAt: string | null;
  canDispense: boolean;
  items: Array<{
    medicineId: string;
    medicine: string;
    strength: string | null;
    quantity: number;
    dosage: string;
    frequency: string;
    durationDays: number;
    quantityOnHand: number;
  }>;
}

export interface DispenseResult {
  prescriptionId: string;
  dispensedAt: string;
  items: Array<{ medicine: string; strength: string | null; quantity: number; remainingStock: number }>;
}

export interface LowStockItem {
  medicineId: string;
  name: string;
  strength: string | null;
  quantityOnHand: number;
  reorderLevel: number;
}

export interface PharmacistAccount {
  id: string;
  email: string;
  fullName: string;
  enabled: boolean;
  createdAt: string;
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

/** Whether notification emails are on, and whether the server can send them at all. */
export interface EmailPreference {
  enabled: boolean;
  available: boolean;
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

/**
 * SMS: texted. EMAIL: emailed. DEMO: a public demo account, code shown here.
 * UNAVAILABLE: no SMS or email provider is configured yet.
 */
export interface CodeSent {
  delivery: "SMS" | "EMAIL" | "DEMO" | "UNAVAILABLE";
  sentTo: string | null;
  demoCode: string | null;
  expiresInSeconds: number;
}

// --- Privacy: agreements, a copy of the data, deletion -------------------------------

export type ConsentType = "TERMS_OF_SERVICE" | "PRIVACY_NOTICE" | "HEALTH_DATA_PROCESSING";

export interface ConsentItem {
  type: ConsentType;
  currentVersion: string;
  acceptedVersion: string | null;
  acceptedAt: string | null;
  upToDate: boolean;
}

export interface ConsentStatus {
  items: ConsentItem[];
  /** True while any current text has not been agreed to. */
  pending: boolean;
}

/** What deleting an account touched, by kind. */
export type DeletionSummary = Record<string, number>;

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
  /** Ticked on the sign-up form; the API refuses a sign-up without it. */
  acceptedTerms: boolean;
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
export interface ClinicLocation {
  name: string | null;
  address: string | null;
  latitude: number | null;
  longitude: number | null;
}

export interface PracticeUpdate {
  clinic: ClinicLocation | null;
  consultationFee: number;
  bio: string | null;
  yearsExperience: number;
  insurers: string[];
  prices: ProcedurePrice[];
}

export interface Practice extends PracticeUpdate {
  availableInsurers: Insurer[];
  /** The doctor works at a clinic, which owns the name, address and location shown to patients. */
  clinicManaged?: boolean;
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

/** A clinic or hospital, as the public directory lists it. */
export interface ClinicListing {
  id: string;
  name: string;
  address: string | null;
  city: string | null;
  latitude: number | null;
  longitude: number | null;
  phone: string | null;
  email: string | null;
  doctors: number;
}

export interface ClinicProfile {
  id: string;
  name: string;
  address: string | null;
  city: string | null;
  latitude: number | null;
  longitude: number | null;
  phone: string | null;
  email: string | null;
  timezone: string;
  active: boolean;
}

export type ClinicProfileInput = Omit<ClinicProfile, "id" | "active">;

export interface ClinicStaffMember {
  userId: string;
  fullName: string;
  email: string;
  phone: string | null;
  role: "CLINIC_ADMIN" | "RECEPTIONIST" | "DOCTOR";
  doctorId: string | null;
  specialization: string | null;
  verified: boolean;
  enabled: boolean;
  mustChangePassword: boolean;
}

/** Shown once, when an account is made or its password reset. */
export interface ClinicCredentials {
  userId: string;
  email: string;
  fullName: string;
  role: string;
  temporaryPassword: string;
}

export interface NewClinicDoctor {
  fullName: string;
  email: string;
  phone?: string | undefined;
  specialization: string;
  medicalCouncil: string;
  registrationNumber: string;
  qualification: string;
  yearsExperience: number;
  consultationFee: number;
}

export interface NewClinicReceptionist {
  fullName: string;
  email: string;
  phone?: string | undefined;
}

export interface ClinicDayRow {
  date: string;
  scheduled: number;
  completed: number;
  noShows: number;
  cancellations: number;
  walkIns: number;
  walkInsSeen: number;
  bookedByDesk: number;
}

export interface ClinicReport {
  from: string;
  to: string;
  timezone: string;
  days: ClinicDayRow[];
  totals: ClinicDayRow;
}

export interface DeskDoctor {
  id: string;
  fullName: string;
  specialization: string;
  verified: boolean;
  walkInsOpen: boolean;
  waiting: number;
}

export interface DeskVisit {
  id: string;
  doctorId: string;
  doctorName: string;
  scheduledAt: string;
  endsAt: string;
  status: AppointmentStatus;
  patientName: string;
  patientPhone: string | null;
  reason: string | null;
  visitType: string | null;
  arrivedAt: string | null;
  bookedByDesk: boolean;
}

export interface DeskToday {
  date: string;
  timezone: string;
  doctors: DeskDoctor[];
  visits: DeskVisit[];
  walkIns: QueueToken[];
}

/** Who a desk action is for: exactly one of the three. */
export type DeskPatientRef =
  | { patientId: string }
  | { email: string }
  | { newPatient: { fullName: string; phone?: string | undefined; ageYears: number; gender?: string | undefined } };

export interface DeskPatientHit {
  patientId: string | null;
  email: string | null;
  fullName: string;
  phone: string | null;
  /** CLINIC: recorded by this clinic. ACCOUNT: a Medicity account found by its exact email. */
  source: "CLINIC" | "ACCOUNT";
}

/** One problem in an imported patient list: the spreadsheet row, the column (null for the whole row) and what is wrong. */
export interface ImportIssue {
  row: number;
  column: string | null;
  code: string;
  message: string;
}

/** What a patient-list import would do (applied false) or did (applied true). */
export interface ImportReport {
  applied: boolean;
  fileSha256: string;
  totalRows: number;
  validRows: number;
  rejectedRows: number;
  created: number;
  updated: number;
  unchanged: number;
  errorCount: number;
  errors: ImportIssue[];
  errorsTruncated: boolean;
  ignoredColumns: string[];
}

/** A clinic's record offered to the patient: only who holds it. */
export interface ClinicRecordOffer {
  id: string;
  clinicName: string;
}

/** A clinic's record the patient accepted: everything the clinic holds. */
export interface LinkedClinicRecord {
  id: string;
  clinicName: string;
  fullName: string;
  phone: string | null;
  email: string | null;
  dateOfBirth: string | null;
  gender: string | null;
  bloodGroup: string | null;
  allergies: string | null;
  notes: string | null;
  acceptedAt: string;
}

export interface ClinicRecordsView {
  emailVerified: boolean;
  offers: ClinicRecordOffer[];
  linked: LinkedClinicRecord[];
}

export interface EmailCodeSent {
  delivery: "EMAIL" | "DEMO" | "UNAVAILABLE";
  sentTo: string | null;
  demoCode: string | null;
  expiresInSeconds: number;
}
