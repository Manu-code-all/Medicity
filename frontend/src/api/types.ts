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
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

// --- Patient portal ------------------------------------------------------

export interface PatientProfile {
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
  notes: string | null;
  /** True when this prescription corrects an earlier one, which it replaces. */
  revised: boolean;
  /** When the pharmacy filled it; null until then. */
  dispensedAt: string | null;
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
  notes: string;
  items: PrescriptionDraftItem[];
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
